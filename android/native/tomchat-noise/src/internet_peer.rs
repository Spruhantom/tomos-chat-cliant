//! Signed, short-lived direct-peer endpoint candidate wire format.
//!
//! This module only encodes and validates the public endpoint record. The
//! 64-byte signature covers every preceding byte and MUST be verified against
//! the contact's separately trusted signing identity by the caller before use.
//! A Noise static key is not an P-256 signing key and must not be reused as
//! one. This codec performs no discovery, NAT traversal or message routing.

use std::fmt;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

const MAGIC: [u8; 2] = *b"TP";
pub const VERSION: u8 = 1;
pub const MAX_RECORD_LEN: usize = 2 + 1 + 1 + 8 + 8 + 16 + 16 + 1 + 16 + 2 + 64;
pub const MAX_TTL_SECS: u64 = 300;
const MAX_FUTURE_SKEW_SECS: u64 = 30;
const SIGNATURE_LEN: usize = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum CandidateTransport {
    Tcp = 1,
    Udp = 2,
}

impl TryFrom<u8> for CandidateTransport {
    type Error = CandidateError;
    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Tcp),
            2 => Ok(Self::Udp),
            _ => Err(CandidateError::InvalidTransport),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EndpointCandidate {
    pub transport: CandidateTransport,
    pub issued_at: u64,
    pub expires_at: u64,
    pub device_id: [u8; 16],
    pub nonce: [u8; 16],
    pub address: IpAddr,
    pub port: u16,
    /// Detached P-256 ECDSA signature in fixed 64-byte r||s form.
    pub signature: [u8; SIGNATURE_LEN],
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CandidateError {
    InvalidMagic,
    UnsupportedVersion,
    InvalidTransport,
    InvalidLength,
    InvalidAddress,
    InvalidPort,
    InvalidLifetime,
    Expired,
    IssuedInFuture,
    EmptyIdentity,
    EmptyNonce,
}

impl fmt::Display for CandidateError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::InvalidMagic => "Invalid peer candidate magic.",
            Self::UnsupportedVersion => "Unsupported peer candidate version.",
            Self::InvalidTransport => "Unsupported peer candidate transport.",
            Self::InvalidLength => "Invalid peer candidate length.",
            Self::InvalidAddress => "Peer candidate address is not globally routable.",
            Self::InvalidPort => "Peer candidate port must be non-zero.",
            Self::InvalidLifetime => "Peer candidate lifetime is invalid.",
            Self::Expired => "Peer candidate has expired.",
            Self::IssuedInFuture => "Peer candidate issue time is too far in the future.",
            Self::EmptyIdentity => "Peer candidate device identity is empty.",
            Self::EmptyNonce => "Peer candidate nonce is empty.",
        })
    }
}
impl std::error::Error for CandidateError {}

impl EndpointCandidate {
    /// Bytes that must be signed by the device's dedicated signing identity.
    pub fn signing_bytes(&self) -> Result<Vec<u8>, CandidateError> {
        self.validate_shape()?;
        let mut out = Vec::with_capacity(MAX_RECORD_LEN - SIGNATURE_LEN);
        out.extend_from_slice(&MAGIC);
        out.push(VERSION);
        out.push(self.transport as u8);
        out.extend_from_slice(&self.issued_at.to_be_bytes());
        out.extend_from_slice(&self.expires_at.to_be_bytes());
        out.extend_from_slice(&self.device_id);
        out.extend_from_slice(&self.nonce);
        match self.address {
            IpAddr::V4(address) => {
                out.push(4);
                out.extend_from_slice(&address.octets());
            }
            IpAddr::V6(address) => {
                out.push(6);
                out.extend_from_slice(&address.octets());
            }
        }
        out.extend_from_slice(&self.port.to_be_bytes());
        Ok(out)
    }

    pub fn encode(&self) -> Result<Vec<u8>, CandidateError> {
        let mut out = self.signing_bytes()?;
        out.extend_from_slice(&self.signature);
        Ok(out)
    }

    /// Decode and enforce structural/time/routability constraints. The caller
    /// must still verify the detached signature before connecting to address.
    pub fn decode(bytes: &[u8], now: u64) -> Result<Self, CandidateError> {
        if bytes.len() < 2 + 1 + 1 + 8 + 8 + 16 + 16 + 1 + 2 + SIGNATURE_LEN
            || bytes.len() > MAX_RECORD_LEN
        {
            return Err(CandidateError::InvalidLength);
        }
        if bytes[0..2] != MAGIC {
            return Err(CandidateError::InvalidMagic);
        }
        if bytes[2] != VERSION {
            return Err(CandidateError::UnsupportedVersion);
        }
        let transport = CandidateTransport::try_from(bytes[3])?;
        let issued_at = u64::from_be_bytes(bytes[4..12].try_into().unwrap());
        let expires_at = u64::from_be_bytes(bytes[12..20].try_into().unwrap());
        let mut device_id = [0; 16];
        device_id.copy_from_slice(&bytes[20..36]);
        let mut nonce = [0; 16];
        nonce.copy_from_slice(&bytes[36..52]);
        let family = bytes[52];
        let (address, address_len, port_offset) = match family {
            4 if bytes.len() == 4 + 8 + 8 + 16 + 16 + 1 + 4 + 2 + SIGNATURE_LEN => {
                (IpAddr::V4(Ipv4Addr::new(bytes[53], bytes[54], bytes[55], bytes[56])), 4, 57)
            }
            6 if bytes.len() == 4 + 8 + 8 + 16 + 16 + 1 + 16 + 2 + SIGNATURE_LEN => {
                let octets: [u8; 16] = bytes[53..69].try_into().unwrap();
                (IpAddr::V6(Ipv6Addr::from(octets)), 16, 69)
            }
            _ => return Err(CandidateError::InvalidLength),
        };
        let _ = address_len;
        let port = u16::from_be_bytes(bytes[port_offset..port_offset + 2].try_into().unwrap());
        let mut signature = [0; SIGNATURE_LEN];
        signature.copy_from_slice(&bytes[port_offset + 2..]);
        let candidate = Self {
            transport, issued_at, expires_at, device_id, nonce, address, port, signature,
        };
        candidate.validate_at(now)?;
        Ok(candidate)
    }

