# TomChat messaging and end-to-end encryption priority

**Priority:** Highest active development priority  
**Branch:** `android-rebuild`  
**Product constraint:** Serverless peer-to-peer. No TomChat application servers, relays, rendezvous services, hosted key directories, push gateways, or cloud message storage.

Messaging and end-to-end encryption take precedence over new features, visual polish, social features, and optional platform integrations until the secure two-device text-messaging milestone is complete.

## Current position

- Android application and account/contact foundations exist.
- Direct TCP peer transport and mutual identity authentication foundations exist. Mutual authentication alone is not end-to-end encryption.
- The Rust Noise XX core and JNI boundary exist as an experimental implementation.
- Noise has not yet been integrated into the direct TCP application-data path.
- There is no complete encrypted application-message framing and delivery path yet.
- Do not describe TomChat as having production-ready E2EE or enable ordinary messaging until the release gates below are met.

## Priority sequence

### 0. Keep the build and test foundation green

- Maintain Rust formatting, check, test and Clippy checks.
- Run Android JVM tests for the Kotlin Noise wrapper lifecycle and handshake guard.
- Build the Android APK and verify the packaged native library and required JNI exports for ARM64, ARM32 and x86_64.
- Keep physical-device checks as a documented manual validation activity; emulator boot is not a required CI gate.

### 1. Review and harden the identity-to-Noise binding

- Confirm the local Noise static private key is generated, stored and retrieved securely, with Android Keystore-backed protection and explicit lifecycle handling.
- Bind the Noise public key to the existing signed contact identity card.
- Verify the contact-card signature and the peer key fingerprint before starting a session.
- Ensure the key presented during the Noise XX handshake exactly matches the trusted contact's pinned Noise key.
- Test changed keys, missing bindings, malformed keys, revoked contacts and identity replacement.
- Keep direct TCP identity authentication separate from the cryptographic protection of application data.

### 2. Define a bounded encrypted message protocol

- Specify a versioned, length-prefixed frame format with strict maximum sizes.
- Carry application message type, stable message ID and payload only inside Noise-encrypted records.
- Reject malformed, oversized, truncated and unsupported frames safely.
- Define connection/session binding, ordering, disconnect and re-handshake behaviour.
- Use Noise transport sequencing for cryptographic replay/order protection and message IDs for application-level duplicate suppression.
- Never fall back to plaintext if encryption, key verification or framing fails.

### 3. Connect Noise to direct peer transport

- Integrate the Noise session into the existing direct TCP connection lifecycle.
- Exchange bounded handshake messages over the authenticated direct connection.
- Complete and verify the Noise handshake before accepting or sending application data.
- Route all application payloads through authenticated Noise transport records.
- Make session close, cancellation, timeout and network loss fail closed.
- Keep Bluetooth discovery and any other transport as separate follow-on work; do not delay the first Wi-Fi messaging milestone for them.

### 4. Prove the encrypted transport with automated tests

Add deterministic tests for:

- Two peers completing the Noise XX handshake with the correct pinned keys.
- Wrong, changed or untrusted peer keys being rejected.
- Bidirectional encrypted payload exchange.
- Tampered ciphertext and malformed records being rejected.
- Replay, duplicate message IDs, out-of-order and truncated frames.
- Oversized payloads and invalid protocol versions.
- Partial TCP reads/writes and multiple frames in one stream.
- Disconnect during handshake, disconnect during transfer and reconnect with a fresh session.
- Session closure and attempts to use closed or incomplete sessions.
- Confirming no application plaintext reaches the socket writer or UI before encryption.

Use Rust integration tests with loopback TCP and Kotlin/JVM tests for wrapper and state-machine behaviour. Keep Android native library packaging/JNI export checks in CI. Physical Android-to-Android testing remains required before claiming real-device compatibility.

### 5. Deliver the first two-device messaging prototype

- Add a minimal conversation view and message composer.
- Send and receive one-to-one text messages over direct Wi-Fi TCP using the integrated Noise session.
- Persist received and sent messages locally using encrypted storage.
- Show clear connection and delivery states without claiming delivery when a peer is unreachable.
- Test on two physical Android devices on the same Wi-Fi network.
- Capture logs and test results with private keys and message contents excluded.

## Release gates

The first messaging test milestone is complete only when:

1. Identity and Noise key binding is verified against signed, trusted contact data.
2. No application data can pass before the Noise handshake and peer-key pin check succeed.
3. The direct TCP data path uses Noise encryption exclusively.
4. Automated tests cover valid exchange and the negative cases listed above.
5. Two physical Android devices exchange encrypted text messages successfully over direct Wi-Fi.
6. A code/security review has checked key handling, JNI boundaries, framing, session lifecycle and failure behaviour.

Until all gates are met, messaging remains a development prototype and E2EE must not be advertised as production-ready.

## Explicitly deferred

- Group messaging and group key management.
- File and voice-message transfer.
- Voice/video calls.
- Bluetooth transport integration.
- TomSocial and public discovery.
- UI polish unrelated to the secure messaging test path.

These are deferred so the team can focus on proving a small, secure, observable end-to-end path first.
