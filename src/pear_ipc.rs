//! Versioned length-prefixed IPC framing shared with the Pear/Bare worker.
//! This codec is independent of the Bare Kit process lifecycle and FFI host.

use serde_json::{Map, Value};

pub const PROTOCOL_VERSION: u64 = 1;
pub const MAX_FRAME_BYTES: usize = 64 * 1024;
const HEADER_BYTES: usize = 4;

/// Encode one protocol-v1 JSON object with a four-byte big-endian length prefix.
pub fn encode_frame(
    message_type: &str,
    mut payload: Map<String, Value>,
) -> Result<Vec<u8>, String> {
    if message_type.trim().is_empty() {
        return Err("Pear worker message type is required".to_string());
    }
    // Protocol metadata is controlled by the host, never by caller payload.
    payload.insert("version".to_string(), Value::from(PROTOCOL_VERSION));
    payload.insert("type".to_string(), Value::from(message_type));
    let body = serde_json::to_vec(&Value::Object(payload))
        .map_err(|error| format!("Could not encode Pear worker message: {error}"))?;
    if body.is_empty() || body.len() > MAX_FRAME_BYTES {
        return Err("Pear worker frame exceeds the 64 KiB limit".to_string());
    }
    let length = u32::try_from(body.len())
        .map_err(|_| "Pear worker frame length cannot be represented".to_string())?;
    let mut frame = Vec::with_capacity(HEADER_BYTES + body.len());
    frame.extend_from_slice(&length.to_be_bytes());
    frame.extend_from_slice(&body);
    Ok(frame)
}

pub struct FrameDecoder {
    pending: Vec<u8>,
}

impl FrameDecoder {
    pub fn new() -> Self {
        Self {
            pending: Vec::new(),
        }
    }

    /// Accept arbitrary IPC chunks; partial frames remain buffered.
    pub fn push(&mut self, chunk: &[u8]) -> Result<Vec<Value>, String> {
        self.pending.extend_from_slice(chunk);
        let mut messages = Vec::new();
        loop {
            if self.pending.len() < HEADER_BYTES {
                break;
            }
            let length = u32::from_be_bytes([
                self.pending[0],
                self.pending[1],
                self.pending[2],
                self.pending[3],
            ]) as usize;
            if length == 0 || length > MAX_FRAME_BYTES {
                self.pending.clear();
                return Err("Invalid Pear worker frame length".to_string());
            }
            let frame_length = HEADER_BYTES + length;
            if self.pending.len() < frame_length {
                break;
            }
            let body = self.pending[HEADER_BYTES..frame_length].to_vec();
            self.pending.drain(..frame_length);
            match parse_message(&body) {
                Ok(message) => messages.push(message),
                Err(error) => {
                    // A malformed frame makes the stream unusable. Drop any
                    // coalesced trailing bytes rather than parsing them after
                    // the protocol has lost frame synchronization.
                    self.pending.clear();
                    return Err(error);
                }
            }
        }
        Ok(messages)
    }

    pub fn has_pending_data(&self) -> bool {
        !self.pending.is_empty()
    }
}

impl Default for FrameDecoder {
    fn default() -> Self {
        Self::new()
    }
}

fn parse_message(body: &[u8]) -> Result<Value, String> {
    if body.is_empty() || body.len() > MAX_FRAME_BYTES {
        return Err("Invalid Pear worker frame size".to_string());
    }
    let message: Value =
        serde_json::from_slice(body).map_err(|_| "Invalid Pear worker JSON frame".to_string())?;
    let object = message
        .as_object()
        .ok_or_else(|| "Pear worker frame must be a JSON object".to_string())?;
    if object.get("version").and_then(Value::as_u64) != Some(PROTOCOL_VERSION)
        || object
            .get("type")
            .and_then(Value::as_str)
            .is_none_or(|message_type| message_type.trim().is_empty())
    {
        return Err("Unsupported Pear worker protocol version or message type".to_string());
    }
    Ok(message)
}

#[cfg(test)]
mod tests {
    use super::{encode_frame, FrameDecoder, MAX_FRAME_BYTES, PROTOCOL_VERSION};
    use serde_json::{json, Map};

    fn frame_body(frame: &[u8]) -> Vec<u8> {
        let length = u32::from_be_bytes(frame[..4].try_into().expect("header")) as usize;
        frame[4..4 + length].to_vec()
    }

