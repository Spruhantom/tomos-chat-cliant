# TomChat direct transport and cryptographic architecture

Status: Android direct peer transport and Noise one-to-one foundations exist. A shared Android/Linux conversation protocol, durable MLS group messaging and production-grade E2EE validation are incomplete.

## 1. Decentralised network model

TomChat is a peer-to-peer application. Each participating device owns its identity and local data. Devices discover one another using local network discovery, nearby Bluetooth discovery, QR contact exchange, or a user-shared reachable address.

Discovery results are hints only. They must never be treated as proof of identity. No central application server, required hosted rendezvous, TURN relay, push gateway or cloud message store is part of the product design. Internet peer discovery is planned through signed, short-lived endpoint hints exchanged with multiple independently operated bootstrap peers and trusted peers. Rendezvous peers carry no message or media payloads. When a direct path is unavailable, the app reports that the peer cannot currently be reached.

## 2. Direct transport

The Android prototype provides direct TCP transport, automatic route selection, local network discovery, and nearby Bluetooth discovery foundations. Wi-Fi and Bluetooth transports share a transport-neutral socket interface. Internet-wide connectivity is a planned next stage: direct IPv6/manual endpoints first, then signed peer-assisted rendezvous and UDP hole punching, with opt-in UPnP/NAT-PMP/PCP only where supported. There is no TURN relay fallback; restrictive NAT/firewalls may prevent direct connectivity. The peer connection must complete mutual identity proof and a pinned-key Noise XX handshake before encrypted application frames are accepted.

Bluetooth discovery is for finding nearby devices and selecting a direct connection; it is not identity authentication. Network changes, Android permission changes, background limits, and device sleep can interrupt a direct session. Reconnection and failover must preserve peer identity and session safety.

The active Linux GTK application uses a direct peer chat worker over the shared Noise XX transport. The obsolete REST/WebSocket conversation modules and their unused runtime dependencies have been removed. Linux now encodes text using the same version-1 application-frame layout as Android. Physical Android/Linux interoperability still needs to be demonstrated.

## 3. Encryption, identity and groups

Each installation has its own device identity. Contact cards are exchanged out of band and the user approves the full fingerprint before trusting a peer. IP addresses, Bluetooth addresses, display names, and discovery advertisements are not identity credentials.

The Android prototype contains a native Rust Noise XX core exposed through JNI, Kotlin session integration, encrypted application-frame foundations and a one-peer conversation session. Linux and Android now share the version-1 application frame format and matching Rust/Kotlin golden tests. A bounded group-control frame, a versioned group-protocol envelope in Rust and Kotlin, and a Kotlin group-roster reducer also exist. Linux and Android can now carry opaque group envelopes inside the authenticated Noise channel. The reducer is explicitly only a UI/domain projection: it assumes membership events were already authenticated and committed by a future MLS layer. The envelope is not a group cryptographic protocol.

The current tomchat-noise Rust crate contains Noise/JNI dependencies; OpenMLS is not yet integrated into the shared core. Durable MLS group storage, transactional group-state updates, restart restoration, epoch handling and multi-device recovery are not implemented. The group design is specified in [group-messaging.md](group-messaging.md).

Group messages must use OpenMLS (target baseline 0.9.x) for group membership and application-message protection. Do not emulate a group by sending independent pairwise Noise ciphertexts or by trusting a UI roster. Authenticated MLS proposals/commits determine membership and epochs. Removing a member must advance the epoch so that the removed member cannot decrypt later messages.

Do not describe TomChat as production-ready E2EE until persistent cryptographic state survives app updates and restarts, rollback/replay protections are verified, and Android/Linux interoperability tests pass.

## 4. Direct group distribution

There is no central service to fan out or store messages. A sender transmits an MLS ciphertext to currently reachable group members over authenticated direct peer sessions. Delivery is tracked per peer; offline delivery is not guaranteed. Wi-Fi and Bluetooth are transport choices only and do not alter group identity or cryptographic membership.

## 5. Status updates\n\nEphemeral text/photo status updates are planned as signed, audience-scoped objects distributed directly to selected trusted contacts. The default lifetime is 24 hours. Media is encrypted before direct transfer and kept in encrypted local storage. Group audiences require the MLS group engine. There is no global feed or central media store. Expiry removes managed local copies but cannot erase screenshots, exports or copies retained by recipients. See [status updates](status-updates.md).\n\n## 6. Voice calling

Call-control messages must travel inside an already authenticated and encrypted peer channel. The Android WebRTC media foundation is configured for direct host candidates only; it must not introduce hosted signalling, STUN, TURN, relays, or cloud fallback.

The Android voice audio engine provides platform audio capture/playback foundations. Actual calling support remains subject to end-to-end signalling integration, authenticated DTLS fingerprint binding, media lifecycle handling, network-change recovery, and physical two-device testing. Direct calls may be unavailable when peers cannot establish a reachable direct path.

## 7. Local storage and recovery

Private keys and cryptographic session state remain on participating devices. Use Android Keystore or the Linux desktop Secret Service to protect local wrapping keys. Store encrypted conversation data with restrictive permissions and never log plaintext, private keys, session secrets, or message contents.

MLS state changes and outbound messages must be committed transactionally. A crash or failed write must not leave in-memory session state ahead of durable storage. Recovery must be explicit and user-controlled; a new device must not silently inherit an existing device identity. Missing, corrupted or rollback-suspected group state must fail closed.

## 8. Security and reliability gates

- Complete persistent OpenMLS storage and authenticated restoration after process restart.
- Test replay, reorder, duplicate, malformed and unauthenticated frame rejection.
- Test wrong group, stale epoch, unauthorised membership change and removed-member forward secrecy.
- Test identity changes, contact revocation, and recovery.
- Exercise Wi-Fi and Bluetooth paths on multiple physical Android devices.
- Test Android permissions, lifecycle transitions, sleep/wake, and network changes.
- Complete shared Android/Linux protocol interoperability.
- Validate direct voice signalling and media identity binding end to end.
- Clearly report unreachable peers instead of introducing hidden infrastructure fallbacks.

## 9. Current status

- Android direct TCP peer transport and Noise XX foundations: implemented.\n- Internet-wide direct peer connectivity, peer-assisted rendezvous and NAT traversal: planned; not implemented or physically validated.\n- Status updates: product/protocol design documented; not implemented.
- Android encrypted one-to-one conversation session: implemented foundation; physical Android/Linux interoperability remains unverified.
- Local network and Bluetooth discovery foundations: implemented; physical-device coverage remains required.
- Automatic peer route selection: implemented as an initial foundation; live route measurement and robust failover remain future work.
- Shared Android/Linux application framing and group envelope: implemented with matching wire-format tests; this is transport framing only.
- Android group-control frame and roster projection: present; not a cryptographic group implementation.
- OpenMLS integration and durable group-state storage: not implemented in the shared Rust core.
- Linux direct peer foundations exist, but its legacy REST/WebSocket conversation browser must be replaced for decentralised messaging parity.
- Android direct WebRTC media foundation: present; complete calling and physical-device validation remain outstanding.
