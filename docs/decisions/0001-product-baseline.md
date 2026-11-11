# ADR 0001: TomChat decentralised peer-to-peer product baseline

- Status: Accepted
- Date: 2026-11-09

## Decisions

1. **Network: strict decentralised peer-to-peer.** No application centralised service of any kind: no central service, hosted rendezvous, relay/TURN/STUN, bootstrap node, DHT bootstrap, push gateway, central directory, cloud storage, or central message store. This applies to TomChat and future TomSocial.
2. **Feature scope: full.** Plan one-to-one and group messaging, one-to-one and group voice, file sharing, voice notes, contact exchange, social profiles, posts, communities, and direct peer synchronisation.
3. **Identity backup: both.** Provide local encrypted export/import and encrypted peer-to-peer replication to explicitly trusted personal devices.

## Consequences

- Connectivity is best-effort. Some remote device pairs will not connect through carrier-grade NAT or restrictive firewalls without centralised services or relays.
- No guaranteed instant delivery while recipient devices are offline.
- Incoming calls cannot be guaranteed when Android suspends or force-stops the app.
- Social discovery is limited to known peers, local discovery, and invitations; no global searchable user directory or guaranteed global feed.
- Public content availability depends on the author or a peer holding a local copy being reachable.
- Group voice mesh has scaling and battery limits; evaluate participant limits before implementation.
- Identity recovery depends on the user's backup secret or trusted devices. Loss of all recovery material can be permanent.

## Revisit criteria

The decentralised peer-to-peer rule must not be weakened by an implementation convenience or silent fallback. Any change requires an explicit new product decision.
