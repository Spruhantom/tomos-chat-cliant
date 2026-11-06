//! Realtime protocol state used by the WebSocket worker and GTK event bridge.
//!
//! This module is independent of the socket implementation so reconnect,
//! session-expiry and delivery rules can be tested without a live server.

use crate::messaging::Message;
use serde::Deserialize;
use std::collections::{HashMap, VecDeque};
use std::time::Duration;
use futures_util::{SinkExt, StreamExt};
use std::sync::mpsc;
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::watch;
use tokio::time::{sleep, timeout};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::header::{HeaderValue, AUTHORIZATION};
use tokio_tungstenite::tungstenite::http::StatusCode;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::connect_async;

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
    /// Expiry is terminal for this token; do not retry it indefinitely.
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
    /// Return the current delay, then double it for the next attempt.
    /// Jitter should be sampled in the range 0.8..=1.2 by the worker.
    pub fn next_delay(&mut self, jitter: f64) -> Duration {
        let factor = if jitter.is_finite() { jitter.clamp(0.8, 1.2) } else { 1.0 };
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
        Self { capacity: capacity.max(1), ids: HashMap::new(), order: VecDeque::new() }
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


#[derive(Debug)]
pub enum RealtimeEvent {
    Connected,
    Disconnected(String),
    LiveMessage(Message),
    HistorySync(Vec<Message>),
    Acknowledged { request_id: String, message_id: String, sequence: i64 },
    SessionExpired,
}

/// Owns the background socket task. Dropping it asks the worker to close cleanly.
pub struct RealtimeWorker {
    shutdown: watch::Sender<bool>,
}

impl RealtimeWorker {
    pub fn spawn(
        base_url: String,
        token: String,
        events: mpsc::Sender<RealtimeEvent>,
    ) -> Result<Self, String> {
        let (shutdown, receiver) = watch::channel(false);
        thread::Builder::new()
            .name("tomchat-websocket".into())
            .spawn(move || {
                let runtime = match tokio::runtime::Builder::new_multi_thread()
                    .enable_all()
                    .build()
                {
                    Ok(runtime) => runtime,
                    Err(error) => {
                        let _ = events.send(RealtimeEvent::Disconnected(format!(
                            "Could not start realtime worker: {error}"
                        )));
                        return;
                    }
                };
                runtime.block_on(run_realtime(base_url, token, events, receiver));
            })
            .map_err(|error| format!("Could not start realtime worker: {error}"))?;
        Ok(Self { shutdown })
    }
}

impl RealtimeWorker {
    pub fn stop(&self) {
        let _ = self.shutdown.send(true);
    }
}

impl Drop for RealtimeWorker {
    fn drop(&mut self) {
        let _ = self.shutdown.send(true);
    }
}

async fn run_realtime(
    base_url: String,
    token: String,
    events: mpsc::Sender<RealtimeEvent>,
    mut shutdown: watch::Receiver<bool>,
) {
    let mut backoff = ReconnectBackoff::default();
    let mut seen = MessageDeduplicator::default();
    let mut session = SessionState::Active;

    while session.should_reconnect() && !*shutdown.borrow() {
        let api = match crate::messaging::TomChatMessaging::new(&base_url, &token) {
            Ok(api) => api,
            Err(error) => {
                let _ = events.send(RealtimeEvent::Disconnected(error));
                break;
            }
        };
        let endpoint = match api.websocket_endpoint() {
            Ok(endpoint) => endpoint,
            Err(error) => {
                let _ = events.send(RealtimeEvent::Disconnected(error));
                break;
            }
        };
        let mut request = match endpoint.into_client_request() {
            Ok(request) => request,
            Err(error) => {
                let _ = events.send(RealtimeEvent::Disconnected(format!(
                    "Could not build authenticated WebSocket request: {error}"
                )));
                break;
            }
        };
        let authorization = match HeaderValue::from_str(&format!("Bearer {token}")) {
            Ok(value) => value,
            Err(_) => {
                let _ = events.send(RealtimeEvent::Disconnected(
                    "The session token cannot be used as an HTTP header.".into(),
                ));
                break;
            }
        };
        request.headers_mut().insert(AUTHORIZATION, authorization);

        let connection = tokio::select! {
            _ = shutdown.changed() => return,
            result = timeout(Duration::from_secs(10), connect_async(request)) => result,
        };

        let (mut socket, _) = match connection {
            Ok(Ok(connection)) => connection,
            Ok(Err(error)) => {
                if matches!(&error, tokio_tungstenite::tungstenite::Error::Http(response)
                    if response.status() == StatusCode::UNAUTHORIZED)
                {
                    let _ = events.send(RealtimeEvent::SessionExpired);
                    session = SessionState::Expired;
                    break;
                }
                let _ = events.send(RealtimeEvent::Disconnected(format!(
                    "WebSocket connection failed: {error}"
                )));
                wait_to_reconnect(&mut backoff, &mut shutdown).await;
                continue;
            }
            Err(_) => {
                let _ = events.send(RealtimeEvent::Disconnected(
                    "WebSocket connection timed out.".into(),
                ));
                wait_to_reconnect(&mut backoff, &mut shutdown).await;
                continue;
            }
        };

        backoff.reset();
        let _ = events.send(RealtimeEvent::Connected);
        sync_recent_history(&api, &events, &mut seen);

        let mut disconnected_reason = "The server closed the WebSocket connection.".to_string();
        loop {
            tokio::select! {
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() {
                        let _ = socket.close(None).await;
                        return;
                    }
                }
                frame = socket.next() => {
                    match frame {
                        Some(Ok(WsMessage::Text(text))) => {
                            match handle_server_text(text.as_ref(), &events, &mut seen, &mut session) {
                                Ok(true) => {}
                                Ok(false) => {
                                    let _ = socket.close(None).await;
                                    return;
                                }
                                Err(error) => {
                                    disconnected_reason = error;
                                }
                            }
                            if !session.should_reconnect() {
                                let _ = events.send(RealtimeEvent::SessionExpired);
                                let _ = socket.close(None).await;
                                return;
                            }
                        }
                        Some(Ok(WsMessage::Ping(payload))) => {
                            if let Err(error) = socket.send(WsMessage::Pong(payload)).await {
                                disconnected_reason = format!("WebSocket pong failed: {error}");
                                break;
                            }
                        }
                        Some(Ok(WsMessage::Close(_))) | None => break,
                        Some(Ok(WsMessage::Binary(_))) => {
                            disconnected_reason = "The server sent an unsupported binary event.".into();
                            break;
                        }
                        Some(Ok(WsMessage::Pong(_))) => {}
                        Some(Err(error)) => {
                            disconnected_reason = format!("WebSocket disconnected: {error}");
                            break;
                        }
                        Some(Ok(_)) => {}
                    }
                }
            }
        }

        let _ = events.send(RealtimeEvent::Disconnected(disconnected_reason));
        wait_to_reconnect(&mut backoff, &mut shutdown).await;
    }
}

