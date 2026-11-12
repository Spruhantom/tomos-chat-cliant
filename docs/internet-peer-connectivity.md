# Internet-wide decentralised peer connectivity

Status: initial shared candidate codec and Linux direct TCP dial helper are implemented on the feature branch. Peer discovery, Android connection integration, NAT traversal and real-world internet validation remain outstanding.

## Product contract

TomChat must establish direct encrypted connections between trusted devices on different networks where possible. No central application server, required hosted rendezvous, TURN relay, cloud message store or plaintext fallback is introduced.

A reachable route is not an identity. Every connection must still validate the signed contact card, compare the pinned Noise static key and complete the Noise XX handshake before application traffic is accepted.

## Connection strategy

Try connection methods in this order, subject to platform/network capability:

1. Existing authenticated connection reuse.
2. Direct globally reachable IPv6 endpoint, exchanged as a signed short-lived candidate.
3. User-approved public IPv4 endpoint or manually shared endpoint.
4. Peer-assisted rendezvous: trusted peers exchange signed, expiring endpoint candidates through a distributed peer directory (DHT) bootstrapped from multiple independently operated peers.
5. Coordinated UDP hole punching between the two peers, followed by an authenticated Noise handshake.
6. Optional user-approved local gateway mapping using UPnP IGD, NAT-PMP or PCP, with a short lease, explicit revocation and cleanup.

There is no TURN-style relay fallback. A peer-assisted DHT carries only signed endpoint hints and short-lived rendezvous coordination; it never stores messages, status content, media or private keys. No single bootstrap peer should be required for normal operation. Users can import/share a bootstrap peer list out of band and run their own peers.

## Implemented protocol foundation

The Rust core now defines a bounded IPv4/IPv6 endpoint-candidate record with a 5-minute maximum lifetime, public-address checks, a nonce and a detached P-256 ECDSA signature in fixed r||s form. The signature covers all endpoint and timing fields and is verified against the trusted contact's P-256 identity key and expected device ID; the Noise X25519 static key is never reused as a signing key. Android has matching Kotlin encoding, decoding, signing and verification, plus a direct TCP connector that validates the candidate before invoking the existing identity/Noise authenticator. Linux Rust can dial a TCP candidate only after signature, device-ID and expiry validation, then completes the existing pinned-key Noise XX handshake. UDP candidates are currently encoded but not connected. These are protocol primitives, not a complete peer-discovery or NAT traversal system.

## Candidate and rendezvous rules

- Candidate records are signed by the device identity and include device ID, transport, address/port, expiry, nonce and protocol version.
- Records have a short TTL, are replay-protected and are discarded when expired or when the contact is revoked.
- Public IP/port observations are network metadata; they are never treated as identity credentials.
- Candidate exchange must not disclose a user's endpoint to arbitrary contacts. Only approved peers and explicitly configured bootstrap peers may receive it.
- Do not publish private LAN addresses to the public DHT.
- Use bounded rate limits and reject malformed, oversized, stale and unauthenticated records.
- NAT traversal coordination is not application signalling authority: Noise identity pinning remains mandatory.

## Network behaviour

- Detect Wi-Fi/mobile/Ethernet changes and invalidate stale candidates.
- Retry with bounded exponential backoff and jitter.
- Preserve message IDs and deduplicate reconnect retransmissions.
- Report states accurately: discovering, attempting direct connection, connected, peer offline, blocked by network, and retrying.
- If symmetric NAT, carrier-grade NAT, firewall policy or UDP blocking prevents a direct path, explain that direct connectivity is unavailable. Do not silently introduce a relay.
- No always-on peer means no guaranteed offline delivery. An optional user-owned always-on device may act as a peer mailbox only after a separate explicit design/security review; it is not part of this initial scope.

## Tests and acceptance

- Unit and property tests for signatures, expiry, replay, candidate parsing, version negotiation and rate limits.
- Shared Rust wire vectors consumed by Android and Linux.
- Integration tests for IPv6 direct, manual endpoint, peer-assisted rendezvous and UDP hole punching.
- Network matrix: same LAN, separate public IPv6, home NAT, carrier NAT, symmetric NAT, restrictive firewall, UDP blocked and network switching.
- Packet review confirms no message payload, status content, media or private key reaches rendezvous peers.
- Physical Android/Linux devices exchange bidirectional Noise-encrypted messages over different internet connections.
- Blocked networks fail closed and present an understandable diagnostic.

Do not describe internet P2P as complete until real devices pass the network matrix.