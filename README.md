# TomChat

**TomChat** is an Android-first project to build a decentralised, peer-to-peer messaging and voice app that keeps identity and communication under the user's control.

> **Development status:** early Android foundation. The current app contains a first-run age-band prototype and basic navigation. It is not yet a functioning messenger, and age verification, guardian consent, end-to-end encrypted messaging, identity storage and peer networking are not yet implemented.

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
- Device-local P-256 signing identity protected by Android Keystore, plus versioned signed contact cards and local QR rendering. Camera scanning, fingerprint approval and encrypted trusted-contact persistence are implemented. A direct TCP peer transport with mutual signed-nonce identity proof is now prototyped; it does not yet carry application data or provide an encrypted session.
- Encrypted local backup and encrypted peer-to-peer replication to explicitly trusted devices.
- TomSocial integration as a later, carefully scoped decentralised social layer.

All networking must use the same authenticated peer identity and end-to-end encrypted message envelope. Bluetooth LE discovery/GATT and Bluetooth Classic data transfer are planned; direct Bluetooth peer voice remains experimental until physical-device testing demonstrates acceptable quality and battery use.

## Android application

The Android foundation uses Kotlin, Jetpack Compose and Material 3, with the TomOS visual design documented in [docs/design-system.md](docs/design-system.md). Tom OS Hyprland 1 is the primary brand reference; TomOS Android SIP informs Android interaction patterns, without importing SIP or Linphone functionality.

Current UI foundation:
- first-run display-name and date-of-birth screen with local age-band preview;
- Chats, Contacts, Calls and Settings navigation;
- TomOS neon visual styling;
- account experience and zero-server policy status placeholders.

The age-band classifier, central capability policy and unit tests are under `android/app/src/main` and `android/app/src/test`. The prototype defaults to unverified accounts, so communication capabilities remain locked; minors additionally require guardian approval. This is domain-policy scaffolding, not a connected age-verification or consent flow. Account setup now persists a minimal profile using AES-256-GCM with a device-held Android Keystore key; the full date of birth is not saved. Keystore/atomic-file behaviour still requires physical-device testing.

## Build

Requirements: Java 17, Android SDK 35 and Gradle 8.9.

From the repository root:

```bash
gradle --no-daemon :android:app:testDebugUnitTest
gradle --no-daemon :android:app:assembleDebug
```

The debug APK is produced at:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs Android unit tests and builds the debug APK. The existing Rust desktop checks are separate. Check the [Actions page](https://github.com/Spruhantom/tomos-chat-cliant/actions) for the latest result; do not assume a run has passed while it is still in progress.

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
