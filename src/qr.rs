//! Desktop QR rendering and image-based scanning for signed contact cards.
//! Uses distribution-provided qrencode and zbar tools to keep the Rust dependency
//! graph small and the QR payload identical to Android's contact-card codec.

use std::path::{Path, PathBuf};
use std::process::Command;

pub fn write_png(payload: &str) -> Result<PathBuf, String> {
    validate_payload(payload)?;
    let path = std::env::temp_dir().join(format!("tomchat-contact-{}.png", std::process::id()));
    let output = Command::new("qrencode")
        .args(["-o"])
        .arg(&path)
        .args(["-t", "PNG", "-m", "4", "-s", "8"])
        .arg(payload)
        .output()
        .map_err(|error| format!("qrencode is unavailable: {error}"))?;
    if !output.status.success() {
        return Err(format!(
            "qrencode failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))
            .map_err(|error| error.to_string())?;
    }
    Ok(path)
}

/// Open the default V4L2 camera, read one QR code, then stop the camera process.
pub fn scan_webcam_once() -> Result<String, String> {
    use std::io::{BufRead, BufReader};
    use std::process::Stdio;

    let mut child = Command::new("zbarcam")
        .args(["--raw", "--quiet"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| format!("Live QR scanning needs zbarcam and camera access: {error}"))?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| "Could not read camera scanner output.".to_string())?;
    let mut reader = BufReader::new(stdout);
    let mut line = String::new();
    let read_result = reader.read_line(&mut line);
    let _ = child.kill();
    let _ = child.wait();
    read_result.map_err(|error| format!("Could not read webcam QR result: {error}"))?;
    let payload = line.trim();
    validate_payload(payload)?;
    Ok(payload.to_owned())
}

pub fn decode_image(path: &Path) -> Result<String, String> {
    let output = Command::new("zbarimg")
        .args(["--quiet", "--raw"])
        .arg(path)
        .output()
        .map_err(|error| format!("zbarimg is unavailable: {error}"))?;
    if !output.status.success() {
        return Err("No readable QR code was found in that image.".into());
    }
    let payload = String::from_utf8(output.stdout)
        .map_err(|_| "The QR code did not contain valid text.".to_string())?;
    let payload = payload.trim();
    validate_payload(payload)?;
    Ok(payload.to_owned())
}

fn validate_payload(payload: &str) -> Result<(), String> {
    if payload.len() > 4096 || !payload.starts_with("TOMCHAT-CONTACT-V2.") {
        return Err("Only signed V2 TomChat contact cards are supported.".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_legacy_and_oversized_qr_payloads() {
        assert!(validate_payload("TOMCHAT-CONTACT-V1.legacy").is_err());
        assert!(validate_payload(&format!("TOMCHAT-CONTACT-V2.{}", "x".repeat(4096))).is_err());
    }

    #[test]
    fn accepts_v2_qr_payload_prefix() {
        assert!(validate_payload("TOMCHAT-CONTACT-V2.payload").is_ok());
    }
}
