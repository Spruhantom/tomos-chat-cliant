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

## Visual design

TomChat follows the [TomOS visual design system](docs/design-system.md): Tom OS Hyprland 1 supplies the branding and colour palette, while TomOS Android SIP informs Android interaction patterns.

## Repository status

This is the planning and architecture baseline, not a working messenger yet. The next milestone is a minimal Android shell plus a Rust networking-core proof of concept. Do not describe the application as secure or production-ready until independent protocol review, threat modelling, interoperability tests, and device testing are complete.

See [architecture](docs/architecture.md), [roadmap](docs/roadmap.md), [decisions](docs/decisions/0001-product-baseline.md), and the proposed [TomOS Social direction](docs/tomsocial-direction.md).
