//! Signed manifest format for decentralised ephemeral status updates.
//!
//! The manifest contains metadata and a digest of the separately encrypted
//! status payload. It does not distribute content keys or provide encryption;
//! callers must encrypt content to the selected trusted recipients before
//! transfer and verify this detached signature before displaying the status.

use std::fmt;

const MAGIC: [u8; 2] = *b"TS";
pub const VERSION: u8 = 1;
pub const STATUS_TTL_SECS: u64 = 24 * 60 * 60;
pub const MAX_CIPHERTEXT_BYTES: u32 = 10 * 1024 * 1024;
const SIGNATURE_LEN: usize = 64;
pub const MANIFEST_LEN: usize = 185;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum StatusAudience {
    AllTrustedContacts = 1,
    SelectedContacts = 2,
    Group = 3,
}
impl TryFrom<u8> for StatusAudience {
    type Error = StatusError;
    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::AllTrustedContacts),
            2 => Ok(Self::SelectedContacts),
            3 => Ok(Self::Group),
            _ => Err(StatusError::InvalidAudience),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum StatusContentType {
    Text = 1,
    Photo = 2,
}
impl TryFrom<u8> for StatusContentType {
    type Error = StatusError;
    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Text),
            2 => Ok(Self::Photo),
            _ => Err(StatusError::InvalidContentType),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StatusManifest {
    pub status_id: [u8; 16],
    pub author_device_id: [u8; 16],
    pub created_at: u64,
    pub expires_at: u64,
    pub audience: StatusAudience,
    /// SHA-256 of the canonical audience descriptor. Zero for all trusted contacts.
    pub audience_digest: [u8; 32],
    pub content_type: StatusContentType,
    pub ciphertext_len: u32,
    /// SHA-256 of the encrypted content blob, not plaintext.
    pub ciphertext_digest: [u8; 32],
    /// Detached P-256 ECDSA signature in fixed 64-byte r||s form.
    pub signature: [u8; SIGNATURE_LEN],
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StatusError {
    InvalidMagic,
    UnsupportedVersion,
    InvalidLength,
    InvalidAudience,
    InvalidContentType,
    InvalidIdentity,
    InvalidLifetime,
    Expired,
    IssuedInFuture,
    InvalidAudienceDigest,
    InvalidCiphertext,
    InvalidSignature,
    IdentityMismatch,
}
impl fmt::Display for StatusError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::InvalidMagic => "Invalid status manifest magic.",
            Self::UnsupportedVersion => "Unsupported status manifest version.",
            Self::InvalidLength => "Invalid status manifest length.",
            Self::InvalidAudience => "Invalid status audience.",
            Self::InvalidContentType => "Invalid status content type.",
            Self::InvalidIdentity => "Status identity is empty.",
            Self::InvalidLifetime => "Status must expire exactly 24 hours after creation.",
            Self::Expired => "Status has expired.",
            Self::IssuedInFuture => "Status creation time is too far in the future.",
            Self::InvalidAudienceDigest => "Status audience digest is inconsistent.",
            Self::InvalidCiphertext => "Status encrypted content metadata is invalid.",
            Self::InvalidSignature => "Status author signature is invalid.",
            Self::IdentityMismatch => "Status author device ID does not match the trusted contact.",
        })
    }
}
impl std::error::Error for StatusError {}

impl StatusManifest {
    pub fn signing_bytes(&self) -> Result<Vec<u8>, StatusError> {
        self.validate_shape()?;
        let mut out = Vec::with_capacity(MANIFEST_LEN - SIGNATURE_LEN);
        out.extend_from_slice(&MAGIC);
        out.push(VERSION);
        out.push(self.audience as u8);
        out.extend_from_slice(&self.status_id);
        out.extend_from_slice(&self.author_device_id);
        out.extend_from_slice(&self.created_at.to_be_bytes());
        out.extend_from_slice(&self.expires_at.to_be_bytes());
        out.extend_from_slice(&self.audience_digest);
        out.push(self.content_type as u8);
        out.extend_from_slice(&self.ciphertext_len.to_be_bytes());
        out.extend_from_slice(&self.ciphertext_digest);
        Ok(out)
    }

