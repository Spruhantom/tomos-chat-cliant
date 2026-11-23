//! Pear/Hyperswarm X25519 transport identity and pairwise key derivation.
//! Private material is stored only in Secret Service and zeroized on drop.

use curve25519_dalek::montgomery::MontgomeryPoint;
use ring::{hkdf, rand::{SecureRandom, SystemRandom}};
use std::{fmt, process::{Command, Stdio}, io::Write};
use zeroize::Zeroize;

const PEAR_KEY_ATTRIBUTE: [&str; 5] = ["lookup", "application", "tomchat", "key", "pear-transport-v1"];
const CONVERSATION_INFO: &[u8] = b"tomchat/pear/conversation-key/v1";
const PAIR_SALT_PREFIX: &[u8] = b"tomchat/pear/pair-salt/v1";
const TOPIC_PREFIX: &[u8] = b"tomchat/pear/discovery-topic/v1";

#[derive(Debug)]
pub enum PearIdentityError {
    Crypto,
    KeyStore(String),
    InvalidKey,
}
impl fmt::Display for PearIdentityError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Crypto => f.write_str("Pear transport key operation failed."),
            Self::KeyStore(message) => write!(f, "Pear identity storage failed: {message}"),
            Self::InvalidKey => f.write_str("Pear transport key is invalid or inconsistent."),
        }
    }
}
impl std::error::Error for PearIdentityError {}

pub struct PearTransportIdentity {
    private_key: [u8; 32],
    public_key: [u8; 32],
}
impl PearTransportIdentity {
    pub fn generate() -> Result<Self, PearIdentityError> {
        let mut private_key = [0u8; 32];
        SystemRandom::new().fill(&mut private_key).map_err(|_| PearIdentityError::Crypto)?;
        let public_key = MontgomeryPoint::mul_base_clamped(private_key).to_bytes();
        Ok(Self { private_key, public_key })
    }

    pub fn public_key(&self) -> &[u8; 32] { &self.public_key }

    pub fn with_private_key<T>(&self, operation: impl FnOnce(&[u8; 32]) -> T) -> T {
        operation(&self.private_key)
    }