    #[test]
    fn round_trips_worker_message() {
        let mut payload = Map::new();
        payload.insert("topic".into(), json!("ab".repeat(32)));
        let frame = encode_frame("swarm:join", payload).expect("encoded");
        let mut decoder = FrameDecoder::new();
        let messages = decoder.push(&frame).expect("decoded");
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0]["type"], "swarm:join");
        assert_eq!(messages[0]["version"], PROTOCOL_VERSION);
        assert_eq!(messages[0]["topic"], "ab".repeat(32));
        assert!(!decoder.has_pending_data());
    }

    #[test]
    fn buffers_partial_header_and_body() {
        let frame = encode_frame("backend:close", Map::new()).expect("encoded");
        let mut decoder = FrameDecoder::new();
        assert!(decoder
            .push(&frame[..2])
            .expect("header fragment")
            .is_empty());
        assert!(decoder
            .push(&frame[2..7])
            .expect("body fragment")
            .is_empty());
        let messages = decoder.push(&frame[7..]).expect("remainder");
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0]["type"], "backend:close");
        assert!(!decoder.has_pending_data());
    }

    #[test]
    fn decodes_multiple_frames_in_one_chunk() {
        let first = encode_frame("swarm:join", Map::new()).expect("first");
        let second = encode_frame("backend:close", Map::new()).expect("second");
        let mut decoder = FrameDecoder::new();
        let messages = decoder.push(&[first, second].concat()).expect("decoded");
        assert_eq!(messages.len(), 2);
        assert_eq!(messages[0]["type"], "swarm:join");
        assert_eq!(messages[1]["type"], "backend:close");
    }

    #[test]
    fn rejects_zero_and_oversized_lengths() {
        let mut decoder = FrameDecoder::new();
        assert!(decoder.push(&0_u32.to_be_bytes()).is_err());
        let too_large = ((MAX_FRAME_BYTES as u32) + 1).to_be_bytes();
        assert!(decoder.push(&too_large).is_err());
        assert!(!decoder.has_pending_data());
    }

    #[test]
    fn rejects_invalid_json_and_wrong_version() {
        let mut decoder = FrameDecoder::new();
        let invalid = [4_u32.to_be_bytes().as_slice(), b"nope".as_slice()].concat();
        assert!(decoder.push(&invalid).is_err());

        let wrong =
            serde_json::to_vec(&json!({"version": 2, "type": "backend:ready"})).expect("json");
        let frame = [
            (wrong.len() as u32).to_be_bytes().as_slice(),
            wrong.as_slice(),
        ]
        .concat();
        assert!(decoder.push(&frame).is_err());
    }

    #[test]
    fn rejects_non_objects_and_missing_message_types() {
        let mut decoder = FrameDecoder::new();
        let body = b"[]";
        let frame = [
            (body.len() as u32).to_be_bytes().as_slice(),
            body.as_slice(),
        ]
        .concat();
        assert!(decoder.push(&frame).is_err());

        let body = br#"{"version":1}"#;
        let frame = [
            (body.len() as u32).to_be_bytes().as_slice(),
            body.as_slice(),
        ]
        .concat();
        assert!(decoder.push(&frame).is_err());
    }

    #[test]
    fn malformed_frame_clears_coalesced_trailing_data() {
        let invalid_body = b"not-json";
        let invalid = [
            (invalid_body.len() as u32).to_be_bytes().as_slice(),
            invalid_body.as_slice(),
        ]
        .concat();
        let trailing = encode_frame("backend:ready", Map::new()).expect("trailing frame");
        let mut decoder = FrameDecoder::new();

        assert!(decoder.push(&[invalid, trailing].concat()).is_err());
        assert!(!decoder.has_pending_data());
        assert!(decoder
            .push(&trailing)
            .expect("fresh stream after error")
            .len() == 1);
    }

    #[test]
    fn rejects_whitespace_only_message_types() {
        let body = br#"{"version":1,"type":"   "}"#;
        let frame = [
            (body.len() as u32).to_be_bytes().as_slice(),
            body.as_slice(),
        ]
        .concat();
        let mut decoder = FrameDecoder::new();
        assert!(decoder.push(&frame).is_err());
    }

    #[test]
    fn caller_cannot_override_protocol_metadata() {
        let mut payload = Map::new();
        payload.insert("version".into(), json!(99));
        payload.insert("type".into(), json!("forged"));
        let frame = encode_frame("backend:close", payload).expect("encoded");
        let mut decoder = FrameDecoder::new();
        let messages = decoder.push(&frame).expect("decoded");
        assert_eq!(messages[0]["version"], PROTOCOL_VERSION);
        assert_eq!(messages[0]["type"], "backend:close");
        assert_eq!(
            frame_body(&frame).len() as u32,
            u32::from_be_bytes(frame[..4].try_into().unwrap())
        );
    }
}
