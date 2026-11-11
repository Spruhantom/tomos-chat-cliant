# Experimental TomChat Noise core

This crate is an isolated Rust protocol prototype using the established
[snow](https://github.com/mcginty/snow) implementation of the Noise Protocol
Framework revision 34.

## Fixed protocol suite

- Pattern: Noise XX
- DH: X25519
- Cipher: ChaCha20-Poly1305
- Hash: SHA-256
- Protocol name: `Noise_XX_25519_ChaChaPoly_SHA256`

XX provides a three-message interactive handshake and forward-secret session
keys. The wrapper requires the caller to pin the remote static X25519 public key.
The pin is checked before the session enters transport mode. Transport records
are encrypted and authenticated by Noise; its ordered transport state rejects
out-of-order/replayed records through the protocol nonce sequence.

## Current scope and limitations

- The crate accepts caller-supplied 32-byte local static private keys and pinned
  remote public keys.
- Secure Android key generation, encryption-at-rest, persistence, rotation and
  recovery for Noise static keys are not implemented.
- The existing QR contact card currently signs an ECDSA identity key, not a
  Noise X25519 static key. A signed binding between those keys must be designed
  and added before this crate can authenticate existing trusted contacts.
- A reusable Rust direct TCP adapter now performs the three-message Noise XX handshake, pins the remote static key, and carries length-bounded encrypted frames. It is covered by loopback integration tests.
- The Android JNI wrapper does not yet route Kotlin sockets through this adapter; that cross-platform integration remains future work.
- The existing Kotlin signed-nonce handshake is not replaced yet.
- The Linux GTK UI and Android application message paths do not yet use this shared TCP adapter.

## Security status

This is experimental code, not production E2EE. Snow itself explicitly states
that it has not received a formal security audit. The wrapper and the identity
binding have not been independently reviewed. Do not expose encrypted messaging
as production-ready or send user messages over this prototype.

Before integration, complete a threat model and independent review, validate
Noise test vectors and malformed-message handling, bind the Noise static key to
the user's fingerprint-verified ECDSA contact identity, store private key
material using an Android Keystore-protected design, and test on physical
Android devices. Do not add a custom handshake or custom cryptographic primitive.
