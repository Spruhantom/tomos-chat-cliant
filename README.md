# TomChat

**TomChat** is an Android-first project to build a decentralised, peer-to-peer messaging and voice app that keeps identity and communication under the user's control.

> **Development status:** Android messaging prototype integration is in progress. The Compose Chats screen now has user-invoked direct TCP listen/connect controls for independently trusted contacts. A successful connection performs mutual signed identity proof followed by a pinned-key Noise XX handshake; text frames and delivery acknowledgements then travel only inside the encrypted channel. Conversation history is protected at rest with Android Keystore AES-256-GCM. This is an early prototype, not a production-ready messenger: CI validation, physical two-device tests, protocol review and independent security review remain outstanding. The account safety policy intentionally keeps messaging disabled until age assurance (and guardian approval where required) is complete.

## Project principles

- **Strict peer-to-peer:** TomChat will not deploy or depend on application servers, account servers, relays, push gateways, hosted discovery, central directories or hosted message storage.
- **Device-controlled identity:** the intended design keeps identity keys under the user's control on their devices.
- **End-to-end encryption:** message and call content must be protected independently of the transport. The protocol is not yet implemented or security-reviewed.
- **Local-first data:** conversations and account data should remain on the user's devices, with encrypted local backup and explicitly trusted-device replication planned.
- **Honest connectivity:** direct connections are best-effort. NAT, carrier restrictions, firewalls, device sleep and offline peers can prevent remote connections. Without relays or mailbox servers, TomChat cannot promise global reachability or offline delivery.
- **No silent fallback:** if a direct connection cannot be made, the app must explain the limitation rather than quietly using a server.

## One app, age-aware account setup

Everyone installs the same TomChat app. During first-run setup, the user enters a display name and date of birth. The app calculates an age band and assigns the corresponding experience; users do not choose their own child, teen or adult mode.

| Calculated age | Planned experience | Intended safeguards |
| --- | --- | --- |
| Under 5 | Guardian-led setup only | Independent account setup is blocked in the current prototype |
| 5–8 | TomChat Kids | Simplified interface, strongest contact and sharing restrictions |
| 9–12 | TomChat Kids | Age-appropriate messaging and guardian-approved contacts |
| 13–17 | TomChat Teen | More independence with age-appropriate safeguards |
| 18+ | TomChat Adult | Standard TomChat experience |

### Important age-safety status

The current implementation is a **self-declared age-band prototype**, not an age-verification system. It previews the experience based on the entered date of birth; it does not prove age, verify a guardian or obtain parental consent. Those checks must be implemented separately before restricted production features are enabled.

The production design must:
- minimise personal data and avoid retaining a full date of birth unless a reviewed requirement makes it necessary;
- keep age-band assignment, age assurance and guardian consent as distinct states;
- prevent users from simply changing a profile setting to unlock adult features;
- enforce restrictions in application/domain logic, not only by hiding UI;
- support safe, authenticated and revocable guardian-device pairing;
- avoid public discovery and unrestricted public groups for child accounts;
- provide clear block/report controls and age-appropriate contact approval;
- meet applicable child privacy, age-assurance, consent and online-safety requirements in each launch jurisdiction.

Any proposed verification mechanism must be assessed against TomChat's strict no-application-server requirement. A local date-of-birth calculation alone is not trustworthy age assurance. See [Account age and child safety](docs/account-age-safety.md).

## Planned features

- One-to-one and group messaging.
- One-to-one and group voice calls.
- File sharing and voice messages.
- Direct Bluetooth and Wi-Fi LAN connections, plus direct IP connections where reachable.
- Automatic selection of the best available direct route, based on latency, throughput, stability and battery impact.
- Device-local P-256 signing identity protected by Android Keystore, plus persistent X25519 Noise identity protected at rest by an Android Keystore AES-GCM wrapping key. V2 QR contact cards cryptographically bind the Noise public key into the P-256 signature. Camera scanning, fingerprint approval and encrypted trusted-contact persistence are implemented, including compatibility with older V1 cards (which are not Noise-capable). A direct TCP peer transport now performs mutual signed-nonce identity proof and then establishes a pinned-key Noise XX session before carrying encrypted application frames.
- Encrypted local backup and encrypted peer-to-peer replication to explicitly trusted devices.
- TomSocial integration as a later, carefully scoped decentralised social layer.

