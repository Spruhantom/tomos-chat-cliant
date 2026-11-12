//! Direct TCP transport for the shared Noise peer session.
//!
//! This module has no discovery, directory, relay or account dependency.
//! Callers must obtain the peer's pinned Noise static key from a separately
//! authenticated contact identity before opening a channel.

use crate::{NoiseSession, NoiseSessionError};
use crate::internet_peer::{CandidateError, CandidateTransport, EndpointCandidate};
use std::fmt;
use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::time::Duration;
use std::sync::{Arc, Mutex};

const MAX_FRAME: usize = 65_535;
const MIN_CIPHERTEXT: usize = 16;

#[derive(Debug)]
pub enum PeerTransportError {
    Io(io::Error),
    Noise(NoiseSessionError),
    InvalidFrame,
    SessionLock,
    Candidate(CandidateError),
    UnsupportedCandidateTransport,
}

impl fmt::Display for PeerTransportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(f, "Peer transport I/O failed: {error}"),
            Self::Noise(error) => write!(f, "Peer encryption failed: {error}"),
            Self::InvalidFrame => f.write_str("Peer sent an invalid or oversized frame."),
            Self::SessionLock => f.write_str("Peer encryption state is unavailable."),
            Self::Candidate(error) => write!(f, "Peer endpoint candidate rejected: {error}"),
            Self::UnsupportedCandidateTransport => f.write_str("This direct connector currently supports TCP candidates only."),
        }
    }
}
impl std::error::Error for PeerTransportError {}
impl From<io::Error> for PeerTransportError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}
impl From<CandidateError> for PeerTransportError {
    fn from(value: CandidateError) -> Self { Self::Candidate(value) }
}
impl From<NoiseSessionError> for PeerTransportError {
    fn from(value: NoiseSessionError) -> Self {
        Self::Noise(value)
    }
}

/// A TCP stream usable only after the pinned-key Noise XX handshake succeeds.
/// Mutable methods preserve Noise transport-counter ordering.
pub struct EncryptedPeerChannel {
    stream: TcpStream,
    noise: NoiseSession,
}

impl EncryptedPeerChannel {
    /// Verify a signed public endpoint hint and establish a pinned Noise
    /// session over direct TCP. Candidate discovery is deliberately external;
    /// this method never contacts a directory or relay.
    pub fn connect_candidate(
        candidate: &EndpointCandidate,
        now_seconds: u64,
        trusted_p256_public_key: &[u8],
        expected_device_id: &[u8; 16],
        local_private_key: &[u8],
        expected_peer_static_key: &[u8],
        timeout: Duration,
    ) -> Result<Self, PeerTransportError> {
        candidate.validate_at(now_seconds)?;
        candidate.verify_signature(trusted_ed25519_public_key)?;
        if candidate.transport != CandidateTransport::Tcp {
            return Err(PeerTransportError::UnsupportedCandidateTransport);
        }
        let address = SocketAddr::new(candidate.address, candidate.port);
        let stream = TcpStream::connect_timeout(&address, timeout)?;
        stream.set_nodelay(true)?;
        stream.set_read_timeout(Some(timeout))?;
        stream.set_write_timeout(Some(timeout))?;
        Self::connect(stream, local_private_key, expected_peer_static_key)
    }

    /// Connect as initiator to a peer whose static key is already trusted.
    pub fn connect(
        mut stream: TcpStream,
        local_private_key: &[u8],
        expected_peer_static_key: &[u8],
    ) -> Result<Self, PeerTransportError> {
        let mut noise = NoiseSession::initiator(local_private_key, expected_peer_static_key)?;
        write_frame(&mut stream, &noise.write_handshake(&[])?)?;
        let message_two = read_frame(&mut stream)?;
        noise.read_handshake(&message_two)?;
        let message_three = noise.write_handshake(&[])?;
        write_frame(&mut stream, &message_three)?;
        noise.finish_handshake()?;
        Ok(Self { stream, noise })
    }

    /// Accept as responder, pinning the initiator's trusted static key.
    pub fn accept(
        mut stream: TcpStream,
        local_private_key: &[u8],
        expected_peer_static_key: &[u8],
    ) -> Result<Self, PeerTransportError> {
        let mut noise = NoiseSession::responder(local_private_key, expected_peer_static_key)?;
        let message_one = read_frame(&mut stream)?;
        noise.read_handshake(&message_one)?;
        let message_two = noise.write_handshake(&[])?;
        write_frame(&mut stream, &message_two)?;
        let message_three = read_frame(&mut stream)?;
        noise.read_handshake(&message_three)?;
        noise.finish_handshake()?;
        Ok(Self { stream, noise })
    }

    /// Encrypt and send one application payload.
    pub fn send(&mut self, plaintext: &[u8]) -> Result<(), PeerTransportError> {
        let ciphertext = self.noise.encrypt(plaintext)?;
        write_frame(&mut self.stream, &ciphertext)
    }

    /// Receive and authenticate one application payload.
    pub fn receive(&mut self) -> Result<Vec<u8>, PeerTransportError> {
        let ciphertext = read_frame(&mut self.stream)?;
        if ciphertext.len() < MIN_CIPHERTEXT {
            return Err(PeerTransportError::InvalidFrame);
        }
        Ok(self.noise.decrypt(&ciphertext)?)
    }

    pub fn stream(&self) -> &TcpStream {
        &self.stream
    }

