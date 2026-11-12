//! Android-compatible signed TomChat contact cards.
//!
//! V2 signs the Noise X25519 public key together with the P-256 identity.
//! V1 remains importable for migration but cannot authenticate a Noise peer.

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use ring::rand::SystemRandom;
use ring::signature::{
    EcdsaKeyPair, KeyPair, UnparsedPublicKey, ECDSA_P256_SHA256_ASN1,
    ECDSA_P256_SHA256_ASN1_SIGNING,
};
use std::fmt;
use zeroize::Zeroize;

const V1: &str = "TOMCHAT-CONTACT-V1";
const V2: &str = "TOMCHAT-CONTACT-V2";
const SPKI_P256_PREFIX: &[u8] = &[
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
    0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContactCardError {
    InvalidFormat,
    InvalidName,
    InvalidPublicKey,
    InvalidFingerprint,
    InvalidNoiseKey,
    InvalidSignature,
    KeyStore(String),
    Crypto,
}
impl fmt::Display for ContactCardError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidFormat => f.write_str("Unsupported or malformed contact card."),
            Self::InvalidName => f.write_str("Contact display name is invalid."),
            Self::InvalidPublicKey => f.write_str("Contact signing public key is invalid."),
            Self::InvalidFingerprint => {
                f.write_str("Contact fingerprint does not match its public key.")
            }
            Self::InvalidNoiseKey => f.write_str("Contact Noise key must be exactly 32 bytes."),
            Self::InvalidSignature => f.write_str("Contact card signature verification failed."),
            Self::KeyStore(message) => write!(f, "Secure identity storage failed: {message}"),
            Self::Crypto => f.write_str("Contact identity cryptographic operation failed."),
        }
    }
}
impl std::error::Error for ContactCardError {}

