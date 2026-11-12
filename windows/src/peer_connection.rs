//! Direct peer connection helpers for Windows.
//!
//! The caller must obtain the remote card from TrustedContactStore after the
//! user has confirmed its fingerprint out of band. This module has no relay,
//! account, directory or hosted discovery path.

use std::net::{SocketAddr, TcpListener, TcpStream};
use std::time::Duration;
use tomchat_noise::contact_card::ContactCard;
use tomchat_noise::identity::NoiseIdentity;
use tomchat_noise::peer_transport::EncryptedPeerChannel;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(12);

fn trusted_noise_key(contact: &ContactCard) -> Result<&[u8; 32], String> {
    contact
        .verify()
        .map_err(|error| format!("Trusted contact signature is invalid: {error}"))?;
    contact.noise_static_public_key.as_ref().ok_or_else(|| {
        "Legacy contact cards do not contain a signed Noise key and cannot connect.".to_string()
    })
}

fn configure_stream(stream: &TcpStream) -> Result<(), String> {
    stream
        .set_nodelay(true)
        .map_err(|error| format!("Could not configure peer TCP stream: {error}"))?;
    stream
        .set_read_timeout(Some(CONNECT_TIMEOUT))
        .map_err(|error| format!("Could not configure peer read timeout: {error}"))?;
    stream
        .set_write_timeout(Some(CONNECT_TIMEOUT))
        .map_err(|error| format!("Could not configure peer write timeout: {error}"))?;
    Ok(())
}

/// Connect directly to an IP:port using the trusted contact's pinned Noise key.
pub fn connect(
    address: &str,
    local_identity: &NoiseIdentity,
    trusted_contact: &ContactCard,
) -> Result<EncryptedPeerChannel, String> {
    let expected_peer = trusted_noise_key(trusted_contact)?;
    let socket: SocketAddr = address
        .parse()
        .map_err(|_| "Enter a direct peer address in IP:port format.".to_string())?;
    let stream = TcpStream::connect_timeout(&socket, CONNECT_TIMEOUT)
        .map_err(|error| format!("Could not connect directly to peer: {error}"))?;
    configure_stream(&stream)?;
    local_identity.with_private_key(|private| {
        EncryptedPeerChannel::connect(stream, private, expected_peer)
            .map_err(|error| format!("Pinned-key Noise handshake failed: {error}"))
    })
}

/// Bind a local IP:port, accept one incoming TCP connection and pin its Noise key.
/// Run this blocking operation on a worker thread.
pub fn accept_one(
    address: &str,
    local_identity: &NoiseIdentity,
    trusted_contact: &ContactCard,
) -> Result<EncryptedPeerChannel, String> {
    let expected_peer = trusted_noise_key(trusted_contact)?;
    let socket: SocketAddr = address
        .parse()
        .map_err(|_| "Enter a local bind address in IP:port format.".to_string())?;
    let listener = TcpListener::bind(socket)
        .map_err(|error| format!("Could not listen for direct peer connections: {error}"))?;
    let (stream, remote) = listener
        .accept()
        .map_err(|error| format!("Could not accept incoming peer connection: {error}"))?;
    configure_stream(&stream)?;
    local_identity.with_private_key(|private| {
        EncryptedPeerChannel::accept(stream, private, expected_peer)
            .map_err(|error| format!("Incoming peer {remote} failed Noise authentication: {error}"))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;
    use tomchat_noise::app_frame::{AppFrame, AppFrameType};
    use tomchat_noise::contact_card::SigningIdentity;

    fn card(name: &str, noise: &NoiseIdentity) -> ContactCard {
        let signing = SigningIdentity::generate().unwrap();
        ContactCard::create(name, &signing, *noise.public_key()).unwrap()
    }

    #[test]
    fn direct_windows_peer_channel_exchanges_android_compatible_frames() {
        let alice_noise = NoiseIdentity::generate().unwrap();
        let bob_noise = NoiseIdentity::generate().unwrap();
        let alice_card = card("Alice", &alice_noise);
        let bob_card = card("Bob", &bob_noise);
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let bob_private = bob_noise.with_private_key(|key| *key);
        let alice_public = *alice_noise.public_key();

        let responder = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            let channel = EncryptedPeerChannel::accept(stream, &bob_private, &alice_public).unwrap();
            let (mut sender, mut receiver) = channel.split().unwrap();
            let received = AppFrame::decode(&receiver.receive().unwrap()).unwrap();
            assert_eq!(received.frame_type, AppFrameType::Text);
            assert_eq!(received.payload.as_slice(), b"hello from Windows");
            let ack = AppFrame::with_id(
                AppFrameType::DeliveryAck,
                received.message_id,
                Vec::new(),
            )
            .unwrap()
            .encode()
            .unwrap();
            sender.send(&ack).unwrap();
        });

        let mut channel = connect(&address.to_string(), &alice_noise, &bob_card).unwrap();
        let frame = AppFrame::new(AppFrameType::Text, b"hello from Windows".to_vec())
            .unwrap()
            .encode()
            .unwrap();
        channel.send(&frame).unwrap();
        let ack = AppFrame::decode(&channel.receive().unwrap()).unwrap();
        assert_eq!(ack.frame_type, AppFrameType::DeliveryAck);
        responder.join().unwrap();
        assert!(alice_card.is_noise_compatible());
    }

    #[test]
    fn rejects_legacy_or_tampered_contact_cards_before_connecting() {
        let local = NoiseIdentity::generate().unwrap();
        let remote = NoiseIdentity::generate().unwrap();
        let contact = card("Remote", &remote);
        let mut tampered = contact.clone();
        tampered.noise_static_public_key = Some([0x55; 32]);
        assert!(connect("127.0.0.1:1", &local, &tampered).is_err());
    }

    #[test]
    fn invalid_socket_address_is_rejected_before_network_access() {
        let local = NoiseIdentity::generate().unwrap();
        let remote = NoiseIdentity::generate().unwrap();
        let contact = card("Remote", &remote);
        assert!(connect("not-an-address", &local, &contact).is_err());
    }
}
