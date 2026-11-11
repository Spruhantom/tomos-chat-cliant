# Android voice calling foundation

Status: local audio-device foundation only. Calling is not enabled in the UI and no voice media is transmitted.

## Implemented foundation

- Android `AudioRecord` capture at 48 kHz, mono, PCM16.
- Fixed 20 ms PCM frames (960 samples / 1,920 bytes).
- Android `AudioTrack` playback for decoded 20 ms PCM frames.
- Voice-communication audio mode and transient-exclusive audio focus.
- Best-effort platform acoustic echo cancellation, noise suppression and automatic gain control when available.
- Mute replaces captured frames with silence while preserving frame cadence.
- Built-in speaker/earpiece selection where the Android API supports it; wired and Bluetooth headsets remain under Android audio routing.
- Idempotent resource cleanup and restoration of prior audio mode/routing.
- Runtime microphone permission is declared but must be requested only after a user explicitly starts a call.

## Security boundary

`AndroidVoiceAudioEngine` is not a network transport. Its capture callback receives raw PCM and must remain local to a future media pipeline. Never pass these bytes to `EncryptedPeerChannel.send` or any socket. The existing TCP Noise channel is for bounded application frames and is not a real-time voice media transport.

Before calling can be enabled, implement and review:
1. A maintained, audited real-time media stack (prefer WebRTC) with authenticated media protection such as DTLS-SRTP, or an equivalently reviewed design.
2. Peer-to-peer media path establishment without STUN, TURN, relays, rendezvous services or hosted signalling. Direct calls will be unreachable on some NATs/carrier networks; show this honestly.
3. Call setup/teardown bound to the authenticated peer identity and call ID, with replay protection and explicit state transitions.
4. Media packetisation, jitter buffering, loss handling, congestion control and audio clock handling from the chosen media stack. Do not invent a custom codec or cryptographic media protocol.
5. Android runtime microphone permission, audio focus interruption handling, wired/Bluetooth route changes, device rotation, screen lock, app backgrounding and foreground-call service policy.
6. Two-physical-device tests for earpiece, speaker, wired headset, Bluetooth headset, mute, focus loss, route switching, packet loss, disconnect and teardown.

## Validation status

- JVM tests cover the fixed PCM frame size and bounds.
- Android CI can compile and run JVM tests; it cannot prove microphone, speaker, acoustic echo, latency, Bluetooth routing or physical-device behaviour.
- No physical-device voice-call tests have been performed.
- Calls remain unavailable until authenticated signalling, encrypted media transport, safety checks and real-device validation are complete.
