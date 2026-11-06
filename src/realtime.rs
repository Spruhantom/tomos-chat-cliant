//! Realtime protocol state used by the WebSocket worker and GTK event bridge.
//!
//! This module is deliberately independent of a socket implementation so the
//! reconnect, session-expiry and delivery rules can be tested without a server.

use crate::messaging::Message;
use serde::Deserialize;
use std::collections::{HashMap, VecDeque};
use std::time::Duration;

pub const MAX_RECONNECT_DELAY: Duration = Duration::from_secs(30);
pub const INITIAL_RECONNECT_DELAY: Duration = Duration::from_secs(1);
const DEFAULT_DEDUPE_CAPACITY: usize = 2_000;

#[derive(Clone, Debug, Deserialize)]
#[serde(tag = "event", content = "payload")]
pub enum IncomingEvent {
    #[serde(rename = "message.deliver")]
    MessageDeliver(Message),
    #[serde(rename = "message.ack")]
    MessageAck(MessageAck),
    #[serde(rename = "error")]
    Error(ServerError),
}

#[derive(Clone, Debug, Deserialize)]
pub struct MessageAck {
    pub message_id: String,
    pub sequence: i64,
}

#[derive(Clone, Debug, Deserialize)]
pub struct ServerError {
    pub code: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionState {
    Active,
    Expired,
    Stopped,
}

impl SessionState {
    /// Session expiry is terminal for this token: do not retry the same token.
    pub fn apply_server_error(&mut self, error: &ServerError) {
        if error.code == "session_expired" {
            *self = Self::Expired;
        }
    }

    pub fn should_reconnect(self) -> bool {
        self == Self::Active
    }
}

#[derive(Clone, Debug)]
pub struct ReconnectBackoff {
    next: Duration,
}

impl Default for ReconnectBackoff {
    fn default() -> Self {
        Self { next: INITIAL_RECONNECT_DELAY }
    }
}

impl ReconnectBackoff {
    /// Return the current delay, then double the delay for the next attempt.
    /// Apply caller-provided jitter in the range 0.8..=1.2 to avoid synchronized
    /// reconnect storms; tests can pass 1.0 for deterministic values.
    pub fn next_delay(&mut self, jitter: f64) -> Duration {
        let factor = if jitter.is_finite() {
            jitter.clamp(0.8, 1.2)
        } else {
            1.0
        };
        let delay = Duration::from_secs_f64(self.next.as_secs_f64() * factor);
        self.next = self.next.saturating_mul(2).min(MAX_RECONNECT_DELAY);
        delay.min(MAX_RECONNECT_DELAY)
    }

    pub fn reset(&mut self) {
        self.next = INITIAL_RECONNECT_DELAY;
    }
}

#[derive(Debug)]
pub struct MessageDeduplicator {
    capacity: usize,
    ids: HashMap<String, ()>,
    order: VecDeque<String>,
}

impl Default for MessageDeduplicator {
    fn default() -> Self {
        Self::new(DEFAULT_DEDUPE_CAPACITY)
    }
}

impl MessageDeduplicator {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.max(1),
            ids: HashMap::new(),
            order: VecDeque::new(),
        }
    }

    /// Returns true only for the first delivery of a server message ID.
    pub fn insert(&mut self, message_id: &str) -> bool {
        if self.ids.contains_key(message_id) {
            return false;
        }
        self.ids.insert(message_id.to_string(), ());
        self.order.push_back(message_id.to_string());
        while self.order.len() > self.capacity {
            if let Some(oldest) = self.order.pop_front() {
                self.ids.remove(&oldest);
            }
        }
        true
    }
}

#[derive(Debug, Default)]
pub struct PendingAcknowledgements {
    requests: HashMap<String, String>,
}

impl PendingAcknowledgements {
    pub fn track(&mut self, request_id: String, client_message_id: String) {
        self.requests.insert(request_id, client_message_id);
    }

    pub fn acknowledge(&mut self, request_id: &str) -> Option<String> {
        self.requests.remove(request_id)
    }

    pub fn len(&self) -> usize {
        self.requests.len()
    }

    pub fn is_empty(&self) -> bool {
        self.requests.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disconnects_use_exponential_backoff_capped_at_thirty_seconds() {
        let mut backoff = ReconnectBackoff::default();
        let delays = (0..7).map(|_| backoff.next_delay(1.0)).collect::<Vec<_>>();
        assert_eq!(
            delays,
            vec![
                Duration::from_secs(1),
                Duration::from_secs(2),
                Duration::from_secs(4),
                Duration::from_secs(8),
                Duration::from_secs(16),
                Duration::from_secs(30),
                Duration::from_secs(30),
            ]
        );
    }

    #[test]
    fn successful_connection_resets_backoff() {
        let mut backoff = ReconnectBackoff::default();
        let _ = backoff.next_delay(1.0);
        let _ = backoff.next_delay(1.0);
        backoff.reset();
        assert_eq!(backoff.next_delay(1.0), Duration::from_secs(1));
    }

    #[test]
    fn duplicate_message_deliveries_are_ignored() {
        let mut seen = MessageDeduplicator::new(2);
        assert!(seen.insert("server-message-1"));
        assert!(!seen.insert("server-message-1"));
        assert!(seen.insert("server-message-2"));
        assert!(seen.insert("server-message-3"));
        // The bounded cache evicts the oldest ID, preventing unbounded growth.
        assert!(seen.insert("server-message-1"));
    }

    #[test]
    fn acknowledgement_matches_and_removes_pending_request() {
        let mut pending = PendingAcknowledgements::default();
        pending.track("request-1".into(), "client-message-1".into());
        assert_eq!(pending.acknowledge("unknown"), None);
        assert_eq!(
            pending.acknowledge("request-1").as_deref(),
            Some("client-message-1")
        );
        assert!(pending.is_empty());
    }

    #[test]
    fn expired_session_is_not_reconnected_with_the_same_token() {
        let mut state = SessionState::Active;
        assert!(state.should_reconnect());
        state.apply_server_error(&ServerError {
            code: "session_expired".into(),
        });
        assert_eq!(state, SessionState::Expired);
        assert!(!state.should_reconnect());
    }

    #[test]
    fn unrelated_server_errors_do_not_expire_the_session() {
        let mut state = SessionState::Active;
        state.apply_server_error(&ServerError {
            code: "rate_limit_exceeded".into(),
        });
        assert_eq!(state, SessionState::Active);
        assert!(state.should_reconnect());
    }

    #[test]
    fn parses_delivery_acknowledgement_and_expiry_events() {
        let delivery = r#"{"event":"message.deliver","payload":{"id":"m1","sequence":1,"conversation_id":"c1","sender_id":"u1","client_message_id":"cm1","ciphertext":"YQ==","nonce":"Yg==","encryption_version":1,"key_id":null,"created_at":"2026-11-06T00:00:00Z"}}"#;
        assert!(matches!(
            serde_json::from_str::<IncomingEvent>(delivery).unwrap(),
            IncomingEvent::MessageDeliver(_)
        ));
        let ack = r#"{"event":"message.ack","payload":{"message_id":"m1","sequence":1}}"#;
        assert!(matches!(
            serde_json::from_str::<IncomingEvent>(ack).unwrap(),
            IncomingEvent::MessageAck(_)
        ));
        let expired = r#"{"event":"error","payload":{"code":"session_expired"}}"#;
        assert!(matches!(
            serde_json::from_str::<IncomingEvent>(expired).unwrap(),
            IncomingEvent::Error(_)
        ));
    }
}
