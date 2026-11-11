//! Desktop QR rendering and image-based scanning for Android-compatible contact cards.

use image::{DynamicImage, ImageFormat, Luma};
use qrcode::QrCode;
use std::io::Cursor;
use std::path::{Path, PathBuf};

pub fn write_png(payload: &str) -> Result<PathBuf, String> {
    if payload.len() > 4096 || !payload.starts_with("TOMCHAT-CONTACT-V2.") {
        return Err("Only signed V2 TomChat contact cards can be shown as a QR code.".into());
    }
    let code = QrCode::new(payload.as_bytes()).map_err(|error| error.to_string())?;
    let image = code
        .render::<Luma<u8>>()
        .quiet_zone(true)
        .module_dimensions(8, 8)
        .build();
    let path = std::env::temp_dir().join(format!("tomchat-contact-{}.png", std::process::id()));
    let mut bytes = Cursor::new(Vec::new());
    DynamicImage::ImageLuma8(image)
        .write_to(&mut bytes, ImageFormat::Png)
        .map_err(|error| error.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        let mut file = std::fs::OpenOptions::new()
            .create(true).truncate(true).write(true).mode(0o600)
            .open(&path).map_err(|error| error.to_string())?;
        use std::io::Write;
        file.write_all(bytes.get_ref()).map_err(|error| error.to_string())?;
    }
    #[cfg(not(unix))]
    std::fs::write(&path, bytes.get_ref()).map_err(|error| error.to_string())?;
    Ok(path)
}

pub fn decode_image(path: &Path) -> Result<String, String> {
    let image = image::open(path).map_err(|error| error.to_string())?.to_luma8();
    let mut prepared = rqrr::PreparedImage::prepare(image);
    let grids = prepared.detect_grids();
    for grid in grids {
        if let Ok((_, payload)) = grid.decode() {
            if payload.starts_with("TOMCHAT-CONTACT-V2.") && payload.len() <= 4096 {
                return Ok(payload);
            }
        }
    }
    Err("No valid TomChat V2 contact QR code was found.".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_and_decodes_android_wire_format_payload() {
        let payload = "TOMCHAT-CONTACT-V2.example";
        let path = write_png(payload).unwrap_err();
        assert!(path.contains("signed V2"));
    }
}
