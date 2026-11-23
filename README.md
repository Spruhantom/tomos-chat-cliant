# TomChat

TomChat is a decentralised peer-to-peer messaging prototype with two native clients:

- **Android:** Kotlin, Jetpack Compose and Material 3, with a shared Rust Noise transport core.
- **Linux desktop:** Rust, GTK4 and libadwaita.

There is no TomChat application server, central account service, relay, hosted discovery service, push gateway, central directory or hosted message store. Peers connect directly when the network permits it. Direct connectivity is best-effort: carrier NAT, firewalls, network policy and device sleep can prevent connections.

> **Development status:** This is a pre-release prototype. Automated builds and tests pass on the merged client baseline, but physical-device testing, Android-to-Linux interoperability, broader network testing and an independent security review remain outstanding. Do not use it for sensitive or emergency communications, and do not describe it as production-ready or independently audited.

## Install

### Linux desktop — Arch, Omarchy, Debian and Ubuntu

Run the installer as your normal user (do not run the whole command with `sudo`). It installs system build dependencies through sudo, fetches the latest `main` branch, builds the GTK client and installs it under `~/.local`.

```bash
curl -fsSL https://raw.githubusercontent.com/Spruhantom/tomos-chat-cliant/main/install.sh | bash
```

For safer inspection before execution:

```bash
curl -fsSLO https://raw.githubusercontent.com/Spruhantom/tomos-chat-cliant/main/install.sh
less install.sh
bash install.sh
```

Uninstall the user-local executable and launcher (this does not delete identity/keyring data):

```bash
curl -fsSL https://raw.githubusercontent.com/Spruhantom/tomos-chat-cliant/main/install.sh | bash -s -- --uninstall
```

Supported package managers: `pacman` (Arch Linux and Omarchy) and `apt-get` (Debian 13+ and Ubuntu 24.04+). The GTK client requires libadwaita 1.5 or newer; older Debian/Ubuntu releases may not provide a compatible version. QR contact support uses `qrencode` and `zbar`; live webcam QR scanning uses `zbarcam` and requires a working camera device and permission for your desktop user. The installer requires an active desktop session with a working Secret Service/keyring for secure identity and trusted-contact storage. It fails closed if secure storage is unavailable.

### Android

**Android test builds:** [Download from GitHub Releases](https://github.com/Spruhantom/tomos-chat-cliant/releases).

Successful Android CI runs on `main` automatically publish a prerelease containing both the debug APK and the release-variant APK signed with the Android debug key. These are test builds, not production-signed releases. Check the release notes and APK checksum before installing. Enable installation only from a source you trust.

## Build from source

### Linux desktop

Requirements: Rust stable/Cargo, a C toolchain, pkg-config, GTK4, libadwaita, and Secret Service command-line support (`secret-tool`, provided by `libsecret-tools` on Debian/Ubuntu or `libsecret` on Arch).

```bash
sudo apt-get install build-essential pkg-config libgtk-4-dev libadwaita-1-dev libsecret-tools
cargo build --release --locked
cargo test --locked
cargo clippy --all-targets --locked -- -D warnings
cargo fmt --all -- --check
```

On Arch/Omarchy, install the equivalent packages with pacman: `base-devel pkgconf gtk4 libadwaita libsecret`.

The executable is `target/release/tomchat-client`.

### Android

Requirements: Java 17, Android SDK 35, Android NDK 27.2.12479018, Gradle 8.9, Rust stable with the Android targets, and cargo-ndk (`cargo install cargo-ndk --locked`).

From the repository root:

```bash
gradle --no-daemon :android:app:testDebugUnitTest
gradle --no-daemon :android:app:assembleDebug
```

The debug APK is produced at `android/app/build/outputs/apk/debug/app-debug.apk`. To run device/emulator instrumentation tests:

```bash
gradle --no-daemon :android:app:connectedDebugAndroidTest
```

See [Android build and debug releases](docs/android-debug-test-releases.md) and [the Android device test checklist](docs/android-device-test-checklist.md). For Linux installation, QR contact sharing, webcam scanning and source builds, see the [Linux desktop guide](docs/linux-desktop.md).

## Architecture and security

- Device identities are generated locally. Android uses Android Keystore; the Linux client uses the desktop Secret Service/keyring.
- Signed V2 contact cards bind the P-256 signing identity to the X25519 Noise static key. Users must independently compare contact fingerprints before trusting them.
- The shared Rust core implements a Noise XX transport with pinned remote static-key verification and bounded encrypted frames.
- The Linux GTK client supports local identity setup, V2 contact import/trust, QR display, QR image import, live webcam QR scanning, direct TCP connect/listen and encrypted peer chat.
- Android provides the Kotlin application and JNI integration with the Rust Noise core.
- No network route is trusted as an identity. The peer's signed identity and pinned Noise key must be verified before encrypted application traffic is accepted.

These are prototype implementations, not a security audit. See [architecture](docs/architecture.md), [the E2EE threat model](docs/e2ee-threat-model.md), [the Linux identity contract](docs/linux-peer-identity-and-desktop-migration.md), and [real-world testing](docs/real-world-testing.md).

## Pear P2P backend migration

TomChat is moving to Holepunch's Pear/Bare P2P stack as its **only** networking
backend. The Android Kotlin/Compose and Linux Rust/GTK4 interfaces remain
native; a shared Bare worker will handle peer discovery, encrypted transport
and replicated storage through Hyperswarm, Hypercore/Corestore and Hyperbee.

The Pear desktop shell is not being embedded in the mobile app. The native
clients will embed Bare and run the shared Pear-end worker through the
platform's Bare Kit binding.

The Android Pear client will require Android 10 (API 29) or newer with a
compatible Kotlin/Gradle toolchain. Android 8/9 will not be supported by the
Pear-only client. The Linux client will embed Bare Kit's C API behind Rust FFI.

The worker foundation is experimental and is not yet connected to either
native client. The existing Rust Noise code may remain temporarily during
implementation, but it is not a planned fallback: the completed cutover will
remove it and route all P2P traffic through Pear. Do not treat the worker as
production-ready until host integration, signed identity migration,
replication, reconnect, and real-device cross-platform tests pass.

See [the Pear P2P migration plan](docs/pear-p2p-migration.md) for architecture,
compatibility requirements, protocol details and acceptance gates.
## Project structure

- `android/app` — Android application and tests.
- `android/native/tomchat-noise` — shared Rust Noise session and peer transport core.
- `src` — native Linux GTK4/libadwaita desktop client.
- `docs` — architecture, security, product decisions, platform instructions and testing plans.
- `.github/workflows` — Android and Linux CI plus Android build/release workflows.

## Development status and roadmap

The current phase is feature freeze and validation. Priorities are reproducible bug fixes, security fixes, build fixes, documentation and test coverage—not new product features.

1. Verify Android and Linux installation on supported real systems.
2. Test identity creation, secure persistence, contact-card exchange and fingerprint verification.
3. Demonstrate Android-to-Linux Noise handshake and encrypted bidirectional messaging on real devices.
4. Exercise reconnect, malformed/tampered frames, key mismatch, network changes and failure recovery.
5. Complete independent security review, privacy/age-safety review and release hardening.

See [the roadmap](docs/roadmap.md) and [real-world testing plan](docs/real-world-testing.md).

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Keep changes small and testable. Do not add centralised infrastructure, relays, hosted discovery or message storage. Do not claim physical interoperability or production security without evidence.
