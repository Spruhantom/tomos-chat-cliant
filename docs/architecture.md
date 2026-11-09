# TomChat architecture

## Non-negotiable zero-server policy

TomChat and the planned TomSocial layer are designed to run without any server, whether operated by TomOS, a third party, a community, or the user. No server is a required or optional network component in the product architecture.

Prohibited dependencies include hosted rendezvous, bootstrap nodes, DHT bootstrap services, relays, TURN, STUN services, push gateways, central directories, cloud databases, hosted object storage, and central message or feed stores. Do not add a fallback that silently contacts any such service.

The internet's routers and carrier networks are still infrastructure, but TomChat does not deploy or depend on application-layer servers. The app may use local network infrastructure and direct public IP connectivity.

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
Direct device-to-device transports
  |-- Bluetooth LE (nearby discovery / GATT data channel)
  |-- Bluetooth Classic (RFCOMM where supported)
  |-- Wi-Fi LAN (mDNS + direct sockets / QUIC)
  |-- direct IP over internet (best effort)
      |
Other TomChat peers
```

Kotlin owns Android lifecycle, permissions, notifications, audio routing, foreground-service policy, and UI. Rust owns protocol state, peer networking, message validation, and cryptographic integration. Keep the FFI surface small, typed, versioned, and asynchronous.

## Peer discovery without servers

- Bluetooth LE advertisements and GATT can discover nearby TomChat peers and exchange bounded connection/bootstrap data directly. Use rotating privacy-preserving identifiers; do not advertise a stable public identity or personal details.
- Bluetooth Classic RFCOMM can provide a direct byte-stream transport on compatible paired devices. Pairing and Android permission requirements must be handled explicitly.
- Same-network discovery: mDNS / local multicast where Android permits it. This requires no central service.
- Nearby out-of-band exchange: QR codes, NFC, Bluetooth, or a user-shared contact card containing a signed identity and connection hints.
- Remote discovery: peers exchange signed address/contact cards directly. Existing contacts can share updated signed reachability hints peer-to-peer.
- No DHT bootstrap service, hosted rendezvous, central index, public peer directory, or server-seeded discovery.
- A device may discover peers it already knows or peers on its local network. Finding an arbitrary stranger across the global internet without any shared discovery path is not guaranteed.

## Direct connectivity and hard limits

The transport manager should select an available direct path without changing the end-to-end message protocol:
1. Bluetooth LE for nearby discovery and small control/message payloads.
2. Bluetooth Classic RFCOMM for nearby higher-volume data transfer where supported.
3. Wi-Fi LAN for higher throughput and larger transfers.
4. Direct QUIC/IP for reachable remote peers.

Messages and attachments remain end-to-end encrypted and authenticated identically across every transport. Switching transports must not create a new identity or weaken session security. Support resumable chunked file transfer and transport interruption.

**Bluetooth voice distinction:** Bluetooth is a supported peer transport, but BLE/GATT and RFCOMM are not assumed to provide reliable, low-latency continuous voice media on every Android handset. Initial voice calls should use a tested direct IP/Wi-Fi path; investigate direct Bluetooth media separately with real-device latency, throughput, coexistence, and battery tests. Bluetooth headsets must be supported as audio input/output for calls regardless of the peer transport.

Use direct QUIC where possible, with a carefully evaluated direct TCP fallback. Investigate hole punching only as a peer-coordinated technique; do not introduce STUN, TURN, relay nodes, or a hosted signalling service.

**Known limitation:** phones are commonly behind carrier-grade NAT, firewalls, and restrictive networks. Without a rendezvous service or relay, some remote device pairs cannot discover each other or establish a connection. Some carriers do not provide reachable inbound addresses. A strict zero-server product must clearly report "peer unreachable" rather than silently routing through infrastructure.

The first proof of concept must test two physical Android phones over Bluetooth and on the same LAN. Remote direct-IP tests come after that and must document network conditions and failure cases.

## Automatic fastest-path selection

The connection manager should choose the fastest suitable direct path automatically, rather than always preferring one transport by name. It should consider the actual task and current link conditions:

- Measure recent connection setup time, round-trip latency, usable throughput, packet loss, stability, and estimated battery cost for each available direct path.
- For nearby devices, evaluate Bluetooth LE, Bluetooth Classic, and Wi-Fi LAN. For remote peers, evaluate only direct IP paths that are already known and reachable.
- Prefer low latency for chat and call signalling; prefer sustained throughput and stability for file transfers; consider battery use for background synchronisation.
- Use a short, bounded connection probe and cached recent measurements. Re-evaluate when the network changes or a transfer/call experiences degradation.
- If a better direct path becomes available, authenticate it as the same peer and migrate the session safely where the protocol permits. Otherwise finish the current operation and use the better path next time.
- Never send message plaintext over an unverified path. End-to-end encryption and peer authentication remain identical regardless of transport.
- Do not scan or probe continuously: respect Android permissions, battery, user settings, and background-execution limits.
- Expose the active transport and a manual "prefer Bluetooth / Wi-Fi / automatic" setting for troubleshooting and user control.

There is no universally fastest transport: Bluetooth can win for nearby small payloads when Wi-Fi is unavailable, while Wi-Fi usually wins for large transfers. The app must benchmark real conditions and choose accordingly. Fastest-path selection must never introduce a relay, STUN/TURN, hosted discovery, or any other server dependency.

## Messaging model

- Durable local outbox with stable message IDs and idempotent retry.
- Signed envelopes; encrypt payloads end-to-end before transport.
- Per-conversation ordering metadata, acknowledgements, and replay protection.
- Peer-to-peer synchronisation when both peers are reachable.
- No global delivery guarantee while recipients are offline. The sender retains encrypted pending messages locally and retries when the peer becomes reachable. No server-side mailbox exists.
- Group state must converge safely; membership changes are authenticated and auditable.
- A trusted device can receive a user-authorised encrypted backup, but it is another user's device, not a hosted service or guaranteed always-on mailbox.

## Cryptography

Do not invent cryptographic primitives or a custom handshake. Use maintained, independently reviewed implementations and protocol specifications. Evaluate OpenMLS for group key management and a reviewed asynchronous one-to-one protocol for pairwise sessions. Bind device identity, signed prekeys/session establishment, membership changes, and backup encryption to explicit threat-model requirements.

Transport encryption (for example QUIC/TLS) is not a substitute for end-to-end encryption. A peer forwarding ciphertext must not gain plaintext access.

## Voice

Voice media should use an audited WebRTC media stack where feasible, with DTLS-SRTP or equivalent authenticated media protection. Call signalling must travel over an already-established authenticated peer channel or be exchanged out of band. No signalling server, STUN, or TURN service is allowed. Calls may fail across NATs when direct paths cannot be established.

Group voice uses a direct peer mesh for the initial design. Bandwidth and battery costs grow with participant count; establish conservative participant limits through testing. No central SFU or user-hosted forwarding server is assumed. Bluetooth headsets are supported for local audio routing. Direct Bluetooth peer-to-peer voice media is a separate experimental transport and must pass device testing before being advertised as supported.

## Backup and multi-device

Support both:
1. Local encrypted export, protected by a user-chosen passphrase and a modern password KDF.
2. Encrypted replication to explicitly trusted devices, with per-device authorization, revocation, and clear retention controls.

Backups must not silently export plaintext keys. Restore must detect rollback/replay where the protocol supports it. Losing all devices and the backup secret may mean permanent loss of identity and history; communicate this clearly. Trusted-device replication requires both devices to be reachable and is not cloud backup.

## Android lifecycle

Use WorkManager for deferrable local retry/sync, not real-time calls. Use a foreground service only while an active user-visible call or explicitly enabled connection session requires it, subject to current Android policy. Do not promise reliable incoming calls while the app is force-stopped, background-restricted, or disconnected. No push notification service is permitted; incoming-call alerting depends on an already-running/reachable peer connection and Android background execution constraints.

Request Bluetooth runtime permissions only when the user invokes nearby discovery or transfer. Handle Android-version differences, scan/connect permissions, user denial, Bluetooth disabled state, pairing prompts, adapter loss, MTU negotiation, and transfer interruption. Do not keep scanning continuously in the background.

## Social layer compatibility

TomSocial must follow the same zero-server rule. Public posts and profile updates are signed objects exchanged directly between peers. A user's devices keep local copies. Users may explicitly replicate selected public content to their own trusted devices, but there is no shared relay, public content host, global index, or central feed database. Public discovery is limited to known peers, local discovery, and user-shared invitations. Global searchable discovery is not promised.

## Security and privacy requirements

- No passwords/accounts on a TomChat server.
- No plaintext message content in logs, crash reports, analytics, or notifications by default.
- Validate and bound all network inputs; rate-limit peer requests.
- Explicit contact verification and key-change warnings.
- Secure key storage using Android Keystore where appropriate; understand hardware-backed key limitations and backup/restore semantics.
- Threat model malicious peers, stolen devices, compromised endpoints, replay, spam/DoS, traffic analysis, metadata leakage, and malicious backup peers.

## Device identity and contact cards (prototype)

Each Android installation creates a P-256 ECDSA signing key pair in Android Keystore. The private key is non-exportable through the app's identity API; the public key is encoded as X.509 SubjectPublicKeyInfo. A contact card contains a version marker, display name, public key, SHA-256 fingerprint and a signature over the canonical card fields. Import-side verification checks the fingerprint and signature before presenting the card.

The QR representation is generated locally using the versioned signed contact-card payload. It contains no date of birth, phone number, IP address, location, or server URL. A self-signature proves that the card fields match the included public key; it does **not** prove that the person presenting the card is the person named. Users must compare the full fingerprint through a separate trusted channel before accepting a contact. A future contact-import flow must require a clear confirmation and persist the accepted public key locally.

Current implementation status:
- Android Keystore P-256 identity creation/retrieval: implemented, physical-device testing pending.
- Signed contact-card encoding, decoding and tamper checks: implemented with JVM tests.
- QR image rendering: implemented.
- Camera QR scanning, import confirmation, trusted-contact persistence and key-change/revocation UI: not implemented yet.
- Contact-card sharing remains blocked by the central account policy while age assurance or required guardian approval is incomplete.

Do not treat a valid self-signed card as a trusted contact, and do not use a display name as an identity identifier. Fingerprints are the human-verification mechanism for this prototype.

## QR scanning and trusted contacts (prototype)

The Android client now requests camera permission only when the user opens the QR scanner. CameraX provides a local preview and ZXing decodes QR frames on a bounded single-thread executor. The scanner accepts only the signed TomChat contact-card format and validates its embedded fingerprint and ECDSA signature before showing a review screen.

Trust is a separate explicit action. The review screen displays the full SHA-256 fingerprint and requires the user to confirm that they compared it with the other person's device through a separate trusted channel. The self-signature alone does not authenticate a real-world person. The user can reject the card or explicitly save/remove a trusted contact.

Trusted contacts are stored in an app-private atomic file encrypted with AES-256-GCM under a separate Android Keystore key. The store rejects malformed counts, oversized keys, duplicate fingerprints, invalid fingerprints, trailing data, and unreadable/corrupt ciphertext. The raw QR payload is not stored. Camera permission is not requested until scanning is invoked.

Remaining validation: run Android CI, test on physical devices with different camera sensor row/pixel strides and lighting, test permission denial/revocation, inspect QR readability at different display sizes, and test Keystore key invalidation/interrupted writes. This prototype is not a complete contact synchronisation, revocation, key-change or identity-assurance system.

## Direct peer connection and mutual identity proof (prototype)

The Android prototype now has an explicitly invoked direct TCP connector and a temporary peer listener bound to a caller-selected local interface and port. There is no registration, hosted rendezvous, relay, directory, or persistent background listener. The peer must already know the other device's reachable address and port; global reachability is not guaranteed.

Before returning a connection handle, both sides exchange fresh 256-bit random nonces and sign a domain-separated transcript containing both nonces and the local identity fingerprint with the device's P-256 identity key. Each side verifies the other signature against the public key and fingerprint in its locally stored trusted-contact record, then exchanges an explicit success/failure acknowledgement so neither side treats one-sided verification as mutual authentication. The exchange is bounded by an eight-second socket read timeout, a bounded signature length, a protocol magic value and version. Failed authentication closes the socket. A wrong-key peer is rejected.

**Critical security boundary:** this is mutual proof of possession and replay-resistant connection authentication, not an encrypted application channel. The connection handle deliberately exposes no input/output streams or message-send methods. Do not send chat messages, files, call signalling, or other application data over it. The next cryptographic milestone must integrate a maintained, reviewed Noise or equivalent protocol implementation and bind its authenticated peer identity to the trusted contact before any application payload API is exposed. Do not expand this prototype into a custom encryption or key-agreement protocol.

Current status:
- Direct TCP connector and explicitly started temporary listener: implemented.
- Mutual signed-nonce identity proof and trusted-key pinning: implemented.
- Loopback success and unexpected-identity rejection tests: added; CI result pending.
- End-to-end encrypted session, secure stream framing, message protocol and physical-device Wi-Fi tests: not implemented.
- Direct internet connectivity remains best effort and depends on reachable addresses and firewall/NAT configuration.

## Experimental Noise session core

An isolated Rust crate at `android/native/tomchat-noise` now prototypes the Noise
XX pattern using Snow 0.10.0 and the fixed suite
`Noise_XX_25519_ChaChaPoly_SHA256` (X25519, ChaCha20-Poly1305, SHA-256).
The wrapper requires the caller to provide a local 32-byte X25519 static
private key and a pinned 32-byte peer static public key. It completes the
three-message XX handshake, checks the remote static key against the pin, and
only then permits Noise transport records. The Noise transport provides
authenticated encryption and ordered nonce state; callers cannot use transport
before handshake completion and pin verification.

The Android client now generates a per-installation X25519 static key
using the Bouncy Castle X25519 implementation. Its 32-byte private scalar is
stored only as AES-256-GCM ciphertext in an app-private atomic file, with the
wrapping key held by Android Keystore. On load, the public key is re-derived
from the decrypted private key and compared in constant time with the stored
public key. A missing wrapping key or invalid ciphertext fails closed without
replacing existing identity data.

New V2 QR contact cards include the X25519 static public key in the canonical
fields signed by the device's P-256 ECDSA identity. The existing SHA-256
fingerprint remains the fingerprint of the P-256 identity; the Noise key is
cryptographically bound by that signature. The trusted-contact store now
persists the bound Noise public key and can read the previous V1 store format.
Legacy V1 cards remain readable for compatibility, but are clearly marked and
cannot be saved as Noise-capable contacts in the new UI.

The Rust core is now exposed through a narrow JNI boundary. Rust owns
Noise handshake and transport state behind opaque session handles; Kotlin can
only exchange bounded handshake messages, finish the handshake, encrypt,
decrypt, and close a session. The JNI entry point copies the local private
scalar into Rust, zeroizes its temporary copy, and does not return cipher state
or key schedules to Kotlin. The Kotlin wrapper copies the private scalar only
for the native call and clears that temporary array afterward. The trusted
contact's saved signed Noise public key is passed as the expected remote static
key, and the Rust core refuses to enable transport records until the remote
static key matches it.

The Android Gradle build now invokes cargo-ndk to package the Rust library for
arm64-v8a, armeabi-v7a and x86_64. Android CI installs the NDK and Rust targets,
checks formatting, runs host-side Rust tests, and builds the native library as
part of the APK build. Android instrumentation tests exercise the JNI handshake,
peer-key pinning and bidirectional encrypted records on a device/emulator.

This is a cryptographic core integration only. It is not yet connected to the
direct TCP socket, has no bounded stream framing or application message
protocol, and the legacy identity-proof socket still must not carry chat data.
The next step is to connect a framed handshake exchange to the direct transport
without exposing plaintext APIs, then validate two physical Android devices.

Snow explicitly states that it has not received a formal security audit. The
TomChat wrapper and identity-binding design are also unaudited. This is an
experimental protocol integration, not production-ready E2EE. Do not enable
messaging over it until independent security review, official Noise test-vector
coverage, malformed-input tests, key lifecycle design, FFI integration and
physical-device validation are complete.


### Signed Noise identity binding milestone

- [x] Create a persistent X25519 static key on each Android installation.
- [x] Encrypt the private scalar at rest with AES-256-GCM and an Android Keystore wrapping key.
- [x] Verify the stored public key matches the private scalar on every load.
- [x] Add V2 signed contact cards that include the Noise public key in the P-256 signature.
- [x] Persist the bound peer Noise key with trusted contacts; retain V1 encrypted-store read compatibility.
- [x] Keep legacy V1 cards readable but prevent treating them as Noise-authenticated contacts.
- [x] Connect the identity store and Noise session core through a narrow JNI boundary.
- [x] Add Android instrumentation tests for JNI Noise handshake, key pinning and encrypted records.
- [ ] Add Keystore invalidation/recovery instrumentation tests.
- [ ] Run official Noise test vectors, malformed handshake tests, and physical-device peer tests.
