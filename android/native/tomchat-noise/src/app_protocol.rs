//! Cross-platform application frames matching Android AppFrameCodec v1.
//!
//! These frames are plaintext at this layer. Send them only through an
//! authenticated EncryptedPeerChannel; never write encoded frames to a socket.

use std::fmt;

pub const VERSION: u8 = 1;
pub const HEADER_LEN: usize = 22;
pub const MAX_PAYLOAD_LEN: usize = 16 * 1024;
pub const MAX_FRAME_LEN: usize = HEADER_LEN + MAX_PAYLOAD_LEN;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
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
            _ => Err(AppFrameError::UnknownType),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AppFrame {
    pub frame_type: AppFrameType,
    pub message_id: [u8; 16],
    pub payload: Vec<u8>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AppFrameError {
    TooShort,
    TooLarge,
    UnsupportedVersion,
    UnknownType,
    InvalidLength,
    InvalidPayload,
}

impl fmt::Display for AppFrameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::TooShort => "Application frame is shorter than its header.",
            Self::TooLarge => "Application frame exceeds the configured limit.",
            Self::UnsupportedVersion => "Unsupported application protocol version.",
            Self::UnknownType => "Unsupported application frame type.",
            Self::InvalidLength => "Application payload length does not match the frame.",
            Self::InvalidPayload => "Application frame payload is invalid for its type.",
        })
    }
}
impl std::error::Error for AppFrameError {}

impl AppFrame {
    pub fn encode(&self) -> Result<Vec<u8>, AppFrameError> {
        if self.payload.len() > MAX_PAYLOAD_LEN {
            return Err(AppFrameError::TooLarge);
        }
        if self.frame_type == AppFrameType::DeliveryAck && !self.payload.is_empty() {
            return Err(AppFrameError::InvalidPayload);
        }
        if matches!(self.frame_type, AppFrameType::Text) && self.payload.is_empty() {
            return Err(AppFrameError::InvalidPayload);
        }
        let mut out = Vec::with_capacity(HEADER_LEN + self.payload.len());
        out.push(VERSION);
        out.push(self.frame_type as u8);
        out.extend_from_slice(&self.message_id);
        out.extend_from_slice(&(self.payload.len() as u32).to_be_bytes());
        out.extend_from_slice(&self.payload);
        Ok(out)
    }

    pub fn decode(input: &[u8]) -> Result<Self, AppFrameError> {
        if input.len() < HEADER_LEN {
            return Err(AppFrameError::TooShort);
        }
        if input.len() > MAX_FRAME_LEN {
            return Err(AppFrameError::TooLarge);
        }
        if input[0] != VERSION {
            return Err(AppFrameError::UnsupportedVersion);
        }
        let frame_type = AppFrameType::try_from(input[1])?;
        let mut message_id = [0u8; 16];
        message_id.copy_from_slice(&input[2..18]);
        let mut length = [0u8; 4];
        length.copy_from_slice(&input[18..22]);
        let payload_len = u32::from_be_bytes(length) as usize;
        if payload_len > MAX_PAYLOAD_LEN || payload_len != input.len() - HEADER_LEN {
            return Err(AppFrameError::InvalidLength);
        }
        let payload = input[HEADER_LEN..].to_vec();
        if (frame_type == AppFrameType::DeliveryAck && !payload.is_empty())
            || (frame_type == AppFrameType::Text && payload.is_empty())
        {
            return Err(AppFrameError::InvalidPayload);
        }
        Ok(Self { frame_type, message_id, payload })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> AppFrame {
        AppFrame {
            frame_type: AppFrameType::Text,
            message_id: [
                0x9d, 0x5a, 0x4f, 0x2a, 0x0e, 0x54, 0x4f, 0x77,
                0x8f, 0x5b, 0x8f, 0x39, 0xc0, 0xf5, 0xa0, 0xa1,
            ],
            payload: b"hello securely".to_vec(),
        }
    }

    #[test]
    fn round_trips_android_text_frame() {
        let frame = sample();
        assert_eq!(AppFrame::decode(&frame.encode().unwrap()).unwrap(), frame);
    }

    #[test]
    fn matches_android_golden_header_and_payload() {
        let encoded = sample().encode().unwrap();
        assert_eq!(&encoded[..22], &[
            1, 1, 0x9d, 0x5a, 0x4f, 0x2a, 0x0e, 0x54, 0x4f, 0x77,
            0x8f, 0x5b, 0x8f, 0x39, 0xc0, 0xf5, 0xa0, 0xa1,
            0, 0, 0, 14,
        ]);
        assert_eq!(&encoded[22..], b"hello securely");
    }

    #[test]
    fn rejects_unknown_type_version_and_bad_lengths() {
        let encoded = sample().encode().unwrap();
        let mut bad = encoded.clone();
        bad[0] = 2;
        assert_eq!(AppFrame::decode(&bad), Err(AppFrameError::UnsupportedVersion));
        let mut bad = encoded.clone();
        bad[1] = 99;
        assert_eq!(AppFrame::decode(&bad), Err(AppFrameError::UnknownType));
        let mut bad = encoded;
        bad[21] = 99;
        assert_eq!(AppFrame::decode(&bad), Err(AppFrameError::InvalidLength));
    }

    #[test]
    fn delivery_ack_must_have_an_empty_payload() {
        let frame = AppFrame {
            frame_type: AppFrameType::DeliveryAck,
            message_id: [7; 16],
            payload: vec![],
        };
        assert_eq!(AppFrame::decode(&frame.encode().unwrap()).unwrap(), frame);
        let invalid = AppFrame { payload: vec![1], ..frame };
        assert_eq!(invalid.encode(), Err(AppFrameError::InvalidPayload));
    }
}
