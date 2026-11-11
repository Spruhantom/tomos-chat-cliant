use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{fs, path::PathBuf};

#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct Peer {
    pub name: String,
    pub fingerprint: String,
    pub address: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct Config {
    pub display_name: String,
    pub peers: Vec<Peer>,
}

impl Config {
    fn path() -> Result<PathBuf> {
        let root = dirs::config_dir().context("Could not locate XDG config directory")?;
        Ok(root.join("tomchat").join("config.json"))
    }

    pub fn load() -> Result<Self> {
        let path = Self::path()?;
        if !path.exists() { return Ok(Self::default()); }
        let raw = fs::read_to_string(&path).context("Could not read TomChat TUI configuration")?;
        Ok(serde_json::from_str(&raw).context("TomChat TUI configuration is invalid")?)
    }

    pub fn save(&self) -> Result<()> {
        let path = Self::path()?;
        let parent = path.parent().context("Invalid config path")?;
        fs::create_dir_all(parent)?;
        let data = serde_json::to_vec_pretty(self)?;
        let temporary = path.with_extension("json.tmp");
        fs::write(&temporary, data)?;
        #[cfg(unix)] {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&temporary, fs::Permissions::from_mode(0o600))?;
        }
        fs::rename(temporary, path)?;
        Ok(())
    }
}
