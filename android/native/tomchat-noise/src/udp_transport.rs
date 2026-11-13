//! Direct UDP transport: Noise XX handshake followed by reliable ordered
//! encrypted datagrams. No relay, directory or plaintext fallback is used.
//!
//! This is a bounded stop-and-wait reliability layer over UDP. One outstanding
//! application datagram per direction avoids Noise transport-counter gaps when
//! packets are lost or reordered. ACKs are themselves Noise-encrypted.
use crate::{NoiseSession, NoiseSessionError};
use crate::internet_peer::{CandidateError, CandidateTransport, EndpointCandidate};
use crate::internet_discovery::{udp_hole_punch, DiscoveryError, HolePunchProbe};
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
const MAX_PLAINTEXT: usize = crate::app_protocol::MAX_FRAME_BYTES;
const MAX_FRAGMENT_BYTES: usize = 1_000;
const MAX_FRAGMENTS: usize = 32;
const FRAGMENT_HEADER_BYTES: usize = 18;
const FRAGMENT_MAGIC: [u8;2] = *b"TF";
const RETRIES: usize = 8;
const RETRY_MS: u64 = 400;

#[derive(Debug)]
pub enum UdpNoiseError {
    Io(io::Error),
    Noise(NoiseSessionError),
    InvalidPacket,
    Timeout,
    Sequence,
    Candidate(CandidateError),
    Discovery(DiscoveryError),
    UnsupportedCandidateTransport,
}
impl fmt::Display for UdpNoiseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(e) => write!(f, "UDP Noise I/O failed: {e}"),
            Self::Noise(e) => write!(f, "UDP Noise authentication failed: {e}"),
            Self::InvalidPacket => f.write_str("Invalid UDP Noise packet."),
            Self::Timeout => f.write_str("UDP Noise peer did not acknowledge the packet."),
            Self::Sequence => f.write_str("UDP Noise sequence is out of order."),
            Self::Candidate(e) => write!(f, "UDP endpoint candidate rejected: {e}"),
            Self::Discovery(e) => write!(f, "UDP peer discovery failed: {e}"),
            Self::UnsupportedCandidateTransport => f.write_str("The endpoint candidate is not UDP."),
        }
    }
}
impl std::error::Error for UdpNoiseError {}
impl From<io::Error> for UdpNoiseError { fn from(e: io::Error) -> Self { Self::Io(e) } }
impl From<NoiseSessionError> for UdpNoiseError { fn from(e: NoiseSessionError) -> Self { Self::Noise(e) } }
impl From<CandidateError> for UdpNoiseError { fn from(e: CandidateError) -> Self { Self::Candidate(e) } }
impl From<DiscoveryError> for UdpNoiseError { fn from(e: DiscoveryError) -> Self { Self::Discovery(e) } }

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
    reassembly: Option<Reassembly>,
}

struct Reassembly {
    message_id: u64,
    fragment_count: usize,
    total_length: usize,
    next_index: usize,
    bytes: Vec<u8>,
}

impl UdpNoiseChannel {
    /// Complete the signed simultaneous-open probe and immediately run
    /// the pinned Noise XX handshake over the same local UDP socket.
    pub fn connect_after_punch(
        socket: UdpSocket,
        candidate: &EndpointCandidate,
        local_probe: &HolePunchProbe,
        now: u64,
        trusted_p256_key: &[u8],
        expected_device_id: &[u8;16],
        local_private_key: &[u8],
        expected_peer_static_key: &[u8],
        timeout: Duration,
    ) -> Result<Self, UdpNoiseError> {
        candidate.validate_at(now)?;
        candidate.verify_signature(trusted_p256_key,expected_device_id)?;
        if candidate.transport != CandidateTransport::Udp { return Err(UdpNoiseError::UnsupportedCandidateTransport); }
        let remote=SocketAddr::new(candidate.address,candidate.port);
        udp_hole_punch(&socket,remote,local_probe,trusted_p256_key,expected_device_id,now,timeout)?;
        Self::connect(socket,remote,local_private_key,expected_peer_static_key,timeout)
    }

