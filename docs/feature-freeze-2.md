# TomChat second feature freeze

Status: planned release milestone. This freeze begins only after the agreed scope is implemented; it is not active yet.

## Scope to complete before the freeze

- Internet-wide direct peer connectivity across separate networks, without a central relay.
- Android/Linux interoperable direct conversations and authenticated group messaging.
- OpenMLS group cryptography, durable encrypted storage and epoch/recovery handling.
- Decentralised ephemeral status updates with contact/group audience controls.
- Existing QR contact exchange, webcam scanning, Bluetooth discovery and transport selection.

## Freeze rules

When the scope above is complete, stop accepting new product features. During the freeze, only the following are permitted:

- Critical/high security fixes and dependency vulnerability fixes.
- Data-loss, crash, compatibility and release-blocking bug fixes.
- Tests, CI reliability, diagnostics, documentation and accessibility fixes.
- Privacy improvements that do not expand the product scope.

Any proposed feature addition requires explicitly ending the freeze and updating the release scope. No central server, TURN relay, cloud storage or plaintext fallback may be introduced to make a release gate appear to pass.

## Exit gates

- Exact release commit has green Android and Linux CI.
- Physical Android↔Linux encrypted direct chat works across separate internet connections.
- IPv6, manual endpoint, peer-assisted rendezvous and supported NAT traversal paths have documented test results.
- Restrictive NAT/firewall failure is clear, safe and does not trigger relay fallback.
- Packet/architecture review confirms no central message or status storage.
- MLS create, add/Welcome, join, send/receive, remove, epoch and removed-member forward-secrecy tests pass.
- Durable MLS storage survives process death, restart and app upgrade; corruption/rollback fails closed.
- Status audience exclusion, expiry, tampering, replay, mute and cleanup tests pass.
- Install, upgrade, key persistence, reconnect and recovery pass on supported platforms.
- No unresolved critical/high security or data-loss defects.
- Independent security review is completed before production-ready security claims.

A feature freeze is a stabilisation period, not evidence that a feature is complete or secure.