All networking must use the same authenticated peer identity and end-to-end encrypted message envelope. Bluetooth LE discovery/GATT and Bluetooth Classic data transfer are planned; direct Bluetooth peer voice remains experimental until physical-device testing demonstrates acceptable quality and battery use.

## Android application

The Android foundation uses Kotlin, Jetpack Compose and Material 3, with the Rust Noise XX core using Snow at `android/native/tomchat-noise`. Rust owns Noise session state behind a narrow JNI API. The messaging prototype connects the direct TCP identity-proof socket to the pinned Noise XX handshake, then exposes only encrypted application frames to the conversation session. The Compose chat screen can initiate or accept a direct connection to an independently trusted contact and displays encrypted local history. See [docs/architecture.md](docs/architecture.md) for remaining security work. The TomOS visual design is documented in [docs/design-system.md](docs/design-system.md). Tom OS Hyprland 1 is the primary brand reference; TomOS Android SIP informs Android interaction patterns, without importing SIP or Linphone functionality.

Current UI foundation:
- adaptive compact/medium/expanded window navigation: bottom bar on compact windows and navigation rail on wider windows; content width is constrained on large displays, and setup/contacts can scroll on short or unfolded screens;
- fold posture detection through Jetpack WindowManager, with book mode for half-open vertical folds and tabletop mode for half-open horizontal folds; both layouts provide a hinge gutter and companion navigation pane. Device-by-device physical validation is still required;
- first-run display-name and date-of-birth screen with local age-band preview;
- Chats, Contacts, Calls and Settings navigation;
- prototype chat flow with manual direct-IP connection/listen, trusted-contact selection, Noise XX gating, encrypted text frames, encrypted delivery receipts and local history; the composer stays locked until the account safety policy allows messaging and the Noise session is established;
- TomOS neon visual styling;
- account experience and zero-server policy status placeholders.

The age-band classifier, central capability policy and unit tests are under `android/app/src/main` and `android/app/src/test`. The prototype defaults to unverified accounts, so communication capabilities remain locked; minors additionally require guardian approval. This is domain-policy scaffolding, not a connected age-verification or consent flow. Account setup persists a minimal profile using AES-256-GCM with a device-held Android Keystore key; the full date of birth is not saved. The persistent Noise X25519 private scalar is wrapped with AES-256-GCM and is not stored in plaintext. Keystore/atomic-file behaviour still requires physical-device testing.

## Build

Requirements: Java 17, Android SDK 35, Android NDK 27.2.12479018, Gradle 8.9, Rust stable with Android targets, and `cargo-ndk` (`cargo install cargo-ndk --locked`).

From the repository root:

```bash
gradle --no-daemon :android:app:testDebugUnitTest
gradle --no-daemon :android:app:assembleDebug
```

The debug APK is produced at:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs Android unit tests, Android instrumentation tests on an API 35 emulator, validates native JNI exports and builds the debug APK. The existing Rust desktop checks are separate. Check the [Actions page](https://github.com/Spruhantom/tomos-chat-cliant/actions) for the latest result; do not assume a run has passed while it is still in progress.

## Repository layout

- `android/app` — native Android application and tests.
- `docs/design-system.md` — TomOS-inspired visual design.
- `docs/account-age-safety.md` — age bands, child safety and verification requirements.
- `docs/architecture.md` — architecture direction.
- `docs/roadmap.md` — development milestones.
- `docs/decisions/0001-product-baseline.md` — core product decisions.
- `docs/tomsocial-direction.md` — future TomSocial direction.
- `legacy-desktop` branch — preserved previous Rust/GTK desktop client.

## Development roadmap

1. Stabilise Android build, navigation and first-run setup.
2. Implement local identity and secure on-device storage.
3. Design and review age assurance, guardian consent and age-policy enforcement.
4. Prototype authenticated direct Bluetooth communication between two physical Android devices.
5. Add direct Wi-Fi LAN and reachable direct-IP transport.
6. Implement the reviewed end-to-end encryption protocol and messaging.
7. Add calls, file transfer, backups and later TomSocial capabilities.

## Security and release policy

TomChat must not be described as secure, production-ready or complete E2EE until the cryptographic protocol and implementation have undergone independent review, threat modelling, interoperability testing and real-device validation. Child-facing features must not be released until age assurance, guardian consent, safeguarding controls and relevant legal/privacy reviews are complete.

## Contributing

Development is currently taking place on the `android-rebuild` branch. The previous desktop client is preserved on `legacy-desktop`; Android work should not be merged into `main` until the new foundation and CI have been reviewed.
