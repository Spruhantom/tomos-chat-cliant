package uk.co.tomos.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerConnectionRoleTest {
    @Test
    fun bothDevicesDeriveOppositeRolesFromTheSameFingerprints() {
        val a = "0000 AAAA"
        val b = "FFFF BBBB"
        assertTrue(PeerConnectionRole.shouldInitiate(a, b))
        assertFalse(PeerConnectionRole.shouldInitiate(b, a))
    }

    @Test(expected = IllegalArgumentException::class)
    fun identicalFingerprintsAreRejected() {
        PeerConnectionRole.shouldInitiate("SAME", "SAME")
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankFingerprintIsRejected() {
        PeerConnectionRole.shouldInitiate("", "REMOTE")
    }
}
