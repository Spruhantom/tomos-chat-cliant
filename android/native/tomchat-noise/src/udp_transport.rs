//! Direct UDP transport: Noise XX handshake followed by reliable ordered
//! encrypted datagrams. No relay, directory or plaintext fallback is used.
//!
//! This is a bounded stop-and-wait reliability layer over UDP. One outstanding
//! application datagram per direction avoids Noise transport-counter gaps when
//! packets are lost or reordered. ACKs are themselves Noise-encrypted.
use crate::{NoiseSession, NoiseSessionError};
use std::collections::VecDeque;
use std::fmt;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Duration;

const MAGIC: [u8; 2] = *b"TN";
const HANDSHAKE: u8 = 1;
const DATA: u8 = 2;
const ACK: u8 = 3;
const MAX_PACKET: usize = 65_507;
const MAX_PLAINTEXT: usize = 60_000;
const RETRIES: usize = 8;
const RETRY_MS: u64 = 400;

#[derive(Debug)]
pub enum UdpNoiseError {
    Io(io::Error),
    Noise(NoiseSessionError),
    InvalidPacket,
    Timeout,
    Sequence,
}
impl fmt::Display for UdpNoiseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(e) => write!(f, "UDP Noise I/O failed: {e}"),
            Self::Noise(e) => write!(f, "UDP Noise authentication failed: {e}"),
            Self::InvalidPacket => f.write_str("Invalid UDP Noise packet."),
            Self::Timeout => f.write_str("UDP Noise peer did not acknowledge the packet."),
            Self::Sequence => f.write_str("UDP Noise sequence is out of order."),
        }
    }
}
impl std::error::Error for UdpNoiseError {}
impl From<io::Error> for UdpNoiseError { fn from(e: io::Error) -> Self { Self::Io(e) } }
impl From<NoiseSessionError> for UdpNoiseError { fn from(e: NoiseSessionError) -> Self { Self::Noise(e) } }

/// Authenticated UDP Noise channel. Methods require exclusive mutable access;
/// callers should serialize access through one networking task.
pub struct UdpNoiseChannel {
    socket: UdpSocket,
    noise: NoiseSession,
    send_sequence: u64,
    receive_sequence: u64,
    queued: VecDeque<Vec<u8>>,
    last_ack: Option<(u64, Vec<u8>)>,
    duplicate_handshake: Option<(Vec<u8>, Vec<u8>)>,
    retry_timeout: Duration,
}

impl UdpNoiseChannel {
    pub fn connect(
        socket: UdpSocket,
        remote: SocketAddr,
        local_private_key: &[u8],
        expected_peer_static_key: &[u8],
        timeout: Duration,
    ) -> Result<Self, UdpNoiseError> {
        socket.connect(remote)?;
        socket.set_read_timeout(Some(Duration::from_millis(RETRY_MS)))?;
        socket.set_write_timeout(Some(timeout))?;
        let mut noise = NoiseSession::initiator(local_private_key, expected_peer_static_key)?;
        let m1 = noise.write_handshake(&[])?;
        let p1 = handshake_packet(1, &m1);
        let m2 = loop {
            let mut found = None;
            for _ in 0..RETRIES {
                socket.send(&p1)?;
                match recv_packet(&socket, timeout)? {
                    Some(packet) if packet.len() >= 4 && packet[..3] == [MAGIC[0], MAGIC[1], HANDSHAKE] && packet[3] == 2 => {
                        found = Some(packet[4..].to_vec()); break;
                    }
                    _ => {}
                }
            }
            if let Some(value) = found { break value; }
            return Err(UdpNoiseError::Timeout);
        };
        noise.read_handshake(&m2)?;
        let m3 = noise.write_handshake(&[])?;
        noise.finish_handshake()?;
        let p3 = handshake_packet(3, &m3);
        let ack = loop {
            let mut found = false;
            for _ in 0..RETRIES {
                socket.send(&p3)?;
                match recv_packet(&socket, timeout)? {
                    Some(packet) if packet == handshake_packet(4, &[]) => { found = true; break; }
                    _ => {}
                }
            }
            if found { break handshake_packet(4, &[]); }
            return Err(UdpNoiseError::Timeout);
        };
        let _ = ack;
        Ok(Self::new(socket, noise, timeout))
    }

