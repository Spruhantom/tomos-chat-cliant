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
