# TomChat real-world testing plan

**Phase:** Feature freeze / validation  
**Scope:** Android and Linux desktop clients, plus the shared Rust peer transport.  
**Policy:** Only reproducible bug fixes, security fixes, build fixes, documentation and test coverage should be added during this phase.

## Before distributing a build

- [ ] Record the exact commit SHA, build variant and platform.
- [ ] Confirm the Android and Linux CI workflows pass for that exact SHA.
- [ ] Archive the APK or Linux package/binary and record its SHA-256 checksum.
- [ ] Record supported Android versions and Linux distribution versions actually tested.
- [ ] Back up test identities and understand that identity recovery is not guaranteed.
- [ ] Tell testers this is a pre-release prototype; do not use sensitive conversations or rely on it for emergency communications.
- [ ] Do not describe the app as independently security-audited or production-ready.

## Stage 1 — Android device testing

- [ ] Fresh install and first-run setup on at least two physical Android devices.
- [ ] Upgrade install; verify identity, trusted contacts and local data persist.
- [ ] Create an identity and export a signed V2 contact card.
- [ ] Import a contact card and compare the full fingerprint out-of-band.
- [ ] Reject altered cards, invalid signatures and mismatched pinned keys.
- [ ] Test direct connection on the same Wi-Fi network.
- [ ] Exchange messages in both directions and verify acknowledgements.
- [ ] Test disconnect, reconnect, app backgrounding, force-stop and device reboot.
- [ ] Test voice setup, two-way audio, mute/unmute, route changes and hang-up.
- [ ] Check notifications, battery use, memory use and crash logs.

## Stage 2 — Linux desktop testing

- [ ] Install and launch on Arch Linux and Omarchy.
- [ ] Install and launch on supported Debian and Ubuntu releases.
- [ ] Confirm GTK4/libadwaita startup and desktop launcher behaviour.
- [ ] Complete local identity creation and load an existing identity.
- [ ] Verify Secret Service/keyring locked, unavailable and recovery states fail safely.
- [ ] Import, verify, list and remove trusted V2 contacts.
- [ ] Test direct TCP connect/listen, firewall prompts and invalid peer addresses.
- [ ] Verify wrong-key peers and malformed contact cards are rejected.

## Stage 3 — Android/Linux interoperability

- [ ] Verify the shared Rust/Kotlin V2 contact-card test vector on both platforms.
- [ ] Exchange cards in both directions and independently compare fingerprints.
- [ ] Establish a Noise XX session Android-to-Linux and Linux-to-Android.
- [ ] Exchange authenticated encrypted messages in both directions.
- [ ] Verify wrong-key, tampered-frame, malformed-frame and replay/duplicate handling.
- [ ] Test LAN changes, peer restart, offline peer and reconnection behaviour.

## Stage 4 — closed tester group

Begin only after relevant device tests and CI gates pass. Use a small group of consenting testers with a clear issue-reporting and withdrawal process. Do not promise internet-wide reachability: direct peer connections can be blocked by NAT, carrier networks, firewalls or device sleep.

## Release gates

A build is **not ready for wider testing** until:
- CI passes for the exact candidate commit.
- No known critical/high severity security or data-loss defects remain.
- Identity persistence and contact verification have been exercised.
- Each advertised platform combination has actually been tested.
- Android/Linux interoperability is demonstrated before claiming cross-platform support.

Completing this checklist does not make a build production-ready. Independent security review, privacy/age-safety review and broader reliability testing are still required.

## Tester report template

For every test, record:
- Date/time and timezone:
- Tester/device identifier (do not include personal account details):
- App version, build variant and commit SHA:
- OS/distribution and version:
- Network type (Wi-Fi/mobile/LAN; do not publish public IPs):
- Test case:
- Expected result:
- Actual result:
- Pass/fail/not tested:
- Reproduction steps:
- Sanitised logs or screenshots (remove keys, contact cards, tokens and personal data):

## Current known limitations

- Automated Android and Linux CI passed on the merged PR #19 head; check Actions for the latest main-branch result.
- Physical Android device and cross-platform interoperability testing are not yet evidenced by CI.
- Direct connectivity is best-effort; no relay, hosted discovery or hosted message store is part of the product design.
- The Noise implementation and integration have not received an independent security audit.
- Linux identity/contact persistence requires a working desktop Secret Service provider.
- The Android voice path still needs physical-device audio quality and lifecycle testing.
