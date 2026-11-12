//! Distributed peer discovery, UDP hole-punch coordination and reconnect policy.
//!
//! This module contains bounded wire primitives and NAT-PMP packet support.
//! Rendezvous messages are intended to travel inside an authenticated peer
//! channel between independently operated bootstrap peers. They contain only
//! short-lived signed endpoint hints: never messages, status content or keys.
//! A rendezvous result is untrusted until each endpoint signature and the
//! subsequent pinned Noise handshake have been verified.

use crate::internet_peer::{CandidateError, EndpointCandidate, MAX_TTL_SECS};
use ring::signature::{UnparsedPublicKey, ECDSA_P256_SHA256_FIXED};
use std::fmt;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::time::Duration;

const RV_MAGIC: [u8; 2] = *b"TR";
const PUNCH_MAGIC: [u8; 2] = *b"TH";
pub const MAX_CANDIDATES: usize = 8;
pub const MAX_RENDEZVOUS_RECORD: usize = 2 + 1 + 16 + 8 + 8 + 1 + MAX_CANDIDATES * 135 + 64;
pub const MAX_RENDEZVOUS_TTL_SECS: u64 = 300;
pub const MAX_PUNCH_CLOCK_SKEW_SECS: u64 = 30;
pub const MAX_GATEWAY_LEASE_SECS: u32 = 600;

#[derive(Debug)]
pub enum DiscoveryError {
    InvalidRecord,
    Expired,
    FutureTimestamp,
    Candidate(CandidateError),
    InvalidSignature,
    IdentityMismatch,
    Io(std::io::Error),
    NatPmp(&'static str),
}
impl fmt::Display for DiscoveryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidRecord => f.write_str("Invalid rendezvous or hole-punch record."),
            Self::Expired => f.write_str("Rendezvous record has expired."),
            Self::FutureTimestamp => f.write_str("Record timestamp is too far in the future."),
            Self::Candidate(e) => write!(f, "Invalid endpoint candidate: {e}"),
            Self::InvalidSignature => f.write_str("Record signature is invalid."),
            Self::IdentityMismatch => f.write_str("Record identity does not match the trusted peer."),
            Self::Io(e) => write!(f, "Network operation failed: {e}"),
            Self::NatPmp(e) => write!(f, "NAT-PMP error: {e}"),
        }
    }
}
impl std::error::Error for DiscoveryError {}
impl From<CandidateError> for DiscoveryError { fn from(e: CandidateError) -> Self { Self::Candidate(e) } }
impl From<std::io::Error> for DiscoveryError { fn from(e: std::io::Error) -> Self { Self::Io(e) } }

/// A short-lived, identity-signed DHT value. The DHT key is the target device
/// ID; the value contains no private information beyond approved public routes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RendezvousRecord {
    pub device_id: [u8; 16],
    pub sequence: u64,
    pub issued_at: u64,
    pub expires_at: u64,
    pub candidates: Vec<Vec<u8>>,
    pub signature: [u8; 64],
}
impl RendezvousRecord {
    pub fn signing_bytes(&self) -> Result<Vec<u8>, DiscoveryError> {
        if self.device_id.iter().all(|b| *b == 0)
            || self.candidates.is_empty() || self.candidates.len() > MAX_CANDIDATES
            || self.expires_at <= self.issued_at
            || self.expires_at - self.issued_at > MAX_RENDEZVOUS_TTL_SECS {
            return Err(DiscoveryError::InvalidRecord);
        }
        let mut out = Vec::with_capacity(MAX_RENDEZVOUS_RECORD);
        out.extend_from_slice(&RV_MAGIC);
        out.push(1);
        out.extend_from_slice(&self.device_id);
        out.extend_from_slice(&self.sequence.to_be_bytes());
        out.extend_from_slice(&self.issued_at.to_be_bytes());
        out.extend_from_slice(&self.expires_at.to_be_bytes());
        out.push(self.candidates.len() as u8);
        for candidate in &self.candidates {
            if candidate.len() > u16::MAX as usize { return Err(DiscoveryError::InvalidRecord); }
            out.extend_from_slice(&(candidate.len() as u16).to_be_bytes());
            out.extend_from_slice(candidate);
        }
        Ok(out)
    }
    pub fn encode(&self) -> Result<Vec<u8>, DiscoveryError> {
        let mut out = self.signing_bytes()?;
        out.extend_from_slice(&self.signature);
        if out.len() > MAX_RENDEZVOUS_RECORD { return Err(DiscoveryError::InvalidRecord); }
        Ok(out)
    }
    pub fn verify(&self, now: u64, public_key: &[u8], expected_device: &[u8;16]) -> Result<(), DiscoveryError> {
        if &self.device_id != expected_device { return Err(DiscoveryError::IdentityMismatch); }
        if self.issued_at > now.saturating_add(MAX_PUNCH_CLOCK_SKEW_SECS) { return Err(DiscoveryError::FutureTimestamp); }
        if self.expires_at <= now { return Err(DiscoveryError::Expired); }
        for bytes in &self.candidates {
            let candidate = EndpointCandidate::decode(bytes, now)?;
            candidate.verify_signature(public_key, expected_device)?;
        }
        UnparsedPublicKey::new(&ECDSA_P256_SHA256_FIXED, public_key)
            .verify(&self.signing_bytes()?, &self.signature)
            .map_err(|_| DiscoveryError::InvalidSignature)
    }
}

