# Linux peer identity and desktop migration

## Existing identity contract

Android already defines the contact identity format that Linux must interoperate with:

- V2 contact cards are self-signed with ECDSA P-256 (`SHA256withECDSA`).
- The canonical signed fields include the display name, encoded P-256 public key, its SHA-256 fingerprint, and the 32-byte Noise X25519 static public key.
- The encoded V2 card is prefixed `TOMCHAT-CONTACT-V2`.
- A V1 card can still be imported for migration, but it has no Noise key binding and must never be accepted as an encrypted peer identity.
- Android's trusted-contact store persists the Noise key binding in its encrypted version-2 format.

Linux must use the same canonicalisation, fingerprint formatting, signature algorithm, and V1/V2 rules. It must not invent a second contact-card format.

## Linux identity storage requirements

The Linux client must hold two distinct keys:

1. P-256 signing identity, used to sign and verify contact cards.
2. Noise X25519 static identity, used by the Noise XX transport.

The Noise public key must be included in the signed V2 contact card. A contact's Noise public key is trusted only after the contact-card signature and fingerprint have been verified and the user has explicitly trusted that fingerprint.

Private key persistence must use the desktop Secret Service API (for example GNOME Keyring or another compatible Secret Service provider). The application must fail closed when no secure key store is available. It must not silently write private keys to plaintext files, environment variables, command-line arguments, or logs. Key rotation must create a new signed V2 card and require contacts to re-verify the changed fingerprint/key binding.

## Direct peer connection contract

- A peer address is a transport hint, never an identity.
- The Noise XX handshake must complete before application payloads are accepted.
- The remote Noise static key must match the key in the verified, trusted V2 contact card.
- Frames are length-bounded and encrypted/authenticated by the Noise transport state.
- Discovery may be manual QR/contact-card exchange or local-network discovery; discovery results are untrusted until identity verification.
- No central account, login endpoint, hosted directory, relay dependency, or global user database is part of this client contract.

## Desktop migration sequence

1. Shared Rust Noise identity primitive and encrypted TCP channel (in progress in PR #19).
2. Linux Secret Service-backed identity and trusted-contact store.
3. Rust V2 contact-card codec with cross-language test vectors shared with Android.
4. Linux onboarding: create/load identity, display own contact QR, import a contact card, verify fingerprint, and explicitly trust.
5. Replace the existing GTK sign-in screen with a peer/contact home screen.
6. Connect GTK conversations to authenticated direct peer sessions; do not display a successful connection until the pinned-key Noise handshake succeeds.
7. Add Android/Linux interoperability tests and only then remove the legacy REST/WebSocket UI modules.

## Security gates

- Never downgrade a V2 trusted contact to an unbound V1 card.
- Never accept a caller-provided Noise key without verifying the signed card and checking the trusted fingerprint.
- Never claim end-to-end encryption for a UI path that still displays or sends opaque legacy envelopes.
- Do not enable message composition until the selected peer has an authenticated Noise transport and message payload encryption is wired end-to-end.
- Do not merge the desktop migration until key storage, contact verification, handshake mismatch, malformed frame, reconnect, and interoperability tests pass.
