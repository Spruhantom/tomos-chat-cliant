# TomChat direct transport and cryptographic architecture

Status: direct Android peer transport and Noise foundations are implemented; durable production E2EE and full multi-device group messaging remain incomplete.

## 1. Decentralised network model

TomChat is a peer-to-peer application. Each participating device owns its identity and local data. Devices discover one another using local network discovery, nearby Bluetooth discovery, QR contact exchange, or a user-shared reachable address.

Discovery results are hints only. They must never be treated as proof of identity. No central directory, hosted rendezvous, relay, push gateway, or cloud message store is part of the design. When a direct path is unavailable, the app reports that the peer cannot currently be reached.

## 2. Direct transport

The Android prototype provides direct TCP transport, automatic route selection, local network discovery, and nearby Bluetooth discovery foundations. Wi-Fi and Bluetooth transports share a transport-neutral socket interface. The peer connection must complete mutual identity proof and a pinned-key Noise XX handshake before encrypted application frames are accepted.

Bluetooth discovery is for finding nearby devices and selecting a direct connection; it is not a voice-media relay. Network changes, Android permission changes, background limits, and device sleep can interrupt a direct session. Reconnection and failover must preserve peer identity and session safety.

The Linux TUI is an early foundation. It must reuse the same wire format, identity rules, and Noise protocol rather than inventing a separate protocol. A shared Android/Linux implementation and interoperability tests remain outstanding.

## 3. Encryption and identity

Each installation has its own device identity. Contact cards are exchanged out of band and the user approves the full fingerprint before trusting a peer. IP addresses, Bluetooth addresses, display names, and discovery advertisements are not identity credentials.

The Android prototype contains a native Rust Noise XX core exposed through JNI, Kotlin session integration, and encrypted application-frame foundations. The repository also pins OpenMLS 0.9.2 as a future group-messaging baseline. OpenMLS persistence, transactional group-state updates, restart restoration, device lifecycle, and multi-device recovery are not yet complete.

Do not describe TomChat as production-ready E2EE until persistent cryptographic state survives app updates and restarts, rollback/replay protections are verified, and two-device tests pass.

## 4. Voice calling

Call-control messages must travel inside an already authenticated and encrypted peer channel. The Android WebRTC media foundation is configured for direct host candidates only; it must not introduce hosted signalling, STUN, TURN, relays, or cloud fallback.

The Android voice audio engine provides platform audio capture/playback foundations. Actual calling support remains subject to end-to-end signalling integration, authenticated DTLS fingerprint binding, media lifecycle handling, network-change recovery, and physical two-device testing. Direct calls may be unavailable when peers cannot establish a reachable direct path.

## 5. Local storage and recovery

Private keys and cryptographic session state remain on participating devices. Use Android Keystore or the relevant desktop secret store to protect local wrapping keys. Store encrypted conversation data with restrictive permissions and never log plaintext, private keys, session secrets, or message contents.

Cryptographic state changes and outbound messages must be committed transactionally. A crash or failed write must not leave in-memory session state ahead of durable storage. Recovery must be explicit and user-controlled; a new device must not silently inherit an existing device identity.

## 6. Security and reliability gates

- Complete persistent OpenMLS storage and authenticated restoration after process restart.
- Test replay, reorder, duplicate, malformed and unauthenticated frame rejection.
- Test identity changes, contact revocation, and recovery.
- Exercise Wi-Fi and Bluetooth paths on multiple physical Android devices.
- Test Android permissions, lifecycle transitions, sleep/wake, and network changes.
- Complete shared Android/Linux protocol interoperability.
- Validate direct voice signalling and media identity binding end to end.
- Clearly report unreachable peers instead of introducing hidden infrastructure fallbacks.

## 7. Current status

- Direct Android TCP peer transport and Noise XX foundations: implemented.
- Local network and Bluetooth discovery foundations: implemented; physical-device coverage remains required.
- Automatic peer route selection: implemented as an initial foundation; live route measurement and robust failover remain future work.
- Linux TUI: early foundation; shared protocol integration remains outstanding.
- OpenMLS 0.9.2 baseline: pinned; durable group-state integration and restart restoration remain outstanding.
- Android direct WebRTC media foundation: present; complete calling and physical-device validation remain outstanding.
