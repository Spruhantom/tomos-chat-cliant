# Pear P2P backend migration

## Decision

TomChat will adopt a shared Bare worker with Holepunch P2P building blocks while
keeping its native Android (Kotlin/Compose) and Linux (Rust/GTK4) interfaces.
This is a backend replacement, not a Pear desktop-shell rewrite. Pear's current
desktop platform API is not the mobile embedding API: TomChat will embed the
Bare runtime and run the shared Pear-end/P2P worker inside each native client.
The native Android and Linux user interfaces remain.

Upstream references:

- Pear platform and Bare/P2P layer separation: https://docs.pears.com/
- Hyperswarm topic discovery and encrypted transport: https://docs.pears.com/p2p/reference/building-blocks/hyperswarm/
- Shared worker pattern: https://github.com/holepunchto/hello-pear-worker

## Platform compatibility decision

TomChat is moving to Pear/Bare as its only P2P backend. There will be no
permanent Rust Noise fallback or split backend by Android version.

The Pear-backed Android client now targets API 29 (Android 10) or newer.
The Android Gradle plugin and Kotlin toolchain have been upgraded for the
Bare Kit Kotlin binding, and the app now declares the Bare Kit runtime. Android 8/9 (API 26–28) will no longer
be supported by the Pear-only Android client.

For Linux, the existing GTK4/Rust UI remains native. Embed the Bare Kit C API
behind a small Rust FFI boundary. Native library packaging, ABI builds,
lifecycle and IPC framing must be validated as part of the host integration.

## Target architecture

- Native UI: Android Kotlin/Compose and Linux Rust/GTK4 remain in place.
- P2P worker: one JavaScript worker running on Bare.
- Discovery and encrypted transport: Hyperswarm / HyperDHT.
- Durable replicated data: Corestore and Hypercore; Hyperbee indexes message
  records. Multi-writer conversation semantics will be evaluated separately
  (Autobase or per-device writer feeds).
- Host boundary: framed IPC with a versioned JSON protocol. The host owns
  platform lifecycle, identity/key access, notifications, and user interface.
- No TomChat application server, central account service, relay, hosted
  discovery service, push gateway, or central message store is introduced.

## Security boundary — do not bypass

Hyperswarm's encrypted connection is not, by itself, proof that a transport
public key belongs to a TomChat contact. The worker only accepts a remote key that the native host has already verified
against the existing signed contact card, independently checked fingerprint,
and identity-to-transport-key binding. Unknown, legacy, changed, or downgraded
identities are rejected and banned from reconnecting until explicitly trusted.
The worker never authorizes a peer merely because it knows a swarm topic.

The worker receives a dedicated local Pear/Hyperswarm transport identity and
a per-contact conversation key from the native host. It configures Hyperswarm
with that identity and only attaches replication when the remote Hyperswarm
public key exactly matches the transport identity bound to a trusted contact
card. Existing X25519 contact identity material must be bound to the new
transport identity through a reviewed, signed migration/update to the contact
trust record; do not assume an existing Noise static key is automatically a
Hyperswarm identity. Each contact has an isolated
Corestore directory and a pair of deterministically derived device-writer
Hypercores, preventing one contact's replication stream from receiving another
contact's conversation. The conversation key must be derived by the native host
from the X25519 shared secret with domain separation; that derivation and host
provisioning adapter are not yet implemented.
Until it is, the new backend is experimental and must not be enabled for
ordinary messaging.

The old Rust Noise transport is not a fallback target. It may remain in the
repository temporarily while the Pear host bridges are built, but the final
Pear-only application must route all P2P discovery, sessions and replication
through the shared Bare worker. Remove the old transport and its JNI/API
surface as part of the cutover once the Pear path is implemented and its
acceptance tests pass; do not ship a dual-backend mode.

## Worker IPC v1

Every IPC frame is a 4-byte big-endian unsigned length followed by one UTF-8
JSON object with version: 1 and a type string. The Kotlin host and Bare worker
both enforce the 64 KiB frame limit and buffer partial reads. The worker accepts:

- swarm:join — join a 32-byte shared topic (hex).
- peer:trust / peer:untrust — add or remove a verified contact key and its
  pairwise conversation key.
- message:append — persist an already end-to-end-encrypted message for one
  trusted contact. The worker stores ciphertext only.
- message:list — return the local and remote writer feeds for one contact.
- backend:close — clean shutdown.

Worker events include backend:ready, identity:configured, peer:authorized,
peer:rejected, peer:closed, message:stored, message:list-result, and backend:error.

Frames are capped at 64 KiB; encrypted message payloads are capped at 24 KiB. The protocol
is intentionally versioned so Android and Linux can migrate independently.

## Implementation phases

1. Foundation (this change): add the shared Bare worker, IPC framing,
   Hyperswarm discovery/transport, per-contact Corestore isolation,
   device-writer Hypercore feeds, Hyperbee indexes and protocol tests.
2. Android host (in progress): upgrade the Android toolchain, set minSdk 29,
   add the Bare Kit Kotlin runtime, add a Kotlin IPC host, and package the
   worker plus linked native addons with Bare Pack/Bare Link. Complete secure
   Pear identity provisioning and route native chat actions through this host.
3. Linux host: bundle/run the same worker with Bare Kit's C API and bridge
   the same IPC protocol from Rust.
4. Identity migration: define and implement signed binding of each trusted
   TomChat contact to its new Pear/Hyperswarm transport identity. Derive
   per-contact encryption keys with domain-separated HKDF and fail closed
   on unknown, changed or downgraded identities.
5. Replication semantics: test device-writer feeds, message ordering,
   deletion/tombstone rules, duplicate handling, reconnect and bounded storage.
6. Cutover: route both native clients exclusively through Pear, remove the old
   Noise transport and JNI/API surface, and pass Android-to-Android and
   Android-to-Linux acceptance tests before release.

## Current limitations

- Android now has a Bare Kit Kotlin host bridge, a Bare Pack/Bare Link build
  script, and app-lifecycle worker startup. The IPC uses a shared 4-byte
  length-prefixed frame format. It does not yet provision a Pear identity or
  route conversations through the worker, so end-to-end messaging is not switched.
- Linux host integration is not yet implemented.
- The worker cannot start discovery until the native host supplies a valid
  local keypair and verified per-contact conversation keys.
- The worker has per-contact storage and separate device-writer feeds, but
  end-to-end replication, conflict handling, and history reconciliation have
  not yet been integration-tested.
- Android minimum SDK is now 29 (Android 10) for the current Bare Kit Kotlin
  binding.
- No production readiness, real-device compatibility, or security audit is
  claimed.
