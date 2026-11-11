# Contributing to TomChat

Thanks for helping improve TomChat. The project currently contains two clients: Android (Kotlin/Compose) and Linux desktop (Rust/GTK4/libadwaita), sharing the Rust Noise peer-transport core.

## Product constraints

- Keep TomChat fully decentralised and peer-to-peer.
- Do not add an application server, central account service, relay, hosted discovery/rendezvous, push gateway, central directory, TURN/STUN dependency or hosted message store.
- Do not add a silent centralised fallback when a direct peer connection fails.
- Treat addresses and discovery results as hints, never as identity. Verify signed contact cards and pinned peer keys.
- Do not invent cryptographic protocols or primitives. Preserve the reviewed protocol boundaries and fail-closed key handling.
- Do not log private keys, contact-card secrets, plaintext messages or sensitive user data.
- Do not claim production readiness, audited security or Android/Linux interoperability without evidence.

## Current development phase

TomChat is in feature-freeze / real-world validation. Restrict changes to:
- reproducible bug fixes;
- security and privacy fixes;
- build and packaging fixes;
- documentation;
- tests and validation tooling.

Discuss any proposed feature expansion before implementation.

## Branch and pull-request workflow

1. Branch from the latest `main` using a focused name such as `fix/android-keystore-recovery` or `docs/linux-install`.
2. Keep one logical change per pull request.
3. Never force-push or rewrite shared history unless the maintainers explicitly request it.
4. Keep PRs small, describe the risk and user-visible effect, and link related issues.
5. Do not merge your own PR unless you have explicit maintainer approval.
6. Do not delete or overwrite user identity data as part of migration or uninstall behaviour.

## Required checks

For Linux/Rust changes, run:

```bash
cargo test --locked
cargo clippy --all-targets --locked -- -D warnings
cargo fmt --all -- --check
```

For Android changes, run:

```bash
gradle --no-daemon :android:app:testDebugUnitTest
gradle --no-daemon :android:app:assembleDebug
```

Run `gradle --no-daemon :android:app:connectedDebugAndroidTest` when a suitable device/emulator is available. Explain any checks that could not be run. CI must pass for the exact PR head before merge.

## Security-sensitive changes

- Explain threat assumptions and failure behaviour.
- Add negative tests for wrong keys, malformed input, tampering and replay where relevant.
- Preserve bounded network input and authenticated identity checks.
- Ensure key-store failures fail closed; never fall back to plaintext key storage.
- Do not state that a feature is end-to-end encrypted unless the complete data path is demonstrably encrypted and authenticated.

## Documentation

Update README and relevant platform/security docs when commands, dependencies, supported platforms, user-visible behaviour or limitations change. Keep installation instructions reproducible and do not advertise a distribution as supported until it has been tested.

## Pull-request checklist

- [ ] Scope is within the current feature-freeze policy.
- [ ] No centralised service or relay dependency was introduced.
- [ ] Tests and CI pass for this commit, or unavailable checks are explained.
- [ ] Security/privacy implications and limitations are documented.
- [ ] No secrets, private keys, tokens, user data or generated build outputs are committed.
- [ ] README/platform docs are updated where needed.
