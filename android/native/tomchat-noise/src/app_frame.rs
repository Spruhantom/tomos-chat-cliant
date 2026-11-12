//! Versioned application frames carried only inside an authenticated Noise channel.
//!
//! Wire format matches Android AppFrameCodec: version (u8), type (u8),
//! message ID (16 bytes), payload length (u32 big-endian), then payload.
//! This codec does not encrypt; callers must pass its output to Noise.

use ring::rand::{SecureRandom, SystemRandom};
use std::fmt;

pub const APP_FRAME_VERSION: u8 = 1;
pub const APP_FRAME_HEADER_SIZE: usize = 22;
pub const MAX_APP_PAYLOAD_BYTES: usize = 16 * 1024;
pub const MAX_APP_FRAME_BYTES: usize = APP_FRAME_HEADER_SIZE + MAX_APP_PAYLOAD_BYTES;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum AppFrameType {
    Text = 1,
    GroupControl = 2,
    VoiceSignal = 3,
    GroupVoiceControl = 4,
    DeliveryAck = 5,
}

impl TryFrom<u8> for AppFrameType {
    type Error = AppFrameError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Text),
            2 => Ok(Self::GroupControl),
            3 => Ok(Self::VoiceSignal),
            4 => Ok(Self::GroupVoiceControl),
            5 => Ok(Self::DeliveryAck),
            _ => Err(AppFrameError::UnsupportedType),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AppFrameError {
    InvalidSize,
    UnsupportedVersion,
    UnsupportedType,
    InvalidPayloadLength,
    RandomGeneration,
}

impl fmt::Display for AppFrameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidSize => f.write_str("Invalid application frame size."),
            Self::UnsupportedVersion => f.write_str("Unsupported application protocol version."),
            Self::UnsupportedType => f.write_str("Unsupported application frame type."),
            Self::InvalidPayloadLength => f.write_str("Invalid application payload length."),
            Self::RandomGeneration => f.write_str("Could not generate an application message ID."),
        }
    }
}
impl std::error::Error for AppFrameError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppFrame {
    pub frame_type: AppFrameType,
    pub message_id: [u8; 16],
    pub payload: Vec<u8>,
}

impl AppFrame {
    pub fn new(frame_type: AppFrameType, payload: Vec<u8>) -> Result<Self, AppFrameError> {
        if payload.len() > MAX_APP_PAYLOAD_BYTES {
            return Err(AppFrameError::InvalidPayloadLength);
        }
        let mut message_id = [0u8; 16];
        SystemRandom::new()
            .fill(&mut message_id)
            .map_err(|_| AppFrameError::RandomGeneration)?;
        Ok(Self {
            frame_type,
            message_id,
            payload,
        })
    }

    pub fn with_id(
        frame_type: AppFrameType,
        message_id: [u8; 16],
        payload: Vec<u8>,
    ) -> Result<Self, AppFrameError> {
        if payload.len() > MAX_APP_PAYLOAD_BYTES {
            return Err(AppFrameError::InvalidPayloadLength);
        }
        Ok(Self {
            frame_type,
            message_id,
            payload,
        })
    }

    pub fn encode(&self) -> Result<Vec<u8>, AppFrameError> {
        if self.payload.len() > MAX_APP_PAYLOAD_BYTES {
            return Err(AppFrameError::InvalidPayloadLength);
        }
        let mut encoded = Vec::with_capacity(APP_FRAME_HEADER_SIZE + self.payload.len());
        encoded.push(APP_FRAME_VERSION);
        encoded.push(self.frame_type as u8);
        encoded.extend_from_slice(&self.message_id);
        encoded.extend_from_slice(&(self.payload.len() as u32).to_be_bytes());
        encoded.extend_from_slice(&self.payload);
        Ok(encoded)
    }

    pub fn decode(encoded: &[u8]) -> Result<Self, AppFrameError> {
        if !(APP_FRAME_HEADER_SIZE..=MAX_APP_FRAME_BYTES).contains(&encoded.len()) {
            return Err(AppFrameError::InvalidSize);
        }
        if encoded[0] != APP_FRAME_VERSION {
            return Err(AppFrameError::UnsupportedVersion);
        }
        let frame_type = AppFrameType::try_from(encoded[1])?;
        let mut message_id = [0u8; 16];
        message_id.copy_from_slice(&encoded[2..18]);
        let payload_length =
            u32::from_be_bytes(encoded[18..22].try_into().map_err(|_| AppFrameError::InvalidSize)?)
                as usize;
        if payload_length > MAX_APP_PAYLOAD_BYTES {
            return Err(AppFrameError::InvalidPayloadLength);
        }
        if payload_length != encoded.len() - APP_FRAME_HEADER_SIZE {
            return Err(AppFrameError::InvalidSize);
        }
        Ok(Self {
            frame_type,
            message_id,
            payload: encoded[APP_FRAME_HEADER_SIZE..].to_vec(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_format_matches_android_codec() {
        let frame = AppFrame::with_id(
            AppFrameType::Text,
            [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
            b"hi".to_vec(),
        )
        .unwrap();
        let encoded = frame.encode().unwrap();
        let expected = [
            1, 1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0, 0, 0, 2, b'h', b'i',
        ];
        assert_eq!(encoded, expected);
        assert_eq!(AppFrame::decode(&encoded).unwrap(), frame);
    }

    #[test]
    fn rejects_unknown_versions_types_and_trailing_bytes() {
        let mut encoded = AppFrame::new(AppFrameType::Text, b"hello".to_vec())
            .unwrap()
            .encode()
            .unwrap();
        encoded[0] = 2;
        assert_eq!(AppFrame::decode(&encoded), Err(AppFrameError::UnsupportedVersion));
        encoded[0] = APP_FRAME_VERSION;
        encoded[1] = 99;
        assert_eq!(AppFrame::decode(&encoded), Err(AppFrameError::UnsupportedType));
        encoded[1] = AppFrameType::Text as u8;
        encoded.push(0);
        assert_eq!(AppFrame::decode(&encoded), Err(AppFrameError::InvalidSize));
    }

    #[test]
    fn rejects_oversized_payloads() {
        let payload = vec![0; MAX_APP_PAYLOAD_BYTES + 1];
        assert_eq!(
            AppFrame::new(AppFrameType::Text, payload),
            Err(AppFrameError::InvalidPayloadLength)
        );
    }
}
