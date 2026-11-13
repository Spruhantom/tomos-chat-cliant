# Internet-wide decentralised peer connectivity

Status: this branch adds initial Rust/Kotlin rendezvous and UDP traversal primitives, an Android signed UDP punch attempt, IPv6-first route policy, bounded reconnect backoff and an explicit opt-in NAT-PMP client. A complete multi-hop DHT request/response engine, UPnP IGD/PCP, background reconnection orchestration, desktop conversation/UI integration and real-world internet validation remain outstanding.

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

The Rust core now defines a bounded IPv4/IPv6 endpoint-candidate record with a 5-minute maximum lifetime, public-address checks, a nonce and a detached P-256 ECDSA signature in fixed r||s form. The signature covers all endpoint and timing fields and is verified against the trusted contact's P-256 identity key and expected device ID; the Noise X25519 static key is never reused as a signing key. Android has matching Kotlin encoding, decoding, signing and verification, plus a direct TCP connector that validates the candidate before invoking the existing identity/Noise authenticator. Linux Rust can dial a TCP candidate only after signature, device-ID and expiry validation, then completes the existing pinned-key Noise XX handshake. UDP candidates can now be signature-checked, punched and connected using the new UDP Noise channel. The transport is a direct peer-to-peer path with no relay fallback. Android now adapts the UDP Noise channel to the shared encrypted conversation interface and exposes coordinator connect/accept entry points, so text, delivery acknowledgements, voice control and group-control frames use the same encrypted app framing over UDP. Linux has the shared Rust UDP channel API; desktop conversation/UI integration and physical-device validation across separate networks remain outstanding.

## New implementation on the NAT-traversal branch\n\n- Rust defines bounded signed rendezvous records (up to eight signed endpoint candidates), authenticated-channel lookup envelopes and a DHT-value format. These records carry endpoint hints only and have a maximum five-minute lifetime.\n- Android has matching lookup and signed UDP probe codecs. The UDP puncher sends bounded simultaneous-open probes and accepts only a peer probe with the expected session ID and trusted device signature. It then hands the same socket to the authenticated Noise XX UDP transport. After a verified probe, Android and Linux now have a Noise XX handshake over the same UDP socket, with the remote static key pinned to the trusted contact. Application datagrams are Noise-encrypted; stop-and-wait sequencing, retransmission and encrypted acknowledgements prevent loss/reordering from desynchronising Noise transport counters.\n- Rust and Android route policy prefer globally routable IPv6 candidates before IPv4 and expose bounded exponential reconnect delays with jitter.\n- Android and Rust include explicit NAT-PMP UDP mapping support with a maximum ten-minute lease and a deletion operation. It is not enabled automatically; the user must explicitly opt in and choose the gateway.\n- UPnP IGD and PCP are not yet implemented. Rust now includes a bounded 256-bucket Kademlia XOR routing-table core (20 peers per bucket), closest-peer ordering and stable device rendezvous keys. It still needs an actual multi-hop Kademlia request/response engine, liveness probes, persistence and independent bootstrap-peer management.\n\n## Candidate and rendezvous rules

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
- Integration tests for IPv6 direct, manual endpoint, peer-assisted rendezvous and UDP hole punching. Unit tests currently cover wire bounds, signatures, route preference, retry bounds and NAT-PMP lease limits.
- Network matrix: same LAN, separate public IPv6, home NAT, carrier NAT, symmetric NAT, restrictive firewall, UDP blocked and network switching.
- Packet review confirms no message payload, status content, media or private key reaches rendezvous peers.
- Physical Android/Linux devices exchange bidirectional Noise-encrypted messages over different internet connections.
- Blocked networks fail closed and present an understandable diagnostic.

Do not describe internet P2P as complete until real devices pass the network matrix.