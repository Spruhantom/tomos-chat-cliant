# Windows client development plan

TomChat for Windows is a native Rust desktop client. The initial implementation uses Win32 windowing APIs through `windows-sys`; it does not embed a web runtime.

## Shared cross-platform foundations

The Windows client must reuse the shared Rust Noise XX implementation in `android/native/tomchat-noise`. The current shared crate exposes the Noise session and direct TCP encrypted peer transport. Its JNI exports exist for Android, while the Rust API remains available to native clients.

The existing Linux client currently owns separate contact-card and signing-identity code. Before the Windows client can exchange contacts safely, these identity and signed V2 contact-card contracts should be extracted into platform-neutral Rust modules and covered by shared tests. Platform key storage remains separate.

## Milestones

1. **Native application shell:** Win32 executable, CI build and test job. (Started.)
2. **Windows identity adapter:** DPAPI or Windows Credential Manager, atomic create/load behavior, no silent identity replacement, key validation and recovery documentation.
3. **Shared identity/contact layer:** extract signed V2 contact-card verification and fingerprint formatting from Linux-specific code; verify identical fixtures on Android, Linux and Windows.
4. **Encrypted direct messaging:** integrate the shared Noise peer transport with bounded, versioned application frames and a cancellable connection lifecycle.
5. **Cross-platform groups:** define and test a shared authenticated group protocol before exposing group UI; preserve sender authentication and replay protection.
6. **Transport routing:** implement direct LAN/TCP first. Add Bluetooth only as an optional transport beneath the same authenticated encrypted channel; never downgrade to plaintext.
7. **Desktop features:** QR import/display, webcam scanning, notifications, tray behavior, attachments and call capabilities where protocol support exists.
8. **Release hardening:** Windows signing, installer, upgrade-safe identity/data migration, reproducible builds, interoperability tests and independent security review.

## Non-goals

No server, hosted relay, central accounts, hosted discovery, central directory, push gateway or central message store. Direct connectivity remains best-effort and can be blocked by NATs, firewalls, sleep and network policy.

## Validation requirements

A Windows CI build is not proof of interoperability. Release claims require physical Windows ↔ Android ↔ Linux testing, verified contact fingerprints, successful Noise key pinning, bidirectional encrypted messaging, tamper/key-mismatch rejection, reconnect tests and an independent security review.