/// P-256 signing identity. PKCS#8 private bytes are zeroized when dropped.
pub struct SigningIdentity {
    pkcs8: Vec<u8>,
    public_key_der: Vec<u8>,
}
impl SigningIdentity {
    pub fn generate() -> Result<Self, ContactCardError> {
        let rng = SystemRandom::new();
        let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng)
            .map_err(|_| ContactCardError::Crypto)?;
        Self::from_pkcs8(pkcs8.as_ref().to_vec())
    }

    /// Restore a signing identity from key bytes obtained from an OS-protected store.
    pub fn from_pkcs8(mut pkcs8: Vec<u8>) -> Result<Self, ContactCardError> {
        let rng = SystemRandom::new();
        let pair = match EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &pkcs8, &rng) {
            Ok(pair) => pair,
            Err(_) => {
                pkcs8.zeroize();
                return Err(ContactCardError::Crypto);
            }
        };
        let mut public_key_der = SPKI_P256_PREFIX.to_vec();
        public_key_der.extend_from_slice(pair.public_key().as_ref());
        Ok(Self {
            pkcs8,
            public_key_der,
        })
    }

    pub fn public_key_der(&self) -> &[u8] {
        &self.public_key_der
    }

    /// Expose PKCS#8 bytes only inside a caller-provided secure-storage operation.
    pub fn with_pkcs8<T>(&self, operation: impl FnOnce(&[u8]) -> T) -> T {
        operation(&self.pkcs8)
    }

    fn sign(&self, message: &[u8]) -> Result<Vec<u8>, ContactCardError> {
        let pair = EcdsaKeyPair::from_pkcs8(
            &ECDSA_P256_SHA256_ASN1_SIGNING,
            &self.pkcs8,
            &SystemRandom::new(),
        )
        .map_err(|_| ContactCardError::Crypto)?;
        pair.sign(&SystemRandom::new(), message)
            .map(|signature| signature.as_ref().to_vec())
            .map_err(|_| ContactCardError::Crypto)
    }

    /// Persist the PKCS#8 signing key in Secret Service. No plaintext fallback.
    #[cfg(target_os = "linux")]
    pub fn save_to_secret_service(&self) -> Result<(), ContactCardError> {
        use std::io::Write;
        use std::process::{Command, Stdio};
        let mut existing = Command::new("secret-tool")
            .args(["lookup", "application", "tomchat", "key", "p256-signing-v1"])
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output()
            .map_err(|error| ContactCardError::KeyStore(error.to_string()))?;
        let already_exists = existing.status.success();
        existing.stdout.zeroize();
        if already_exists {
            return Err(ContactCardError::KeyStore(
                "A signing identity already exists; refusing to replace it.".into(),
            ));
        }
        let mut child = Command::new("secret-tool")
            .args([
                "store",
                "--label=TomChat signing identity",
                "application",
                "tomchat",
                "key",
                "p256-signing-v1",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| ContactCardError::KeyStore(e.to_string()))?;
        let mut encoded = URL_SAFE_NO_PAD.encode(&self.pkcs8);
        let result = if let Some(mut stdin) = child.stdin.take() {
            stdin.write_all(encoded.as_bytes())
        } else {
            Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "Secret Service input unavailable",
            ))
        };
        encoded.zeroize();
        result.map_err(|e| ContactCardError::KeyStore(e.to_string()))?;
        let status = child
            .wait()
            .map_err(|e| ContactCardError::KeyStore(e.to_string()))?;
        if !status.success() {
            return Err(ContactCardError::KeyStore(
                "Secret Service refused the signing key.".into(),
            ));
        }
        Ok(())
    }

    /// Load only the existing Secret Service identity; never silently rotate it.
    #[cfg(target_os = "linux")]
    pub fn load_from_secret_service() -> Result<Self, ContactCardError> {
        use std::process::{Command, Stdio};
        let mut output = Command::new("secret-tool")
            .args(["lookup", "application", "tomchat", "key", "p256-signing-v1"])
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output()
            .map_err(|e| ContactCardError::KeyStore(e.to_string()))?;
        if !output.status.success() {
            output.stdout.zeroize();
            return Err(ContactCardError::KeyStore(
                "No signing identity was found.".into(),
            ));
        }
        let mut encoded = String::from_utf8(output.stdout.clone()).map_err(|_| {
            ContactCardError::KeyStore("Secret Service returned invalid data.".into())
        })?;
        output.stdout.zeroize();
        let decoded = URL_SAFE_NO_PAD.decode(encoded.trim());
        encoded.zeroize();
        let mut pkcs8 = decoded
            .map_err(|_| ContactCardError::KeyStore("Stored signing key is malformed.".into()))?;
        let identity = Self::from_pkcs8(pkcs8.clone());
        pkcs8.zeroize();
        identity
    }
}
impl Drop for SigningIdentity {
    fn drop(&mut self) {
        self.pkcs8.zeroize();
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContactCard {
    pub display_name: String,
    pub encoded_public_key: Vec<u8>,
    pub fingerprint: String,
    pub signature: Vec<u8>,
    /// None means a legacy V1 card; V1 must never be trusted for Noise sessions.
    pub noise_static_public_key: Option<[u8; 32]>,
}
impl ContactCard {
    pub fn create(
        display_name: &str,
        identity: &SigningIdentity,
        noise_static_public_key: [u8; 32],
    ) -> Result<Self, ContactCardError> {
        validate_name(display_name)?;
        let public_key = identity.public_key_der().to_vec();
        let fingerprint = fingerprint(&public_key);
        let canonical = canonical(
            V2,
            display_name,
            &public_key,
            &fingerprint,
            Some(&noise_static_public_key),
        );
        let signature = identity.sign(&canonical)?;
        Ok(Self {
            display_name: display_name.to_owned(),
            encoded_public_key: public_key,
            fingerprint,
            signature,
            noise_static_public_key: Some(noise_static_public_key),
        })
    }

    pub fn verify(&self) -> Result<(), ContactCardError> {
        validate_name(&self.display_name)?;
        if self.encoded_public_key.len() != SPKI_P256_PREFIX.len() + 65
            || !self.encoded_public_key.starts_with(SPKI_P256_PREFIX)
            || self.encoded_public_key[SPKI_P256_PREFIX.len()] != 0x04
        {
            return Err(ContactCardError::InvalidPublicKey);
        }
        if fingerprint(&self.encoded_public_key) != self.fingerprint {
            return Err(ContactCardError::InvalidFingerprint);
        }
        let (prefix, noise_key) = match self.noise_static_public_key.as_ref() {
            Some(key) => (V2, Some(key)),
            None => (V1, None),
        };
        let message = canonical(
            prefix,
            &self.display_name,
            &self.encoded_public_key,
            &self.fingerprint,
            noise_key,
        );
        let sec1 = &self.encoded_public_key[SPKI_P256_PREFIX.len()..];
        UnparsedPublicKey::new(&ECDSA_P256_SHA256_ASN1, sec1)
            .verify(&message, &self.signature)
            .map_err(|_| ContactCardError::InvalidSignature)
    }

    /// Wire format exactly matches Android ContactCardCodec.encode().
    pub fn encode(&self) -> Result<String, ContactCardError> {
        self.verify()?;
        let mut fields = vec![
            if self.noise_static_public_key.is_some() {
                V2
            } else {
                V1
            }
            .to_owned(),
            b64(self.display_name.as_bytes()),
            b64(&self.encoded_public_key),
            self.fingerprint.clone(),
        ];
        if let Some(noise) = self.noise_static_public_key.as_ref() {
            fields.push(b64(noise));
        }
        fields.push(b64(&self.signature));
        Ok(fields.join("."))
    }

    pub fn decode(encoded: &str) -> Result<Self, ContactCardError> {
        if encoded.len() > 4096 {
            return Err(ContactCardError::InvalidFormat);
        }
        let fields: Vec<&str> = encoded.split('.').collect();
        let (card, expected_fields) = match fields.first().copied() {
            Some(V1) if fields.len() == 5 => {
                let name = String::from_utf8(unb64(fields[1])?)
                    .map_err(|_| ContactCardError::InvalidName)?;
                (
                    Self {
                        display_name: name,
                        encoded_public_key: unb64(fields[2])?,
                        fingerprint: fields[3].to_owned(),
                        signature: unb64(fields[4])?,
                        noise_static_public_key: None,
                    },
                    5,
                )
            }
            Some(V2) if fields.len() == 6 => {
                let name = String::from_utf8(unb64(fields[1])?)
                    .map_err(|_| ContactCardError::InvalidName)?;
                let noise: [u8; 32] = unb64(fields[4])?
                    .try_into()
                    .map_err(|_| ContactCardError::InvalidNoiseKey)?;
                (
                    Self {
                        display_name: name,
                        encoded_public_key: unb64(fields[2])?,
                        fingerprint: fields[3].to_owned(),
                        signature: unb64(fields[5])?,
                        noise_static_public_key: Some(noise),
                    },
                    6,
                )
            }
            _ => return Err(ContactCardError::InvalidFormat),
        };
        if fields.len() != expected_fields {
            return Err(ContactCardError::InvalidFormat);
        }
        card.verify()?;
        Ok(card)
    }

    pub fn is_noise_compatible(&self) -> bool {
        self.noise_static_public_key.is_some() && self.verify().is_ok()
    }
}

fn validate_name(name: &str) -> Result<(), ContactCardError> {
    if name.trim().is_empty()
        || name.encode_utf16().count() > 64
        || name.chars().any(char::is_control)
    {
        Err(ContactCardError::InvalidName)
    } else {
        Ok(())
    }
}
fn canonical(
    prefix: &str,
    name: &str,
    public: &[u8],
    fingerprint: &str,
    noise: Option<&[u8; 32]>,
) -> Vec<u8> {
    let mut fields = vec![
        prefix.to_owned(),
        b64(name.as_bytes()),
        b64(public),
        fingerprint.to_owned(),
    ];
    if let Some(key) = noise {
        fields.push(b64(key));
    }
    fields.join(".").into_bytes()
}
fn b64(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}
fn unb64(value: &str) -> Result<Vec<u8>, ContactCardError> {
    URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| ContactCardError::InvalidFormat)
}
pub fn fingerprint(public_key_der: &[u8]) -> String {
    let digest = ring::digest::digest(&ring::digest::SHA256, public_key_der);
    digest
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<String>()
        .as_bytes()
        .chunks(4)
        .map(|chunk| std::str::from_utf8(chunk).unwrap_or_default().to_owned())
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn verifies_shared_android_rust_v2_fixture() {
        let fixture = include_str!("../../../app/src/test/resources/contact-card-v2.txt").trim();
        let decoded = ContactCard::decode(fixture).unwrap();
        assert_eq!(
            decoded.display_name,
            "TomChat shared interoperability fixture"
        );
        assert!(decoded.is_noise_compatible());
    }

    #[test]
    fn android_v2_wire_format_round_trips_and_binds_noise_key() {
        let identity = SigningIdentity::generate().unwrap();
        let card = ContactCard::create("Tom's Linux PC", &identity, [0x2a; 32]).unwrap();
        let encoded = card.encode().unwrap();
        assert!(encoded.starts_with("TOMCHAT-CONTACT-V2."));
        let decoded = ContactCard::decode(&encoded).unwrap();
        assert_eq!(decoded, card);
        assert!(decoded.is_noise_compatible());
    }

    #[test]
    fn noise_key_tampering_invalidates_signature() {
        let identity = SigningIdentity::generate().unwrap();
        let card = ContactCard::create("Alice", &identity, [0x11; 32]).unwrap();
        let mut tampered = card.clone();
        tampered.noise_static_public_key = Some([0x22; 32]);
        assert_eq!(tampered.verify(), Err(ContactCardError::InvalidSignature));
    }

    #[test]
    fn v2_card_rejects_invalid_noise_key_length() {
        let identity = SigningIdentity::generate().unwrap();
        let card = ContactCard::create("Bob", &identity, [0x33; 32]).unwrap();
        let encoded = card.encode().unwrap();
        let mut fields: Vec<String> = encoded.split('.').map(str::to_owned).collect();
        fields[4] = b64(&[1, 2, 3]);
        assert_eq!(
            ContactCard::decode(&fields.join(".")),
            Err(ContactCardError::InvalidNoiseKey)
        );
    }

    #[test]
    fn name_validation_matches_android_utf16_limit() {
        assert!(validate_name(&"x".repeat(64)).is_ok());
        assert!(validate_name(&"x".repeat(65)).is_err());
        assert!(validate_name(&"😀".repeat(33)).is_err());
    }
}
