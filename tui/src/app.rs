use crate::config::{Config, Peer};
use anyhow::Result;

pub struct App {
    pub config: Config,
    pub should_quit: bool,
    pub input_mode: bool,
    pub input: String,
    pub status: String,
    pub selected_peer: usize,
    pub active_panel: usize,
    pub show_help: bool,
    pub messages: Vec<String>,
}

impl App {
    pub fn load() -> Result<Self> {
        Ok(Self {
            config: Config::load()?,
            should_quit: false,
            input_mode: false,
            input: String::new(),
            status: "Offline · no peer connection".into(),
            selected_peer: 0,
            active_panel: 0,
            show_help: false,
            messages: vec![
                "Welcome to TomChat TUI.".into(),
                "This build is the terminal UI foundation; peer transport is not enabled yet.".into(),
                "No server, relay, hosted discovery or cloud account is used.".into(),
            ],
        })
    }

    pub fn save(&self) -> Result<()> {
        self.config.save()
    }

    pub fn next_panel(&mut self) {
        self.active_panel = (self.active_panel + 1) % 3;
    }

    pub fn previous_item(&mut self) {
        if !self.config.peers.is_empty() {
            self.selected_peer = self.selected_peer.saturating_sub(1);
        }
    }

    pub fn next_item(&mut self) {
        if !self.config.peers.is_empty() {
            self.selected_peer = (self.selected_peer + 1).min(self.config.peers.len() - 1);
        }
    }

    pub fn submit_input(&mut self) {
        let line = self.input.trim().to_string();
        self.input.clear();
        self.input_mode = false;
        if line.is_empty() { return; }
        if let Some(command) = line.strip_prefix('/') {
            self.command(command);
        } else {
            self.messages.push("Not sent: encrypted peer transport is not connected in this build.".into());
        }
    }

    fn command(&mut self, command: &str) {
        let mut parts = command.split_whitespace();
        match parts.next().unwrap_or("") {
            "help" => self.show_help = true,
            "quit" | "exit" => self.should_quit = true,
            "peers" => self.active_panel = 1,
            "chats" => self.active_panel = 0,
            "settings" => self.active_panel = 2,
            "add-peer" => {
                let name = parts.next();
                let fingerprint = parts.next();
                let address = parts.next();
                match (name, fingerprint, address) {
                    (Some(name), Some(fingerprint), Some(address)) => {
                        if fingerprint.len() != 64 || !fingerprint.chars().all(|c| c.is_ascii_hexdigit()) {
                            self.status = "Peer fingerprint must be 64 hexadecimal characters.".into();
                        } else {
                            self.config.peers.push(Peer {
                                name: name.to_string(),
                                fingerprint: fingerprint.to_ascii_lowercase(),
                                address: address.to_string(),
                            });
                            self.status = format!("Added local peer record for {name}; trust verification is still required.");
                        }
                    }
                    _ => self.status = "Usage: /add-peer <name> <sha256-fingerprint> <host:port>".into(),
                }
            }
            "listen" | "connect" => self.status = "Direct Noise transport is the next milestone; networking is deliberately disabled in this UI-only foundation.".into(),
            _ => self.status = format!("Unknown command: /{command}. Press F1 for help."),
        }
    }
}