    pub fn accept(
        socket: UdpSocket,
        local_private_key: &[u8],
        expected_peer_static_key: &[u8],
        timeout: Duration,
    ) -> Result<Self, UdpNoiseError> {
        socket.set_read_timeout(Some(Duration::from_millis(RETRY_MS)))?;
        socket.set_write_timeout(Some(timeout))?;
        let mut noise = NoiseSession::responder(local_private_key, expected_peer_static_key)?;
        let mut m1_seen: Option<Vec<u8>> = None;
        let mut m2_packet: Option<Vec<u8>> = None;
        let mut m3_seen: Option<Vec<u8>> = None;
        let deadline = std::time::Instant::now() + timeout;
        let mut buffer = [0u8; MAX_PACKET];
        loop {
            if std::time::Instant::now() >= deadline { return Err(UdpNoiseError::Timeout); }
            match socket.recv(&mut buffer) {
                Ok(n) if n >= 4 && buffer[..3] == [MAGIC[0], MAGIC[1], HANDSHAKE] && buffer[3] == 1 => {
                    let packet = buffer[..n].to_vec();
                    if m1_seen.as_ref() == Some(&packet) {
                        if let Some(reply) = &m2_packet { socket.send(reply)?; }
                        continue;
                    }
                    let m1 = packet[4..].to_vec();
                    noise.read_handshake(&m1)?;
                    let m2 = noise.write_handshake(&[])?;
                    let reply = handshake_packet(2, &m2);
                    m1_seen = Some(packet);
                    m2_packet = Some(reply.clone());
                    socket.send(&reply)?;
                }
                Ok(n) if n >= 4 && buffer[..3] == [MAGIC[0], MAGIC[1], HANDSHAKE] && buffer[3] == 3 => {
                    let packet = buffer[..n].to_vec();
                    if m3_seen.as_ref() == Some(&packet) {
                        socket.send(&handshake_packet(4, &[]))?;
                        continue;
                    }
                    if m2_packet.is_none() { continue; }
                    noise.read_handshake(&packet[4..])?;
                    noise.finish_handshake()?;
                    m3_seen = Some(packet);
                    socket.send(&handshake_packet(4, &[]))?;
                    return Ok(Self {
                        socket, noise, send_sequence: 0, receive_sequence: 0,
                        queued: VecDeque::new(), last_ack: None,
                        duplicate_handshake: m3_seen.map(|m| (m, handshake_packet(4, &[]))),
                        retry_timeout: timeout,
                    });
                }
                Ok(_) => {}
                Err(e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut => {}
                Err(e) => return Err(e.into()),
            }
        }
    }

    fn new(socket: UdpSocket, noise: NoiseSession, timeout: Duration) -> Self {
        Self { socket, noise, send_sequence: 0, receive_sequence: 0,
            queued: VecDeque::new(), last_ack: None, duplicate_handshake: None,
            retry_timeout: timeout }
    }

    pub fn send(&mut self, plaintext: &[u8]) -> Result<(), UdpNoiseError> {
        if plaintext.len() > MAX_PLAINTEXT { return Err(UdpNoiseError::InvalidPacket); }
        let ciphertext = self.noise.encrypt(plaintext)?;
        let packet = data_packet(DATA, self.send_sequence, &ciphertext);
        for _ in 0..RETRIES {
            self.socket.send(&packet)?;
            let deadline = std::time::Instant::now() + Duration::from_millis(RETRY_MS);
            while std::time::Instant::now() < deadline {
                match recv_packet(&self.socket, self.retry_timeout)? {
                    Some(incoming) => {
                        if self.handle_incoming(&incoming)? { continue; }
                        if incoming.len() >= 11 && incoming[..3] == [MAGIC[0], MAGIC[1], ACK] {
                            let seq = u64::from_be_bytes(incoming[3..11].try_into().unwrap());
                            let ack_plain = self.noise.decrypt(&incoming[11..])?;
                            if seq == self.send_sequence && ack_plain == ack_payload(seq) {
                                self.send_sequence = self.send_sequence.checked_add(1).ok_or(UdpNoiseError::Sequence)?;
                                return Ok(());
                            }
                        }
                    }
                    None => break,
                }
            }
        }
        Err(UdpNoiseError::Timeout)
    }

    pub fn receive(&mut self) -> Result<Vec<u8>, UdpNoiseError> {
        if let Some(value) = self.queued.pop_front() { return Ok(value); }
        loop {
            match recv_packet(&self.socket, self.retry_timeout)? {
                Some(packet) => {
                    if self.handle_incoming(&packet)? {
                        if let Some(value) = self.queued.pop_front() { return Ok(value); }
                    }
                }
                None => return Err(UdpNoiseError::Timeout),
            }
        }
    }

    fn handle_incoming(&mut self, packet: &[u8]) -> Result<bool, UdpNoiseError> {
        if packet.len() == 4 && packet[..3] == [MAGIC[0], MAGIC[1], HANDSHAKE] && packet[3] == 3 {
            if let Some((original, ack)) = &self.duplicate_handshake {
                let _ = original;
                self.socket.send(ack)?;
            }
            return Ok(true);
        }
        if packet.len() < 27 || packet[..3] != [MAGIC[0], MAGIC[1], DATA] { return Ok(false); }
        let seq = u64::from_be_bytes(packet[3..11].try_into().unwrap());
        let ciphertext = &packet[11..];
        if seq == self.receive_sequence {
            let plaintext = self.noise.decrypt(ciphertext)?;
            let ack_cipher = self.noise.encrypt(&ack_payload(seq))?;
            let ack = data_packet(ACK, seq, &ack_cipher);
            self.socket.send(&ack)?;
            self.last_ack = Some((seq, ack));
            self.receive_sequence = self.receive_sequence.checked_add(1).ok_or(UdpNoiseError::Sequence)?;
            self.queued.push_back(plaintext);
            return Ok(true);
        }
        if self.receive_sequence > 0 && seq == self.receive_sequence - 1 {
            if let Some((last_seq, ack)) = &self.last_ack {
                if *last_seq == seq { self.socket.send(ack)?; }
            }
            return Ok(true);
        }
        // Stop-and-wait permits no future sequence to arrive before this one.
        Ok(true)
    }
}

fn handshake_packet(phase: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(4 + payload.len());
    out.extend_from_slice(&MAGIC); out.push(HANDSHAKE); out.push(phase); out.extend_from_slice(payload); out
}
fn data_packet(kind: u8, sequence: u64, ciphertext: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(11 + ciphertext.len());
    out.extend_from_slice(&MAGIC); out.push(kind); out.extend_from_slice(&sequence.to_be_bytes()); out.extend_from_slice(ciphertext); out
}
fn ack_payload(sequence: u64) -> Vec<u8> {
    let mut out = b"TA".to_vec(); out.extend_from_slice(&sequence.to_be_bytes()); out
}
fn recv_packet(socket: &UdpSocket, timeout: Duration) -> Result<Option<Vec<u8>>, UdpNoiseError> {
    socket.set_read_timeout(Some(timeout.min(Duration::from_millis(RETRY_MS))))?;
    let mut buffer = [0u8; MAX_PACKET];
    match socket.recv(&mut buffer) {
        Ok(n) => Ok(Some(buffer[..n].to_vec())),
        Err(e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut => Ok(None),
        Err(e) => Err(e.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use snow::{params::NoiseParams, Builder};
    use std::net::UdpSocket;
    use std::thread;
    const PROTOCOL: &str = "Noise_XX_25519_ChaChaPoly_SHA256";
    fn keypair() -> (Vec<u8>, Vec<u8>) {
        let params: NoiseParams = PROTOCOL.parse().unwrap();
        let pair = Builder::new(params).generate_keypair().unwrap();
        (pair.private, pair.public)
    }
    #[test]
    fn udp_channel_performs_pinned_noise_handshake_and_encrypts_both_directions() {
        let (alice_sk, alice_pk) = keypair();
        let (bob_sk, bob_pk) = keypair();
        let bob_socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let bob_addr = bob_socket.local_addr().unwrap();
        let responder = thread::spawn(move || {
            let mut channel = UdpNoiseChannel::accept(bob_socket, &bob_sk, &alice_pk, Duration::from_secs(5)).unwrap();
            assert_eq!(channel.receive().unwrap(), b"hello over punched UDP");
            channel.send(b"encrypted UDP reply").unwrap();
        });
        let alice_socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let mut channel = UdpNoiseChannel::connect(alice_socket, bob_addr, &alice_sk, &bob_pk, Duration::from_secs(5)).unwrap();
        channel.send(b"hello over punched UDP").unwrap();
        assert_eq!(channel.receive().unwrap(), b"encrypted UDP reply");
        responder.join().unwrap();
    }
    #[test]
    fn udp_channel_rejects_wrong_pinned_peer_key() {
        let (alice_sk, _) = keypair();
        let (bob_sk, _) = keypair();
        let (_, wrong_bob_pk) = keypair();
        let bob_socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        let bob_addr = bob_socket.local_addr().unwrap();
        let responder = thread::spawn(move || UdpNoiseChannel::accept(bob_socket, &bob_sk, &[0;32], Duration::from_secs(2)).is_err());
        let alice_socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        assert!(UdpNoiseChannel::connect(alice_socket, bob_addr, &alice_sk, &wrong_bob_pk, Duration::from_secs(2)).is_err());
        assert!(responder.join().unwrap());
    }
}