    pub fn derive(&self, remote_public_key: &[u8; 32]) -> Result<PearConversationMaterial, PearIdentityError> {
        let derived_public = MontgomeryPoint::mul_base_clamped(self.private_key).to_bytes();
        if !constant_time_eq(&derived_public, &self.public_key) || self.public_key == *remote_public_key {
            return Err(PearIdentityError::InvalidKey);
        }
        let mut shared = MontgomeryPoint(*remote_public_key).mul_clamped(self.private_key).to_bytes();
        if shared.iter().all(|byte| *byte == 0) {
            shared.zeroize();
            return Err(PearIdentityError::InvalidKey);
        }
        let (first, second) = if self.public_key <= *remote_public_key {
            (&self.public_key, remote_public_key)
        } else {
            (remote_public_key, &self.public_key)
        };
        let mut salt_input = Vec::with_capacity(PAIR_SALT_PREFIX.len() + 64);
        salt_input.extend_from_slice(PAIR_SALT_PREFIX);
        salt_input.extend_from_slice(first);
        salt_input.extend_from_slice(second);
        let salt_digest = ring::digest::digest(&ring::digest::SHA256, &salt_input);
        salt_input.zeroize();
        let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, salt_digest.as_ref());
        let prk = salt.extract(&shared);
        shared.zeroize();
        let okm = prk.expand(&[CONVERSATION_INFO], hkdf::HKDF_SHA256).map_err(|_| PearIdentityError::Crypto)?;
        let mut conversation_key = [0u8; 32];
        okm.fill(&mut conversation_key).map_err(|_| PearIdentityError::Crypto)?;
        let mut topic_input = Vec::with_capacity(TOPIC_PREFIX.len() + 32);
        topic_input.extend_from_slice(TOPIC_PREFIX);
        topic_input.extend_from_slice(&conversation_key);
        let topic = ring::digest::digest(&ring::digest::SHA256, &topic_input);
        topic_input.zeroize();
        let mut discovery_topic = [0u8; 32];
        discovery_topic.copy_from_slice(topic.as_ref());
        Ok(PearConversationMaterial { conversation_key, discovery_topic })
    }

    #[cfg(target_os = "linux")]
    pub fn save_to_secret_service(&self) -> Result<(), PearIdentityError> {
        let mut existing = Command::new("secret-tool").args(PEAR_KEY_ATTRIBUTE)
            .stdin(Stdio::null()).stderr(Stdio::null()).output()
            .map_err(|e| PearIdentityError::KeyStore(e.to_string()))?;
        let found = existing.status.success();
        existing.stdout.zeroize();
        if found {
            return Err(PearIdentityError::KeyStore("A Pear transport identity already exists; refusing to replace it.".into()));
        }
        let mut secret = [0u8; 64];
        secret[..32].copy_from_slice(&self.private_key);
        secret[32..].copy_from_slice(&self.public_key);
        let mut encoded = hex_encode(&secret);
        secret.zeroize();
        let mut child = Command::new("secret-tool").args([
            "store", "--label=TomChat Pear transport identity",
            "application", "tomchat", "key", "pear-transport-v1"
        ]).stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null()).spawn()
            .map_err(|e| PearIdentityError::KeyStore(e.to_string()))?;
        let result = if let Some(mut stdin) = child.stdin.take() { stdin.write_all(encoded.as_bytes()) }
            else { Err(std::io::Error::new(std::io::ErrorKind::BrokenPipe, "Secret Service input unavailable")) };
        encoded.zeroize();
        result.map_err(|e| PearIdentityError::KeyStore(e.to_string()))?;
        if !child.wait().map_err(|e| PearIdentityError::KeyStore(e.to_string()))?.success() {
            return Err(PearIdentityError::KeyStore("Secret Service refused the Pear identity.".into()));
        }
        Ok(())
    }

    #[cfg(target_os = "linux")]
    pub fn load_from_secret_service() -> Result<Self, PearIdentityError> {
        let mut output = Command::new("secret-tool").args(PEAR_KEY_ATTRIBUTE)
            .stdin(Stdio::null()).stderr(Stdio::null()).output()
            .map_err(|e| PearIdentityError::KeyStore(e.to_string()))?;
        if !output.status.success() {
            output.stdout.zeroize();
            return Err(PearIdentityError::KeyStore("No Pear transport identity was found.".into()));
        }
        let mut encoded = std::str::from_utf8(&output.stdout)
            .map_err(|_| PearIdentityError::KeyStore("Secret Service returned invalid data.".into()))?
            .trim().to_owned();
        output.stdout.zeroize();
        let decoded = hex_decode(&encoded);
        encoded.zeroize();
        let mut secret = decoded?;
        if secret.len() != 64 {
            secret.zeroize();
            return Err(PearIdentityError::InvalidKey);
        }
        let mut private_key = [0u8; 32];
        let mut public_key = [0u8; 32];
        private_key.copy_from_slice(&secret[..32]);
        public_key.copy_from_slice(&secret[32..]);
        secret.zeroize();
        let derived = MontgomeryPoint::mul_base_clamped(private_key).to_bytes();
        if !constant_time_eq(&derived, &public_key) {
            private_key.zeroize();
            return Err(PearIdentityError::InvalidKey);
        }
        Ok(Self { private_key, public_key })
    }
}
impl Drop for PearTransportIdentity {
    fn drop(&mut self) { self.private_key.zeroize(); }
}

pub struct PearConversationMaterial {
    pub conversation_key: [u8; 32],
    pub discovery_topic: [u8; 32],
}
impl Drop for PearConversationMaterial {
    fn drop(&mut self) { self.conversation_key.zeroize(); }
}

fn constant_time_eq(first: &[u8; 32], second: &[u8; 32]) -> bool {
    first.iter().zip(second).fold(0u8, |diff, (a, b)| diff | (*a ^ *b)) == 0
}
#[cfg(target_os = "linux")]
fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}
#[cfg(target_os = "linux")]
fn hex_decode(value: &str) -> Result<Vec<u8>, PearIdentityError> {
    if !value.len().is_multiple_of(2) || value.len() > 256 {
        return Err(PearIdentityError::InvalidKey);
    }
    value.as_bytes().chunks_exact(2).map(|pair| {
        let high = (pair[0] as char).to_digit(16).ok_or(PearIdentityError::InvalidKey)?;
        let low = (pair[1] as char).to_digit(16).ok_or(PearIdentityError::InvalidKey)?;
        Ok(((high << 4) | low) as u8)
    }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn both_devices_derive_identical_pairwise_material() {
        let alice = PearTransportIdentity::generate().unwrap();
        let bob = PearTransportIdentity::generate().unwrap();
        let alice_material = alice.derive(bob.public_key()).unwrap();
        let bob_material = bob.derive(alice.public_key()).unwrap();
        assert_eq!(alice_material.conversation_key, bob_material.conversation_key);
        assert_eq!(alice_material.discovery_topic, bob_material.discovery_topic);
    }

    #[test]
    fn rejects_self_and_mismatched_keys() {
        let alice = PearTransportIdentity::generate().unwrap();
        assert!(matches!(alice.derive(alice.public_key()), Err(PearIdentityError::InvalidKey)));
    }
}
