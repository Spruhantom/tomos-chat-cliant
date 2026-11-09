# Android application

This directory is reserved for the Kotlin/Jetpack Compose application.

Planned modules:
- `app`: Android UI, lifecycle, permissions, notifications and audio routing.
- `core-model`: typed domain models and use cases.
- `core-storage`: encrypted local database and migrations.
- `core-identity`: Android Keystore integration and identity lifecycle.
- `core-network`: Kotlin-facing API to the Rust peer engine.
- `native/`: Rust workspace and Android-target builds (initial location; may be split into a workspace later).

## Direct Bluetooth support

Bluetooth is a first-class direct peer transport, not a server substitute:
- BLE advertisements/GATT for nearby discovery, connection setup, and small encrypted payloads.
- Bluetooth Classic RFCOMM for a direct byte stream and suitable file transfers where supported.
- The same authenticated, end-to-end encrypted message envelope is used over Bluetooth and Wi-Fi.
- An automatic path selector measures available direct links and chooses the best route for the task (low latency for chat/calls, throughput for files, battery efficiency for background sync); users can override it.
- Android Bluetooth permissions, pairing, adapter state, MTU negotiation, transfer resume, and lifecycle limits must be handled.
- Bluetooth headset audio routing is required for calls. Direct Bluetooth peer voice media remains experimental until tested on physical devices.

The Android Gradle project is not scaffolded in this commit. Phase 1 will add the Gradle wrapper, version catalog, app module, CI, and tests after the Rust/Android FFI spike determines the integration boundary.