    pub fn encode(&self) -> Result<Vec<u8>, StatusError> {
        let mut bytes = self.signing_bytes()?;
        bytes.extend_from_slice(&self.signature);
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8], now: u64) -> Result<Self, StatusError> {
        if bytes.len() != MANIFEST_LEN {
            return Err(StatusError::InvalidLength);
        }
        if bytes[0..2] != MAGIC {
            return Err(StatusError::InvalidMagic);
        }
        if bytes[2] != VERSION {
            return Err(StatusError::UnsupportedVersion);
        }
        let audience = StatusAudience::try_from(bytes[3])?;
        let mut status_id = [0; 16];
        status_id.copy_from_slice(&bytes[4..20]);
        let mut author_device_id = [0; 16];
        author_device_id.copy_from_slice(&bytes[20..36]);
        let created_at = u64::from_be_bytes(bytes[36..44].try_into().unwrap());
        let expires_at = u64::from_be_bytes(bytes[44..52].try_into().unwrap());
        let mut audience_digest = [0; 32];
        audience_digest.copy_from_slice(&bytes[52..84]);
        let content_type = StatusContentType::try_from(bytes[84])?;
        let ciphertext_len = u32::from_be_bytes(bytes[85..89].try_into().unwrap());
        let mut ciphertext_digest = [0; 32];
        ciphertext_digest.copy_from_slice(&bytes[89..121]);
        let mut signature = [0; SIGNATURE_LEN];
        signature.copy_from_slice(&bytes[121..185]);
        let value = Self {
            status_id, author_device_id, created_at, expires_at, audience,
            audience_digest, content_type, ciphertext_len, ciphertext_digest, signature,
        };
        value.validate_at(now)?;
        Ok(value)
    }

    pub fn validate_at(&self, now: u64) -> Result<(), StatusError> {
        self.validate_shape()?;
        if self.created_at > now.saturating_add(30) {
            return Err(StatusError::IssuedInFuture);
        }
        if self.expires_at <= now {
            return Err(StatusError::Expired);
        }
        Ok(())
    }

    pub fn verify_signature(&self, trusted_p256_public_key: &[u8], expected_author_device_id: &[u8; 16]) -> Result<(), StatusError> {
        if &self.author_device_id != expected_author_device_id { return Err(StatusError::IdentityMismatch); }
        use ring::signature::{UnparsedPublicKey, ECDSA_P256_SHA256_FIXED};
        UnparsedPublicKey::new(&ECDSA_P256_SHA256_FIXED, trusted_p256_public_key)
            .verify(&self.signing_bytes()?, &self.signature)
            .map_err(|_| StatusError::InvalidSignature)
    }

    fn validate_shape(&self) -> Result<(), StatusError> {
        if self.status_id.iter().all(|b| *b == 0)
            || self.author_device_id.iter().all(|b| *b == 0)
        {
            return Err(StatusError::InvalidIdentity);
        }
        if self.expires_at.checked_sub(self.created_at) != Some(STATUS_TTL_SECS) {
            return Err(StatusError::InvalidLifetime);
        }
        let audience_is_zero = self.audience_digest.iter().all(|b| *b == 0);
        match self.audience {
            StatusAudience::AllTrustedContacts if !audience_is_zero => return Err(StatusError::InvalidAudienceDigest),
            StatusAudience::SelectedContacts | StatusAudience::Group if audience_is_zero => return Err(StatusError::InvalidAudienceDigest),
            _ => {}
        }
        if self.ciphertext_len == 0 || self.ciphertext_len > MAX_CIPHERTEXT_BYTES
            || self.ciphertext_digest.iter().all(|b| *b == 0)
        {
            return Err(StatusError::InvalidCiphertext);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> StatusManifest {
        StatusManifest {
            status_id: [1; 16],
            author_device_id: [2; 16],
            created_at: 1_000,
            expires_at: 1_000 + STATUS_TTL_SECS,
            audience: StatusAudience::SelectedContacts,
            audience_digest: [3; 32],
            content_type: StatusContentType::Text,
            ciphertext_len: 128,
            ciphertext_digest: [4; 32],
            signature: [5; 64],
        }
    }

    #[test]
    fn manifest_has_stable_fixed_wire_length_and_round_trips() {
        let value = sample();
        let encoded = value.encode().unwrap();
        assert_eq!(encoded.len(), MANIFEST_LEN);
        assert_eq!(StatusManifest::decode(&encoded, 1_100).unwrap(), value);
    }

    #[test]
    fn enforces_audience_digest_and_exact_24_hour_expiry() {
        let mut value = sample();
        value.audience_digest = [0; 32];
        assert_eq!(value.encode(), Err(StatusError::InvalidAudienceDigest));
        value.audience = StatusAudience::AllTrustedContacts;
        value.expires_at -= 1;
        assert_eq!(value.encode(), Err(StatusError::InvalidLifetime));
    }

    #[test]
    fn rejects_expired_tampered_and_oversized_manifests() {
        let value = sample();
        let mut encoded = value.encode().unwrap();
        assert_eq!(StatusManifest::decode(&encoded, value.expires_at), Err(StatusError::Expired));
        encoded[0] = 0;
        assert_eq!(StatusManifest::decode(&encoded, 1_100), Err(StatusError::InvalidMagic));
        assert_eq!(StatusManifest::decode(&encoded[..encoded.len()-1], 1_100), Err(StatusError::InvalidLength));
    }

    #[test]
    fn verifies_detached_p256_author_signature() {
        use ring::rand::SystemRandom;
        use ring::signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_FIXED_SIGNING};
        let rng = SystemRandom::new();
        let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, &rng).unwrap();
        let key = EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, pkcs8.as_ref(), &rng).unwrap();
        let mut value = sample();
        value.signature.copy_from_slice(key.sign(&rng, &value.signing_bytes().unwrap()).unwrap().as_ref());
        value.verify_signature(key.public_key().as_ref(), &value.author_device_id).unwrap();
        value.ciphertext_len += 1;
        assert_eq!(value.verify_signature(key.public_key().as_ref(), &value.author_device_id), Err(StatusError::InvalidSignature));
    }
}
