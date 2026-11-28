//! Typed command/event contract for the shared Pear/Bare worker.

use crate::pear_ipc::{encode_frame, FrameDecoder};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use serde::Deserialize;
use serde_json::{json, Value};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrustedPeer {
    pub transport_key: String,
    pub conversation_key: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncryptedMessage {
    pub id: String,
    pub ciphertext: String,
    pub sender_fingerprint: String,
    pub created_at: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WorkerCommand {
    ConfigureIdentity { public_key: String, secret_key: String, trusted_peers: Vec<TrustedPeer> },
    JoinSwarm { topic: String },
    TrustPeer(TrustedPeer),
    UntrustPeer { transport_key: String },
    AppendMessage { transport_key: String, message: EncryptedMessage },
    ListMessages { transport_key: String, request_id: String },
    Close,
}

impl WorkerCommand {
    pub fn encode(&self) -> Result<Vec<u8>, String> {
        let (kind, payload) = match self {
            Self::ConfigureIdentity { public_key, secret_key, trusted_peers } => {
                validate_key(public_key, "Local public key")?;
                validate_key(secret_key, "Local secret key")?;
                if trusted_peers.len() > 10_000 { return Err("Trusted peer list exceeds the worker limit".into()); }
                for peer in trusted_peers { validate_peer(peer)?; }
                ("identity:configure", json!({
                    "publicKey": public_key, "secretKey": secret_key,
                    "trustedPeers": trusted_peers.iter().map(|peer| json!({
                        "transportKey": peer.transport_key, "conversationKey": peer.conversation_key
                    })).collect::<Vec<_>>()
                }))
            }
            Self::JoinSwarm { topic } => {
                validate_key(topic, "Swarm topic")?;
                ("swarm:join", json!({ "topic": topic }))
            }
            Self::TrustPeer(peer) => {
                validate_peer(peer)?;
                ("peer:trust", json!({ "transportKey": peer.transport_key, "conversationKey": peer.conversation_key }))
            }
            Self::UntrustPeer { transport_key } => {
                validate_key(transport_key, "Peer key")?;
                ("peer:untrust", json!({ "transportKey": transport_key }))
            }
            Self::AppendMessage { transport_key, message } => {
                validate_key(transport_key, "Peer key")?;
                validate_uuid(&message.id, "Message ID")?;
                validate_fingerprint(&message.sender_fingerprint)?;
                if message.created_at > 9_007_199_254_740_991 { return Err("Message timestamp exceeds JavaScript's safe integer range".into()); }
                let decoded = BASE64.decode(&message.ciphertext).map_err(|_| "Message ciphertext must be valid base64".to_string())?;
                if decoded.is_empty() || decoded.len() > 24 * 1024 { return Err("Encrypted message must be between 1 byte and 24 KiB".into()); }
                ("message:append", json!({
                    "transportKey": transport_key, "id": message.id, "ciphertext": message.ciphertext,
                    "senderFingerprint": message.sender_fingerprint, "createdAt": message.created_at
                }))
            }
            Self::ListMessages { transport_key, request_id } => {
                validate_key(transport_key, "Peer key")?;
                validate_uuid(request_id, "History request ID")?;
                ("message:list", json!({ "transportKey": transport_key, "requestId": request_id }))
            }
            Self::Close => ("backend:close", json!({})),
        };
        let object = payload.as_object().cloned().ok_or_else(|| "Worker command payload must be a JSON object".to_string())?;
        encode_frame(kind, object)
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(tag = "type")]
pub enum WorkerEvent {
    #[serde(rename = "backend:ready")]
    BackendReady { #[serde(rename = "protocolVersion")] protocol_version: u64, storage: String, #[serde(rename = "identityConfigurationRequired")] identity_configuration_required: bool },
    #[serde(rename = "identity:configured")]
    IdentityConfigured { #[serde(rename = "trustedPeerCount")] trusted_peer_count: usize },
    #[serde(rename = "swarm:joined")]
    SwarmJoined { topic: String },
    #[serde(rename = "peer:trust-added")]
    PeerTrustAdded { #[serde(rename = "transportKey")] transport_key: String },
    #[serde(rename = "peer:trust-removed")]
    PeerTrustRemoved { #[serde(rename = "transportKey")] transport_key: String },
    #[serde(rename = "peer:authorized")]
    PeerAuthorized { #[serde(rename = "transportKey")] transport_key: String },
    #[serde(rename = "peer:rejected")]
    PeerRejected { #[serde(rename = "transportKey")] transport_key: String },
    #[serde(rename = "peer:closed")]
    PeerClosed { #[serde(rename = "transportKey")] transport_key: String },
    #[serde(rename = "peer:error")]
    PeerError { #[serde(rename = "transportKey")] transport_key: String, message: String },
    #[serde(rename = "message:stored")]
    MessageStored { #[serde(rename = "transportKey")] transport_key: String, id: String },
    #[serde(rename = "message:list-result")]
    MessageListResult { #[serde(rename = "transportKey")] transport_key: String, #[serde(rename = "requestId")] request_id: String, messages: Vec<StoredMessage> },
    #[serde(rename = "backend:error")]
    BackendError { message: String },
    #[serde(rename = "backend:fatal")]
    BackendFatal { message: String },
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StoredMessage {
    pub id: String,
    pub ciphertext: String,
    pub sender_fingerprint: String,
    pub created_at: u64,
    pub writer_transport_key: String,
}

pub fn decode_events(decoder: &mut FrameDecoder, chunk: &[u8]) -> Result<Vec<WorkerEvent>, String> {
    decoder.push(chunk)?.into_iter().map(decode_event).collect()
}

pub fn decode_event(value: Value) -> Result<WorkerEvent, String> {
    let kind = value.get("type").and_then(Value::as_str).ok_or_else(|| "Pear worker event is missing its type".to_string())?;
    if value.get("version").and_then(Value::as_u64) != Some(crate::pear_ipc::PROTOCOL_VERSION) {
        return Err("Pear worker event has an unsupported protocol version".into());
    }
    serde_json::from_value(value).map_err(|error| format!("Invalid Pear worker event {kind}: {error}"))
}

fn validate_peer(peer: &TrustedPeer) -> Result<(), String> {
    validate_key(&peer.transport_key, "Trusted peer key")?;
    validate_key(&peer.conversation_key, "Conversation key")
}

fn validate_key(value: &str, label: &str) -> Result<(), String> {
    if value.len() != 64 || !value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(format!("{label} must be a 32-byte hexadecimal value"));
    }
    Ok(())
}

fn validate_uuid(value: &str, label: &str) -> Result<(), String> {
    let bytes = value.as_bytes();
    if bytes.len() != 36 || ![8, 13, 18, 23].iter().all(|index| bytes[*index] == b'-')
        || !bytes.iter().enumerate().all(|(index, byte)| [8, 13, 18, 23].contains(&index) || byte.is_ascii_hexdigit()) {
        return Err(format!("{label} must be a UUID"));
    }
    Ok(())
}

fn validate_fingerprint(value: &str) -> Result<(), String> {
    let groups: Vec<&str> = value.split(' ').collect();
    if groups.len() != 16 || groups.iter().any(|group| group.len() != 4 || !group.bytes().all(|byte| byte.is_ascii_digit() || (b'A'..=b'F').contains(&byte))) {
        return Err("Sender fingerprint must contain 16 groups of four hexadecimal digits".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{decode_events, EncryptedMessage, TrustedPeer, WorkerCommand, WorkerEvent};
    use crate::pear_ipc::FrameDecoder;
    use serde_json::{json, Map};

    const KEY_A: &str = "1111111111111111111111111111111111111111111111111111111111111111";
    const KEY_B: &str = "2222222222222222222222222222222222222222222222222222222222222222";
    const UUID: &str = "123e4567-e89b-12d3-a456-426614174000";
    const FINGERPRINT: &str = "ABCD ABCD ABCD ABCD ABCD ABCD ABCD ABCD ABCD ABCD ABCD ABCD ABCD ABCD ABCD ABCD";

    fn decoded_command(command: WorkerCommand) -> serde_json::Value {
        let frame = command.encode().expect("valid command");
        FrameDecoder::new().push(&frame).expect("valid frame").remove(0)
    }

    #[test]
    fn encodes_identity_configuration_using_worker_field_names() {
        let message = decoded_command(WorkerCommand::ConfigureIdentity {
            public_key: KEY_A.into(), secret_key: KEY_B.into(),
            trusted_peers: vec![TrustedPeer { transport_key: KEY_B.into(), conversation_key: KEY_A.into() }],
        });
        assert_eq!(message["type"], "identity:configure");
        assert_eq!(message["publicKey"], KEY_A);
        assert_eq!(message["trustedPeers"][0]["transportKey"], KEY_B);
        assert_eq!(message["trustedPeers"][0]["conversationKey"], KEY_A);
    }

    #[test]
    fn encodes_encrypted_message_command() {
        let message = decoded_command(WorkerCommand::AppendMessage {
            transport_key: KEY_A.into(),
            message: EncryptedMessage { id: UUID.into(), ciphertext: "AQID".into(), sender_fingerprint: FINGERPRINT.into(), created_at: 42 },
        });
        assert_eq!(message["type"], "message:append");
        assert_eq!(message["transportKey"], KEY_A);
        assert_eq!(message["ciphertext"], "AQID");
        assert_eq!(message["createdAt"], 42);
    }

    #[test]
    fn rejects_invalid_identity_keys() {
        assert!(WorkerCommand::JoinSwarm { topic: "bad".into() }.encode().is_err());
        assert!(WorkerCommand::ConfigureIdentity { public_key: "bad".into(), secret_key: KEY_B.into(), trusted_peers: Vec::new() }.encode().is_err());
    }

    #[test]
    fn rejects_invalid_message_metadata_before_ipc() {
        let invalid = WorkerCommand::AppendMessage {
            transport_key: KEY_A.into(),
            message: EncryptedMessage { id: "not-a-uuid".into(), ciphertext: "AQID".into(), sender_fingerprint: FINGERPRINT.into(), created_at: 42 },
        };
        assert!(invalid.encode().is_err());
    }

    #[test]
    fn rejects_ciphertext_over_worker_limit() {
        let invalid = WorkerCommand::AppendMessage {
            transport_key: KEY_A.into(),
            message: EncryptedMessage { id: UUID.into(), ciphertext: "A".repeat(40 * 1024), sender_fingerprint: FINGERPRINT.into(), created_at: 42 },
        };
        assert!(invalid.encode().is_err());
    }

    #[test]
    fn decodes_worker_events_and_fragmented_reads() {
        let mut payload = Map::new();
        payload.insert("transportKey".into(), json!(KEY_A));
        let frame = crate::pear_ipc::encode_frame("peer:authorized", payload).expect("frame");
        let mut decoder = FrameDecoder::new();
        assert!(decode_events(&mut decoder, &frame[..3]).expect("header fragment").is_empty());
        assert_eq!(decode_events(&mut decoder, &frame[3..]).expect("remainder"),
            vec![WorkerEvent::PeerAuthorized { transport_key: KEY_A.into() }]);
    }

    #[test]
    fn rejects_unknown_event_types() {
        let frame = crate::pear_ipc::encode_frame("future:event", Map::new()).expect("frame");
        assert!(decode_events(&mut FrameDecoder::new(), &frame).is_err());
    }
}

    #[test]
    fn rejects_lowercase_fingerprints_and_unsafe_timestamps() {
        let lowercase = WorkerCommand::AppendMessage {
            transport_key: KEY_A.into(),
            message: EncryptedMessage {
                id: UUID.into(),
                ciphertext: "AQID".into(),
                sender_fingerprint: FINGERPRINT.to_ascii_lowercase(),
                created_at: 42,
            },
        };
        assert!(lowercase.encode().is_err());

        let unsafe_timestamp = WorkerCommand::AppendMessage {
            transport_key: KEY_A.into(),
            message: EncryptedMessage {
                id: UUID.into(),
                ciphertext: "AQID".into(),
                sender_fingerprint: FINGERPRINT.into(),
                created_at: 9_007_199_254_740_992,
            },
        };
        assert!(unsafe_timestamp.encode().is_err());
    }
}
