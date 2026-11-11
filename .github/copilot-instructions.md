# Repository guidance for automated contributors

- Read `CONTRIBUTING.md` and the relevant files under `docs/` before changing architecture, transport, identity, encryption or installation behaviour.
- TomChat is strictly decentralised peer-to-peer. Do not add centralised application services, relays, hosted rendezvous/discovery, push gateways, central directories or hosted message storage.
- The supported active clients are Android (Kotlin/Compose) and Linux desktop (Rust/GTK4/libadwaita). Do not restore or add a TUI client.
- Keep changes within the current feature-freeze policy: bug/security/build fixes, docs and tests only unless maintainers explicitly approve a feature.
- Do not weaken signed contact-card verification, Noise pinned-key checks, frame bounds, secure key storage or fail-closed behaviour.
- Never store private identity keys in plaintext or include secrets in logs/tests/fixtures.
- Run the relevant Rust and Android checks from `CONTRIBUTING.md`; report checks not run.
- Do not claim real-device testing, Android/Linux interoperability, audit completion or production readiness without recorded evidence.
- Keep install commands and package lists aligned with `install.sh`; installer changes must not delete user data or keyring identities.
