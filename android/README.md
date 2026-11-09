# Android application

This directory is reserved for the Kotlin/Jetpack Compose application.

Planned modules:
- `app`: Android UI, lifecycle, permissions, notifications and audio routing.
- `core-model`: typed domain models and use cases.
- `core-storage`: encrypted local database and migrations.
- `core-identity`: Android Keystore integration and identity lifecycle.
- `core-network`: Kotlin-facing API to the Rust peer engine.
- `native/`: Rust workspace and Android-target builds (initial location; may be split into a workspace later).

The Android Gradle project is not scaffolded in this commit. Phase 1 will add the Gradle wrapper, version catalog, app module, CI, and tests after the Rust/Android FFI spike determines the integration boundary.
