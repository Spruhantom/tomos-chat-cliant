# Decentralised conversations and group messaging

Status: design and implementation plan. This document does not claim that group messaging is implemented.

## Product contract

TomChat conversations are direct peer-to-peer. There is no application server, central account service, hosted directory, relay, push gateway or cloud message store. A peer that cannot be reached is shown as unavailable; the client must not silently fall back to central infrastructure or plaintext.

Android and Linux must implement the same versioned application protocol and cryptographic state machine. Platform UI and secure-key wrapping differ, but group semantics, MLS state transitions and wire test vectors are shared.

## Conversation types

- **Direct conversation:** one trusted peer pair, authenticated using the contact-card fingerprint and pinned Noise XX static key. Text frames travel inside the established Noise channel.
- **Group conversation:** three or more device identities in one OpenMLS group. Group application messages are encrypted and authenticated by MLS, not by encrypting one copy with each pairwise Noise session and not by trusting the UI roster.

The first interoperable target is Android ↔ Linux. A device identity is a member; multi-device accounts and seamless identity migration are separate future work.

## Group lifecycle

1. A verified contact creates a group and becomes its initial owner/administrator.
2. The creator adds trusted contacts through authenticated direct peer sessions. MLS Add proposals and Commits define the authoritative membership change.
3. The new member receives an MLS Welcome package over the authenticated direct channel and joins the exact group/epoch.
4. Members send MLS application messages. The transport carries bounded opaque MLS protocol messages; transport peers do not inspect group plaintext.
5. Adding or removing members advances the MLS epoch. A removed member must not decrypt messages from later epochs. Existing members process the same Commit and persist the new state before acknowledging it.
6. Rejoining is an explicit new MLS add/join operation. A stale or removed member must not be allowed to resume from an old snapshot.

## Wire protocol

Use a versioned, length-bounded application envelope with a conversation identifier, message identifier, message kind and opaque payload. Group protocol payloads distinguish MLS proposals, commits, welcomes and application messages. The payload is passed only through an authenticated direct Noise channel. Unknown versions/types, oversized payloads, malformed encodings, duplicate message IDs, stale epochs and unauthenticated control events are rejected.

Do not invent a second group cryptography protocol. Use OpenMLS 0.9.x APIs and MLS protocol semantics, with exact dependency versions and cross-platform test vectors. The Kotlin roster reducer remains a UI projection only; it must consume already authenticated MLS state and never authorise changes itself.

## Direct peer distribution

There is no always-on server to fan out group messages. A sender transmits the MLS ciphertext to currently reachable group members over authenticated direct sessions. The UI reports per-member delivery/availability and retries only according to a bounded, deduplicated queue. Offline delivery is not guaranteed in the decentralised model. Local encrypted history is retained on each participating device; no central archive is introduced.

Wi-Fi and Bluetooth are transport choices only. Discovery advertisements and Bluetooth addresses are hints, never identity proof. Every direct session verifies the signed contact identity and pinned Noise key before carrying MLS messages.

## Persistence and crash safety

- Implement a shared Rust MLS core and a storage adapter boundary.
- Persist the complete OpenMLS group state, provider/key-package state and required message replay/deduplication metadata.
- Protect local storage with platform-held wrapping keys (Android Keystore; Linux Secret Service) and authenticated encryption.
- Commit MLS state changes and related application records atomically. Never acknowledge a Commit or send from an advanced in-memory epoch before durable storage succeeds.
- On restart, restore the exact group ID, epoch, membership and provider state. Fail closed on missing, corrupt, stale or rollback-suspected state; require explicit recovery rather than silently creating a new group.
- Do not export private keys or raw MLS secrets in logs, backups or diagnostics.

## UI requirements

Both clients should provide:
- A unified conversation list containing direct and group chats.
- New direct chat and Create group actions.
- Group title, member list and owner/admin indicators.
- Invite verified contacts, accept/decline a Welcome, remove members and leave group.
- Message history, composer, delivery state and peer availability.
- Clear warnings when a peer is unreachable; never imply offline delivery or successful delivery without acknowledgement.

## Required tests and release gates

- Shared Rust unit tests and golden wire-format vectors consumed by Android and Linux.
- MLS group create, add, Welcome/join, send/receive, remove, epoch transition and rejoin tests.
- Removed-member forward secrecy: an evicted member cannot decrypt later-epoch application messages.
- Wrong group, wrong epoch, unauthorised actor, malformed/oversized payload, duplicate/replay, reordered Commit, tampering and corrupted-storage rejection.
- Crash/restart tests before and after every durable MLS state transition.
- Android ↔ Linux and Linux ↔ Linux interoperability over Wi-Fi; Android Bluetooth transport coverage.
- Physical-device evidence before declaring the feature usable.

Until these gates pass, describe the work as experimental and do not claim production-ready group E2EE or guaranteed offline delivery.
