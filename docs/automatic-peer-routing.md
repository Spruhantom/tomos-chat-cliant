# Automatic peer routing

TomChat uses one automatic route policy for chat, voice and file transfer. Users must not need to enter peer IP addresses, ports, or choose a "direct connection" mode.

## Implementation status

- **Implemented in this branch:** automatic Wi-Fi LAN discovery with Android NSD/mDNS, a bounded multicast-lock window for older Android releases, ephemeral listener ports, deterministic initiator selection, multiple resolved LAN address attempts (including IPv6 where exposed by Android), and the existing authenticated Noise XX session establishment. The normal chat UI no longer exposes manual IP, port, or Listen controls.
- **Still planned:** Wi-Fi Direct group negotiation and reachable global IPv6 endpoint exchange/probing. The automatic connection path currently tries same-LAN Wi-Fi first, then nearby Bluetooth.
- **Not yet verified:** two-physical-device Wi-Fi and Bluetooth discovery, RFCOMM handshake, reconnect and audio tests. CI and device testing are required before this can be considered ready.
- **Android platform follow-up:** the app currently targets SDK 35. Android 17 (API 37) introduces enforced local-network permission controls for raw LAN sockets; before raising target SDK to 37, implement the `ACCESS_LOCAL_NETWORK` runtime permission flow or migrate to the system NSD service picker where its user-selected-device interaction is acceptable.

## Planned direct transports

1. Wi-Fi LAN: advertise and discover the TomChat service with Android NSD/mDNS.
2. Wi-Fi Direct: discover and establish a peer group when the devices are not attached to the same access point.
3. Bluetooth Classic: use RFCOMM as a low-throughput fallback for nearby peers.
4. Bluetooth LE: use BLE for nearby discovery and control; negotiate a suitable data path rather than sending sustained voice media over BLE.
5. Global IPv6: connect directly when both peers have reachable IPv6 addresses and have exchanged a signed endpoint. Never assume a private IPv4 address is reachable from the public internet.

Every candidate must pass a bounded reachability probe. The selected socket is then subjected to the existing signed peer-identity proof and pinned-key Noise XX handshake. Discovery metadata is never an identity credential.

## Selection policy

- Voice: prioritise measured latency and reliability.
- Chat: prioritise reliability, then latency and battery.
- File transfer: prioritise throughput and reliability.
- Background sync: prioritise battery efficiency and reliability.
- Re-score when a route degrades; exclude failed routes for the current attempt and try the next candidate without changing the conversation identity.
- Preserve message IDs and delivery state across a route switch. Do not replay a message as a new message.
- Never downgrade encryption when switching transports.

PeerRouteSelector is the deterministic ranking policy. Platform adapters are responsible for discovery, reachability measurement, permissions, connection lifecycle, and supplying real measurements.

## No-relay constraint and internet limitation

This design does not use a relay. Same-LAN discovery, Wi-Fi Direct, Bluetooth, and directly reachable global IPv6 can work without an application relay. However, two devices behind unrelated NATs or carrier-grade NAT cannot reliably discover or open a path to each other with zero rendezvous/STUN infrastructure and no exchanged reachable endpoint. That is a network constraint, not an Android implementation gap. TomChat must report that no direct path is available rather than silently sending traffic through a relay.

## Acceptance checks

- No peer IP, port, Listen, or Direct Connection controls in the normal UI.
- Automatic discovery and route selection work on two physical Android devices.
- Route failover retains the same authenticated peer and conversation history.
- Bluetooth and Wi-Fi transitions do not expose plaintext or reset message IDs.
- Voice route selection uses low-latency paths; file transfers use throughput.
- Permission denial, adapter-off, network loss, duplicate discovery, and timeout cases are covered by tests.
- No-relay behaviour is explicit when the network topology offers no direct path.
