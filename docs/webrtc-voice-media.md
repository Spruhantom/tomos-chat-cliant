# TomChat direct peer-to-peer voice media

## Current milestone

This branch adds a pinned Android WebRTC core and a small audio-only peer-connection owner. It does not enable calling in the UI and it does not yet connect two devices.

## Library choice

Use `io.getstream:stream-webrtc-android:1.3.10`, pinned rather than a dynamic version. It provides the upstream-style `org.webrtc` API and is published under Apache-2.0; WebRTC's included third-party components retain their own notices. Keep the dependency limited to the WebRTC core: do not add Stream Video, Stream signalling, or any hosted calling service.

## Transport and trust boundaries

- WebRTC carries real-time audio using its built-in DTLS-SRTP media protection. Do not invent a codec or wrap plaintext PCM in the app's TCP protocol.
- SDP offers/answers and ICE candidates are signalling metadata, not voice media. The next signalling adapter must send them only over the existing authenticated Noise peer channel, after verifying the peer's pinned contact-card identity.
- Never accept a remote SDP/candidate before the Noise session has authenticated the expected peer static key and identity binding.
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

1. Authenticated SDP/ICE signalling over the existing Noise peer channel, including bounded payloads, replay/call-ID checks, glare handling, candidate buffering, timeouts, and cancellation.
2. Runtime microphone permission UX and a foreground call service with correct notification lifecycle.
3. DTLS certificate/fingerprint verification policy tied to the authenticated contact identity, or a documented proof that the authenticated Noise signalling channel plus WebRTC DTLS-SRTP provides the intended peer binding. Do not claim identity-bound media until reviewed.
4. Mute, hold, audio route, Bluetooth, interruption, process-death, and network-change integration.
5. Two-physical-device tests on Wi-Fi and mobile networks, including the expected no-route failure cases.
6. Security review, dependency/licence inventory, and Android APK size/ABI review.

The Calls screen must remain disabled until these gates are completed. CI compilation alone is not evidence of real-device audio or secure end-to-end calls.
