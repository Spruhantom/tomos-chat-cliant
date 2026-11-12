# TomChat for Windows

This is the native Windows desktop client, implemented primarily in Rust using Win32 APIs through `windows-sys`. It is not a browser wrapper, Electron application, or Linux compatibility layer.

## Current status

The first milestone establishes a native Win32 application target and includes the shared `tomchat-noise` Rust crate as a dependency. The current window is a deliberately small bootstrap shell; it is not yet a usable messaging client.

Not yet implemented:

- Windows-protected identity persistence (DPAPI / Windows Credential Manager)
- Signed contact-card and fingerprint verification UI
- Direct TCP listener/connector using the shared Noise peer transport
- Conversation protocol, group messaging and message history
- Bluetooth route discovery/fallback
- Webcam QR scanning, voice/video calling and notifications

Do not create or advertise a new identity until secure Windows persistence and identity recovery behavior are implemented. Never replace an existing identity silently.

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
