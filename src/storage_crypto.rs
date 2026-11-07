//! Authenticated encryption envelope for locally persisted MLS state.
//!
//! The key must be generated once and stored in the desktop Secret Service by
//! the caller. This module deliberately does not persist or derive the key.
//! It provides an authenticated, versioned envelope for opaque state blobs.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use zeroize::Zeroize;

const MAGIC: &[u8; 6] = b"TCMLS\x01";
const NONCE_LEN: usize = 24;
const KEY_LEN: usize = 32;

/// A local wrapping key. Debug output is redacted and key bytes are cleared on drop.
pub struct StorageKey([u8; KEY_LEN]);

impl StorageKey {
    /// Generate a fresh key using the operating system random source.
    pub fn generate() -> Result<Self, StorageCryptoError> {
        let mut bytes = [0_u8; KEY_LEN];
        getrandom::getrandom(&mut bytes).map_err(|_| StorageCryptoError::Random)?;
        Ok(Self(bytes))
    }

    /// Reconstruct a key retrieved from the operating system Secret Service.
    pub fn from_secret_service(bytes: [u8; KEY_LEN]) -> Self {
        Self(bytes)
    }

    /// Borrow the key only long enough to store it in the Secret Service.
    pub fn expose_for_secret_service(&self) -> &[u8; KEY_LEN] {
        &self.0
    }
}

impl Drop for StorageKey {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

impl std::fmt::Debug for StorageKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("StorageKey([REDACTED])")
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StorageCryptoError {
    Random,
    MalformedEnvelope,
    AuthenticationFailed,
}

/// Encrypt a serialized MLS state blob. The context should be a stable,
/// non-secret identifier (for example, the MLS group ID) and is authenticated
/// as associated data so ciphertext cannot be moved between groups.
pub fn encrypt_state(
    key: &StorageKey,
    context: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, StorageCryptoError> {
    let cipher = XChaCha20Poly1305::new((&key.0).into());
    let mut nonce_bytes = [0_u8; NONCE_LEN];
    getrandom::getrandom(&mut nonce_bytes).map_err(|_| StorageCryptoError::Random)?;
    let nonce = XNonce::from_slice(&nonce_bytes);
    let ciphertext = cipher
        .encrypt(
            nonce,
            Payload {
                msg: plaintext,
                aad: context,
            },
        )
        .map_err(|_| StorageCryptoError::AuthenticationFailed)?;

    let mut envelope = Vec::with_capacity(MAGIC.len() + NONCE_LEN + ciphertext.len());
    envelope.extend_from_slice(MAGIC);
    envelope.extend_from_slice(&nonce_bytes);
    envelope.extend_from_slice(&ciphertext);
    nonce_bytes.zeroize();
    Ok(envelope)
}

/// Authenticate and decrypt an MLS state blob previously produced by encrypt_state.
/// Wrong keys, modified bytes, and a different context fail.
pub fn decrypt_state(
    key: &StorageKey,
    context: &[u8],
    envelope: &[u8],
) -> Result<Vec<u8>, StorageCryptoError> {
    let minimum_len = MAGIC.len() + NONCE_LEN + 16;
    if envelope.len() < minimum_len || !envelope.starts_with(MAGIC) {
        return Err(StorageCryptoError::MalformedEnvelope);
    }

    let nonce_start = MAGIC.len();
    let ciphertext_start = nonce_start + NONCE_LEN;
    let nonce = XNonce::from_slice(&envelope[nonce_start..ciphertext_start]);
    let cipher = XChaCha20Poly1305::new((&key.0).into());
    cipher
        .decrypt(
            nonce,
            Payload {
                msg: &envelope[ciphertext_start..],
                aad: context,
            },
        )
        .map_err(|_| StorageCryptoError::AuthenticationFailed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encrypted_state_round_trips_without_exposing_plaintext() {
        let key = StorageKey::generate().expect("generate test key");
        let plaintext = b"serialized MLS epoch and ratchet state";
        let envelope = encrypt_state(&key, b"group-123", plaintext).expect("encrypt state");

        assert!(envelope.starts_with(MAGIC));
        assert!(!envelope
            .windows(plaintext.len())
            .any(|window| window == plaintext));
        assert_eq!(
            decrypt_state(&key, b"group-123", &envelope).expect("decrypt state"),
            plaintext
        );
    }

    #[test]
    fn encrypted_state_rejects_wrong_key_and_context() {
        let key = StorageKey::generate().expect("generate test key");
        let other_key = StorageKey::generate().expect("generate other key");
        let envelope = encrypt_state(&key, b"group-a", b"private MLS state")
            .expect("encrypt state");

        assert_eq!(
            decrypt_state(&other_key, b"group-a", &envelope),
            Err(StorageCryptoError::AuthenticationFailed)
        );
        assert_eq!(
            decrypt_state(&key, b"group-b", &envelope),
            Err(StorageCryptoError::AuthenticationFailed)
        );
    }

    #[test]
    fn encrypted_state_rejects_tampering_and_malformed_envelopes() {
        let key = StorageKey::generate().expect("generate test key");
        let mut envelope =
            encrypt_state(&key, b"group-a", b"private MLS state").expect("encrypt state");
        let last = envelope.len() - 1;
        envelope[last] ^= 0x80;

        assert_eq!(
            decrypt_state(&key, b"group-a", &envelope),
            Err(StorageCryptoError::AuthenticationFailed)
        );
        assert_eq!(
            decrypt_state(&key, b"group-a", b"not-an-envelope"),
            Err(StorageCryptoError::MalformedEnvelope)
        );
    }

    #[test]
    fn storage_key_debug_output_is_redacted() {
        let key = StorageKey::from_secret_service([0x42; KEY_LEN]);
        assert_eq!(format!("{key:?}"), "StorageKey([REDACTED])");
    }
}
