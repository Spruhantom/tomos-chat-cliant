# TomChat direct peer-to-peer voice media

## Current milestone

This branch adds a pinned Android WebRTC core and a small audio-only peer-connection owner. It does not enable calling in the UI and it does not yet connect two devices.

## Library choice

Use `io.getstream:stream-webrtc-android:1.3.10`, pinned rather than a dynamic version. It provides the upstream-style `org.webrtc` API and is published under Apache-2.0; WebRTC's included third-party components retain their own notices. Keep the dependency limited to the WebRTC core: do not add Stream Video, Stream signalling, or any hosted calling service.

## Transport and trust boundaries

- WebRTC carries real-time audio using its built-in DTLS-SRTP media protection. Do not invent a codec or wrap plaintext PCM in the app's TCP protocol.
- SDP offers/answers, ICE candidates and call-control events are signalling metadata, not voice media. `PeerCallSignalling` serializes bounded call messages into `VOICE_SIGNAL` application frames; those frames can only be transported by `EncryptedPeerChannel` after mutual ECDSA identity proof and the pinned Noise static-key handshake.
- `EncryptedPeerChannel` exposes the identity fingerprint established by the direct-peer proof and pinned Noise handshake. `PeerCallSignalling` checks every inbound signal against that authenticated fingerprint, call ID and strictly increasing sequence number. Spoofed identities, cross-call messages, duplicate/replayed and out-of-order signals are rejected.\n- SDP is required to contain one consistent SHA-256 DTLS fingerprint. Conflicting, malformed or unsupported fingerprint attributes are rejected. The verified SDP is passed unchanged to WebRTC; WebRTC's DTLS implementation checks the presented certificate against the fingerprint during the DTLS handshake. The authenticated Noise channel binds that SDP/fingerprint to the trusted contact identity.
- Configure no ICE servers. This means host candidates only: same-LAN or otherwise directly reachable peers may connect, but NAT, carrier-grade NAT, restrictive Wi-Fi, and firewalls can prevent calls. There is deliberately no STUN, TURN, relay, SFU, rendezvous, push, or cloud fallback.
- The direct TCP Noise channel is signalling-only. It must never carry RTP, encoded audio, or PCM.
- The WebRTC audio device module owns microphone capture and playback during a call. Do not run `AndroidVoiceAudioEngine` concurrently; it remains a lower-level prototype/test foundation and will be retired or repurposed after the WebRTC path is validated.

## Implemented in this branch

- Pinned WebRTC Android core dependency.
- Runtime/factory initialisation with a Java audio device module.
- Audio-only Unified Plan peer connection with an empty ICE-server list.
- Explicit microphone permission check before creating an audio session.
- Track/source/peer-connection/factory cleanup.
- Hardware AEC and noise suppression requested where Android supports them.

## Not implemented / release gates

1. Integrate the signalling adapter into a single-owner receive loop and WebRTC call coordinator; add offer glare handling, early ICE candidate buffering, per-call cancellation and bounded call timeouts.
2. Runtime microphone permission UX and a foreground call service with correct notification lifecycle.
3. Independent security review of the identity-bound DTLS fingerprint path, including certificate rotation, renegotiation and malicious SDP cases. Do not claim production-secure calls until reviewed.
4. Mute, hold, audio route, Bluetooth, interruption, process-death, and network-change integration.
5. Two-physical-device tests on Wi-Fi and mobile networks, including the expected no-route failure cases.
6. Security review, dependency/licence inventory, and Android APK size/ABI review.

The Calls screen must remain disabled until these gates are completed. CI compilation alone is not evidence of real-device audio or secure end-to-end calls.
