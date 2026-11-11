# TomChat TUI

A native Rust terminal interface for the decentralised TomChat project.

## Status

This is the first TUI foundation milestone. It provides a Ratatui terminal shell, keyboard navigation, local peer-hint configuration and an explicit security/status panel. It does **not** yet connect to peers or send messages. The legacy GTK REST/WebSocket client is not used.

The next milestone is to reuse the Android project's direct TCP + mutual identity proof + pinned Noise XX protocol and its encrypted application-frame format. The TUI must not invent a second protocol or silently add centralised service discovery.

## Architecture rules

- Strict decentralised peer-to-peer: no central account infrastructure, relay, hosted discovery, STUN/TURN, central directory or cloud storage.
- Direct connections only, using peer-supplied reachability hints.
- Bind Noise static keys to the same signed, fingerprint-verified TomChat contact identity used by Android.
- Do not send application data before mutual identity authentication and Noise XX key pinning complete.
- Store private identity material encrypted at rest; never log plaintext messages or key material.
- Report unreachable peers honestly. Offline delivery is local queued retry, not centralised service mailbox delivery.

## Build on Arch Linux

Install the toolchain:

```bash
sudo pacman -S --needed rust cargo pkgconf
```

Build and run:

```bash
cd tui
cargo build --release
cargo run --release
```

## Controls

- `/` enter a command or message line
- `F1` show help
- `Tab` switch panels
- `Up/Down` select a contact
- `Esc` leave input mode
- `q` quit

Configuration is stored under `$XDG_CONFIG_HOME/tomchat/config.json` (normally `~/.config/tomchat/config.json`) with owner-only file permissions.

## Planned commands

The command parser currently records peer hints only. `/listen` and `/connect` are deliberately not implemented until the Android protocol adapter and contact-key verification are shared.
