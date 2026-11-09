# ADR 0001: TomChat Android product baseline

- Status: Accepted
- Date: 2026-11-09

## Decisions

1. **Network: strict.** No mandatory central service, hosted rendezvous, relay/TURN, bootstrap node, push gateway, or central message store. Direct peer-to-peer only. Optional user-operated trusted devices may assist backup, but cannot be required for core use.
2. **Feature scope: full.** Plan one-to-one and group messaging, one-to-one and group voice, file sharing, voice notes, contact exchange, and delivery/synchronisation.
3. **Identity backup: both.** Provide local encrypted export/import and encrypted peer-to-peer replication to explicitly trusted devices.

## Consequences

- Connectivity is best-effort. Some remote device pairs will not connect through carrier-grade NAT or restrictive firewalls without relays.
- No guaranteed instant delivery while recipient devices are offline.
- Incoming calls cannot be guaranteed when Android suspends or force-stops the app.
- Group voice mesh has scaling and battery limits; evaluate participant limits before implementation.
- Identity recovery depends on the user's backup secret or trusted devices. Loss of all recovery material can be permanent.

## Revisit criteria

Revisit only with an explicit product decision. Do not add hosted infrastructure as an invisible fallback.
