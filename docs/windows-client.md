# Windows client development plan

TomChat for Windows is a native Rust desktop client. The initial implementation uses Win32 windowing APIs through `windows-sys`; it does not embed a web runtime.

## Shared cross-platform foundations

The Windows client must reuse the shared Rust Noise XX implementation in `android/native/tomchat-noise`. The current shared crate exposes the Noise session and direct TCP encrypted peer transport. Its JNI exports exist for Android, while the Rust API remains available to native clients.

The shared Rust crate now owns the signed V2 contact-card implementation and fixture tests. The Linux client imports that shared implementation. Windows onboarding creates/loads its DPAPI-protected identity, displays its signed V2 card, verifies imported card signatures, and only stores a contact after explicit entry of a separately confirmed matching fingerprint. Trusted contact records are also DPAPI-protected. Platform key storage remains separate.

## Milestones

1. **Native application shell:** Win32 executable, CI build and test job. (Started.)
2. **Windows identity adapter:** DPAPI CurrentUser storage, atomic create/load behavior, no silent identity replacement and key validation. (Storage module and basic onboarding controls implemented; portable recovery/export remains.)
3. **Shared identity/contact layer:** shared signed V2 contact-card verification and fingerprint formatting; Linux adapter now reuses the shared crate, Windows can create and verify cards. Cross-platform CI/fixture confirmation remains.
4. **Encrypted direct messaging:** shared bounded version-1 application-frame codec now matches Android's wire format; Linux peer chat now sends framed text and authenticated delivery acknowledgements through Noise. Windows connection lifecycle and UI wiring remain.
5. **Cross-platform groups:** define and test a shared authenticated group protocol before exposing group UI; preserve sender authentication and replay protection.
6. **Transport routing:** implement direct LAN/TCP first. Add Bluetooth only as an optional transport beneath the same authenticated encrypted channel; never downgrade to plaintext.
7. **Desktop features:** QR import/display, webcam scanning, notifications, tray behavior, attachments and call capabilities where protocol support exists. (Text-based contact-card import/display is started.)
8. **Release hardening:** Windows signing, installer, upgrade-safe identity/data migration, reproducible builds, interoperability tests and independent security review.

## Non-goals

No server, hosted relay, central accounts, hosted discovery, central directory, push gateway or central message store. Direct connectivity remains best-effort and can be blocked by NATs, firewalls, sleep and network policy.

## Validation requirements

A Windows CI build is not proof of interoperability. Release claims require physical Windows ↔ Android ↔ Linux testing, verified contact fingerprints, successful Noise key pinning, bidirectional encrypted messaging, tamper/key-mismatch rejection, reconnect tests and an independent security review.
