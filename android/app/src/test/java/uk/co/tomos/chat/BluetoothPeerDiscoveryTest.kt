package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BluetoothPeerDiscoveryTest {
    @Test
    fun fingerprintAdvertisementTokenFitsLegacyBlePayload() {
        val token = BluetoothPeerDiscovery.fingerprintToken("ABCD 1234")
        assertEquals(8, token.size)
        assertFalse(token.all { it == 0.toByte() })
    }

    @Test
    fun sameTrustedFingerprintProducesSameAdvertisementToken() {
        assertArrayEquals(
            BluetoothPeerDiscovery.fingerprintToken("ABCD 1234"),
            BluetoothPeerDiscovery.fingerprintToken("ABCD 1234")
        )
    }
}
