package uk.co.tomos.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceAudioFormatTest {
    @Test
    fun formatUsesTwentyMillisecondMonoPcm16Frames() {
        assertEquals(48_000, VoiceAudioFormat.SAMPLE_RATE_HZ)
        assertEquals(960, VoiceAudioFormat.SAMPLES_PER_FRAME)
        assertEquals(1_920, VoiceAudioFormat.FRAME_BYTES)
    }

    @Test
    fun acceptsExactlyOnePcmFrame() {
        VoiceAudioFormat.requireValidFrame(ByteArray(VoiceAudioFormat.FRAME_BYTES))
    }

    @Test
    fun rejectsPartialOrOversizedFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            VoiceAudioFormat.requireValidFrame(ByteArray(VoiceAudioFormat.FRAME_BYTES - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceAudioFormat.requireValidFrame(ByteArray(VoiceAudioFormat.FRAME_BYTES + 1))
        }
    }
}
