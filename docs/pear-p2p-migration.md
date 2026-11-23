# Pear P2P backend migration

## Decision

TomChat will adopt a shared Bare worker with Holepunch P2P building blocks while
keeping its native Android (Kotlin/Compose) and Linux (Rust/GTK4) interfaces.
This is a staged migration, not a Pear desktop-shell rewrite.

Upstream references:

- Pear platform and Bare/P2P layer separation: https://docs.pears.com/
- Hyperswarm topic discovery and encrypted transport: https://docs.pears.com/p2p/reference/building-blocks/hyperswarm/
- Shared worker pattern: https://github.com/holepunchto/hello-pear-worker

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
public key belongs to a TomChat contact. A peer offer is quarantined until the
native host verifies the existing signed contact card, independently verified
fingerprint, and identity-to-transport-key binding. Unknown, legacy, changed,
or downgraded identities must be rejected. The worker must never authorize a
peer merely because it knows a swarm topic.

The first worker foundation emits peer:offer; it requires an explicit
peer:authorize from the native host before attaching Corestore replication.
The host-to-worker identity verification adapter is not yet implemented.
Until it is, the new backend is experimental and must not be enabled for
ordinary messaging.

The existing Noise transport remains available as a compatibility path during
migration. Do not remove it until Android-to-Android and Android-to-Linux
interoperability, key-mismatch rejection, message durability, reconnect, and
network-transition tests pass.

## Worker IPC v1

Every frame is a JSON object with version: 1 and a type string. The worker
accepts:

- swarm:join — join a 32-byte shared topic (hex).
- peer:authorize / peer:reject — host decision after identity verification.
- message:append — persist an authorized conversation message.
- message:list — return the local indexed message history.
- backend:close — clean shutdown.

Worker events include backend:ready, peer:offer, peer:authorized,
peer:rejected, message:stored, message:list-result, and backend:error.

Frames are capped at 64 KiB; message bodies are capped at 16 KiB. The protocol
is intentionally versioned so Android and Linux can migrate independently.

## Implementation phases

1. Foundation (this change): add the Bare worker package, IPC framing,
   Hyperswarm peer offers, authorization gate, Corestore/Hyperbee local message
   persistence, and protocol tests.
2. Android host: bundle the worker with Bare Pack, embed/start it with BareKit /
   the supported mobile runtime, and bridge framed IPC from Kotlin.
3. Linux host: bundle/run the same worker with Bare and bridge the same IPC
   protocol from Rust.
4. Identity binding: implement a reviewed host adapter that validates
   transport keys against signed TomChat contacts before authorizing replication.
5. Replication semantics: define per-device Hypercore writer feeds or
   Autobase for multi-writer conversations, including conflict ordering,
   deletion/tombstone rules, and bounded storage.
6. Migration and retirement: dual-run in debug builds, run cross-platform
   integration tests, then remove the old direct TCP/UDP transport only after
   parity and security gates pass.

## Current limitations

- This foundation is not yet wired into either native app.
- The authorization callback is intentionally fail-closed until the host
  identity adapter exists.
- Hyperbee local persistence is in place, but conversation replication and
  multi-writer merge semantics are not complete.
- No production readiness, real-device compatibility, or security audit is
  claimed.