/// A lookup/announce envelope. The caller MUST send it only over a mutually
/// authenticated peer channel. Bootstrap peers are configured by the user or
/// learned from trusted peers; no single bootstrap is authoritative.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RendezvousQuery {
    pub target_device_id: [u8; 16],
    pub requester_device_id: [u8; 16],
    pub nonce: [u8; 16],
    pub issued_at: u64,
}
impl RendezvousQuery {
    pub fn encode(&self) -> Result<[u8; 59], DiscoveryError> {
        if self.target_device_id.iter().all(|b| *b == 0)
            || self.requester_device_id.iter().all(|b| *b == 0)
            || self.nonce.iter().all(|b| *b == 0) { return Err(DiscoveryError::InvalidRecord); }
        let mut out = [0u8;59];
        out[..2].copy_from_slice(&RV_MAGIC);
        out[2] = 2;
        out[3..19].copy_from_slice(&self.target_device_id);
        out[19..35].copy_from_slice(&self.requester_device_id);
        out[35..51].copy_from_slice(&self.nonce);
        out[51..59].copy_from_slice(&self.issued_at.to_be_bytes());
        Ok(out)
    }
}

/// Signed UDP probe. A successful probe only establishes bidirectional UDP
/// reachability; application traffic still requires a Noise handshake.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HolePunchProbe {
    pub session_id: [u8;16],
    pub nonce: [u8;16],
    pub device_id: [u8;16],
    pub sent_at: u64,
    pub signature: [u8;64],
}
impl HolePunchProbe {
    pub fn signing_bytes(&self) -> Result<[u8;59], DiscoveryError> {
        if self.session_id.iter().all(|b| *b == 0) || self.nonce.iter().all(|b| *b == 0)
            || self.device_id.iter().all(|b| *b == 0) { return Err(DiscoveryError::InvalidRecord); }
        let mut out=[0u8;59];
        out[..2].copy_from_slice(&PUNCH_MAGIC); out[2]=1;
        out[3..19].copy_from_slice(&self.session_id);
        out[19..35].copy_from_slice(&self.nonce);
        out[35..51].copy_from_slice(&self.device_id);
        out[51..59].copy_from_slice(&self.sent_at.to_be_bytes());
        Ok(out)
    }
    pub fn encode(&self) -> Result<[u8;123], DiscoveryError> {
        let mut out=[0u8;123]; out[..59].copy_from_slice(&self.signing_bytes()?);
        out[59..].copy_from_slice(&self.signature); Ok(out)
    }
    pub fn verify(&self, now:u64, public_key:&[u8], expected_device:&[u8;16]) -> Result<(),DiscoveryError> {
        if &self.device_id != expected_device { return Err(DiscoveryError::IdentityMismatch); }
        if self.sent_at > now.saturating_add(MAX_PUNCH_CLOCK_SKEW_SECS) { return Err(DiscoveryError::FutureTimestamp); }
        if now.saturating_sub(self.sent_at) > MAX_RENDEZVOUS_TTL_SECS { return Err(DiscoveryError::Expired); }
        UnparsedPublicKey::new(&ECDSA_P256_SHA256_FIXED, public_key)
            .verify(&self.signing_bytes()?, &self.signature).map_err(|_| DiscoveryError::InvalidSignature)
    }
}

/// IPv6 candidates are attempted before IPv4 when both are available. Within
/// each family the newest signed candidate wins; callers retain fallback order.
pub fn prioritise_candidates(candidates: &mut [EndpointCandidate]) {
    candidates.sort_by_key(|c| {
        let family = match c.address { IpAddr::V6(_) => 0u8, IpAddr::V4(_) => 1u8 };
        (family, std::cmp::Reverse(c.issued_at))
    });
}

/// Bounded exponential retry schedule with deterministic per-device jitter.
/// The caller resets attempts only after a fully authenticated Noise session.
pub fn reconnect_delay(attempt: u32, jitter_seed: u64) -> Duration {
    let exponent = attempt.min(6);
    let base = 1u64.saturating_shl(exponent).min(60);
    let jitter = (jitter_seed ^ (attempt as u64).wrapping_mul(0x9E3779B97F4A7C15)) % (base / 4 + 1);
    Duration::from_secs((base + jitter).min(75))
}

