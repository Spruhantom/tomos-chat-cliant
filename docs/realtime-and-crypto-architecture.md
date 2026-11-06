# Real-time transport and cryptographic architecture

Status: design record only. This document does not claim that WebSocket transport,
end-to-end encryption, device keys, or manual GTK testing are implemented.

## 1. WebSocket transport

The client will use a maintained WebSocket implementation with TLS support rather
than hand-writing WebSocket framing or cryptographic handshakes. The current
protocol contract is `/api/v1/ws` with the session bearer token in the HTTP
upgrade Authorization header. Tokens must never appear in URLs, query strings,
logs, or crash reports.

The connection worker must run outside the GTK main thread and publish typed
events to the UI through a bounded channel. It must:

- validate the server URL using the same HTTPS/loopback-only HTTP policy as login;
- cap inbound frame size at 64 KiB and reject malformed JSON, unsupported versions,
  and empty request IDs;
- process `message.deliver`, `message.ack`, and `error` envelopes;
- answer ping frames with pong frames and detect a closed or stale socket;
- reconnect with exponential backoff and jitter, bounded at 30 seconds, and stop
  retrying after logout or explicit disconnect;
- never block GTK callbacks on network I/O and never log bearer tokens or ciphertext;
- de-duplicate displayed messages by server message ID and sequence.

On reconnect, REST history remains the source of truth. The current API has a
`before` cursor for older-history pagination, but no `after` cursor. Recovery
must therefore fetch the newest history page, merge it with locally displayed
messages, de-duplicate, and resume live delivery. A future API revision should
add an authenticated `after` sequence cursor for efficient gap recovery.

The sender must use one stable `client_message_id` per logical message and reuse
it on retries. A WebSocket acknowledgement is not a substitute for durable
history; persistence is confirmed by the server acknowledgement or a matching
REST history record.

## 2. Encryption and device identity

The existing API transports opaque ciphertext but does not provide E2EE. The
client must keep plaintext composition disabled until a reviewed protocol and
key lifecycle are implemented. Do not implement custom primitives, custom key
exchange, or ad-hoc group ratchets.

Before selecting a library, review a threat model covering server compromise,
malicious group members, device loss, account recovery, metadata leakage,
downgrade attempts, and compromised endpoints. Evaluate a maintained protocol
library for both direct and group conversations (including a standards-based
MLS implementation), its independent security review, Rust support, persistence
requirements, and migration strategy. The protocol choice is not final here.

Required properties before enabling the composer:

- per-device cryptographic identity and signed device credentials;
- authenticated device addition/removal and a visible device list;
- authenticated key distribution and group membership changes;
- forward secrecy and post-compromise recovery appropriate to the selected protocol;
- crash-safe persistence of cryptographic transcript / group state;
- private-key storage through the desktop secret service (for example,
  Secret Service / libsecret), never plaintext config files or source control;
- user-verifiable safety numbers or QR-based device/contact verification;
- explicit lost-device, revocation, account-recovery, and multi-device history flows;
- strict protocol versioning and downgrade rejection;
- test vectors, interoperability tests, fuzzing, dependency review, and independent
  security review before production claims.

Private keys must never be sent to the server. The server may store public device
credentials and opaque protocol messages, but server-side storage alone does not
establish end-to-end encryption. Backups must be encrypted with a user-controlled
recovery secret and must not silently weaken forward secrecy.

## 3. Delivery plan

1. Add transport tests and a maintained WebSocket dependency with locked,
   reproducible dependency resolution.
2. Implement a reconnecting worker and typed event parsing; test malformed frames,
   oversized frames, ping/pong, authentication failure, logout, and retry shutdown.
3. Integrate live delivery into the conversation view and perform REST catch-up
   after reconnect; test duplicates and out-of-order delivery.
4. Complete the threat model and select a reviewed E2EE protocol/library.
5. Implement device enrollment, verification, key storage, group state, recovery,
   and protocol migration behind disabled-by-default feature gates.
6. Add end-to-end integration tests and obtain independent cryptographic review.
7. Only then enable message composition and describe the product as end-to-end
   encrypted.

## 4. Current limitations

- No WebSocket connection or reconnect loop is implemented yet.
- The message composer remains disabled.
- No encryption, device identity, key storage, or key recovery is implemented.
- The GTK interface has not been manually tested on a desktop session.
- The server's process-local WebSocket fan-out is suitable only for a single
  server instance until shared pub/sub is added.
