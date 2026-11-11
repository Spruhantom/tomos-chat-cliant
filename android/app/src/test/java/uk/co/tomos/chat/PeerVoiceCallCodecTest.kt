package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PeerVoiceCallCodecTest {
    @Test
    fun voiceSignalsRoundTrip() {
        PeerVoiceSignal.entries.forEach { signal ->
            val decoded = PeerVoiceCallCodec.decode(PeerVoiceCallCodec.encodeSignal(signal))
            assertEquals(PeerVoicePayload.Signal(signal), decoded)
        }
    }

    @Test
    fun fixedSizePcmFrameRoundTrips() {
        val pcm = ByteArray(VoiceAudioFormat.FRAME_BYTES) { index -> (index % 251).toByte() }
        val decoded = PeerVoiceCallCodec.decode(PeerVoiceCallCodec.encodeMedia(pcm))
        assert(decoded is PeerVoicePayload.Media)
        assertArrayEquals(pcm, (decoded as PeerVoicePayload.Media).pcm)
    }

    @Test
    fun rejectsTruncatedAndOversizedPcmFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerVoiceCallCodec.encodeMedia(ByteArray(VoiceAudioFormat.FRAME_BYTES - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerVoiceCallCodec.decode(byteArrayOf(0x20) + ByteArray(VoiceAudioFormat.FRAME_BYTES - 1))
        }
    }

    @Test
    fun rejectsUnknownAndMalformedControlSignals() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerVoiceCallCodec.decode(byteArrayOf(99))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerVoiceCallCodec.decode(byteArrayOf(PeerVoiceSignal.INVITE.wireId.toByte(), 0))
        }
    }
}