    /// Verify a signed UDP candidate before performing the pinned Noise XX
    /// handshake on the already-punched local UDP socket.
    pub fn connect_candidate(
        socket: UdpSocket,
        candidate: &EndpointCandidate,
        now: u64,
        trusted_p256_key: &[u8],
        expected_device_id: &[u8;16],
        local_private_key: &[u8],
        expected_peer_static_key: &[u8],
        timeout: Duration,
    ) -> Result<Self, UdpNoiseError> {
        candidate.validate_at(now)?;
        candidate.verify_signature(trusted_p256_key, expected_device_id)?;
        if candidate.transport != CandidateTransport::Udp { return Err(UdpNoiseError::UnsupportedCandidateTransport); }
        Self::connect(socket, SocketAddr::new(candidate.address,candidate.port),
            local_private_key,expected_peer_static_key,timeout)
    }

    /// Verify the other device's signed simultaneous-open probe, then accept
    /// its Noise XX handshake on the same socket and remote endpoint.
    pub fn accept_after_punch(
        socket: UdpSocket,
        remote: SocketAddr,
        local_probe: &HolePunchProbe,
        trusted_peer_p256_key: &[u8],
        expected_peer_device_id: &[u8;16],
        now: u64,
        local_private_key: &[u8],
        expected_peer_static_key: &[u8],
        timeout: Duration,
    ) -> Result<Self, UdpNoiseError> {
        udp_hole_punch(&socket,remote,local_probe,trusted_peer_p256_key,expected_peer_device_id,now,timeout)?;
        Self::accept(socket,local_private_key,expected_peer_static_key,timeout)
    }

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
        let mut remote: Option<SocketAddr> = None;
        loop {
            if std::time::Instant::now() >= deadline { return Err(UdpNoiseError::Timeout); }
            let received = match remote {
                Some(address) => socket.recv(&mut buffer).map(|n| (n, address)),
                None => socket.recv_from(&mut buffer),
            };
            match received {
                Ok((n, source)) if n >= 4 && buffer[..3] == [MAGIC[0], MAGIC[1], HANDSHAKE] && buffer[3] == 1 => {
                    if remote.is_none() { socket.connect(source)?; remote = Some(source); }
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
                Ok((n, _source)) if n >= 4 && buffer[..3] == [MAGIC[0], MAGIC[1], HANDSHAKE] && buffer[3] == 3 => {
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
        if plaintext.is_empty() || plaintext.len() > MAX_PLAINTEXT { return Err(UdpNoiseError::InvalidPacket); }
        let message_id=self.send_sequence;
        let count=(plaintext.len()+MAX_FRAGMENT_BYTES-1)/MAX_FRAGMENT_BYTES;
        if count==0 || count>MAX_FRAGMENTS { return Err(UdpNoiseError::InvalidPacket); }
        for index in 0..count {
            let start=index*MAX_FRAGMENT_BYTES;
            let end=(start+MAX_FRAGMENT_BYTES).min(plaintext.len());
            let fragment=encode_fragment(message_id,index,count,plaintext.len(),&plaintext[start..end]);
            self.send_fragment(&fragment)?;
        }
        Ok(())
    }

    fn send_fragment(&mut self, plaintext: &[u8]) -> Result<(), UdpNoiseError> {
        let ciphertext=self.noise.encrypt(plaintext)?;
        let packet=data_packet(DATA,self.send_sequence,&ciphertext);
        for _ in 0..RETRIES {
            self.socket.send(&packet)?;
            let deadline=std::time::Instant::now()+Duration::from_millis(RETRY_MS);
            while std::time::Instant::now()<deadline {
                match recv_packet(&self.socket,self.retry_timeout)? {
                    Some(incoming) => {
                        if self.handle_incoming(&incoming)? { continue; }
                        if incoming.len()>=11 && incoming[..3]==[MAGIC[0],MAGIC[1],ACK] {
                            let seq=u64::from_be_bytes(incoming[3..11].try_into().unwrap());
                            let ack_plain=self.noise.decrypt(&incoming[11..])?;
                            if seq==self.send_sequence && ack_plain==ack_payload(seq) {
                                self.send_sequence=self.send_sequence.checked_add(1).ok_or(UdpNoiseError::Sequence)?;
                                return Ok(());
                            }
                        }
                    }
                    None=>break,
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

    fn accept_fragment(&mut self, sequence:u64, bytes:&[u8]) -> Result<Option<Vec<u8>>,UdpNoiseError> {
        if bytes.len()<FRAGMENT_HEADER_BYTES || bytes[..2]!=FRAGMENT_MAGIC { return Err(UdpNoiseError::InvalidPacket); }
        let message_id=u64::from_be_bytes(bytes[2..10].try_into().unwrap());
        let index=u16::from_be_bytes(bytes[10..12].try_into().unwrap()) as usize;
        let count=u16::from_be_bytes(bytes[12..14].try_into().unwrap()) as usize;
        let total_length=u32::from_be_bytes(bytes[14..18].try_into().unwrap()) as usize;
        let chunk=&bytes[FRAGMENT_HEADER_BYTES..];
        if count==0 || count>MAX_FRAGMENTS || index>=count || total_length==0 || total_length>MAX_PLAINTEXT
            || count!=(total_length+MAX_FRAGMENT_BYTES-1)/MAX_FRAGMENT_BYTES
            || message_id!=sequence.saturating_sub(index as u64) {
            return Err(UdpNoiseError::InvalidPacket);
        }
        let expected=(MAX_FRAGMENT_BYTES).min(total_length-index*MAX_FRAGMENT_BYTES);
        if chunk.len()!=expected { return Err(UdpNoiseError::InvalidPacket); }
        if index==0 {
            if self.reassembly.is_some() { return Err(UdpNoiseError::InvalidPacket); }
            self.reassembly=Some(Reassembly { message_id,fragment_count:count,total_length,next_index:0,bytes:Vec::with_capacity(total_length) });
        }
        let state=self.reassembly.as_mut().ok_or(UdpNoiseError::InvalidPacket)?;
        if state.message_id!=message_id || state.fragment_count!=count || state.total_length!=total_length || state.next_index!=index {
            return Err(UdpNoiseError::InvalidPacket);
        }
        state.bytes.extend_from_slice(chunk);
        state.next_index+=1;
        if state.next_index==state.fragment_count {
            let state=self.reassembly.take().ok_or(UdpNoiseError::InvalidPacket)?;
            if state.bytes.len()!=state.total_length { return Err(UdpNoiseError::InvalidPacket); }
            return Ok(Some(state.bytes));
        }
        Ok(None)
    }

    fn handle_incoming(&mut self, packet: &[u8]) -> Result<bool, UdpNoiseError> {
        if packet.len() >= 4 && packet[..3] == [MAGIC[0], MAGIC[1], HANDSHAKE] && packet[3] == 3 {
            if let Some((original, ack)) = &self.duplicate_handshake {
                if original.as_slice() == packet { self.socket.send(ack)?; }
            }
            return Ok(true);
        }
        if packet.len() < 27 || packet[..3] != [MAGIC[0], MAGIC[1], DATA] { return Ok(false); }
        let seq = u64::from_be_bytes(packet[3..11].try_into().unwrap());
        let ciphertext = &packet[11..];
        if seq == self.receive_sequence {
            let plaintext = self.noise.decrypt(ciphertext)?;
            let complete = self.accept_fragment(seq,&plaintext)?;
            let ack_cipher = self.noise.encrypt(&ack_payload(seq))?;
            let ack = data_packet(ACK, seq, &ack_cipher);
            self.socket.send(&ack)?;
            self.last_ack = Some((seq, ack));
            self.receive_sequence = self.receive_sequence.checked_add(1).ok_or(UdpNoiseError::Sequence)?;
            if let Some(message)=complete { self.queued.push_back(message); }
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

fn encode_fragment(message_id:u64,index:usize,count:usize,total_length:usize,chunk:&[u8])->Vec<u8> {
    let mut out=Vec::with_capacity(FRAGMENT_HEADER_BYTES+chunk.len());
    out.extend_from_slice(&FRAGMENT_MAGIC);
    out.extend_from_slice(&message_id.to_be_bytes());
    out.extend_from_slice(&(index as u16).to_be_bytes());
    out.extend_from_slice(&(count as u16).to_be_bytes());
    out.extend_from_slice(&(total_length as u32).to_be_bytes());
    out.extend_from_slice(chunk);
    out
}
\nfn handshake_packet(phase: u8, payload: &[u8]) -> Vec<u8> {
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
