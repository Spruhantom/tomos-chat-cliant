# TomOS Social — future zero-server platform direction

- Status: Proposed future product direction
- Date: 2026-11-09
- Relationship: Built into the TomChat Android app after the core P2P messaging foundation is stable

## Non-negotiable zero-server policy

TomSocial must work without any application server: no TomOS-operated server, third-party relay, community server, user-hosted server, hosted rendezvous, DHT bootstrap, central directory, push gateway, cloud database, object store, or central feed index. Do not add optional server integrations or silent infrastructure fallbacks.

## Product vision

TomChat grows into a multi-purpose decentralised communications app. TomSocial is the social layer: profiles, follows, public and private posts, replies, reactions, media, communities, moderation, events, and eventually live audio/video. TomChat remains the private messaging and calling experience. Users keep one cryptographic identity and can choose which parts of their identity and activity are public.

Potential in-app areas:
- TomChat — private conversations, groups, voice calls and file sharing.
- TomSocial — profiles, follows, feeds, posts, replies, reactions and media.
- TomGroups — community spaces, roles, announcements, moderation and events.
- TomCast — podcasts, audio publishing and live audio rooms (later phase).

Names are working product names, not final branding.

## Architecture principles

1. Reuse the TomChat identity and Rust networking core, but keep social data and permissions separate from private conversations.
2. Public posts are signed, versioned objects. A recipient can verify authorship and detect tampering without trusting the peer that delivered the object.
3. Users choose what is public, followers-only, group-only, or private. Public content must never be treated as end-to-end encrypted private chat.
4. No application server is allowed anywhere in the system, including optional relays, bootstrap services, user-hosted servers, or shared storage services.
5. Availability is explicit: content can only be fetched when the author or a peer holding a local replica is reachable. Users can keep their own copies and replicate selected posts directly to trusted personal devices.
6. Build abuse resistance from the beginning: block/mute, rate limits, report evidence, moderator roles, community rules, spam controls, and user-controlled feed filters.
7. Social features must not weaken the strict P2P requirements or expose private contact graphs and chat metadata.

## Proposed data model

- Identity document: public signing key, rotating device keys, profile metadata, and signed key-change history.
- Follow relationship: signed follow/unfollow events, with local privacy controls.
- Post: stable identifier, author, creation time, visibility policy, content type, body or media manifest, parent/reply references, and signature.
- Reaction: signed event referencing a post.
- Community: signed membership/role state, rules, moderation policy, and content references.
- Media: encrypted or public content-addressed chunks stored on user devices, with integrity hashes and explicit retention choices.
- Moderation event: signed block, mute, hide, report, or moderator action with scope and reason.

The protocol must define canonical serialization, signature domain separation, event versioning, expiry/deletion semantics, replay handling, and safe handling of unknown fields before implementation.

## Zero-server distribution model

- Nearby discovery through local multicast and Bluetooth/NFC/QR contact exchange.
- Remote peers exchange signed contact cards and reachable address hints directly, out of band or through existing peer connections.
- Direct peer fetch and synchronisation when peers are reachable.
- Each user's devices may replicate selected public posts and media for that user's own availability. This is device-to-device replication, not a server or always-on hosting guarantee.
- No DHT bootstrap, public index, relay, TURN/STUN service, shared community host, or central directory.
- No global search or guaranteed public feed discovery. Discovery is limited to known peers, local peers, and invitations users exchange.
- Mobile devices sleeping or going offline reduce availability. No promise of instant global feed delivery.

## Interoperability research

ActivityPub and Nostr are useful research references, not dependencies:
- ActivityPub is a W3C standard for federated social networking. Typical deployments use servers, so conventional federation is incompatible with this zero-server requirement.
- Nostr uses signed events and user-selected relays. Relays are servers, so standard relay-based operation is incompatible with this requirement.
- libp2p Gossipsub may be evaluated for announcements among currently connected peers, but it does not provide durable storage, global discovery, or offline delivery by itself.

Do not implement bridges that require a server. Any future import/export must be local and user-initiated.

## Privacy and safety

- Separate public profile keys from device authentication and private messaging session keys.
- Never publish a user's phone number, address book, IP address, or private contact graph by default.
- Visibility is enforced by cryptographic access control where possible, but screenshots, exports, and recipient devices cannot be technically prevented from copying content.
- Public content is effectively copyable; deletion means publishing a signed tombstone and requesting known replicas to remove content, not guaranteeing erasure from every offline or malicious device.
- Communities need moderator roles, abuse reporting, block lists, rate limiting, spam controls, and transparent moderation history.
- Provide chronological feeds by default; any ranking/recommendation must be user-controlled and explainable.
- Do not put plaintext private messages or private group content into public social replication.

## Delivery sequence

Do not build the social platform before the core messaging identity and direct-peer protocol are stable.

1. Stabilise device identity, peer connections, encrypted local storage, and one-to-one messaging.
2. Add signed public identity/profile documents and QR sharing.
3. Add follow relationships and a local-only chronological feed.
4. Add signed posts, replies, reactions, and direct peer fetch.
5. Add opt-in direct replication across a user's trusted devices.
6. Add communities, membership, roles, moderation, and events.
7. Add public media distribution and podcast publishing.
8. Prototype live audio rooms and video only after voice reliability and moderation controls are proven.

## Fundamental trade-offs

- There is no global feed index or searchable directory of all users.
- Posts are not guaranteed to reach people who are offline or unknown to the author's peer graph.
- Content availability depends on author devices and explicitly selected peer replicas being online.
- Deleting a post cannot guarantee removal of copies already downloaded by other users.
- Strict zero-server operation sacrifices some convenience and reachability to preserve independence.