    pub fn validate_at(&self, now: u64) -> Result<(), CandidateError> {
        self.validate_shape()?;
        if self.issued_at > now.saturating_add(MAX_FUTURE_SKEW_SECS) {
            return Err(CandidateError::IssuedInFuture);
        }
        if self.expires_at <= now {
            return Err(CandidateError::Expired);
        }
        Ok(())
    }

    fn validate_shape(&self) -> Result<(), CandidateError> {
        if self.device_id.iter().all(|byte| *byte == 0) {
            return Err(CandidateError::EmptyIdentity);
        }
        if self.nonce.iter().all(|byte| *byte == 0) {
            return Err(CandidateError::EmptyNonce);
        }
        if self.port == 0 {
            return Err(CandidateError::InvalidPort);
        }
        if self.expires_at <= self.issued_at
            || self.expires_at - self.issued_at > MAX_TTL_SECS
        {
            return Err(CandidateError::InvalidLifetime);
        }
        if !is_public_address(self.address) {
            return Err(CandidateError::InvalidAddress);
        }
        Ok(())
    }
}

fn is_public_address(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(ip) => is_public_v4(ip),
        IpAddr::V6(ip) => is_public_v6(ip),
    }
}

fn is_public_v4(ip: Ipv4Addr) -> bool {
    let [a, b, c, d] = ip.octets();
    !(a == 0
        || a == 10
        || a == 127
        || (a == 100 && (64..=127).contains(&b))
        || (a == 169 && b == 254)
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 168)
        || (a == 192 && b == 0 && c == 0)
        || (a == 192 && b == 0 && c == 2)
        || (a == 192 && b == 88 && c == 99)
        || (a == 198 && (b == 18 || b == 19))
        || (a == 198 && b == 51 && c == 100)
        || (a == 203 && b == 0 && c == 113)
        || a >= 224
        || (a == 255 && b == 255 && c == 255 && d == 255))
}

fn is_public_v6(ip: Ipv6Addr) -> bool {
    let octets = ip.octets();
    !(ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_multicast()
        || ip.is_unicast_link_local()
        || (octets[0] & 0xfe) == 0xfc // unique-local fc00::/7
        || (octets[0] == 0x20 && octets[1] == 0x01 && octets[2] == 0x0d && octets[3] == 0xb8) // documentation
        || ip.to_ipv4_mapped().is_some())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(address: IpAddr) -> EndpointCandidate {
        EndpointCandidate {
            transport: CandidateTransport::Tcp,
            issued_at: 1_000,
            expires_at: 1_240,
            device_id: [7; 16],
            nonce: [9; 16],
            address,
            port: 4242,
            signature: [3; 64],
        }
    }

    #[test]
    fn ipv4_candidate_round_trips_with_fixed_signature() {
        let value = sample("8.8.8.8".parse().unwrap());
        let encoded = value.encode().unwrap();
        assert_eq!(EndpointCandidate::decode(&encoded, 1_100).unwrap(), value);
    }

    #[test]
    fn ipv6_candidate_round_trips() {
        let value = sample("2606:4700:4700::1111".parse().unwrap());
        let encoded = value.encode().unwrap();
        assert_eq!(EndpointCandidate::decode(&encoded, 1_100).unwrap(), value);
    }

    #[test]
    fn rejects_private_and_local_addresses() {
        for address in ["10.0.0.1", "192.168.1.2", "127.0.0.1", "169.254.1.1", "100.64.0.1", "224.0.0.1"] {
            assert_eq!(sample(address.parse().unwrap()).encode(), Err(CandidateError::InvalidAddress));
        }
        for address in ["::1", "fe80::1", "fc00::1", "ff02::1", "2001:db8::1"] {
            assert_eq!(sample(address.parse().unwrap()).encode(), Err(CandidateError::InvalidAddress));
        }
    }

    #[test]
    fn rejects_expired_future_and_overlong_records() {
        let mut value = sample("1.1.1.1".parse().unwrap());
        assert_eq!(value.validate_at(1_240), Err(CandidateError::Expired));
        value.issued_at = 1_100;
        value.expires_at = 1_401;
        assert_eq!(value.encode(), Err(CandidateError::InvalidLifetime));
        value.issued_at = 2_000;
        value.expires_at = 2_100;
        assert_eq!(value.validate_at(1_000), Err(CandidateError::IssuedInFuture));
    }

    #[test]
    fn rejects_bad_header_and_trailing_bytes() {
        let value = sample("1.1.1.1".parse().unwrap());
        let mut bytes = value.encode().unwrap();
        bytes[0] = 0;
        assert_eq!(EndpointCandidate::decode(&bytes, 1_100), Err(CandidateError::InvalidMagic));
        let mut bytes = value.encode().unwrap();
        bytes.push(0);
        assert_eq!(EndpointCandidate::decode(&bytes, 1_100), Err(CandidateError::InvalidLength));
    }

    #[test]
    fn signing_bytes_exclude_detached_signature() {
        let value = sample("1.1.1.1".parse().unwrap());
        assert_eq!(value.encode().unwrap().len(), value.signing_bytes().unwrap().len() + 64);
    }
}
