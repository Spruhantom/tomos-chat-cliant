# TomChat end-to-end encryption threat model

Status: design and implementation boundary. This document is not an independent security audit and does not claim production-ready end-to-end encryption.

## Architecture and trust boundaries

TomChat is a decentralised peer-to-peer application. Devices discover and connect directly over available local or reachable network paths. There is no central message broker, account directory, hosted key store, relay, or cloud message store in the product architecture.

Treat every remote peer, discovery hint, network packet, and imported contact card as untrusted until authenticated. Local Wi-Fi, Bluetooth, TCP, and other transports provide connectivity only; they do not establish identity or message confidentiality.

## Assets

- Device signing private keys and Noise static/ephemeral key material.
- Conversation keys, session state, and any future MLS group/epoch secrets.
- User-authored plaintext, attachments, and locally queued messages.
- Public identity credentials, trusted-contact records, and revocation state.
- User-held recovery material and encrypted local backups.

## Adversaries

Assume an attacker can observe, delay, drop, reorder, replay, modify, or inject network traffic; spoof discovery advertisements; present a false peer address; attempt identity substitution; and cause denial of service. A malicious or compromised peer can send malformed protocol data, lie about its identity, or disclose messages it legitimately receives.

A compromised endpoint, operating system administrator, malware, screenshots, coercion, or a person with access to an unlocked device can expose plaintext and keys. End-to-end encryption cannot protect a compromised endpoint.

## Security goals

- Authenticate the peer identity independently of its IP address, Bluetooth address, or discovery metadata.
- Bind every encrypted session to a user-approved, pinned identity key.
- Protect message and call-control content against network observation and modification.
- Reject unauthenticated frames, replayed or malformed protocol messages, and unexpected identity changes.
- Keep private keys and plaintext on the participating devices.
- Persist cryptographic state safely and prevent rollback after crashes or updates.
- Make unavailable direct paths visible; never silently route through hosted infrastructure.

## Current implementation boundary

The Android prototype includes signed peer identity proof and a pinned-key Noise XX session for direct transport. Encrypted application frames and local message storage foundations exist. Android peer discovery uses local network and nearby Bluetooth mechanisms; discovery data is only a hint and is not authentication.

The inspected `main` dependency manifests do not currently include the OpenMLS crates, and no persistent OpenMLS `MlsGroup` storage/load implementation has been evidenced. The threat-model and roadmap references to OpenMLS are requirements/plans, not a completed integration. The Android Gradle manifest includes CameraX and WebRTC dependencies, but dependency presence is not evidence of a complete camera capture, peer media negotiation and remote rendering path. Production E2EE claims remain blocked until a reviewed MLS integration (if group messaging is retained), authenticated durable state restoration, replay protection, and real two-device validation have passed review.

## Device and contact lifecycle

1. Generate device identity material locally and protect private keys using platform secure storage.
2. Exchange a signed contact card out of band, then compare and approve the full identity fingerprint.
3. Persist the approved public key locally; do not trust a display name or discovery address as proof of identity.
4. Authenticate a direct peer connection before accepting application frames.
5. For future MLS groups, authenticate credentials and membership changes, persist commits transactionally, and reject stale or forked state.
6. On device loss, require a user-held recovery secret or an existing trusted device. Encrypted backups remain under user control.
7. Make identity changes and unverified contacts explicit in the interface.

## Local persistence requirements

- Keep private key material in platform secure storage; never write it to plaintext configuration, logs, crash dumps, or source control.
- Encrypt durable conversation and cryptographic state under a locally protected key.
- Commit message state, replay protection, and future MLS state atomically before acknowledging success.
- On failed writes or interrupted updates, reload the last authenticated durable state rather than continuing with uncertain in-memory state.
- Detect stale backups and prevent rollback to an older conversation epoch.
- Disable cryptographic debug output in release builds.

## Required verification before launch

Test protocol vectors; two-device identity verification; Noise handshake failure cases; replay, reorder, duplicate and malformed frame handling; contact-key changes; local storage corruption; process termination during state updates; backup rollback; Android lifecycle and network changes; and real Wi-Fi/Bluetooth device interoperability. Complete independent cryptographic review before making production security claims.

## Implementation gates

A. Finish persistent cryptographic storage and restore tests.
B. Integrate the selected MLS implementation behind a narrow, reviewed adapter for group messaging.
C. Complete contact verification, device revocation, and recovery flows.
D. Validate direct transports on multiple physical devices and supported Android versions.
E. Enable production messaging only after the security and recovery gates pass.
