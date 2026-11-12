# TomChat for Windows

This is the native Windows desktop client, implemented primarily in Rust using Win32 APIs through `windows-sys`. It is not a browser wrapper, Electron application, or Linux compatibility layer.

## Current status

The native Win32 window provides first-launch controls to create a DPAPI-protected identity or load the existing identity, generate a signed V2 contact card, verify an imported card's signature and fingerprint, and explicitly trust a contact only after entering a fingerprint confirmed through a separate trusted channel. Trusted contact records are protected with DPAPI. The shared Rust crate now contains the same bounded versioned application-frame format used by Android, and the Linux peer chat adapter uses it for text and delivery acknowledgements. Windows peer connection and conversation UI, plus identity recovery/export, remain under development.

Not yet implemented:

- Windows DPAPI-protected identity persistence (CurrentUser scope), with create/load onboarding controls
- Automatic QR display/scanning and explicit contact acceptance after out-of-band fingerprint comparison
- Direct TCP listener/connector helper with pinned-key Noise authentication is implemented and has loopback tests; UI connection controls are still pending
- Conversation protocol, group messaging and message history
- Bluetooth route discovery/fallback
- Webcam QR scanning, voice/video calling and notifications

Identity creation refuses to replace an existing identity. DPAPI data is bound to the current Windows user; moving an identity to another device requires a separately designed, user-approved recovery/export flow.

## Build

Requirements: Rust stable and the MSVC C++ build tools / Windows SDK.

From the repository root on Windows:

```powershell
cargo check --manifest-path windows/Cargo.toml
cargo test --manifest-path windows/Cargo.toml
cargo build --release --manifest-path windows/Cargo.toml
```

The executable is produced at `windows/target/release/tomchat-windows.exe` (or the workspace target directory if Cargo target configuration is added later).

## Architecture constraints

- Reuse the existing `android/native/tomchat-noise` crate for Noise XX and direct TCP encrypted transport.
- Keep the signed V2 contact-card identity binding and explicit fingerprint confirmation requirements.
- Keep platform-specific secret persistence behind a narrow Windows adapter; never store private identity keys in plaintext files or logs.
- Keep networking peer-to-peer and best-effort. No application server, central account service, relay, hosted discovery, push gateway, central directory or hosted message store.
- Cross-platform group messages must use a documented, versioned protocol shared by Android, Linux and Windows; do not invent a Windows-only group format.
- Treat Bluetooth as a transport path only. It must not bypass peer authentication or Noise encryption.

See [the project architecture](../docs/architecture.md), [security threat model](../docs/e2ee-threat-model.md) and [roadmap](../docs/roadmap.md).
