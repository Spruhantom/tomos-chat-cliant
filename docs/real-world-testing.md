# TomChat real-world testing plan

**Phase:** Feature freeze / validation  
**Policy:** No new product features during this phase. Changes are limited to reproducible bug fixes, security fixes, build fixes, documentation and test coverage.

## Before distributing a build

- [ ] Select and record the exact commit SHA and build variant.
- [ ] Confirm the Android CI and Linux GTK/Rust CI workflows pass for that SHA.
- [ ] Archive the APK and record its SHA-256 checksum.
- [ ] Record supported Android versions and Linux distributions for this build.
- [ ] Back up test identities and understand that identity recovery is not yet guaranteed.
- [ ] Tell testers this is a pre-release prototype; do not use sensitive conversations or rely on it for emergency communications.
- [ ] Do not describe the app as independently security-audited or production-ready.

## Test stages

### Stage 1 — Developer-owned devices

- [ ] Fresh Android install and first-run setup.
- [ ] Upgrade install; verify identity, trusted contacts and local data persist.
- [ ] Create an identity and export a signed V2 contact card.
- [ ] Import a contact card and compare the displayed fingerprint out-of-band.
- [ ] Reject an altered card, invalid signature and mismatched pinned key.
- [ ] Test direct peer connection on the same Wi-Fi network.
- [ ] Exchange messages in both directions and verify delivery/acknowledgement behaviour.
- [ ] Test disconnect, reconnect, app backgrounding, force-stop and device reboot.
- [ ] Test voice call setup, two-way audio, mute/unmute, pause/resume, route changes and hang-up.
- [ ] Check notification stability, battery use, memory use and crash logs.

### Stage 2 — Linux desktop

- [ ] Install and launch on Arch/Omarchy.
- [ ] Install and launch on Debian/Ubuntu.
- [ ] Complete GTK onboarding and create/load the signing identity.
- [ ] Verify Secret Service/keyring unavailable and locked states fail safely.
- [ ] Import, verify, list and remove trusted contacts.
- [ ] Confirm no legacy centralised sign-in or messaging dependency remains in the tested path.

### Stage 3 — Cross-platform peer tests

- [ ] Generate a genuine shared Rust/Kotlin V2 contact-card test vector.
- [ ] Verify Android-to-Linux contact-card signature and fingerprint compatibility.
- [ ] Establish a Noise session Android-to-Linux and Linux-to-Android.
- [ ] Exchange authenticated encrypted messages in both directions.
- [ ] Verify wrong-key, tampered-frame and replay/duplicate handling.
- [ ] Test LAN changes, peer restart, offline peer and reconnection behaviour.

### Stage 4 — Closed tester group

Begin only after the relevant Stage 1 tests and CI gates pass. Expand to a small group of consenting testers with a clear way to report issues and withdraw. Do not promise internet-wide reachability: direct peer connections can be blocked by NAT, carrier networks, firewalls or device sleep.

## Release gates

A build is **not ready for wider testing** until:
- CI passes for the exact candidate commit.
- No known critical/high severity security or data-loss defects remain.
- Identity persistence and contact verification have been exercised.
- The advertised platform combinations have actually been tested.
- Android/Linux interoperability is demonstrated before claiming cross-platform support.

A build is **not production-ready** solely because this checklist is complete; independent security review and broader reliability testing are still required.

## Tester report template

For every test, record:

- Date/time and timezone:
- Tester/device identifier (do not include personal account details):
- App version, build variant and commit SHA:
- OS/distribution and version:
- Network type (Wi-Fi/mobile/LAN; avoid publishing public IPs):
- Test case:
- Expected result:
- Actual result:
- Pass/fail/not tested:
- Reproduction steps:
- Sanitised logs or screenshots (remove keys, contact cards, tokens and personal data):

## Current known limitations

- Android/Linux interoperability has not yet been demonstrated.
- The shared Rust transport and GTK desktop path still require integration and real-device validation.
- Direct connectivity is best-effort; no relay, hosted discovery or hosted message store is part of the product design.
- A successful CI run is not a substitute for physical-device testing or a security review.
