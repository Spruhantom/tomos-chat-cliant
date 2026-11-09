# Android real-device validation checklist

The normal GitHub Actions workflow intentionally does not boot an Android emulator.
It runs Rust Noise/JNI boundary tests, Kotlin/JVM wrapper tests, builds the debug APK,
and inspects the packaged native libraries and JNI exports for every supported ABI.

Use this checklist when a physical Android device is available. These checks complement
CI; they are not evidence that hardware validation has already passed.

## Before testing

- [ ] Install the CI-produced debug APK on a physical Android device.
- [ ] Confirm the app launches and can be backgrounded and reopened.
- [ ] Record device model, Android version, ABI, APK commit SHA, and test date.

## Native library and JNI smoke checks

- [ ] Confirm the app loads `libtomchat_noise.so` without `UnsatisfiedLinkError`.
- [ ] Create two test identities and mutually trusted peer records in a controlled test build.
- [ ] Complete a Noise XX handshake between the two peers using the expected pinned static keys.
- [ ] Confirm each side can encrypt and decrypt a test payload in both directions.
- [ ] Confirm encryption is rejected before handshake completion.
- [ ] Confirm a wrong pinned peer key is rejected.
- [ ] Close a session and confirm later operations fail safely.
- [ ] Repeat after force-stopping and relaunching the app.

## Report

Record pass/fail, device details, relevant logcat output with secrets and personal data removed,
and the exact APK commit SHA. Never include private keys, identity secrets, or real user messages.
