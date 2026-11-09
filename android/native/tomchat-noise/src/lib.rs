//! Experimental Noise session core for TomChat.
//!
//! Uses Noise XX with Noise_XX_25519_ChaChaPoly_SHA256. The caller must
//! securely provision and persist local static private key bytes, and obtain
//! the expected peer static public key from a signed, fingerprint-verified
//! contact card. This crate does not perform that identity binding.
//!
//! Prototype only; not independently audited. Do not enable app messaging
//! until key provisioning, identity binding, FFI and security review complete.

use snow::{params::NoiseParams, Builder, HandshakeState, TransportState};
use std::fmt;

const PROTOCOL: &str = "Noise_XX_25519_ChaChaPoly_SHA256";
const MAX_MESSAGE: usize = 65_535;
const MAX_PLAINTEXT: usize = 65_519;

#[derive(Debug)]
pub enum NoiseSessionError {
    Noise(snow::Error),
    InvalidState(&'static str),
    PeerKeyMismatch,
    MessageTooLarge,
}
impl fmt::Display for NoiseSessionError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Noise(e) => write!(f, "Noise protocol error: {e}"),
            Self::InvalidState(s) => f.write_str(s),
            Self::PeerKeyMismatch => f.write_str("Noise static key does not match trusted peer."),
            Self::MessageTooLarge => f.write_str("Noise message exceeds configured size limit."),
        }
    }
}
impl std::error::Error for NoiseSessionError {}
impl From<snow::Error> for NoiseSessionError {
    fn from(value: snow::Error) -> Self { Self::Noise(value) }
}

enum State { Handshake(HandshakeState), Transport(TransportState), Closed }

/// One role-specific Noise XX session. Not Clone.
pub struct NoiseSession {
    state: State,
    expected_peer_static: Vec<u8>,
}
impl NoiseSession {
    /// Local private and pinned peer public keys are 32-byte X25519 keys.
    pub fn initiator(local_private: &[u8], expected_peer_static: &[u8]) -> Result<Self, NoiseSessionError> {
        Self::build(local_private, expected_peer_static, true)
    }
    /// Local private and pinned peer public keys are 32-byte X25519 keys.
    pub fn responder(local_private: &[u8], expected_peer_static: &[u8]) -> Result<Self, NoiseSessionError> {
        Self::build(local_private, expected_peer_static, false)
    }
    fn build(local_private: &[u8], expected_peer_static: &[u8], initiator: bool) -> Result<Self, NoiseSessionError> {
        if local_private.len() != 32 || expected_peer_static.len() != 32 {
            return Err(NoiseSessionError::InvalidState("Noise X25519 static keys must be 32 bytes."));
        }
        let params: NoiseParams = PROTOCOL.parse().map_err(|_| NoiseSessionError::InvalidState("Invalid fixed Noise suite."))?;
        let builder = Builder::new(params).local_private_key(local_private);
        let state = if initiator { builder.build_initiator()? } else { builder.build_responder()? };
        Ok(Self { state: State::Handshake(state), expected_peer_static: expected_peer_static.to_vec() })
    }
    pub fn write_handshake(&mut self, payload: &[u8]) -> Result<Vec<u8>, NoiseSessionError> {
        if payload.len() > MAX_PLAINTEXT { return Err(NoiseSessionError::MessageTooLarge); }
        let state = match &mut self.state { State::Handshake(s) => s, _ => return Err(NoiseSessionError::InvalidState("Handshake is not active.")) };
        let mut out = vec![0; MAX_MESSAGE];
        let n = state.write_message(payload, &mut out)?;
        out.truncate(n);
        Ok(out)
    }
    pub fn read_handshake(&mut self, message: &[u8]) -> Result<Vec<u8>, NoiseSessionError> {
        if message.is_empty() || message.len() > MAX_MESSAGE { return Err(NoiseSessionError::MessageTooLarge); }
        let state = match &mut self.state { State::Handshake(s) => s, _ => return Err(NoiseSessionError::InvalidState("Handshake is not active.")) };
        let mut out = vec![0; MAX_MESSAGE];
        let n = state.read_message(message, &mut out)?;
        out.truncate(n);
        Ok(out)
    }
    /// Pin-checks the remote Noise static key before enabling encrypted records.
    pub fn finish_handshake(&mut self) -> Result<(), NoiseSessionError> {
        let handshake = match &self.state {
            State::Handshake(s) if s.is_handshake_finished() => s,
            State::Handshake(_) => return Err(NoiseSessionError::InvalidState("Noise handshake is incomplete.")),
            _ => return Err(NoiseSessionError::InvalidState("Noise handshake cannot be finished twice.")),
        };
        let remote = handshake.get_remote_static().ok_or(NoiseSessionError::PeerKeyMismatch)?;
        if remote != self.expected_peer_static.as_slice() {
            self.state = State::Closed;
            return Err(NoiseSessionError::PeerKeyMismatch);
        }
        let previous = std::mem::replace(&mut self.state, State::Closed);
        let State::Handshake(handshake) = previous else {
            return Err(NoiseSessionError::InvalidState("Unexpected Noise state transition."));
        };
        self.state = State::Transport(handshake.into_transport_mode()?);
        Ok(())
    }
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, NoiseSessionError> {
        if plaintext.len() > MAX_PLAINTEXT { return Err(NoiseSessionError::MessageTooLarge); }
        let state = match &mut self.state { State::Transport(s) => s, _ => return Err(NoiseSessionError::InvalidState("Encrypted transport is not ready.")) };
        let mut out = vec![0; MAX_MESSAGE];
        let n = state.write_message(plaintext, &mut out)?;
        out.truncate(n);
        Ok(out)
    }
    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>, NoiseSessionError> {
        if ciphertext.is_empty() || ciphertext.len() > MAX_MESSAGE { return Err(NoiseSessionError::MessageTooLarge); }
        let state = match &mut self.state { State::Transport(s) => s, _ => return Err(NoiseSessionError::InvalidState("Encrypted transport is not ready.")) };
        let mut out = vec![0; MAX_MESSAGE];
        let n = state.read_message(ciphertext, &mut out)?;
        out.truncate(n);
        Ok(out)
    }
    pub fn is_transport_ready(&self) -> bool { matches!(self.state, State::Transport(_)) }
}

