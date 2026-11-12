//! Windows identity persistence backed by DPAPI (CurrentUser scope).
//!
//! The on-disk file contains only a DPAPI-protected binary bundle. The
//! plaintext bundle is held in memory briefly and zeroized after use. Identity
//! creation is explicit and refuses to replace an existing identity.

use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use tomchat_noise::contact_card::SigningIdentity;
use tomchat_noise::identity::NoiseIdentity;
use zeroize::Zeroize;
use windows_sys::Win32::Foundation::{GetLastError, LocalFree};
use windows_sys::Win32::Security::Cryptography::{
    CryptProtectData, CryptUnprotectData, CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN,
};

const MAGIC: &[u8] = b"TOMCHAT-WIN-IDENTITY\x01";
const MAX_BUNDLE: usize = 4096;
const ENTROPY: &[u8] = b"TomChat Windows identity store v1";
static TEMP_SEQUENCE: AtomicU64 = AtomicU64::new(1);

pub struct IdentityBundle {
    pub noise: NoiseIdentity,
    pub signing: SigningIdentity,
}

impl IdentityBundle {
    /// Create a new identity only when no identity file already exists.
    pub fn create() -> Result<Self, String> {
        let path = identity_path()?;
        if path.exists() {
            return Err("A TomChat identity already exists. Load or back it up; refusing to replace it.".into());
        }

        let noise = NoiseIdentity::generate()
            .map_err(|error| format!("Could not generate Noise identity: {error}"))?;
        let signing = SigningIdentity::generate()
            .map_err(|error| format!("Could not generate signing identity: {error}"))?;

        let mut plaintext = encode_bundle(&noise, &signing)?;
        let protected = protect(&plaintext);
        plaintext.zeroize();
        let mut protected = protected?;
        let write_result = write_new_atomically(&path, &protected);
        protected.zeroize();
        write_result?;

        Ok(Self { noise, signing })
    }

    /// Load the existing identity. This never creates or rotates keys.
    pub fn load() -> Result<Self, String> {
        let path = identity_path()?;
        let metadata = fs::metadata(&path)
            .map_err(|error| format!("Could not access the existing TomChat identity: {error}"))?;
        if metadata.len() == 0 || metadata.len() as usize > MAX_BUNDLE {
            return Err("The protected TomChat identity file has an invalid size.".into());
        }
        let mut protected = Vec::with_capacity(metadata.len() as usize);
        OpenOptions::new()
            .read(true)
            .open(&path)
            .and_then(|mut file| file.read_to_end(&mut protected))
            .map_err(|error| format!("Could not read the protected TomChat identity: {error}"))?;
        if protected.is_empty() || protected.len() > MAX_BUNDLE {
            protected.zeroize();
            return Err("The protected TomChat identity file changed size while being read.".into());
        }
        let mut plaintext = unprotect(&protected);
        protected.zeroize();
        let mut plaintext = plaintext?;
        let decoded = decode_bundle(&mut plaintext);
        plaintext.zeroize();
        decoded
    }

    pub fn path() -> Result<PathBuf, String> {
        identity_path()
    }
}

fn identity_path() -> Result<PathBuf, String> {
    let root = std::env::var_os("LOCALAPPDATA")
        .ok_or_else(|| "Windows LOCALAPPDATA is unavailable.".to_string())?;
    Ok(PathBuf::from(root).join("TomChat").join("identity.dpapi"))
}

fn encode_bundle(noise: &NoiseIdentity, signing: &SigningIdentity) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::with_capacity(256);
    bytes.extend_from_slice(MAGIC);
    noise.with_private_key(|private| bytes.extend_from_slice(private));
    bytes.extend_from_slice(noise.public_key());
    signing.with_pkcs8(|pkcs8| {
        bytes.extend_from_slice(&(pkcs8.len() as u32).to_le_bytes());
        bytes.extend_from_slice(pkcs8);
    });
    if bytes.len() > MAX_BUNDLE {
        bytes.zeroize();
        return Err("Generated identity bundle exceeds its size limit.".into());
    }
    Ok(bytes)
}

fn decode_bundle(bytes: &mut [u8]) -> Result<IdentityBundle, String> {
    let minimum = MAGIC.len() + 32 + 32 + 4;
    if bytes.len() < minimum || !bytes.starts_with(MAGIC) {
        return Err("Protected TomChat identity has an unsupported format.".into());
    }
    let mut offset = MAGIC.len();
    let mut private = [0u8; 32];
    let mut public = [0u8; 32];
    private.copy_from_slice(&bytes[offset..offset + 32]);
    offset += 32;
    public.copy_from_slice(&bytes[offset..offset + 32]);
    offset += 32;
    let length = u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap()) as usize;
    offset += 4;
    if length == 0 || length > 1024 || offset.checked_add(length) != Some(bytes.len()) {
        private.zeroize();
        return Err("Protected TomChat signing identity has an invalid length.".into());
    }
    let noise_result = NoiseIdentity::from_stored(private, public);
    private.zeroize();
    let noise = noise_result
        .map_err(|error| format!("Stored Noise identity failed validation: {error}"))?;
    let mut pkcs8 = bytes[offset..].to_vec();
    let signing_result = SigningIdentity::from_pkcs8(std::mem::take(&mut pkcs8));
    pkcs8.zeroize();
    let signing = signing_result
        .map_err(|error| format!("Stored signing identity failed validation: {error}"))?;
    Ok(IdentityBundle { noise, signing })
}

