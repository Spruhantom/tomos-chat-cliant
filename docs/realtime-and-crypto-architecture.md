# Real-time transport and cryptographic architecture

Status: real-time transport is implemented; end-to-end encryption and device identity are not.

## 1. Implemented transport

The client uses a background WebSocket worker for the authenticated `/api/v1/ws` endpoint. The session bearer token is sent in the HTTP upgrade Authorization header and never in a URL. The worker handles delivery, acknowledgements, errors, ping/pong, bounded reconnect backoff, session expiry, message-ID de-duplication, and REST history catch-up using the forward `after` cursor.

The GTK conversation view receives worker events through a channel, displays messages for the selected conversation, and buffers messages for other conversations. The transport is not itself encryption: it relays opaque payloads only.

## 2. Encryption and device identity

TomChat does not yet implement E2EE. The message composer must remain disabled until the cryptographic protocol, device lifecycle, persistence, recovery and verification work has been implemented and independently reviewed.

The current protocol evaluation selects IETF Messaging Layer Security (MLS, RFC 9420) as the preferred design for both direct and group conversations, using OpenMLS 0.9.2 as the first Rust implementation candidate, pinned exactly with compatible supporting crates. This is an engineering decision for a prototype, not a security certification or a claim that OpenMLS has been independently audited for TomChat's integration. OpenMLS is pre-1.0; its API and storage formats may change. Isolate it behind a narrow adapter and review release/security notes before upgrades. Upstream currently declares Rust 1.91.0 as its minimum supported toolchain, which must be reflected in CI.

Initial ciphersuite candidate: `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`. Do not silently negotiate a weaker or different suite. OpenMLS 0.9.2 documents this ciphersuite as supported. The selected suite and protocol version must be authenticated in the MLS group state and visible to clients.

## 3. Device identity and key lifecycle

Each installation is a distinct device. Account credentials authenticate to the service but are not encryption keys. A device creates its signing credential and MLS key material locally. Private keys and MLS group state remain local and are never uploaded.

The service stores only public device credentials, signed KeyPackages, device status/revocation metadata, and opaque MLS protocol messages. Device enrollment must be authenticated by an existing verified device or by an explicit recovery ceremony. A new device must not silently inherit old message history or impersonate an existing device.

Required lifecycle:
- create a device identity and private key material locally;
- publish a signed public credential and replenishable KeyPackages;
- verify a new device by QR/safety-code comparison with an existing device;
- add/remove devices through authenticated MLS membership changes;
- revoke a lost device and force a fresh group epoch;
- persist MLS state atomically before acknowledging a commit or application message;
- support encrypted, user-controlled recovery separately from normal device sync;
- clearly show unverified devices and membership/key changes in the UI.

## 4. Local storage

Use the desktop Secret Service through a maintained integration for small secrets and key-encryption material. Store encrypted MLS state in an application data directory with restrictive permissions. Derive a local wrapping key from a random secret held by the secret service; do not use the account password as the MLS key.

MLS state changes and outbound protocol messages must be committed transactionally. OpenMLS async operations are not cancellation-safe: on cancellation or storage failure, roll back the transaction, discard the in-memory group and reload it from durable storage. A crash or failed write must not leave the in-memory group state ahead of durable storage. Never log plaintext, private keys, KeyPackages, exported secrets, or message ciphertext. Disable cryptographic debug features in release builds.

## 5. Threat model and limitations

Assume the delivery server can observe, drop, reorder, replay, delay and inject network messages, and can lie about device-directory contents. MLS protects message confidentiality and integrity only when clients validate credentials and membership changes correctly.

Out of scope for the initial desktop release: compromised endpoints, keyloggers, screenshots, malicious OS/root access, traffic analysis, timing/size metadata, denial of service, and recovery-secret theft. The server still sees account identifiers, conversation membership, device directory metadata, message sizes and timestamps.

Account password reset must not recover or replace encryption identity. A user who loses all trusted devices and their recovery secret must be told that encrypted history is unrecoverable. Never create a server-side escrow key.

## 6. Implementation gates

1. Finalise the server and protocol API for device credentials, KeyPackages and opaque MLS messages.
2. Implement a narrow crypto adapter around a pinned OpenMLS release and a reviewed crypto provider.
3. Implement durable transactional storage and Secret Service integration.
4. Add unit tests using MLS test vectors, multi-device and multi-member integration tests, replay/reorder/fork tests, crash-recovery tests, and fuzzing.
5. Implement device verification, revocation and recovery UI.
6. Obtain independent review of the full integration and threat model.
7. Only then enable encrypted message composition behind a feature gate.

## 7. Current status

- Authenticated live transport and reconnect history recovery: implemented.
- MLS protocol adapter: not implemented.
- Device registration/directory APIs: not implemented.
- Secret Service integration and encrypted local state: not implemented.
- Device verification, revocation and recovery: not implemented.
- Message composer: intentionally disabled.
- Manual GTK runtime and multi-device testing: outstanding.
