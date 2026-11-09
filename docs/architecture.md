# TomChat architecture

## High-level design

```text
Jetpack Compose UI
      |
Kotlin application layer
      | JNI / UniFFI boundary (decision pending prototype)
Rust core
  |-- identity and device registry
  |-- encrypted local store and outbox
  |-- peer discovery and connection manager
  |-- message protocol and synchronisation
  |-- group state / MLS adapter
  |-- call signalling and media session coordinator
      |
Direct peer transports: QUIC first, TCP fallback where supported
      |
Other TomChat peers
```

Kotlin owns Android lifecycle, permissions, notifications, audio routing, foreground-service policy, and UI. Rust owns protocol state, peer networking, message validation, and cryptographic integration. Keep the FFI surface small, typed, versioned, and asynchronous.

## Strict network policy

- Local discovery: mDNS on the same LAN, subject to Android multicast and network lifecycle restrictions.
- Remote peer discovery: exchange signed peer cards through QR, NFC, Bluetooth, or another user-controlled out-of-band channel. A future optional distributed DHT may be evaluated, but must not introduce mandatory bootstrap operators or central indexes.
- Connectivity: direct QUIC with TCP fallback if the selected implementation supports it. Use NAT mapping / hole punching only where available and permitted.
- No relay, TURN, hosted rendezvous, central bootstrap, central directory, or push provider in strict mode.
- A user may operate their own always-on peer device, but the app must not require one.

**Known limitation:** phones are commonly behind carrier-grade NAT, firewalls, and restrictive networks. Without a relay or public rendezvous/bootstrap infrastructure, some remote peers cannot discover or reach one another. LAN communication and explicitly exchanged reachable addresses are the first reliable targets.

## Messaging model

- Durable local outbox with stable message IDs and idempotent retry.
- Signed envelopes; encrypt payloads end-to-end before transport.
- Per-conversation ordering metadata, acknowledgements, and replay protection.
- Peer-to-peer synchronisation when both peers are reachable.
- No global delivery guarantee while recipients are offline. Trusted devices can optionally hold encrypted mailbox copies, but they are not a universal substitute for a relay.
- Group state must converge safely; membership changes are authenticated and auditable.

## Cryptography

Do not invent cryptographic primitives or a custom handshake. Use maintained, independently reviewed implementations and protocol specifications. Evaluate OpenMLS for group key management and a reviewed asynchronous one-to-one protocol for pairwise sessions. Bind device identity, signed prekeys/session establishment, membership changes, and backup encryption to explicit threat-model requirements.

Transport encryption (for example QUIC/TLS) is not a substitute for end-to-end encryption. A peer forwarding ciphertext must not gain plaintext access.

## Voice

Voice media should use an audited WebRTC media stack where feasible, with DTLS-SRTP or equivalent authenticated media protection. Call signalling must travel over an already-established authenticated peer channel or be exchanged out of band. No mandatory signalling server or TURN service is allowed. Calls may fail across NATs when direct paths cannot be established.

Group voice needs a separate topology decision (peer mesh versus a user-hosted forwarding peer). Mesh has bandwidth and battery costs that grow with participant count; no central SFU is assumed.

## Backup and multi-device

Support both:
1. Local encrypted export, protected by a user-chosen passphrase and a modern password KDF.
2. Encrypted replication to explicitly trusted devices, with per-device authorization, revocation, and clear retention controls.

Backups must not silently export plaintext keys. Restore must detect rollback/replay where the protocol supports it. Losing all devices and the backup secret may mean permanent loss of identity and history; communicate this clearly.

## Android lifecycle

Use WorkManager for deferrable sync, not real-time calls. Use a foreground service only while an active user-visible call or explicitly enabled connection session requires it, subject to current Android policy. Do not promise reliable incoming calls while the app is force-stopped, background-restricted, or disconnected.

## Security and privacy requirements

- No passwords/accounts on a TomChat server.
- No plaintext message content in logs, crash reports, analytics, or notifications by default.
- Validate and bound all network inputs; rate-limit peer requests.
- Explicit contact verification and key-change warnings.
- Secure key storage using Android Keystore where appropriate; understand hardware-backed key limitations and backup/restore semantics.
- Threat model malicious peers, stolen devices, compromised endpoints, replay, spam/DoS, traffic analysis, metadata leakage, and malicious backup peers.