fn sync_recent_history(
    api: &crate::messaging::TomChatMessaging,
    events: &mpsc::Sender<RealtimeEvent>,
    seen: &mut MessageDeduplicator,
) {
    let conversations = match api.list_conversations() {
        Ok(page) => page.conversations,
        Err(error) => {
            let _ = events.send(RealtimeEvent::Disconnected(format!(
                "Could not synchronise conversations after reconnecting: {error}"
            )));
            return;
        }
    };
    for conversation in conversations {
        match api.list_messages(&conversation.id, 100, None) {
            Ok(page) => {
                let messages = page.messages.into_iter()
                    .filter(|message| seen.insert(&message.id))
                    .collect::<Vec<_>>();
                if !messages.is_empty() {
                    let _ = events.send(RealtimeEvent::HistorySync(messages));
                }
            }
            Err(error) => {
                let _ = events.send(RealtimeEvent::Disconnected(format!(
                    "Could not synchronise conversation history: {error}"
                )));
            }
        }
    }
}

fn handle_server_text(
    text: &str,
    events: &mpsc::Sender<RealtimeEvent>,
    seen: &mut MessageDeduplicator,
    session: &mut SessionState,
) -> Result<bool, String> {
    let envelope: crate::messaging::WebSocketEnvelope<serde_json::Value> =
        serde_json::from_str(text)
            .map_err(|error| format!("Invalid WebSocket event envelope: {error}"))?;
    if envelope.version != 1 || envelope.request_id.trim().is_empty() {
        return Err("The server sent an unsupported WebSocket event envelope.".into());
    }
    match envelope.event.as_str() {
        "message.deliver" => {
            let message: Message = serde_json::from_value(envelope.payload)
                .map_err(|error| format!("Invalid message delivery event: {error}"))?;
            if seen.insert(&message.id) {
                let _ = events.send(RealtimeEvent::LiveMessage(message));
            }
        }
        "message.ack" => {
            let ack: MessageAck = serde_json::from_value(envelope.payload)
                .map_err(|error| format!("Invalid message acknowledgement: {error}"))?;
            let _ = events.send(RealtimeEvent::Acknowledged {
                request_id: envelope.request_id,
                message_id: ack.message_id,
                sequence: ack.sequence,
            });
        }
        "error" => {
            let error: ServerError = serde_json::from_value(envelope.payload)
                .map_err(|error| format!("Invalid server error event: {error}"))?;
            session.apply_server_error(&error);
            if !session.should_reconnect() {
                return Ok(false);
            }
        }
        _ => {}
    }
    Ok(true)
}

