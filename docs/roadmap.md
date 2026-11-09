# Development roadmap

## Phase 0 — repository and design baseline
- [x] Preserve current desktop/server-connected code on `legacy-desktop`.
- [x] Create isolated `android-rebuild` branch.
- [x] Record strict network, full feature scope, and dual backup decisions.
- [ ] Agree protocol and threat-model review before production cryptography.

## Phase 1 — Android foundation
- Kotlin + Jetpack Compose Android app.
- Gradle wrapper, version catalog, lint, unit tests, instrumented test scaffold, GitHub Actions.
- App navigation shell: onboarding, identity, contacts, chats, calls, settings.
- Permission and lifecycle handling; no network dependency yet.

## Phase 2 — identity and local security
- Generate device identity locally.
- Android Keystore integration and key lifecycle.
- Signed contact card and QR import/export.
- Encrypted local database, migrations, lock and recovery UX.
- Tests for key loss, rotation, device replacement, and backup restore.

## Phase 3 — direct peer networking proof of concept
- Rust networking crate and Android FFI prototype.
- LAN discovery and direct peer connection between two physical Android devices.
- Authenticated peer handshake and protocol version negotiation.
- Explicit address/contact exchange for remote peers.
- Test QUIC and TCP fallback, network changes, IPv4/IPv6, Wi-Fi and mobile data.
- Demonstrate no central service, relay, DHT bootstrap, or account registration.

## Phase 4 — encrypted one-to-one messaging
- Select and integrate reviewed pairwise cryptographic protocol.
- Signed/encrypted message envelopes, durable outbox, acknowledgements and deduplication.
- Offline queue and reconnect synchronisation between reachable peers.
- Negative tests: tampering, replay, wrong keys, downgrade, malformed payloads.

## Phase 5 — groups and file sharing
- MLS-based group membership/key lifecycle after protocol review.
- Encrypted attachments, chunking, integrity checks, size limits and resumable transfer.
- Group delivery/synchronisation semantics and conflict handling.
- Abuse controls for untrusted peers.

## Phase 6 — voice
- One-to-one voice over direct peer connectivity.
- Call setup over authenticated peer channel; WebRTC media protection.
- Mute, speaker, Bluetooth, audio focus, interruption and call lifecycle.
- Group voice topology prototype and participant limits.
- Measure NAT failure rate; never silently add TURN/relay.

## Phase 7 — backups and multiple devices
- Passphrase-protected local encrypted export/import.
- Trusted-device encrypted replication.
- Device authorization, revocation, backup versioning and restore tests.
- Recovery UX and clear irrecoverable-loss warnings.

## Phase 8 — hardening and release
- Independent cryptographic and protocol review.
- Fuzzing, property tests, dependency audit, static analysis and CI.
- Battery, memory, network-loss, roaming and Android-version testing.
- Privacy review, threat model update, release signing and reproducible build work.
- Publish APKs and documented limitations.

## First development target

A two-device LAN demo: install the debug APK on two physical Android phones, exchange identities by QR, establish a direct authenticated connection, and exchange a test payload. No server, relay, DHT bootstrap, or account registration. This validates the strict premise before adding chat UI or cryptographic complexity.
