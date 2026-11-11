//! Direct, contact-authenticated TCP connections for the Linux client.

use crate::contact_card::ContactCard;
use std::net::{TcpListener, TcpStream, ToSocketAddrs};
use std::time::Duration;
use tomchat_noise::identity::NoiseIdentity;
use tomchat_noise::peer_transport::EncryptedPeerChannel;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(12);

fn trusted_noise_key(contact: &ContactCard) -> Result<&[u8; 32], String> {
    contact.verify().map_err(|error| error.to_string())?;
    contact.noise_static_public_key.as_ref()
        .ok_or_else(|| "This legacy contact has no signed Noise key; encrypted connections are disabled.".to_string())
}

fn configure_stream(stream: &TcpStream) -> Result<(), String> {
    stream.set_nodelay(true).map_err(|error| error.to_string())?;
    stream.set_read_timeout(Some(HANDSHAKE_TIMEOUT)).map_err(|error| error.to_string())?;
    stream.set_write_timeout(Some(HANDSHAKE_TIMEOUT)).map_err(|error| error.to_string())?;
    Ok(())
}

/// Connect directly to a peer. The address is only a transport hint; identity
/// is established by the signed contact card and pinned Noise handshake.
pub fn connect_peer(
    address: &str,
    local: &NoiseIdentity,
    contact: &ContactCard,
) -> Result<EncryptedPeerChannel, String> {
    let expected_peer = trusted_noise_key(contact)?;
    let socket = address.parse().map_err(|_| "Enter a peer address in IP:port format.".to_string())?;
    let stream = TcpStream::connect_timeout(&socket, HANDSHAKE_TIMEOUT)
        .map_err(|error| format!("Could not connect directly to peer: {error}"))?;
    configure_stream(&stream)?;
    local.with_private_key(|private| {
        EncryptedPeerChannel::connect(stream, private, expected_peer)
            .map_err(|error| format!("Peer authentication failed: {error}"))
    })
}

/// Accept one direct peer connection while pinning it to the selected contact.
pub fn accept_peer(
    bind_address: &str,
    local: &NoiseIdentity,
    contact: &ContactCard,
) -> Result<EncryptedPeerChannel, String> {
    let expected_peer = trusted_noise_key(contact)?;
    let listener = TcpListener::bind(bind_address)
        .map_err(|error| format!("Could not listen for a direct peer connection: {error}"))?;
    let (stream, _) = listener.accept()
        .map_err(|error| format!("Could not accept direct peer connection: {error}"))?;
    configure_stream(&stream)?;
    local.with_private_key(|private| {
        EncryptedPeerChannel::accept(stream, private, expected_peer)
            .map_err(|error| format!("Peer authentication failed: {error}"))
    })
}
