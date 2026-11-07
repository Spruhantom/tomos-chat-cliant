//! Durable, SQLCipher-encrypted OpenMLS storage.
//!
//! SQLCipher encrypts the complete SQLite database, including serialized
//! OpenMLS secrets and lookup keys. The database key must be retrieved from
//! Linux Secret Service before opening this provider.

use std::{
    error::Error,
    fmt::Write as FmtWrite,
    fs::{self, OpenOptions},
    path::Path,
    rc::Rc,
};

use openmls_sqlite_storage::{Connection, SqliteStorageProvider};
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::OpenMlsProvider as OpenMlsProviderTrait;
use zeroize::Zeroizing;

use crate::storage_crypto::StorageKey;

/// OpenMLS requires self-describing storage formats. JSON is encrypted in full
/// by SQLCipher before it reaches disk.
#[derive(Default)]
pub struct JsonCodec;

impl openmls_sqlite_storage::Codec for JsonCodec {
    type Error = serde_json::Error;

    fn to_vec<T: serde::Serialize>(value: &T) -> Result<Vec<u8>, Self::Error> {
        serde_json::to_vec(value)
    }

    fn from_slice<T: serde::de::DeserializeOwned>(slice: &[u8]) -> Result<T, Self::Error> {
        serde_json::from_slice(slice)
    }
}

/// Provider combining OpenMLS RustCrypto with durable SQLCipher storage.
pub struct PersistentMlsProvider {
    crypto: OpenMlsRustCrypto,
    connection: Rc<Connection>,
    storage: SqliteStorageProvider<JsonCodec, Rc<Connection>>,
}

impl PersistentMlsProvider {
    /// Open or create an encrypted MLS database and apply OpenMLS migrations.
    /// The caller must obtain the key from Secret Service before calling this.
    pub fn open(path: impl AsRef<Path>, key: &StorageKey) -> Result<Self, Box<dyn Error + Send + Sync>> {
        let path = path.as_ref();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
            }
        }

        // Create the database file with owner-only permissions before SQLite
        // opens it; tighten permissions on an existing file as well.
        if !path.exists() {
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            match options.open(path) {
                Ok(file) => drop(file),
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
                Err(error) => return Err(Box::new(error)),
            }
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
        }

        let connection = Connection::open(path)?;
        configure_sqlcipher(&connection, key)?;
        connection.pragma_update(None, "cipher_memory_security", "ON")?;
        connection.pragma_update(None, "secure_delete", "ON")?;
        connection.pragma_update(None, "journal_mode", "WAL")?;

        let connection = Rc::new(connection);
        let mut storage = SqliteStorageProvider::<JsonCodec, _>::new(Rc::clone(&connection));
        storage.run_migrations()?;

        Ok(Self {
            crypto: OpenMlsRustCrypto::default(),
            connection,
            storage,
        })
    }

    /// Access the OpenMLS storage provider.
    pub fn storage(&self) -> &SqliteStorageProvider<JsonCodec, Rc<Connection>> {
        &self.storage
    }

    /// Run one synchronous MLS operation atomically against the SQLite store.
    ///
    /// If operation returns an error, SQLite rolls back. The caller MUST
    /// discard any in-memory MlsGroup changed by the failed operation and
    /// reload it from this provider before continuing.
    pub fn transaction<T>(
        &self,
        operation: impl FnOnce(&Self) -> Result<T, Box<dyn Error + Send + Sync>>,
    ) -> Result<T, Box<dyn Error + Send + Sync>> {
        let transaction = self.connection.unchecked_transaction()?;
        let result = operation(self)?;
        transaction.commit()?;
        Ok(result)
    }
}

impl OpenMlsProviderTrait for PersistentMlsProvider {
    type CryptoProvider =
        <OpenMlsRustCrypto as OpenMlsProviderTrait>::CryptoProvider;
    type RandProvider =
        <OpenMlsRustCrypto as OpenMlsProviderTrait>::RandProvider;
    type StorageProvider = SqliteStorageProvider<JsonCodec, Rc<Connection>>;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        self.crypto.crypto()
    }

    fn rand(&self) -> &Self::RandProvider {
        self.crypto.rand()
    }
}

fn configure_sqlcipher(
    connection: &Connection,
    key: &StorageKey,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    // SQLCipher accepts a raw 256-bit key using x'…' syntax. The value contains
    // only hex digits, so interpolating it into the PRAGMA cannot inject SQL.
    let mut key_hex = Zeroizing::new(String::with_capacity(64));
    for byte in key.expose_for_secret_service() {
        let _ = write!(&mut *key_hex, "{byte:02x}");
    }
    let key_pragma = Zeroizing::new(format!("x'{key_hex}'"));
    connection.pragma_update(None, "key", key_pragma.as_str())?;

    // Fail closed on a plain SQLite build: the pragma must expose SQLCipher.
    let cipher_version: String =
        connection.pragma_query_value(None, "cipher_version", |row| row.get(0))?;
    if cipher_version.trim().is_empty() {
        return Err("SQLCipher did not report a cipher version".into());
    }
    Ok(())
}
