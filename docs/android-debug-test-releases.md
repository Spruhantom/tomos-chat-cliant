# Android debug test releases

The `android-rebuild` branch publishes a GitHub prerelease after the
`TomChat Android CI` workflow completes successfully.

The APK is a debug build for controlled testing by consenting adults. The
adult-only debug test mode requires an adult-classified local account and
guardian state `NOT_REQUIRED`; child and teen safeguards remain active.
This is not a production release. Voice calling remains a prototype and
requires physical-device testing.

The prerelease workflow runs from the repository default branch and downloads
the APK artifact from the exact successful Android CI run before publishing.
