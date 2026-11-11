# Linux desktop client

TomChat's Linux desktop client is a native Rust, GTK 4 and libadwaita application. It shares the signed V2 contact-card and Noise transport design with the Android client. There is no central TomChat service, account server, relay, hosted discovery or message store; direct connectivity depends on peer reachability and local network policy.

## Supported distributions

The online installer supports:

- Arch Linux and Omarchy (using `pacman`)
- Debian 13 or newer and Ubuntu 24.04 or newer (using `apt-get`)

The desktop requires libadwaita 1.5 or newer and an active desktop Secret Service/keyring. The installer fails if the required libadwaita version or secure keyring support is unavailable; it does not replace missing keyring infrastructure with plaintext key storage.

## Install

Run as your normal user. Do not prefix the command with `sudo`; the installer uses sudo only for system packages.

```bash
curl -fsSL https://raw.githubusercontent.com/Spruhantom/tomos-chat-cliant/main/install.sh | bash
```

For inspection before execution:

```bash
curl -fsSLO https://raw.githubusercontent.com/Spruhantom/tomos-chat-cliant/main/install.sh
less install.sh
bash install.sh
```

The script installs the platform build/runtime dependencies, Rust stable when Cargo is missing, clones the current `main` branch, builds with `cargo build --release --locked`, and installs:

- Executable: `~/.local/bin/tomchat-client`
- Desktop launcher: `~/.local/share/applications/uk.co.tomserver.TomChat.desktop`

Add `~/.local/bin` to your `PATH` if you want to launch `tomchat-client` from a terminal.

Uninstall the executable and launcher:

```bash
curl -fsSL https://raw.githubusercontent.com/Spruhantom/tomos-chat-cliant/main/install.sh | bash -s -- --uninstall
```

Uninstall does not delete identity keys, trusted contacts or other keyring data. Manage those through the desktop keyring deliberately; do not delete them if you need to retain the device identity.

## QR contact sharing

The installer also installs the distribution's QR utilities:

- `qrencode` generates a PNG for the local signed V2 contact card.
- `zbar-tools` on Debian/Ubuntu or `zbar` on Arch provides `zbarimg` and `zbarcam` for image and webcam scanning.

In TomChat, open Contacts to display your QR code, scan a QR image, or scan once with the webcam. Webcam scanning uses the default camera via `zbarcam`; close other camera applications if the device is busy. The QR parser accepts only bounded signed V2 TomChat contact-card payloads. Importing a card is not the same as trusting it: independently compare the full fingerprint with the other person through a separate trusted channel, then explicitly confirm before saving the contact.

The webcam feature has not yet been validated across physical cameras, drivers and desktop permission systems. Camera availability and access are controlled by the local Linux desktop environment.

## Build and test from source

Install the dependencies with your distribution's package manager, then run:

```bash
cargo build --release --locked
cargo test --locked
cargo clippy --all-targets --locked -- -D warnings
cargo fmt --all -- --check
bash -n install.sh
shellcheck install.sh
```

Arch/Omarchy packages include `base-devel pkgconf gtk4 libadwaita libsecret qrencode zbar`. Debian/Ubuntu packages include `build-essential pkg-config libgtk-4-dev libadwaita-1-dev libsecret-tools qrencode zbar-tools`.

## Limitations

- Direct peer connections are best-effort. Firewalls, carrier NAT, network changes and sleeping devices can prevent connections.
- The installer installs only the Linux desktop client. Android is distributed separately as test APKs through [GitHub Releases](https://github.com/Spruhantom/tomos-chat-cliant/releases).
- Android CI and Linux CI passing do not replace physical Android/Linux interoperability, webcam testing or independent security review.
- Current APKs are test builds, not production-signed releases. TomChat is not production-ready or independently audited.

See [the architecture](architecture.md), [the threat model](e2ee-threat-model.md), [the device testing checklist](real-world-testing.md) and the [project roadmap](roadmap.md).
