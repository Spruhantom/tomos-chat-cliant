package uk.co.tomos.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PeerCallSignallingTest {
    private val localFingerprint = fingerprint('A')
    private val remoteFingerprint = fingerprint('B')
    private val callId = UUID.fromString("1a0e2d42-5b75-4ef1-9b5c-8f4f5c7c6d10")
    private val sdp = """
        v=0
        o=- 1 1 IN IP4 127.0.0.1
        s=TomChat
        t=0 0
        a=fingerprint:sha-256 ${dtlsFingerprint()}
        m=audio 9 UDP/TLS/RTP/SAVPF 111
    """.trimIndent()

    @Test fun offerRoundTripsWithItsDtlsFingerprint() {
        val signal = PeerCallSignal(callId, 1, localFingerprint, PeerCallSignalKind.OFFER,
            sdp, SdpDtlsFingerprint.extract(sdp))
        val decoded = PeerCallSignalCodec.decode(PeerCallSignalCodec.encode(signal))
        assertEquals(signal, decoded)
        assertEquals(dtlsFingerprint(), decoded.dtlsFingerprint)
    }

    @Test fun candidateRoundTripsWithinBounds() {
        val candidate = PeerIceCandidate("audio", 0, "candidate:1 1 UDP 2122260223 192.0.2.1 50000 typ host")
        val signal = PeerCallSignal(callId, 4, localFingerprint, PeerCallSignalKind.ICE_CANDIDATE, candidate = candidate)
        assertEquals(signal, PeerCallSignalCodec.decode(PeerCallSignalCodec.encode(signal)))
    }

    @Test fun rejectsMissingOrUnsupportedDtlsFingerprint() {
        val noFingerprint = "v=0\r\ns=-\r\nt=0 0\r\n"
        assertThrows(IllegalArgumentException::class.java) { SdpDtlsFingerprint.extract(noFingerprint) }
        val unsupported = "v=0\r\na=fingerprint:sha-384 00:11\r\n"
        assertThrows(IllegalArgumentException::class.java) { SdpDtlsFingerprint.extract(unsupported) }
    }

    @Test fun rejectsConflictingFingerprints() {
        val conflicting = sdp + "\na=fingerprint:sha-256 ${dtlsFingerprint('C')}"
        assertThrows(IllegalArgumentException::class.java) { SdpDtlsFingerprint.extract(conflicting) }
    }

    @Test fun acceptsOnlySignalsFromTheNoiseAuthenticatedIdentity() {
        val transport = FakeTransport(remoteFingerprint)
        val signalling = PeerCallSignalling(transport, localFingerprint, callId)
        val remoteOffer = PeerCallSignal(callId, 1, remoteFingerprint, PeerCallSignalKind.OFFER,
            sdp, SdpDtlsFingerprint.extract(sdp))
        val accepted = signalling.receive(frame(remoteOffer))
        assertEquals(remoteFingerprint, accepted.senderFingerprint)
        assertEquals(PeerCallSignalKind.OFFER, accepted.kind)

        val spoofed = remoteOffer.copy(sequence = 2, senderFingerprint = localFingerprint)
        assertThrows(IllegalArgumentException::class.java) { signalling.receive(frame(spoofed)) }
    }

    @Test fun rejectsReplayedAndCrossCallSignals() {
        val signalling = PeerCallSignalling(FakeTransport(remoteFingerprint), localFingerprint, callId)
        val accept = PeerCallSignal(callId, 1, remoteFingerprint, PeerCallSignalKind.ACCEPT)
        val frame = frame(accept)
        signalling.receive(frame)
        assertThrows(IllegalArgumentException::class.java) { signalling.receive(frame) }

        val otherCall = accept.copy(callId = UUID.randomUUID(), sequence = 2)
        assertThrows(IllegalArgumentException::class.java) { signalling.receive(frame(otherCall)) }
    }

    @Test fun outboundSignalsAreWrappedAsNoiseVoiceFrames() {
        val transport = FakeTransport(remoteFingerprint)
        val signalling = PeerCallSignalling(transport, localFingerprint, callId)
        signalling.sendOffer(sdp)
        val sent = transport.sent.single()
        assertEquals(EncryptedAppFrame.Type.VOICE_SIGNAL, sent.type)
        val decoded = PeerCallSignalCodec.decode(sent.payload)
        assertEquals(localFingerprint, decoded.senderFingerprint)
        assertEquals(callId, decoded.callId)
        assertEquals(1L, decoded.sequence)
        assertEquals(PeerCallSignalKind.OFFER, decoded.kind)
    }

    @Test fun rejectsMalformedCandidateAndIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerIceCandidate("audio", 1025, "candidate:bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerCallSignalling(FakeTransport(remoteFingerprint), "not-a-fingerprint", callId)
        }
        assertFalse(ContactIdentityFingerprint.isValid("peer-fingerprint"))
        assertTrue(ContactIdentityFingerprint.isValid(localFingerprint))
    }

    private fun frame(signal: PeerCallSignal) =
        EncryptedAppFrame(EncryptedAppFrame.Type.VOICE_SIGNAL, UUID.randomUUID(), PeerCallSignalCodec.encode(signal))

    private fun fingerprint(char: Char = 'A') =
        (1..32).joinToString("") { char.toString().repeat(2) }.chunked(4).joinToString(" ")

    private fun dtlsFingerprint(char: Char = 'A') =
        (1..32).joinToString(":") { char.toString().repeat(2) }

    private class FakeTransport(override val authenticatedPeerFingerprint: String) : PeerCallSignalTransport {
        val sent = mutableListOf<EncryptedAppFrame>()
        override fun send(frame: EncryptedAppFrame) { sent += frame }
    }
}
