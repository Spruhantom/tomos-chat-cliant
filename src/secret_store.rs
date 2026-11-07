//! Linux Secret Service storage-key provisioning for TomChat.

use std::{collections::HashMap, error::Error, io};

use secret_service::{EncryptionType, SecretService};
use zeroize::Zeroize;

use crate::storage_crypto::StorageKey;

const ATTRIBUTES: [(&str, &str); 3] = [
    ("application", "uk.co.tomserver.TomChat"),
    ("purpose", "openmls-sqlcipher-key"),
    ("key-version", "1"),
];

/// Load the existing SQLCipher key from the desktop Secret Service, or create
/// and store a new random key. Never falls back to a password-derived key or a
/// plaintext file if the Secret Service is unavailable.
pub async fn load_or_create_storage_key() -> Result<StorageKey, Box<dyn Error + Send + Sync>> {
    let service = SecretService::connect(EncryptionType::Dh).await?;
    let attributes = HashMap::from(ATTRIBUTES);

    let matches = service.search_items(attributes.clone()).await?;
    if let Some(item) = matches.unlocked.first() {
        return key_from_secret(item.get_secret().await?);
    }
    if let Some(item) = matches.locked.first() {
        item.unlock().await?;
        return key_from_secret(item.get_secret().await?);
    }

    let key = StorageKey::generate()
        .map_err(|_| io::Error::other("operating-system random generator failed"))?;
    let collection = service.get_default_collection().await?;
    collection
        .create_item(
            "TomChat MLS database encryption key",
            attributes,
            key.expose_for_secret_service(),
            false,
            "application/octet-stream",
        )
        .await?;
    Ok(key)
}

fn key_from_secret(mut secret: Vec<u8>) -> Result<StorageKey, Box<dyn Error + Send + Sync>> {
    let bytes: Result<[u8; 32], _> = secret.as_slice().try_into();
    secret.zeroize();
    let bytes = bytes.map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "TomChat Secret Service key has an invalid length",
        )
    })?;
    Ok(StorageKey::from_secret_service(bytes))
}
