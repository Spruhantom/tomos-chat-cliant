# TomOS Social — future platform direction

- Status: Proposed future product direction
- Date: 2026-11-09
- Relationship: Built into the TomChat Android app after the core P2P messaging foundation is stable

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
4. No single TomOS-owned server, global directory, central feed database, mandatory relay, or push gateway.
5. Availability is explicit: content can only be fetched when the author or a peer holding a replica is reachable. Users may opt to replicate public content for communities they follow.
6. Build abuse resistance from the beginning: block/mute, rate limits, report evidence, moderator roles, community rules, spam controls, and user-controlled feed filters.
7. Social features must not weaken the strict P2P requirements or expose private contact graphs and chat metadata.

## Proposed data model

- Identity document: public signing key, rotating device keys, profile metadata, and signed key-change history.
- Follow relationship: signed follow/unfollow events, with local privacy controls.
- Post: stable identifier, author, creation time, visibility policy, content type, body or media manifest, parent/reply references, and signature.
- Reaction: signed event referencing a post.
- Community: signed membership/role state, rules, moderation policy, and content references.
- Media: encrypted or public content-addressed chunks, integrity hashes, and explicit retention/replication choices.
- Moderation event: signed block, mute, hide, report, or moderator action with scope and reason.

The protocol must define canonical serialization, signature domain separation, event versioning, expiry/deletion semantics, replay handling, and safe handling of unknown fields before implementation.

## Strict P2P distribution model

- Nearby discovery through LAN mechanisms; remote contacts can exchange signed identity/contact cards out of band.
- Direct peer fetch and synchronisation when peers are reachable.
- Optional user-operated trusted peers/devices can replicate selected public posts and media for availability. This is opt-in, not a hosted TomOS service.
- Evaluate libp2p Gossipsub for bounded event announcements, not as durable storage. Event announcements need a separate retrieval/replication protocol.
- A distributed index or DHT may be explored only if it can operate without mandatory bootstrap operators or centrally maintained peer lists. Document the cold-start and discoverability limitations.
- Mobile devices sleeping or going offline reduce availability. No promise of instant global feed delivery or guaranteed reachability.

## Interoperability research

Keep the native TomSocial protocol aligned with open standards where practical, and consider optional bridges only after the native data model is stable.

- ActivityPub is a W3C standard for federated social networking. It is based on actors, inboxes/outboxes, and server-to-server delivery. It is useful for interoperability with existing federated communities, but conventional deployments rely on servers; it must not become a mandatory dependency for strict TomChat mode.
- Nostr uses signed events and user-selected relays. It offers portable key-based identity and an ecosystem for social clients, but relays are infrastructure. Consider an optional interoperability adapter, not a required transport for the strict P2P network.
- libp2p Gossipsub can disseminate announcements among connected peers, but peer discovery and durable content retrieval are separate concerns.

No compatibility claim should be made until conformance and interoperability tests pass.

## Privacy and safety

- Separate public profile keys from device authentication and private messaging session keys.
- Never publish a user's phone number, address book, IP address, or private contact graph by default.
- Visibility is enforced by cryptographic access control where possible, but screenshots, exports, and recipient devices cannot be technically prevented from copying content.
- Public content is effectively copyable; deletion means publishing a signed tombstone and requesting replicas to remove content, not guaranteeing erasure from every offline or malicious peer.
- Communities need moderator roles, abuse reporting, block lists, rate limiting, spam controls, and transparent moderation history.
- Provide chronological feeds by default; any ranking/recommendation must be user-controlled and explainable.
- Do not put plaintext private messages or private group content into public social replication.

## Delivery sequence

Do not build the social platform before the core messaging identity and direct-peer protocol are stable.

1. Stabilise device identity, peer connections, encrypted local storage, and one-to-one messaging.
2. Add signed public identity/profile documents and QR sharing.
3. Add follow relationships and a local-only chronological feed.
4. Add signed posts, replies, reactions, and direct peer fetch.
5. Add opt-in replication across trusted peers and community peers.
6. Add communities, membership, roles, moderation, and events.
7. Add public media distribution and podcast publishing.
8. Prototype live audio rooms and video only after voice reliability and moderation controls are proven.
9. Consider ActivityPub and Nostr adapters as optional interoperability projects.

## Open technical questions

- Canonical signed event format and versioning.
- Key rotation and recovery across multiple devices.
- Public versus follower-only content encryption and revocation semantics.
- Durable content retrieval and peer discovery under strict no-bootstrap constraints.
- Replication quotas, storage pressure, expiry, and user consent.
- Community moderation and abuse response without a central operator.
- Whether group voice should remain a mesh or use explicitly user-operated infrastructure.
