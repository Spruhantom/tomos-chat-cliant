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
- Bluetooth LE discovery and GATT connection between two physical Android devices.
- Bluetooth Classic RFCOMM data channel where both devices support it.
- LAN discovery and direct peer connection between two physical Android devices.
- Authenticated peer handshake and protocol version negotiation, shared across transports.
- Explicit address/contact exchange for remote peers.
- Test transport switching, disconnect/reconnect, MTU negotiation, large transfers, permissions, and battery use.
- Test QUIC and TCP fallback, network changes, IPv4/IPv6, Wi-Fi and mobile data.
- Demonstrate no central service, relay, DHT bootstrap, or account registration.

## Phase 4 — encrypted one-to-one messaging
- Select and integrate reviewed pairwise cryptographic protocol.
- Signed/encrypted message envelopes, durable outbox, acknowledgements and deduplication.
- Send messages over Bluetooth LE, Bluetooth Classic, and LAN using the same encrypted envelope.
- Offline queue and reconnect synchronisation between reachable peers.
- Negative tests: tampering, replay, wrong keys, downgrade, malformed payloads.

## Phase 5 — groups and file sharing
- MLS-based group membership/key lifecycle after protocol review.
- Encrypted attachments, chunking, integrity checks, size limits and resumable transfer.
- Prefer Wi-Fi for large transfers; support Bluetooth transfer for suitable file sizes and devices.
- Group delivery/synchronisation semantics and conflict handling.
- Abuse controls for untrusted peers.

## Phase 6 — voice
- One-to-one voice over direct peer connectivity.
- Call setup over authenticated peer channel; WebRTC media protection.
- Mute, speaker, Bluetooth headset routing, audio focus, interruption and call lifecycle.
- Experiment with direct Bluetooth peer voice media; do not claim support until latency, quality, and battery tests pass across physical devices.
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

A two-device Android demo with two direct transports: exchange identities by QR, discover/connect over Bluetooth LE and send an authenticated test payload; then repeat over the same Wi-Fi LAN. No server, relay, DHT bootstrap, or account registration. This validates transport independence and the strict zero-server premise before adding chat UI or production cryptography.
