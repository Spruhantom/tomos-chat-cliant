# TomChat Desktop Client

Native Linux desktop client for TomChat, targeting Ubuntu, Fedora, and Arch Linux.

## Current status

This is an initial Rust + GTK4 + Libadwaita application scaffold. It displays a simple welcome window. Server connectivity, login, messaging, secure local storage, notifications, encryption, and voice/video calling are not implemented yet.

## Development dependencies

On Debian/Ubuntu development hosts:

```bash
sudo apt install build-essential pkg-config libgtk-4-dev libadwaita-1-dev
```

Then:

```bash
cargo fmt --all -- --check
cargo check
cargo run
```

## Planned packaging

- Ubuntu: DEB
- Fedora: RPM
- Arch Linux: PKGBUILD
- Cross-distribution: Flatpak

## Security

Do not enter real credentials into this scaffold. It does not yet connect to a server or implement authentication.