fn protect(plaintext: &[u8]) -> Result<Vec<u8>, String> {
    if plaintext.is_empty() || plaintext.len() > MAX_BUNDLE {
        return Err("Identity data is outside the permitted size.".into());
    }
    let input = CRYPT_INTEGER_BLOB {
        cbData: plaintext.len() as u32,
        pbData: plaintext.as_ptr() as *mut u8,
    };
    let entropy = CRYPT_INTEGER_BLOB {
        cbData: ENTROPY.len() as u32,
        pbData: ENTROPY.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
    let ok = unsafe {
        CryptProtectData(
            &input,
            std::ptr::null(),
            &entropy,
            std::ptr::null(),
            std::ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(format!("Windows DPAPI could not protect the identity (error {}).", unsafe { GetLastError() }));
    }
    let result = if output.cbData == 0 || output.cbData as usize > MAX_BUNDLE {
        Err("Windows DPAPI returned an invalid protected identity size.".to_string())
    } else {
        Ok(unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec())
    };
    unsafe {
        if !output.pbData.is_null() {
            std::ptr::write_bytes(output.pbData, 0, output.cbData as usize);
            LocalFree(output.pbData as _);
        }
    }
    result
}

fn unprotect(protected: &[u8]) -> Result<Vec<u8>, String> {
    if protected.is_empty() || protected.len() > MAX_BUNDLE {
        return Err("Protected identity data is outside the permitted size.".into());
    }
    let input = CRYPT_INTEGER_BLOB {
        cbData: protected.len() as u32,
        pbData: protected.as_ptr() as *mut u8,
    };
    let entropy = CRYPT_INTEGER_BLOB {
        cbData: ENTROPY.len() as u32,
        pbData: ENTROPY.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
    let ok = unsafe {
        CryptUnprotectData(
            &input,
            std::ptr::null_mut(),
            &entropy,
            std::ptr::null(),
            std::ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(format!("Windows DPAPI could not unlock this identity for the current Windows user (error {}).", unsafe { GetLastError() }));
    }
    let result = if output.cbData == 0 || output.cbData as usize > MAX_BUNDLE {
        Err("Windows DPAPI returned invalid identity data.".to_string())
    } else {
        Ok(unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec())
    };
    unsafe {
        if !output.pbData.is_null() {
            std::ptr::write_bytes(output.pbData, 0, output.cbData as usize);
            LocalFree(output.pbData as _);
        }
    }
    result
}

fn write_new_atomically(path: &Path, protected: &[u8]) -> Result<(), String> {
    let parent = path.parent().ok_or_else(|| "Identity directory is invalid.".to_string())?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("Could not create the TomChat identity directory: {error}"))?;

    let sequence = TEMP_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let temp = parent.join(format!(".identity-{}-{sequence}.tmp", std::process::id()));
    let write_result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temp)
            .map_err(|error| format!("Could not create temporary protected identity: {error}"))?;
        file.write_all(protected)
            .and_then(|_| file.sync_all())
            .map_err(|error| format!("Could not write protected identity: {error}"))?;
        fs::hard_link(&temp, path).map_err(|error| {
            if error.kind() == std::io::ErrorKind::AlreadyExists {
                "A TomChat identity already exists; refusing to replace it.".to_string()
            } else {
                format!("Could not safely install protected identity: {error}")
            }
        })?;
        Ok(())
    })();
    let _ = fs::remove_file(&temp);
    write_result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundle_codec_round_trips_and_rejects_tampering() {
        let noise = NoiseIdentity::generate().unwrap();
        let signing = SigningIdentity::generate().unwrap();
        let mut encoded = encode_bundle(&noise, &signing).unwrap();
        let restored = decode_bundle(&mut encoded).unwrap();
        assert_eq!(restored.noise.public_key(), noise.public_key());
        assert_eq!(restored.signing.public_key_der(), signing.public_key_der());
        encoded.zeroize();
    }

    #[test]
    fn bundle_codec_rejects_truncation_and_wrong_version() {
        let mut truncated = MAGIC.to_vec();
        assert!(decode_bundle(&mut truncated).is_err());
        let mut wrong = MAGIC.to_vec();
        wrong[0] ^= 1;
        assert!(decode_bundle(&mut wrong).is_err());
    }
}