/// NAT-PMP client for an explicitly approved IPv4 gateway address. No gateway
/// is contacted until the user enables mapping. Leases are short and capped.
pub struct NatPmpClient { gateway: Ipv4Addr, timeout: Duration }
impl NatPmpClient {
    pub fn new(gateway: Ipv4Addr, timeout: Duration) -> Result<Self, DiscoveryError> {
        if gateway.is_unspecified() || gateway.is_multicast() || timeout.is_zero() || timeout > Duration::from_secs(5) {
            return Err(DiscoveryError::NatPmp("Invalid gateway or timeout."));
        }
        Ok(Self { gateway, timeout })
    }
    fn exchange(&self, request:&[u8], expected_opcode:u8) -> Result<Vec<u8>,DiscoveryError> {
        let socket=UdpSocket::bind((Ipv4Addr::UNSPECIFIED,0))?;
        socket.set_read_timeout(Some(self.timeout))?;
        socket.set_write_timeout(Some(self.timeout))?;
        socket.send_to(request, SocketAddr::new(IpAddr::V4(self.gateway),5351))?;
        let mut buf=[0u8;64];
        let (len, source)=socket.recv_from(&mut buf)?;
        if source.ip()!=IpAddr::V4(self.gateway) || len<8 || buf[0]!=0 || buf[1]!=(expected_opcode|0x80) {
            return Err(DiscoveryError::NatPmp("Malformed or unexpected gateway response."));
        }
        let result=u16::from_be_bytes([buf[2],buf[3]]);
        if result!=0 { return Err(DiscoveryError::NatPmp("Gateway rejected the mapping request.")); }
        Ok(buf[..len].to_vec())
    }
    pub fn public_address(&self) -> Result<Ipv4Addr,DiscoveryError> {
        let response=self.exchange(&[0,0],0)?;
        if response.len()!=12 { return Err(DiscoveryError::NatPmp("Invalid public-address response.")); }
        Ok(Ipv4Addr::new(response[8],response[9],response[10],response[11]))
    }
    pub fn map_udp(&self, private_port:u16, suggested_public_port:u16, lifetime_secs:u32) -> Result<(u16,u32),DiscoveryError> {
        if private_port==0 || lifetime_secs==0 || lifetime_secs>MAX_GATEWAY_LEASE_SECS {
            return Err(DiscoveryError::NatPmp("Invalid or excessive mapping lease."));
        }
        let mut req=[0u8;12]; req[1]=1; // version 0, UDP opcode 1
        req[4..6].copy_from_slice(&private_port.to_be_bytes());
        req[6..8].copy_from_slice(&suggested_public_port.to_be_bytes());
        req[8..12].copy_from_slice(&lifetime_secs.to_be_bytes());
        let response=self.exchange(&req,1)?;
        if response.len()!=16 { return Err(DiscoveryError::NatPmp("Invalid UDP mapping response.")); }
        Ok((u16::from_be_bytes([response[10],response[11]]),u32::from_be_bytes(response[12..16].try_into().unwrap())))
    }
    pub fn delete_udp_mapping(&self, private_port:u16, public_port:u16) -> Result<(),DiscoveryError> {
        let _=self.map_udp(private_port,public_port,0).or_else(|_| {
            // A zero lifetime is the protocol's explicit delete request.
            let mut req=[0u8;12]; req[1]=1; req[4..6].copy_from_slice(&private_port.to_be_bytes());
            req[6..8].copy_from_slice(&public_port.to_be_bytes());
            let _=self.exchange(&req,1)?; Ok((public_port,0))
        })?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn query_is_fixed_and_rejects_zero_ids() {
        let q=RendezvousQuery { target_device_id:[1;16], requester_device_id:[2;16], nonce:[3;16], issued_at:100 };
        assert_eq!(q.encode().unwrap().len(),59);
        let mut bad=q; bad.nonce=[0;16]; assert!(bad.encode().is_err());
    }
    #[test] fn hole_punch_probe_is_fixed_size_and_identity_bound() {
        let p=HolePunchProbe { session_id:[1;16], nonce:[2;16], device_id:[3;16], sent_at:100, signature:[4;64] };
        assert_eq!(p.encode().unwrap().len(),123);
        assert_eq!(p.verify(101,&[0;65],&[3;16]),Err(DiscoveryError::InvalidSignature));
        assert_eq!(p.verify(101,&[0;65],&[9;16]),Err(DiscoveryError::IdentityMismatch));
    }
    #[test] fn ipv6_precedes_ipv4_and_reconnect_is_bounded() {
        let make=|address:&str,issued_at| EndpointCandidate { transport:crate::internet_peer::CandidateTransport::Udp,
            issued_at,expires_at:issued_at+120,device_id:[1;16],nonce:[2;16],address:address.parse().unwrap(),port:42,signature:[0;64] };
        let mut c=vec![make("8.8.8.8",5),make("2606:4700:4700::1111",4)];
        prioritise_candidates(&mut c); assert!(c[0].address.is_ipv6());
        assert!(reconnect_delay(100,123).as_secs()<=75);
    }
    #[test] fn natpmp_rejects_unbounded_lease() {
        let client=NatPmpClient::new(Ipv4Addr::new(192,168,1,1),Duration::from_secs(1)).unwrap();
        assert!(client.map_udp(4000,4000,MAX_GATEWAY_LEASE_SECS+1).is_err());
    }
}
