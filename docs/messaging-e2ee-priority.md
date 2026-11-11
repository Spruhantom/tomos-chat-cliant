# TomChat parallel communications development plan

**Priority:** Highest active development priority  
**Branch:** `android-rebuild`  
**Product constraint:** Strict fully decentralised peer-to-peer. No TomChat application centralised services, relays, rendezvous services, hosted key directories, push gateways, TURN relays or cloud message storage.

## Product decision

Develop all five communication capabilities in parallel workstreams:

1. One-to-one secure text messaging.
2. End-to-end encryption and identity/key verification.
3. Secure group messaging and group membership management.
4. Encrypted one-to-one voice calls.
5. Encrypted group voice calls.

Parallel development means design, implementation, tests and prototypes can progress at the same time. It does **not** mean bypassing shared security dependencies or shipping unfinished paths. All workstreams must use the same reviewed identity and cryptographic foundations, and each feature remains disabled until its own security and device-test gates pass.

## Shared foundations — owned centrally

These are cross-cutting requirements for every workstream:

- Review and harden identity-to-key binding. Bind the Noise static public key to the existing signed contact identity; verify the signature and trusted fingerprint before session establishment.
- Protect local private keys using Android Keystore-backed storage and define backup, restore, rotation, revocation and device replacement behaviour.
- Complete and review the direct peer Noise transport integration. No application data may pass before handshake and pinned-peer verification succeed.
- Define bounded, versioned framing, strict payload limits, safe parsing, session lifecycle and fail-closed behaviour.
- Keep message and media payloads encrypted end-to-end. No plaintext fallback, cloud storage or remote decryption.
- Review JNI boundaries, key handling, memory lifetime, error paths and cryptographic protocol choices.
- Maintain Rust format/check/test/Clippy, Kotlin JVM tests, Android APK builds and ABI/JNI export checks. Emulator boot is not a required CI gate; physical-device checks remain mandatory for device claims.

## Parallel workstreams

### A. One-to-one text messaging

- Build the versioned encrypted message envelope over the Noise transport.
- Implement send/receive, stable message IDs, duplicate suppression and bounded payload validation.
- Add encrypted local message persistence, conversation state and honest sending/delivery status.
- Test bidirectional exchange, reconnect, partial TCP reads/writes, malformed frames, replay and disconnect behaviour.

### B. End-to-end encryption and identity

- Verify signed contact identity cards and their bound Noise public keys.
- Pin the expected peer static key and reject mismatches, missing bindings, revoked contacts and identity changes.
- Ensure all application data is carried only inside authenticated Noise transport records.
- Add negative tests for wrong keys, tampered ciphertext, replay, incomplete handshakes and closed sessions.
- This workstream is a shared dependency: other workstreams may build against its interfaces and test doubles, but must not bypass its security gates.

### C. Group messaging

- Evaluate and integrate a reviewed group key-management protocol such as MLS/OpenMLS; do not invent a bespoke group cryptographic scheme.
- Design authenticated group creation, invitations, joins, removals, membership changes and key rotation.
- Persist group state safely and test restoration after process/app restart.
- Test concurrent membership changes, stale/forked state, replay, duplicate delivery and removed-member access.
- Keep group message delivery peer-to-peer. Document that offline or unreachable members cannot receive messages until a direct path is available.

### D. One-to-one voice calls

- Develop call signalling/state machines alongside text messaging, but gate real calls on the verified peer session.
- Design direct peer-to-peer audio transport and end-to-end media protection separately from signalling encryption.
- Define authenticated media-key derivation, nonce management, replay protection, rekeying and teardown with cryptographic review.
- Implement microphone permission, audio focus, mute, speaker/headset routing, Bluetooth headset support, packet-loss handling and network interruption.
- Test call setup/teardown, two-way audio, mute, route changes, background/foreground and reconnect on physical devices.

### E. Group voice calls

- Design group-call membership, admission/removal and participant state in parallel with group messaging.
- Use a reviewed group media-key distribution and rotation design; never reuse a one-to-one media key for a group.
- Protect media against replay and unauthorized late joiners.
- Test participant joins/leaves, key rotation, mute, packet loss, network changes and call teardown across multiple devices.
- Keep media direct between peers. No SFU, media centralised service, TURN relay or cloud fallback. Clearly expose when direct connectivity is unavailable.

## How parallel development is coordinated

- Keep shared protocol and cryptographic interfaces in a single reviewed design to prevent incompatible implementations.
- Work in isolated feature branches or clearly separated modules, with small reviewable commits.
- Use mocks/test doubles to let UI, group-state and call-state work proceed while the shared encrypted transport is being completed.
- Integrate frequently into `android-rebuild`; resolve interface changes centrally and run the full CI suite after integration.
- Never merge unfinished experimental media or group-key code into a release path merely to keep parallel work moving.
- Run a threat-model and code review for each cryptographic protocol boundary before enabling it.

## Milestones and release gates

The workstreams can progress concurrently, but release is gated independently:

- **Text messaging:** verified peer identity, Noise-only application transport, framing/negative tests, encrypted local persistence and successful two-physical-device Wi-Fi exchange.
- **Group messaging:** reviewed group protocol, secure state restoration, tested membership changes/removal/key rotation and multi-device encrypted exchange.
- **One-to-one voice:** authenticated call setup, independently protected media, replay/rekey review, and physical-device two-way audio tests.
- **Group voice:** reviewed group media-key lifecycle, secure joins/leaves/removal, replay protection and multi-device audio tests.
- **Overall release:** independent security review of identity, key storage, Noise integration, message framing, group key management, media protection, JNI and lifecycle/error handling.

Until a feature passes its own gates, keep it marked experimental or disabled. Do not describe TomChat or any incomplete feature as production-ready E2EE.

## Deferred

- File sharing and voice notes.
- Bluetooth data transport (Bluetooth headset audio routing for calls remains in scope).
- TomSocial and public discovery.
- Unrelated UI polish.

These remain secondary to delivering the five communication workstreams safely.
