//! Versioned, bounded envelope for MLS control and application messages.
//!
//! This module does not encrypt, authenticate, or apply MLS state. Callers must
//! carry encoded frames inside an already authenticated Noise channel and must
//! pass payloads to OpenMLS before treating them as membership or chat events.

use std::fmt;

pub const VERSION: u8 = 1;
pub const HEADER_LEN: usize = 32;
pub const MAX_PAYLOAD_LEN: usize = 16 * 1024 - HEADER_LEN;
pub const MAX_FRAME_LEN: usize = HEADER_LEN + MAX_PAYLOAD_LEN;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum GroupMessageKind {
    Proposal = 1,
    Commit = 2,
    Welcome = 3,
    Application = 4,
}

impl TryFrom<u8> for GroupMessageKind {
    type Error = GroupFrameError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Proposal),
            2 => Ok(Self::Commit),
            3 => Ok(Self::Welcome),
            4 => Ok(Self::Application),
            _ => Err(GroupFrameError::UnknownKind),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GroupProtocolFrame {
    pub kind: GroupMessageKind,
    pub group_id: [u8; 16],
    pub epoch: u64,
    pub payload: Vec<u8>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GroupFrameError {
    TooShort,
    TooLarge,
    BadMagic,
    UnsupportedVersion,
    UnknownKind,
    InvalidLength,
    EmptyPayload,
}

impl fmt::Display for GroupFrameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::TooShort => "Group protocol frame is shorter than its header.",
            Self::TooLarge => "Group protocol frame exceeds the configured limit.",
            Self::BadMagic => "Group protocol frame has an invalid magic value.",
            Self::UnsupportedVersion => "Unsupported group protocol version.",
            Self::UnknownKind => "Unsupported group protocol message kind.",
            Self::InvalidLength => "Group protocol payload length does not match the frame.",
            Self::EmptyPayload => "Group protocol payload cannot be empty.",
        })
    }
}

impl std::error::Error for GroupFrameError {}

impl GroupProtocolFrame {
    /// Encodes: magic 'TG', version, kind, group UUID, epoch, payload length, payload.
    pub fn encode(&self) -> Result<Vec<u8>, GroupFrameError> {
        if self.payload.is_empty() {
            return Err(GroupFrameError::EmptyPayload);
        }
        if self.payload.len() > MAX_PAYLOAD_LEN {
            return Err(GroupFrameError::TooLarge);
        }

        let mut out = Vec::with_capacity(HEADER_LEN + self.payload.len());
        out.extend_from_slice(b"TG");
        out.push(VERSION);
        out.push(self.kind as u8);
        out.extend_from_slice(&self.group_id);
        out.extend_from_slice(&self.epoch.to_be_bytes());
        out.extend_from_slice(&(self.payload.len() as u32).to_be_bytes());
        out.extend_from_slice(&self.payload);
        Ok(out)
    }

    pub fn decode(input: &[u8]) -> Result<Self, GroupFrameError> {
        if input.len() < HEADER_LEN {
            return Err(GroupFrameError::TooShort);
        }
        if input.len() > MAX_FRAME_LEN {
            return Err(GroupFrameError::TooLarge);
        }
        if &input[0..2] != b"TG" {
            return Err(GroupFrameError::BadMagic);
        }
        if input[2] != VERSION {
            return Err(GroupFrameError::UnsupportedVersion);
        }

        let kind = GroupMessageKind::try_from(input[3])?;
        let mut group_id = [0u8; 16];
        group_id.copy_from_slice(&input[4..20]);
        let mut epoch_bytes = [0u8; 8];
        epoch_bytes.copy_from_slice(&input[20..28]);
        let epoch = u64::from_be_bytes(epoch_bytes);
        let mut length_bytes = [0u8; 4];
        length_bytes.copy_from_slice(&input[28..32]);
        let payload_len = u32::from_be_bytes(length_bytes) as usize;

        if payload_len == 0 {
            return Err(GroupFrameError::EmptyPayload);
        }
        if payload_len > MAX_PAYLOAD_LEN || payload_len != input.len() - HEADER_LEN {
            return Err(GroupFrameError::InvalidLength);
        }

        Ok(Self {
            kind,
            group_id,
            epoch,
            payload: input[HEADER_LEN..].to_vec(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> GroupProtocolFrame {
        GroupProtocolFrame {
            kind: GroupMessageKind::Application,
            group_id: [
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff,
            ],
            epoch: 7,
            payload: vec![0xde, 0xad, 0xbe, 0xef],
        }
    }

    #[test]
    fn round_trips_group_application_frame() {
        let frame = sample();
        assert_eq!(GroupProtocolFrame::decode(&frame.encode().unwrap()).unwrap(), frame);
    }

    #[test]
    fn matches_android_golden_wire_vector() {
        let encoded = sample().encode().unwrap();
        let expected = [
            0x54, 0x47, 0x01, 0x04,
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07,
            0x00, 0x00, 0x00, 0x04, 0xde, 0xad, 0xbe, 0xef,
        ];
        assert_eq!(encoded, expected);
    }

    #[test]
    fn rejects_wrong_magic_version_kind_and_length() {
        let valid = sample().encode().unwrap();
        let mut bad = valid.clone();
        bad[0] = 0;
        assert_eq!(GroupProtocolFrame::decode(&bad), Err(GroupFrameError::BadMagic));
        let mut bad = valid.clone();
        bad[2] = 2;
        assert_eq!(GroupProtocolFrame::decode(&bad), Err(GroupFrameError::UnsupportedVersion));
        let mut bad = valid.clone();
        bad[3] = 99;
        assert_eq!(GroupProtocolFrame::decode(&bad), Err(GroupFrameError::UnknownKind));
        let mut bad = valid.clone();
        bad[31] = 9;
        assert_eq!(GroupProtocolFrame::decode(&bad), Err(GroupFrameError::InvalidLength));
    }

    #[test]
    fn rejects_empty_oversized_and_truncated_frames() {
        let mut frame = sample();
        frame.payload.clear();
        assert_eq!(frame.encode(), Err(GroupFrameError::EmptyPayload));
        assert_eq!(GroupProtocolFrame::decode(&[0u8; 8]), Err(GroupFrameError::TooShort));
        assert_eq!(
            GroupProtocolFrame::decode(&vec![0u8; MAX_FRAME_LEN + 1]),
            Err(GroupFrameError::TooLarge)
        );
    }
}