async fn wait_to_reconnect(backoff: &mut ReconnectBackoff, shutdown: &mut watch::Receiver<bool>) {
    let jitter = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .subsec_millis() as f64;
    let factor = 0.8 + (jitter % 401.0) / 1000.0;
    let delay = backoff.next_delay(factor);
    tokio::select! {
        _ = shutdown.changed() => {}
        _ = sleep(delay) => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disconnects_use_exponential_backoff_capped_at_thirty_seconds() {
        let mut backoff = ReconnectBackoff::default();
        let delays = (0..7).map(|_| backoff.next_delay(1.0)).collect::<Vec<_>>();
        assert_eq!(delays, vec![
            Duration::from_secs(1), Duration::from_secs(2), Duration::from_secs(4),
            Duration::from_secs(8), Duration::from_secs(16), Duration::from_secs(30),
            Duration::from_secs(30),
        ]);
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
    fn duplicate_message_deliveries_are_ignored_and_cache_is_bounded() {
        let mut seen = MessageDeduplicator::new(2);
        assert!(seen.insert("message-1"));
        assert!(!seen.insert("message-1"));
        assert!(seen.insert("message-2"));
        assert!(seen.insert("message-3"));
        assert!(seen.insert("message-1"));
    }

    #[test]
    fn acknowledgement_matches_and_removes_pending_request() {
        let mut pending = PendingAcknowledgements::default();
        pending.track("request-1".into(), "client-message-1".into());
        assert_eq!(pending.acknowledge("unknown"), None);
        assert_eq!(pending.acknowledge("request-1").as_deref(), Some("client-message-1"));
        assert!(pending.is_empty());
    }

    #[test]
    fn expired_session_is_not_reconnected_with_the_same_token() {
        let mut state = SessionState::Active;
        assert!(state.should_reconnect());
        state.apply_server_error(&ServerError { code: "session_expired".into() });
        assert_eq!(state, SessionState::Expired);
        assert!(!state.should_reconnect());
    }

    #[test]
    fn unrelated_server_errors_do_not_expire_the_session() {
        let mut state = SessionState::Active;
        state.apply_server_error(&ServerError { code: "rate_limit_exceeded".into() });
        assert_eq!(state, SessionState::Active);
        assert!(state.should_reconnect());
    }

    #[test]
    fn parses_delivery_acknowledgement_and_expiry_events() {
        let delivery = r#"{"event":"message.deliver","payload":{"id":"m1","sequence":1,"conversation_id":"c1","sender_id":"u1","client_message_id":"cm1","ciphertext":"YQ==","nonce":"Yg==","encryption_version":1,"key_id":null,"created_at":"2026-11-06T00:00:00Z"}}"#;
        assert!(matches!(serde_json::from_str::<IncomingEvent>(delivery).unwrap(), IncomingEvent::MessageDeliver(_)));
        let ack = r#"{"event":"message.ack","payload":{"message_id":"m1","sequence":1}}"#;
        assert!(matches!(serde_json::from_str::<IncomingEvent>(ack).unwrap(), IncomingEvent::MessageAck(_)));
        let expired = r#"{"event":"error","payload":{"code":"session_expired"}}"#;
        assert!(matches!(serde_json::from_str::<IncomingEvent>(expired).unwrap(), IncomingEvent::Error(_)));
    }
}
