# Linux peer identity and desktop client

## Current implementation

The Linux desktop client is now part of `main` in the same repository as the Android client. It is a native Rust GTK4/libadwaita application using the shared Rust Noise transport core.

The active Linux UI currently supports:
- loading or creating a local signing and Noise identity;
- storing identity material and trusted contacts through the desktop Secret Service (`secret-tool`);
- generating a signed V2 contact card;
- importing and verifying a signed V2 contact card;
- requiring explicit user confirmation after out-of-band fingerprint comparison;
- listing/removing trusted contacts;
- direct TCP connect/listen to a selected trusted contact;
- pinned Noise XX handshake before encrypted peer chat.

The Linux client is a prototype. It has not yet passed real-world installation and Android/Linux interoperability testing across supported distributions and physical devices.

## Shared identity contract

- V2 contact cards use ECDSA P-256 with SHA-256.
- Canonical signed fields include the display name, encoded P-256 public key, SHA-256 fingerprint and 32-byte Noise X25519 static public key.
- The encoded V2 card is prefixed `TOMCHAT-CONTACT-V2`.
- V1 cards may be read for migration, but have no Noise-key binding and must not be accepted as encrypted peer identities.
- The Noise public key is trusted only after signature/fingerprint verification and explicit user trust.

Android and Linux must preserve identical canonicalisation, fingerprint formatting, signature algorithm and V1/V2 rules. Use the shared test vector at `android/app/src/test/resources/contact-card-v2.txt` for compatibility checks.

## Linux identity storage

The client stores two distinct identities:
1. P-256 signing identity for contact-card signatures.
2. Noise X25519 static identity for Noise XX.

Private identity material must remain in the desktop Secret Service/keyring. The application must fail closed when secure storage is unavailable; it must not write private keys to plaintext files, environment variables, command-line arguments or logs. Key rotation requires a new signed V2 card and renewed contact verification.

Runtime dependency: `secret-tool` (Debian/Ubuntu package `libsecret-tools`; Arch package `libsecret`) and an unlocked Secret Service provider in the desktop session.

## Direct peer connection contract

- A peer address is a transport hint, never an identity.
- The Noise XX handshake and pinned remote static-key check must succeed before application payloads are accepted.
- Frames are length-bounded and encrypted/authenticated by Noise.
- Manual contact-card exchange and local discovery results remain untrusted until identity verification.
- No central account, login endpoint, hosted directory, relay or global user database is part of the client contract.
- Direct reachability is best-effort and depends on firewall, NAT, carrier and local network conditions.

## Build and install

From the repository root:

```bash
cargo build --release --locked
cargo test --locked
```

The executable is `target/release/tomchat-client`. For Arch/Omarchy, Debian and Ubuntu, use the root installer:

```bash
curl -fsSL https://raw.githubusercontent.com/Spruhantom/tomos-chat-cliant/main/install.sh | bash
```

See [README](../README.md) and [CONTRIBUTING](../CONTRIBUTING.md) for prerequisites and contributor checks.

## Remaining validation

- [ ] Test identity creation/load and contact import/removal on Arch/Omarchy.
- [ ] Test identity creation/load and contact import/removal on Debian/Ubuntu.
- [ ] Verify locked/missing Secret Service behaviour fails safely.
- [ ] Verify Android and Linux accept the same V2 test vector.
- [ ] Demonstrate Android/Linux Noise handshake and encrypted bidirectional messages.
- [ ] Exercise wrong-key, malformed-frame, tampered-frame and reconnect cases.
- [ ] Review the implementation independently before production claims.
