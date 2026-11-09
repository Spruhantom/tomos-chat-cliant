# TomChat

TomChat is an Android-first, decentralised messaging and voice application. This branch begins the Android rebuild; the previous Rust/GTK server-connected desktop client is preserved on `legacy-desktop`.

## Product principles

- **Strict peer-to-peer:** no TomChat-operated server, account service, relay, push gateway, or central message store is required.
- **User-owned identity:** keys are generated and controlled on the user's device.
- **End-to-end encryption:** message and call content is protected independently of transport encryption.
- **Offline-first:** persist outgoing messages locally and synchronise when peers reconnect.
- **Open protocols:** document wire formats and versioning; avoid vendor lock-in.
- **Honest connectivity:** direct communication is best-effort across NATs and mobile networks. No relay means some pairs of devices will be unable to connect remotely.

## Scope

Android app with one-to-one and group messaging, voice and group voice, file sharing, QR/contact exchange, local encrypted backups, and encrypted peer-to-peer backup to trusted devices.

## Age-aware account setup

TomChat uses one app with automatic age-band assignment during first-run setup. The current local classifier is a prototype only; age assurance and guardian consent are separate, not yet implemented checks. See [age-aware account and child safety requirements](docs/account-age-safety.md).

## Visual design

TomChat follows the [TomOS visual design system](docs/design-system.md): Tom OS Hyprland 1 supplies the branding and colour palette, while TomOS Android SIP informs Android interaction patterns.

## Repository status

The native Android foundation is now scaffolded under `android/app`: TomOS neon styling, Chats / Contacts / Calls / Settings navigation, Gradle configuration, unit-test scaffold, and Android CI. It is not a working messenger yet: identity, encrypted storage, Bluetooth/Wi-Fi transports, messaging, and calls remain future implementation milestones. The Android build and test workflow is being validated. Do not describe the application as secure or production-ready until independent protocol review, threat modelling, interoperability tests, and device testing are complete.

See [architecture](docs/architecture.md), [roadmap](docs/roadmap.md), [decisions](docs/decisions/0001-product-baseline.md), and the proposed [TomOS Social direction](docs/tomsocial-direction.md).
