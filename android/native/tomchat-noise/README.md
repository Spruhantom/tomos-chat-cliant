# TomChat shared Noise and application protocol core

This crate is the shared Rust protocol core used by the Linux GTK client and
through JNI by the Android client. It uses the established
[snow](https://github.com/mcginty/snow) implementation of the Noise Protocol
Framework revision 34.

## Fixed peer protocol suite

- Pattern: Noise XX
- DH: X25519
- Cipher: ChaCha20-Poly1305
- Hash: SHA-256
- Protocol name: `Noise_XX_25519_ChaChaPoly_SHA256`

XX provides a three-message interactive handshake and forward-secret session
keys. The wrapper requires the caller to pin the remote static X25519 public key.
The pin is checked before the session enters transport mode. Transport records
are encrypted and authenticated by Noise.

## Shared application framing

- `app_protocol` implements the version-1, 22-byte-header application frame
  used by Android `AppFrameCodec` and the Linux direct peer chat.
- Frame types are text, group control, voice signal, group voice control and
  delivery acknowledgement.
- `group_protocol` implements a bounded version-1 envelope for MLS proposals,
  commits, welcomes and application messages. Android Kotlin and Rust share a
  golden wire-format test vector.
- Both codecs are framing only. The caller must send their bytes inside an
  authenticated Noise channel. They do not encrypt, authenticate membership,
  decrypt MLS messages or establish delivery guarantees.
- Linux direct text messages now use the same outer frame as Android. Linux can
  carry an opaque group protocol frame inside the encrypted peer channel.

## Current scope and limitations

- The crate accepts caller-supplied 32-byte local static private keys and pinned
  remote public keys.
- Android and Linux use the same Noise XX suite and application frame layout;
  physical Android/Linux interoperability still needs to be demonstrated.
- Android has a Kotlin direct conversation session and Linux has a direct peer
  chat worker. Local history, reconnect, multi-peer fan-out and background
  delivery are not yet a unified cross-platform implementation.
- OpenMLS is not integrated in this crate. Group frames are only a transport
  envelope; no group can yet be created, joined, advanced or used to protect
  application messages through this API.
- Durable MLS storage, atomic epoch transitions and restart recovery are not
  implemented.
- Secure Android key generation, encryption-at-rest, persistence, rotation and
  recovery for Noise static keys still require release validation.
- Contact-card signing and the signed binding to the Noise static key must be
  independently reviewed before production use.

## Security status

This is experimental code, not production E2EE. Snow itself has not received a
formal security audit, and this wrapper and its identity binding have not been
independently reviewed. The group envelope must never be mistaken for MLS
security. Do not send real user group content through it until OpenMLS is
integrated and the storage, membership, interoperability and negative security
tests pass.

Do not add a custom handshake or custom cryptographic primitive. Use OpenMLS
for group cryptography and retain the platform key stores for local wrapping
keys.
