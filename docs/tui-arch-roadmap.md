# TomChat TUI for Arch Linux

## Milestone 1 — terminal foundation

- [x] Separate Rust/Ratatui application under `tui/`.
- [x] Keyboard-driven contacts, conversation and settings panels.
- [x] Local peer-hint configuration with owner-only permissions.
- [x] Arch Linux PKGBUILD and build instructions.
- [x] Explicitly label messaging and transport as unavailable in this milestone.
- [ ] Run formatting, build, tests and Clippy in Linux CI.

## Milestone 2 — Android protocol interoperability

- [ ] Extract the Android direct-peer protocol into a platform-neutral Rust core.
- [ ] Reuse the exact mutual signed-identity proof and Noise_XX_25519_ChaChaPoly_SHA256 session implementation.
- [ ] Reuse Android's versioned bounded application-frame codec and message/acknowledgement semantics.
- [ ] Import and verify the same signed contact-card format, including the signed binding between P-256 identity and Noise X25519 static key.
- [ ] Add encrypted desktop identity storage and explicit fingerprint re-verification for key changes.
- [ ] Add loopback interoperability tests against the shared protocol test harness.

## Milestone 3 — direct messaging

- [ ] Explicit listen/connect for user-supplied direct addresses.
- [ ] Encrypted one-to-one text messages and delivery acknowledgements.
- [ ] Local encrypted history and outbox with bounded retry while peers are reachable.
- [ ] No centralised service, relay, push gateway, hosted discovery or remote mailbox.

## Milestone 4 — discovery and richer features

- [ ] Local-network discovery and direct Bluetooth transport where supported.
- [ ] Group messaging using the reviewed shared group protocol.
- [ ] File transfer and voice signalling/media after the shared encrypted transport is stable.
- [ ] Physical Android ↔ Linux interoperability testing.

## Security gate

Do not claim production E2EE or production readiness until the shared protocol, identity binding, storage, replay handling and cross-platform interoperability have been independently reviewed and tested. No plaintext application data may be sent before the authenticated Noise session is ready.