    /// Split the authenticated channel into independent blocking send/receive
    /// handles. Noise state remains shared and serialized so transport counters
    /// cannot race between the two workers.
    pub fn split(self) -> Result<(PeerSender, PeerReceiver), PeerTransportError> {
        let reader = self.stream.try_clone()?;
        let noise = Arc::new(Mutex::new(self.noise));
        Ok((
            PeerSender {
                stream: self.stream,
                noise: noise.clone(),
            },
            PeerReceiver {
                stream: reader,
                noise,
            },
        ))
    }
}

pub struct PeerSender {
    stream: TcpStream,
    noise: Arc<Mutex<NoiseSession>>,
}
impl PeerSender {
    pub fn send(&mut self, plaintext: &[u8]) -> Result<(), PeerTransportError> {
        let ciphertext = self
            .noise
            .lock()
            .map_err(|_| PeerTransportError::SessionLock)?
            .encrypt(plaintext)?;
        write_frame(&mut self.stream, &ciphertext)
    }

    pub fn shutdown(&self) {
        let _ = self.stream.shutdown(Shutdown::Both);
    }
}

pub struct PeerReceiver {
    stream: TcpStream,
    noise: Arc<Mutex<NoiseSession>>,
}
impl PeerReceiver {
    pub fn receive(&mut self) -> Result<Vec<u8>, PeerTransportError> {
        let ciphertext = read_frame(&mut self.stream)?;
        if ciphertext.len() < MIN_CIPHERTEXT {
            return Err(PeerTransportError::InvalidFrame);
        }
        Ok(self
            .noise
            .lock()
            .map_err(|_| PeerTransportError::SessionLock)?
            .decrypt(&ciphertext)?)
    }
}

fn write_frame(stream: &mut TcpStream, frame: &[u8]) -> Result<(), PeerTransportError> {
    if frame.is_empty() || frame.len() > MAX_FRAME {
        return Err(PeerTransportError::InvalidFrame);
    }
    stream.write_all(&(frame.len() as u32).to_be_bytes())?;
    stream.write_all(frame)?;
    stream.flush()?;
    Ok(())
}

fn read_frame(stream: &mut TcpStream) -> Result<Vec<u8>, PeerTransportError> {
    let mut length = [0u8; 4];
    stream.read_exact(&mut length)?;
    let length = u32::from_be_bytes(length) as usize;
    if !(1..=MAX_FRAME).contains(&length) {
        return Err(PeerTransportError::InvalidFrame);
    }
    let mut frame = vec![0; length];
    stream.read_exact(&mut frame)?;
    Ok(frame)
}

#[cfg(test)]
mod tests {
    use super::*;
    use snow::{params::NoiseParams, Builder};
    use std::net::TcpListener;
    use std::thread;

    const PROTOCOL: &str = "Noise_XX_25519_ChaChaPoly_SHA256";

    fn keypair() -> (Vec<u8>, Vec<u8>) {
        let params: NoiseParams = PROTOCOL.parse().unwrap();
        let pair = Builder::new(params).generate_keypair().unwrap();
        (pair.private, pair.public)
    }

    #[test]
    fn tcp_channel_authenticates_and_encrypts_both_directions() {
        let (alice_private, alice_public) = keypair();
        let (bob_private, bob_public) = keypair();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();

        let responder = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let mut channel =
                EncryptedPeerChannel::accept(stream, &bob_private, &alice_public).unwrap();
            assert_eq!(channel.receive().unwrap(), b"hello from Alice");
            channel.send(b"hello from Bob").unwrap();
        });

        let stream = TcpStream::connect(address).unwrap();
        let mut channel =
            EncryptedPeerChannel::connect(stream, &alice_private, &bob_public).unwrap();
        channel.send(b"hello from Alice").unwrap();
        assert_eq!(channel.receive().unwrap(), b"hello from Bob");
        responder.join().unwrap();
    }

    #[test]
    fn rejects_a_mismatched_pinned_peer_key() {
        let (alice_private, alice_public) = keypair();
        let (bob_private, _) = keypair();
        let (_, wrong_bob_public) = keypair();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();

        let responder = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            match EncryptedPeerChannel::accept(stream, &bob_private, &alice_public) {
                // Bob's own pin is correct, so his local handshake can finish.
                // Alice rejects Bob's unexpected static key and closes the TCP
                // stream; verify Bob cannot receive an application payload.
                Ok(mut channel) => channel.receive().is_err(),
                Err(_) => true,
            }
        });

        let stream = TcpStream::connect(address).unwrap();
        let result = EncryptedPeerChannel::connect(stream, &alice_private, &wrong_bob_public);
        assert!(matches!(
            result,
            Err(PeerTransportError::Noise(
                NoiseSessionError::PeerKeyMismatch
            ))
        ));
        assert!(responder.join().unwrap());
    }
    #[test]
    fn split_channel_supports_independent_send_and_receive_handles() {
        let (alice_private, alice_public) = keypair();
        let (bob_private, bob_public) = keypair();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();

        let responder = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let channel =
                EncryptedPeerChannel::accept(stream, &bob_private, &alice_public).unwrap();
            let (mut sender, mut receiver) = channel.split().unwrap();
            assert_eq!(receiver.receive().unwrap(), b"hello from Alice");
            sender.send(b"hello from Bob").unwrap();
        });

        let stream = TcpStream::connect(address).unwrap();
        let channel = EncryptedPeerChannel::connect(stream, &alice_private, &bob_public).unwrap();
        let (mut sender, mut receiver) = channel.split().unwrap();
        sender.send(b"hello from Alice").unwrap();
        assert_eq!(receiver.receive().unwrap(), b"hello from Bob");
        responder.join().unwrap();
    }
}
