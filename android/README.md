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
- Contacts: signed QR contact cards, fingerprint confirmation, trusted-contact persistence, and a signed binding between the P-256 device identity and the X25519 Noise static key.
- Calls: placeholder for authenticated direct voice calls.
- Settings: zero-server policy, automatic route preference, identity, backups, and future TomSocial direction.

The Android foundation still does not implement real messaging or calling. The current direct TCP connection proves peer identity but deliberately exposes no application data stream.

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

The Android client now creates a persistent X25519 static key. Its private scalar is encrypted at rest with AES-256-GCM using a non-exportable Android Keystore wrapping key. New V2 QR cards include the Noise public key in the P-256 identity signature, and the trusted-contact store persists that binding. Legacy V1 cards remain readable but cannot be trusted as Noise-capable contacts. The Rust Noise XX core is still isolated and is not connected to Android or the direct TCP adapter. The next networking milestone is a narrow JNI/UniFFI integration that passes local key material into Rust and pins the trusted contact's signed Noise key during the handshake, followed by physical-device LAN testing.

## Planned modules

- `app`: Android UI, lifecycle, permissions, notifications and audio routing.
- `core-model`: typed domain models and use cases.
- `core-storage`: encrypted local database and migrations.
- `core-identity`: Android Keystore integration and identity lifecycle.
- `core-network`: Kotlin-facing API to the Rust peer engine.
- `native/`: Rust workspace and Android-target builds (initial location; may be split into a workspace later).
