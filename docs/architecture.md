# TomChat architecture

## Product boundary

TomChat is a decentralised peer-to-peer application with two active native clients:

- Android: Kotlin, Jetpack Compose and Material 3.
- Linux desktop: Rust, GTK4 and libadwaita.

There is no TomChat application server, central account service, hosted rendezvous/discovery, relay, TURN/STUN service, push gateway, central directory or hosted message store. Peers exchange contact details out of band or through direct local mechanisms and connect directly where the network permits.

Do not add a centralised fallback when direct connectivity fails. Carrier NAT, firewalls, restrictive networks and device sleep can make some peers unreachable. This is an intentional limitation of the decentralised design and must be explained to users.

## Current implementation map

```text
Android Compose UI
  └─ Kotlin application and lifecycle
      └─ JNI boundary
          └─ Shared Rust Noise XX core

Linux GTK4/libadwaita UI
  └─ Rust application
      ├─ P-256 signed contact-card codec
      ├─ Secret Service identity/contact persistence
      └─ Direct TCP peer chat
          └─ Shared Rust Noise XX transport

Direct peer connection
  ├─ Android direct peer transport
  └─ Linux TCP connect/listen
```

The shared Rust core is in `android/native/tomchat-noise`. The Linux desktop application is in `src/`; the Android application is in `android/app/`.

## Identity and trusted contacts

Each installation has two distinct key roles:

1. P-256 ECDSA signing identity for signed contact cards.
2. X25519 static identity for the Noise XX transport.

V2 contact cards use the `TOMCHAT-CONTACT-V2` format and include the P-256 public key, its SHA-256 fingerprint and the Noise public key in the signed canonical fields. The Noise key is therefore bound to the signing identity. A V1 card has no Noise-key binding and must not be treated as a Noise-capable peer.

A valid self-signature proves that the card fields match the included public key; it does not prove a real-world person's identity. Users must independently compare the full fingerprint with the contact before explicitly trusting it.

- Android stores signing identity through Android Keystore and protects persistent Noise identity material using an Android Keystore wrapping key.
- Linux stores identity and trusted-contact data through the desktop Secret Service, using `secret-tool`.
- Both clients must fail closed if secure identity storage is unavailable. Never fall back to plaintext private-key files, environment variables, command-line arguments or logs.

A shared V2 fixture is stored at `android/app/src/test/resources/contact-card-v2.txt` and is used by the Kotlin and Rust test suites.

## Noise peer transport

The shared Rust crate uses Snow's Noise XX pattern with the suite `Noise_XX_25519_ChaChaPoly_SHA256`. It performs the three-message handshake, checks the remote static public key against the key pinned in the verified contact card, and only then enables encrypted transport records.

Transport frames are length-prefixed and bounded. Application data must not be sent until peer authentication and the pinned-key check have succeeded. Wrong-key peers must be rejected. The Rust transport has loopback tests for encrypted bidirectional traffic and pinned-key mismatch.

Android exposes the native Noise session through a narrow JNI wrapper. Linux uses the shared Rust core in its direct peer path. This common implementation is intended to reduce protocol drift; it does not by itself prove Android-to-Linux interoperability.

## Platform details

### Android

- Kotlin, Jetpack Compose and Material 3.
- Android Keystore-backed signing identity and wrapped persistent Noise identity.
- Signed V2 contact-card encode/decode, QR scanning and trusted-contact handling.
- Direct peer transport and a JNI boundary to the shared Rust Noise session.
- Android lifecycle, runtime permissions and audio routing remain platform responsibilities.

### Linux desktop

- Native Rust GTK4/libadwaita application.
- Local identity creation/loading and signed V2 contact-card generation.
- Contact import verifies the signature and requires explicit fingerprint confirmation.
- Secret Service persistence for identity and trusted contacts.
- Direct TCP connect/listen to a selected trusted contact, with Noise identity pinning and encrypted peer chat.
- Runtime secure storage requires an unlocked Secret Service provider; the installer provides the `secret-tool` dependency but cannot guarantee a desktop keyring is configured correctly.

## Connectivity constraints

A peer address is a transport hint, never an identity. The identity and Noise static key must be verified independently.

Direct TCP connections may fail because of:
- carrier-grade NAT or lack of a reachable inbound address;
- router or host firewall rules;
- guest Wi-Fi/client isolation;
- mobile data restrictions;
- device sleep, background restrictions or application shutdown;
- changed local or public addresses.

The current Linux client listens on TCP port 46321 when the user explicitly starts its listener. Users may need to allow this port in their local firewall. Do not imply that this makes a peer reachable across arbitrary networks.

No global peer discovery or guaranteed offline delivery is provided. The sender cannot rely on a hosted mailbox or relay.

## Voice and deferred capabilities

Voice, groups, file sharing, advanced discovery, transport migration, backup replication and social features must not be advertised as complete unless their end-to-end paths are implemented and validated. Physical Android audio testing and cross-platform voice interoperability remain separate release gates.

## Security requirements

- Do not invent cryptographic primitives or custom handshakes.
- Preserve signed contact-card validation and pinned Noise static-key verification.
- Bound every network frame and reject malformed, oversized or unauthenticated input.
- Keep private keys out of logs, crash reports, test fixtures and command-line arguments.
- Fail closed on key-store errors; never downgrade a Noise-capable trusted contact to an unbound V1 card.
- Do not call the implementation production-ready, independently audited or fully secure without an independent review and supporting evidence.

## Validation status

Automated Android and Linux CI passing proves only the checks performed by those workflows. It does not prove:
- installation on every listed Linux distribution;
- physical Android device behaviour;
- Android-to-Linux or Linux-to-Android interoperability;
- internet-wide reachability;
- resistance to all protocol, implementation or endpoint attacks.

See [the real-world testing plan](real-world-testing.md), [the E2EE threat model](e2ee-threat-model.md), [the Linux identity and desktop guide](linux-peer-identity-and-desktop-migration.md), and [CONTRIBUTING.md](../CONTRIBUTING.md).
