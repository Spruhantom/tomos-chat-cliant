# TomChat development roadmap

## Current phase — direct conversations and group messaging

The Android and Linux clients have been brought together, but their conversation paths are not yet one interoperable implementation. Android has a direct one-to-one Noise conversation foundation; the Linux GTK conversation browser still contains a legacy REST/WebSocket envelope path. The next feature is to converge both clients on the decentralised peer protocol before adding cryptographic groups.

- [x] Android Kotlin/Jetpack Compose client foundation.
- [x] Linux Rust/GTK4/libadwaita desktop client foundation.
- [x] Shared Rust Noise XX peer transport core used by Android JNI.
- [x] Signed V2 contact cards binding P-256 signing identity to the X25519 Noise key.
- [x] Android direct TCP/Bluetooth transport foundations and encrypted one-to-one session.
- [x] Android group roster projection and bounded group-control frame foundation (not MLS).
- [x] Remove the unused Linux REST/WebSocket conversation implementation and its runtime dependencies.
- [x] Add a shared Rust application-frame codec matching Android AppFrameCodec v1, with golden wire tests.
- [x] Route Linux direct text chat through the same application frames used by Android.
- [x] Add matching Rust/Kotlin group envelopes and carry them inside authenticated Noise GROUP_CONTROL frames.
- [ ] Complete direct one-to-one conversation UI parity, history and lifecycle handling on Android and Linux.
- [ ] Integrate OpenMLS 0.9.x in the shared Rust core; implement create, add/Welcome, commit, application messages, remove and epoch transitions.
- [ ] Implement encrypted, atomic, durable MLS storage and restart/crash recovery on Android and Linux.
- [ ] Add group creation, invitation, member management, group history and group messaging UI on both clients.
- [ ] Verify Android ↔ Linux group interoperability, including removal/forward-secrecy and replay/wrong-epoch rejection.
- [ ] Demonstrate direct peer messaging on physical systems over Wi-Fi and Android Bluetooth.
- [ ] Complete install/upgrade, lifecycle, reconnect and independent security validation.

## Next phase — internet peer connectivity and status updates\n\n- [ ] Direct IPv6 and manually shared internet endpoint connections.\n- [ ] Signed, short-lived peer-assisted rendezvous records with multiple bootstrap peers; no single required operator.\n- [ ] Authenticated UDP hole punching and opt-in UPnP/NAT-PMP/PCP where safe and supported.\n- [ ] Network-change recovery, route diagnostics and clear no-direct-path handling; no TURN relay.\n- [ ] Status manifests, signed authorship, contact/group audience selection and 24-hour expiry.\n- [ ] Encrypted direct status/media distribution, local encrypted storage and expiry cleanup.\n- [ ] Android/Linux status timeline, mute/hide, local seen state and encrypted replies.\n- [ ] Physical Android↔Linux internet tests across IPv6, NAT and restrictive firewall networks.\n\n## Second feature freeze — planned after scope completion\n\nOnce the internet peer, group messaging and status scope is complete, enter a stabilisation-only freeze. Permit only security, data-loss, compatibility, release-blocking fixes, tests and documentation. See [second feature freeze](feature-freeze-2.md). The freeze is not active yet.\n\n## Platform responsibilities

### Android
- Kotlin, Jetpack Compose and Material 3 UI.
- Android lifecycle, permissions, notifications and audio routing.
- Android Keystore-backed signing identity and wrapped cryptographic storage keys.
- JNI boundary to the shared Rust Noise and MLS core.
- Direct TCP and Bluetooth transports; discovery is a hint, never identity proof.

### Linux desktop
- Native Rust GTK4/libadwaita application.
- Desktop Secret Service/keyring for signing identity, Noise identity, trusted contacts and local storage wrapping keys.
- Signed V2 contact-card creation/import and explicit fingerprint trust.
- Direct peer connection/listen using the same application protocol and MLS state machine as Android.
- No REST/WebSocket server-backed message store.

### Shared core
- Noise XX session and encrypted peer transport.
- Versioned, bounded application and group-protocol envelopes.
- OpenMLS group state machine and storage-provider boundary.
- Pinned remote static-key verification.
- Cross-platform test vectors and negative security tests.

## Security and product constraints

- No central application server, relay, hosted directory, push gateway or cloud message store.
- Never silently fall back to plaintext or central infrastructure when a peer is unreachable.
- Pairwise Noise encryption is not a substitute for MLS group encryption.
- The authenticated MLS transcript/Commit is authoritative for membership; UI roster state is only a projection.
- Persist cryptographic state atomically before acknowledging or using an advanced epoch.
- Offline delivery is not guaranteed without an always-on peer; clients must show availability and delivery status honestly.
- Do not claim production-ready E2EE or group messaging until protocol, persistence, interoperability and physical-device gates pass.

See [the group messaging design](group-messaging.md), [internet peer connectivity](internet-peer-connectivity.md), [status updates](status-updates.md), [second feature freeze](feature-freeze-2.md), and [real-world testing](real-world-testing.md).

## Release gates

A wider release requires:
1. CI passing for the exact release commit.
2. Successful install/upgrade tests on each advertised platform.
3. Real-device identity persistence and trusted-contact verification.
4. Demonstrated cross-platform direct messaging and group interoperability.
5. MLS restart/recovery, replay, epoch and removed-member forward-secrecy tests.
6. No unresolved critical/high-severity security or data-loss defects.
7. Independent cryptographic/security review and appropriate privacy/age-safety review.

A successful CI run alone is not proof of production readiness.
