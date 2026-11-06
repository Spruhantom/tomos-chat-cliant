# TomChat E2EE threat model and implementation decision

Status: design baseline for review. This document is not an audit or a claim that E2EE is implemented.

## Decision

Use the IETF Messaging Layer Security protocol (MLS, RFC 9420) for direct and group conversations. Use OpenMLS 0.9.2 as the initial Rust implementation candidate, pinned exactly (`=0.9.2`) with its supporting crates pinned to the compatible release set. OpenMLS 0.9.2 is pre-1.0, so isolate it behind an internal adapter and review every upgrade. Its upstream workspace currently declares Rust 1.91.0 as the minimum supported toolchain; CI must test that MSRV explicitly. Do not expose OpenMLS types in GTK or REST modules.

Candidate initial ciphersuite: `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`. The crypto review must confirm this choice against platform support, dependency provenance, security advisories and interoperability requirements before release. OpenMLS 0.9.2 documents this ciphersuite as supported. No runtime fallback to an unapproved suite.

## Assets

- Device signing private keys and MLS init/epoch secrets.
- MLS group state, ratchet state and transcript hashes.
- User-authored plaintext and attachments.
- Public device credentials, KeyPackages and device revocation state.
- Recovery secret and encrypted backup material.

## Adversary and trust assumptions

The server/delivery service is untrusted for message confidentiality and integrity. Assume it can read all API metadata, suppress, reorder, replay, delay or inject opaque messages, present stale or inconsistent device-directory data, and deny service. Network attackers may observe and alter traffic; TLS remains mandatory for transport security.

Clients, their OS account, the desktop secret service and verified device endpoints are trusted. A compromised endpoint or OS administrator can read plaintext and keys and is outside the cryptographic protection boundary.

## Security goals

- Only authenticated, currently authorised group members/devices can decrypt application messages.
- Membership changes are cryptographically authenticated and advance the MLS epoch.
- Forward secrecy and post-compromise security follow the guarantees and limits of the selected MLS ciphersuite and update cadence.
- Device credentials bind a device signing key to an account/device identifier.
- Users can detect unexpected device additions and removals through explicit verification and change notices.
- Private keys and plaintext never reach the server.
- State updates survive crashes without rolling the client back to an older epoch.

## Explicit non-goals / leakage

The server necessarily sees account and device identifiers, conversation membership, delivery timing, message sizes, IP/network metadata and availability. Initial release does not hide traffic patterns or protect against compromised endpoints, screenshots, malicious accessibility tooling, malware, coercion or denial of service. MLS does not by itself solve identity proofing: clients need an authenticated credential and device verification model.

## Device lifecycle

1. Enrolment: a logged-in account creates a device locally; an existing verified device approves its credential, or the user completes a documented recovery flow.
2. Publication: the client uploads only public credential material and signed, single-use KeyPackages. Private init/signing keys remain local.
3. Verification: compare a QR code or safety code through an authenticated out-of-band channel. Until verified, clearly label the device as unverified and require explicit approval before it receives conversation keys.
4. Addition: add the device to each MLS group with authenticated MLS proposals/commits. Do not simply copy a group secret over the account session.
5. Revocation: revoke the directory credential, remove the leaf from every group, commit a new epoch, and show the change to remaining members. Revocation cannot retract plaintext already read or data copied by the lost device.
6. Recovery: require a user-held recovery secret or an existing trusted device. Backups are encrypted client-side and must not become server escrow. If all keys and recovery material are lost, encrypted history is unrecoverable.

## Local persistence requirements

- Keep private key material in the OS Secret Service; never in plaintext configuration, logs, crash dumps or source control.
- Store larger serialized MLS state encrypted under a random local wrapping key protected by Secret Service.
- Commit group state, replay protection state and outbound messages atomically. If a storage transaction fails or an async operation is cancelled, discard the in-memory group and reload from durable state.
- Prevent backup rollback from silently restoring an old epoch. Bind backup metadata to group ID, epoch and transcript hash; require authenticated rejoin/recovery if state is stale.
- Never enable OpenMLS content-debug or crypto-debug features in release builds.

## Required verification before launch

MLS test vectors; multi-client and multi-device interoperability; concurrent commit/fork handling; replay, reorder and duplicate delivery; stale KeyPackage rejection; device revocation; storage corruption and crash injection; backup rollback; fuzzing of all untrusted MLS and directory inputs; dependency/SBOM review; independent cryptographic review; and a documented incident/recovery process.

## Implementation phases

A. Device directory API and protocol schemas (public material only).
B. OpenMLS adapter and transactional encrypted storage.
C. Verification, device changes, revocation and recovery UX.
D. Multi-device E2EE integration tests and independent review.
E. Enable composer only after all gates pass.
