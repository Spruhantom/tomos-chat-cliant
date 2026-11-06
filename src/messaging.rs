//! Typed REST and event-envelope support for the TomChat messaging API.
//!
//! This module deliberately transports opaque ciphertext. It does not implement
//! cryptography or claim end-to-end encryption.

use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use std::time::Duration;

#[derive(Clone, Debug, Deserialize)]
pub struct Conversation {
    pub id: String,
    pub kind: String,
    pub title: Option<String>,
    pub created_at: String,
    pub last_message_at: Option<String>,
    pub member_count: usize,
    pub member_ids: Vec<String>,
}

#[derive(Debug, Deserialize)]
pub struct ConversationList {
    pub conversations: Vec<Conversation>,
}

#[derive(Clone, Debug, Serialize)]
pub struct CreateConversation {
    pub kind: String,
    pub title: Option<String>,
    pub member_ids: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct EncryptedMessageInput {
    pub conversation_id: String,
    pub client_message_id: String,
    pub ciphertext: String,
    pub nonce: String,
    pub encryption_version: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub key_id: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Message {
    pub id: String,
    pub sequence: i64,
    pub conversation_id: String,
    pub sender_id: String,
    pub client_message_id: String,
    pub ciphertext: String,
    pub nonce: String,
    pub encryption_version: u32,
    pub key_id: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Deserialize)]
pub struct MessagePage {
    pub messages: Vec<Message>,
    pub next_before: Option<i64>,
    pub next_after: Option<i64>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WebSocketEnvelope<T> {
    pub version: u32,
    pub event: String,
    pub request_id: String,
    pub payload: T,
}

pub struct TomChatMessaging {
    base_url: String,
    token: String,
    client: Client,
}

impl TomChatMessaging {
    pub fn new(base_url: &str, token: &str) -> Result<Self, String> {
        let base_url = super::validate_server_url(base_url)?;
        if token.trim().is_empty() {
            return Err("A TomChat session token is required.".to_string());
        }
        let client = Client::builder()
            .timeout(Duration::from_secs(20))
            .connect_timeout(Duration::from_secs(5))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|error| format!("Could not configure TomChat connection: {error}"))?;
        Ok(Self {
            base_url,
            token: token.to_string(),
            client,
        })
    }

    pub fn list_conversations(&self) -> Result<ConversationList, String> {
        self.client
            .get(format!("{}/api/v1/conversations", self.base_url))
            .bearer_auth(&self.token)
            .send()
            .map_err(|error| format!("Could not load conversations: {error}"))?
            .error_for_status()
            .map_err(|error| format!("Could not load conversations: {error}"))?
            .json()
            .map_err(|error| format!("Invalid conversation response: {error}"))
    }

    pub fn create_conversation(
        &self,
        request: &CreateConversation,
    ) -> Result<Conversation, String> {
        self.client
            .post(format!("{}/api/v1/conversations", self.base_url))
            .bearer_auth(&self.token)
            .json(request)
            .send()
            .map_err(|error| format!("Could not create conversation: {error}"))?
            .error_for_status()
            .map_err(|error| format!("Could not create conversation: {error}"))?
            .json()
            .map_err(|error| format!("Invalid conversation response: {error}"))
    }

    pub fn list_messages(
        &self,
        conversation_id: &str,
        limit: u8,
        before: Option<i64>,
    ) -> Result<MessagePage, String> {
        if !(1..=100).contains(&limit) {
            return Err("Message page size must be between 1 and 100.".to_string());
        }
        let mut request = self
            .client
            .get(format!(
                "{}/api/v1/conversations/{}/messages",
                self.base_url, conversation_id
            ))
            .bearer_auth(&self.token)
            .query(&[("limit", limit.to_string())]);
        if let Some(cursor) = before {
            request = request.query(&[("before", cursor.to_string())]);
        }
        request
            .send()
            .map_err(|error| format!("Could not load messages: {error}"))?
            .error_for_status()
            .map_err(|error| format!("Could not load messages: {error}"))?
            .json()
            .map_err(|error| format!("Invalid message history response: {error}"))
    }

    /// Fetch messages newer than the last sequence seen before a disconnect.
    pub fn list_messages_after(
        &self,
        conversation_id: &str,
        limit: u8,
        after: i64,
    ) -> Result<MessagePage, String> {
        if !(1..=100).contains(&limit) || after < 1 {
            return Err("Message page size or forward cursor is invalid.".to_string());
        }
        self.client
            .get(format!(
                "{}/api/v1/conversations/{}/messages",
                self.base_url, conversation_id
            ))
            .bearer_auth(&self.token)
            .query(&[("limit", limit.to_string()), ("after", after.to_string())])
            .send()
            .map_err(|error| format!("Could not synchronise new messages: {error}"))?
            .error_for_status()
            .map_err(|error| format!("Could not synchronise new messages: {error}"))?
            .json()
            .map_err(|error| format!("Invalid forward history response: {error}"))
    }

    pub fn send_encrypted_message(
        &self,
        message: &EncryptedMessageInput,
    ) -> Result<Message, String> {
        if message.encryption_version != 1 {
            return Err("Unsupported TomChat encryption envelope version.".to_string());
        }
        self.client
            .post(format!(
                "{}/api/v1/conversations/{}/messages",
                self.base_url, message.conversation_id
            ))
            .bearer_auth(&self.token)
            .json(message)
            .send()
            .map_err(|error| format!("Could not send message: {error}"))?
            .error_for_status()
            .map_err(|error| format!("Could not send message: {error}"))?
            .json()
            .map_err(|error| format!("Invalid sent-message response: {error}"))
    }

    pub fn websocket_endpoint(&self) -> Result<String, String> {
        let mut parsed = url::Url::parse(&self.base_url)
            .map_err(|error| format!("Invalid TomChat server URL: {error}"))?;
        let scheme = match parsed.scheme() {
            "https" => "wss",
            "http" => "ws",
            _ => return Err("Unsupported TomChat transport scheme.".to_string()),
        };
        parsed
            .set_scheme(scheme)
            .map_err(|_| "Could not construct WebSocket endpoint.".to_string())?;
        parsed.set_path("/api/v1/ws");
        parsed.set_query(None);
        parsed.set_fragment(None);
        Ok(parsed.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn websocket_endpoint_uses_secure_scheme_for_https() {
        let client = TomChatMessaging::new("https://chat.example.com", "session-token").unwrap();
        assert_eq!(
            client.websocket_endpoint().unwrap(),
            "wss://chat.example.com/api/v1/ws"
        );
    }

    #[test]
    fn websocket_endpoint_uses_loopback_ws_for_local_development() {
        let client = TomChatMessaging::new("http://127.0.0.1:8080", "session-token").unwrap();
        assert_eq!(
            client.websocket_endpoint().unwrap(),
            "ws://127.0.0.1:8080/api/v1/ws"
        );
    }

    #[test]
    fn rejects_empty_session_tokens() {
        assert!(TomChatMessaging::new("https://chat.example.com", " ").is_err());
    }

    #[test]
    fn rejects_invalid_page_sizes() {
        let client = TomChatMessaging::new("https://chat.example.com", "session-token").unwrap();
        assert!(client.list_messages("conversation-id", 0, None).is_err());
        assert!(client.list_messages("conversation-id", 101, None).is_err());
    }
}
