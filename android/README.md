# Android application

The native Android foundation is now scaffolded in `android/app` using Kotlin, Jetpack Compose, and Material 3.

## Build

The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Java 17, and compile/target SDK 35 (minimum SDK 26).

With Android SDK, Java 17, and Gradle 8.9 installed, build from the repository root:

```bash
gradle --no-daemon :android:app:assembleDebug
gradle --no-daemon :android:app:testDebugUnitTest
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions workflow `.github/workflows/android.yml` runs the unit tests, builds the debug APK, and uploads it as a workflow artifact. The existing Rust desktop CI remains separate and continues to validate the preserved desktop code.

## Age-aware account setup

The first-run screen collects a display name and a date of birth in `YYYY-MM-DD` format, then assigns the 5–8, 9–12, 13–17 or 18+ experience band. Independent setup for under-fives is blocked pending a guardian-led flow. The classifier is covered by unit tests. This is only a self-declared local preview: age assurance and guardian consent are not implemented, and the selected band must not unlock restricted production features yet. See [account age and safety requirements](../docs/account-age-safety.md).

## Current Android screens

- Chats: zero-server connection status and empty conversation state.
- Contacts: placeholder for local identity and QR contact exchange.
- Calls: placeholder for authenticated direct voice calls.
- Settings: zero-server policy, automatic route preference, identity, backups, and future TomSocial direction.

These screens establish navigation and visual foundations; they do not yet implement messaging, calling, identity, or networking.

## Visual design

Follow [docs/design-system.md](../docs/design-system.md):
- Tom OS Hyprland 1 supplies the official neon-punk visual identity and canonical palette.
- TomOS Android SIP supplies Android-native Compose interaction patterns for contacts, calls, dial-pad controls, and call lifecycle UX.
- Build TomChat as its own app experience; do not import SIP-specific Linphone/SIP logic or desktop-only GTK/Quickshell/Hyprland components.
- Centralize Compose Material 3 colour, shape, typography, and motion tokens.

## Direct Bluetooth and peer networking

Bluetooth is a first-class direct peer transport, not a server substitute:
- BLE advertisements/GATT for nearby discovery, connection setup, and small encrypted payloads.
- Bluetooth Classic RFCOMM for a direct byte stream and suitable file transfers where supported.
- The same authenticated, end-to-end encrypted message envelope is used over Bluetooth and Wi-Fi.
- An automatic path selector measures available direct links and chooses the best path for the task (low latency for chat/calls, throughput for files, battery efficiency for background sync); users can override it.
- Android Bluetooth permissions, pairing, adapter state, MTU negotiation, transfer resume, and lifecycle limits must be handled.
- Bluetooth headset audio routing is required for calls. Direct Bluetooth peer voice media remains experimental until tested on physical devices.

The Android client now has device identity, signed contact cards, local QR scanning, fingerprint confirmation, and encrypted local trusted-contact storage. A direct TCP prototype performs mutual signed-nonce proof of possession against saved contacts. An isolated Rust crate now prototypes Noise XX using the Snow implementation (`Noise_XX_25519_ChaChaPoly_SHA256`) and tests pinned static-key authentication plus encrypted records. The Noise core is not yet connected to Android, its static keys are not yet bound to the existing signed contact-card identity, and the Kotlin direct transport still does not carry application data. The next networking milestone is to complete secure Noise key provisioning and signed contact-card binding, then integrate the Rust core through FFI and test two physical Android devices on a LAN without any application server.

## Planned modules

- `app`: Android UI, lifecycle, permissions, notifications and audio routing.
- `core-model`: typed domain models and use cases.
- `core-storage`: encrypted local database and migrations.
- `core-identity`: Android Keystore integration and identity lifecycle.
- `core-network`: Kotlin-facing API to the Rust peer engine.
- `native/`: Rust workspace and Android-target builds (initial location; may be split into a workspace later).
