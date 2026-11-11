//! Secret Service-backed trusted contact storage for the Linux client.
//! Cards are re-verified on every load; malformed records fail closed.

use crate::contact_card::{ContactCard, ContactCardError};
use std::io::Write;
use std::process::{Command, Stdio};

const ATTRS: [&str; 5] = [
    "lookup",
    "application",
    "tomchat",
    "key",
    "trusted-contact-cards-v1",
];

#[derive(Debug)]
pub enum ContactStoreError {
    KeyStore(String),
    Card(ContactCardError),
}
impl std::fmt::Display for ContactStoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::KeyStore(message) => write!(f, "Secure contact storage failed: {message}"),
            Self::Card(error) => write!(f, "{error}"),
        }
    }
}
impl std::error::Error for ContactStoreError {}
impl From<ContactCardError> for ContactStoreError {
    fn from(value: ContactCardError) -> Self {
        Self::Card(value)
    }
}

#[derive(Default)]
pub struct TrustedContactStore;

impl TrustedContactStore {
    pub fn load(&self) -> Result<Vec<ContactCard>, ContactStoreError> {
        let output = Command::new("secret-tool")
            .args(ATTRS)
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output()
            .map_err(|error| ContactStoreError::KeyStore(error.to_string()))?;
        if !output.status.success() {
            return Ok(Vec::new());
        }
        let encoded_cards: Vec<String> =
            serde_json::from_slice(&output.stdout).map_err(|error| {
                ContactStoreError::KeyStore(format!("Stored contacts are malformed: {error}"))
            })?;
        if encoded_cards.len() > 1000 {
            return Err(ContactStoreError::KeyStore(
                "Stored contact count exceeds the safety limit.".into(),
            ));
        }
        encoded_cards
            .into_iter()
            .map(|encoded| ContactCard::decode(&encoded).map_err(Into::into))
            .collect()
    }

    pub fn import(&self, encoded: &str) -> Result<Vec<ContactCard>, ContactStoreError> {
        let card = ContactCard::decode(encoded)?;
        let mut contacts = self.load()?;
        if let Some(existing) = contacts
            .iter()
            .find(|existing| existing.fingerprint == card.fingerprint)
        {
            if existing.encoded_public_key != card.encoded_public_key
                || existing.noise_static_public_key != card.noise_static_public_key
            {
                return Err(ContactStoreError::KeyStore(
                    "An existing fingerprint has a conflicting identity binding.".into(),
                ));
            }
            return Ok(contacts);
        }
        if contacts.len() >= 1000 {
            return Err(ContactStoreError::KeyStore("Contact limit reached.".into()));
        }
        contacts.push(card);
        self.save(&contacts)?;
        Ok(contacts)
    }

    pub fn remove(&self, fingerprint: &str) -> Result<Vec<ContactCard>, ContactStoreError> {
        let mut contacts = self.load()?;
        contacts.retain(|contact| contact.fingerprint != fingerprint);
        self.save(&contacts)?;
        Ok(contacts)
    }

    fn save(&self, contacts: &[ContactCard]) -> Result<(), ContactStoreError> {
        let encoded: Vec<String> = contacts
            .iter()
            .map(ContactCard::encode)
            .collect::<Result<_, _>>()?;
        let mut payload = serde_json::to_vec(&encoded)
            .map_err(|error| ContactStoreError::KeyStore(error.to_string()))?;
        let mut child = Command::new("secret-tool")
            .args([
                "store",
                "--label=TomChat trusted contacts",
                "application",
                "tomchat",
                "key",
                "trusted-contact-cards-v1",
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|error| ContactStoreError::KeyStore(error.to_string()))?;
        let write_result = if let Some(mut stdin) = child.stdin.take() {
            stdin.write_all(&payload)
        } else {
            Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "Secret Service input unavailable",
            ))
        };
        payload.fill(0);
        write_result.map_err(|error| ContactStoreError::KeyStore(error.to_string()))?;
        let status = child
            .wait()
            .map_err(|error| ContactStoreError::KeyStore(error.to_string()))?;
        if !status.success() {
            return Err(ContactStoreError::KeyStore(
                "Secret Service refused the contact list.".into(),
            ));
        }
        Ok(())
    }
}
