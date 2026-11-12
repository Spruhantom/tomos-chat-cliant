# Decentralised status updates

Status: initial signed manifest codec is implemented in the Rust core and Android Kotlin, with validation tests. Content encryption, recipient key envelopes, direct distribution, local storage and UI are still outstanding.

## Product scope

- Publish a short text update or a photo with an optional caption.
- Default lifetime: 24 hours from creation.
- Audience choices: all trusted contacts, selected trusted contacts, or selected groups.
- Separate contact and group audiences; group status encryption depends on the OpenMLS group implementation.
- A contact status timeline shows active, unseen updates.
- Users can mute/hide a contact's updates.
- Replies open a normal encrypted direct conversation.
- Seen state is local by default; no global read receipt is sent unless a future explicit privacy setting is designed.

## Implemented protocol foundation

The shared 185-byte manifest records the status ID, author device ID, creation and expiry timestamps, audience mode and digest, content type, encrypted-payload length and digest, plus a detached P-256 ECDSA author signature. It enforces a fixed 24-hour lifetime, a bounded encrypted payload size and consistent audience digest rules. Rust verifies the signature against a trusted P-256 ECDSA signing key. The manifest contains no plaintext status text or photo. This is metadata framing only; it does not yet encrypt payloads, distribute content keys or deliver updates.

## Decentralised delivery

A status is an authored, signed object distributed directly to the selected trusted recipients. There is no global feed, central upload, public profile service or cloud media store.

- The signed manifest binds the status ID, author device identity, creation time, expiry, content type, media digest, audience reference and protocol version.
- Encrypt the content key and status payload separately for each selected trusted recipient, or use the authenticated MLS group epoch for group audiences once MLS is available.
- Media is encrypted before direct transfer and stored locally in encrypted form.
- A sender may retry delivery while a recipient is reachable, using bounded queues and deduplication.
- There is no guaranteed delivery to an offline recipient. No central store is implied.
- DHT/rendezvous records contain endpoint hints only, never status manifests, media or audience lists.

## Expiry and privacy

- Default expiry is 24 hours. The initial release should use a fixed 24-hour lifetime to keep cross-platform semantics simple.
- Expired statuses are hidden immediately and removed from local managed storage during cleanup.
- Recipients may still have screenshots, exports or copies outside managed storage. Expiry cannot remotely erase those copies; the UI must say so clearly.
- Revoking a contact prevents future distribution but cannot retract content already received.
- Do not reveal audience membership, status views or read times to non-recipients.
- Status data must not appear in logs, crash reports or public discovery records.

## Cross-platform acceptance

- Android and Linux render the same status manifest and expiry semantics.
- Only intended recipients can decrypt status content.
- Excluded contacts, revoked contacts and untrusted peers cannot decrypt newly published statuses.
- Reject tampered signatures, altered audience references, replayed IDs and expired content.
- Local media is encrypted at rest and removed from managed storage after expiry.
- Replies are ordinary authenticated direct messages.
- Test online/offline and reconnect behaviour without a central store.

Status expiry is best-effort local cleanup, not a promise that a recipient cannot preserve a copy.