#[cfg(test)]
mod tests {
    use super::*;
    use snow::Builder;
    fn pair() -> (Vec<u8>, Vec<u8>) {
        let params: NoiseParams = PROTOCOL.parse().unwrap();
        let key = Builder::new(params).generate_keypair().unwrap();
        (key.private, key.public)
    }
    #[test]
    fn xx_handshake_pins_static_keys_and_encrypts_bidirectionally() {
        let (a_sk, a_pk) = pair();
        let (b_sk, b_pk) = pair();
        let mut a = NoiseSession::initiator(&a_sk, &b_pk).unwrap();
        let mut b = NoiseSession::responder(&b_sk, &a_pk).unwrap();
        assert!(a.encrypt(b"early").is_err());
        let m1 = a.write_handshake(&[]).unwrap(); b.read_handshake(&m1).unwrap();
        let m2 = b.write_handshake(&[]).unwrap(); a.read_handshake(&m2).unwrap();
        let m3 = a.write_handshake(&[]).unwrap(); b.read_handshake(&m3).unwrap();
        a.finish_handshake().unwrap(); b.finish_handshake().unwrap();
        let ct = a.encrypt(b"hello TomChat").unwrap();
        assert_ne!(ct, b"hello TomChat");
        assert_eq!(b.decrypt(&ct).unwrap(), b"hello TomChat");
        let reply = b.encrypt(b"reply").unwrap();
        assert_eq!(a.decrypt(&reply).unwrap(), b"reply");
    }
    #[test]
    fn rejects_wrong_pinned_peer_key() {
        let (a_sk, a_pk) = pair();
        let (b_sk, _) = pair();
        let (_, wrong_b_pk) = pair();
        let mut a = NoiseSession::initiator(&a_sk, &wrong_b_pk).unwrap();
        let mut b = NoiseSession::responder(&b_sk, &a_pk).unwrap();
        let m1 = a.write_handshake(&[]).unwrap(); b.read_handshake(&m1).unwrap();
        let m2 = b.write_handshake(&[]).unwrap(); a.read_handshake(&m2).unwrap();
        let m3 = a.write_handshake(&[]).unwrap(); b.read_handshake(&m3).unwrap();
        assert!(matches!(a.finish_handshake(), Err(NoiseSessionError::PeerKeyMismatch)));
        assert!(b.finish_handshake().is_ok());
    }
    #[test]
    fn no_transport_before_handshake() {
        let (sk, pk) = pair();
        let mut session = NoiseSession::initiator(&sk, &pk).unwrap();
        assert!(matches!(session.encrypt(b"x"), Err(NoiseSessionError::InvalidState(_))));
        assert!(matches!(session.decrypt(b"x"), Err(NoiseSessionError::InvalidState(_))));
    }
    #[test]
    fn validates_static_key_lengths() {
        assert!(NoiseSession::initiator(&[0; 31], &[0; 32]).is_err());
        assert!(NoiseSession::responder(&[0; 32], &[0; 33]).is_err());
    }
}
