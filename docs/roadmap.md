# TomChat development roadmap

## Current phase — feature freeze and validation

Android and Linux desktop clients are now integrated into `main`. Work is focused on correctness, security, packaging, documentation and real-world validation. New product features should wait until the current prototype passes the release gates.

- [x] Android Kotlin/Jetpack Compose client foundation.
- [x] Linux Rust/GTK4/libadwaita desktop client foundation.
- [x] Shared Rust Noise XX peer transport core.
- [x] Signed V2 contact cards binding P-256 signing identity to the X25519 Noise key.
- [x] CI workflows for Linux/Rust and Android build/test.
- [x] Linux installer for Arch/Omarchy and Debian/Ubuntu.
- [ ] Test fresh install and upgrade on physical Android devices.
- [ ] Test Linux install, desktop launcher and Secret Service behaviour on supported distributions.
- [ ] Demonstrate Android-to-Linux signed contact-card exchange and fingerprint verification.
- [ ] Demonstrate Android-to-Linux and Linux-to-Android Noise handshake and encrypted bidirectional messaging on physical systems.
- [ ] Test reconnect, network changes, wrong keys, malformed/tampered frames and failure recovery.
- [ ] Complete independent security review, privacy/age-safety review and release hardening.

## Platform responsibilities

### Android
- Kotlin, Jetpack Compose and Material 3 UI.
- Android lifecycle, permissions, notifications and audio routing.
- Android Keystore-backed signing and wrapped Noise identity persistence.
- JNI boundary to the shared Rust Noise core.

### Linux desktop
- Native Rust GTK4/libadwaita application.
- Desktop Secret Service/keyring for signing identity, Noise identity and trusted contacts.
- Signed V2 contact-card creation/import and explicit fingerprint trust.
- Direct TCP peer connection/listen with pinned Noise XX identity and encrypted frames.

### Shared core
- Noise XX session and encrypted peer transport.
- Pinned remote static-key verification.
- Bounded length-prefixed transport frames.
- Cross-platform test vectors and negative security tests.

## Deferred work

Feature additions such as group messaging, file sharing, advanced discovery, transport migration, Opus voice media and social features remain deferred until validation and security gates are complete.

## Release gates

A wider release requires:
1. CI passing for the exact release commit.
2. Successful install/upgrade tests on each advertised platform.
3. Real-device identity persistence and trusted-contact verification.
4. Demonstrated cross-platform interoperability before claiming Android/Linux support.
5. No unresolved critical/high-severity security or data-loss defects.
6. Independent cryptographic/security review and appropriate privacy/age-safety review.

A successful CI run alone is not proof of production readiness.
