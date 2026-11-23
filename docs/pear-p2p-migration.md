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

The worker receives the local X25519 keypair and a per-contact conversation
key from the native host. It configures Hyperswarm with the local keypair and
only attaches replication when the remote Hyperswarm Noise public key exactly
matches a trusted contact card's signed Noise key. Each contact has an isolated
Corestore directory and a pair of deterministically derived device-writer
Hypercores, preventing one contact's replication stream from receiving another
contact's conversation. The conversation key must be derived by the native host
from the X25519 shared secret with domain separation; that derivation and host
provisioning adapter are not yet implemented.
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
- peer:trust / peer:untrust — add or remove a verified contact key and its
  pairwise conversation key.
- message:append — persist an already end-to-end-encrypted message for one
  trusted contact. The worker stores ciphertext only.
- message:list — return the local and remote writer feeds for one contact.
- backend:close — clean shutdown.

Worker events include backend:ready, identity:configured, peer:authorized,
peer:rejected, peer:closed, message:stored, message:list-result, and backend:error.

Frames are capped at 64 KiB; message bodies are capped at 16 KiB. The protocol
is intentionally versioned so Android and Linux can migrate independently.

## Implementation phases

1. Foundation (this change): add the Bare worker package, IPC framing,
   Hyperswarm identity binding, per-contact Corestore isolation, device-writer
   Hypercore feeds, Hyperbee message indexes, and protocol tests.
2. Android host: bundle the worker with Bare Pack, embed/start it with BareKit /
   the supported mobile runtime, and bridge framed IPC from Kotlin.
3. Linux host: bundle/run the same worker with Bare and bridge the same IPC
   protocol from Rust.
4. Identity binding: implement a reviewed host adapter that validates
   transport keys against signed TomChat contacts before authorizing replication.
5. Replication semantics: test the two device-writer feeds, message ordering,
   deletion/tombstone rules, duplicate handling, and bounded storage.
6. Migration and retirement: dual-run in debug builds, run cross-platform
   integration tests, then remove the old direct TCP/UDP transport only after
   parity and security gates pass.

## Current limitations

- This foundation is not yet wired into either native app.
- The worker cannot start discovery until the native host supplies a valid
  local keypair and verified per-contact conversation keys.
- The worker has per-contact storage and separate device-writer feeds, but
  end-to-end replication, conflict handling, and history reconciliation have
  not yet been integration-tested.
- No production readiness, real-device compatibility, or security audit is
  claimed.
