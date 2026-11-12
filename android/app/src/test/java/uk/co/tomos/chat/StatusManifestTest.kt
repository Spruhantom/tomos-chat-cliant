package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StatusManifestTest {
    private fun sample() = StatusManifest(
        statusId = ByteArray(16) { 1 },
        authorDeviceId = ByteArray(16) { 2 },
        createdAtSeconds = 1_000,
        expiresAtSeconds = 87_400,
        audience = StatusManifest.Audience.SELECTED_CONTACTS,
        audienceDigest = ByteArray(32) { 3 },
        contentType = StatusManifest.ContentType.TEXT,
        ciphertextLength = 128,
        ciphertextDigest = ByteArray(32) { 4 },
        signature = ByteArray(64) { 5 }
    )

    @Test fun roundTripsManifestWithRustWireLength() {
        val value = sample()
        val encoded = value.encode()
        assertEquals(185, encoded.size)
        val decoded = StatusManifest.decode(encoded, 1_100)
        assertEquals(value.audience, decoded.audience)
        assertEquals(value.contentType, decoded.contentType)
        assertEquals(value.createdAtSeconds, decoded.createdAtSeconds)
        assertEquals(value.expiresAtSeconds, decoded.expiresAtSeconds)
        assertEquals(value.ciphertextLength, decoded.ciphertextLength)
        assertArrayEquals(value.statusId, decoded.statusId)
        assertArrayEquals(value.authorDeviceId, decoded.authorDeviceId)
        assertArrayEquals(value.audienceDigest, decoded.audienceDigest)
        assertArrayEquals(value.ciphertextDigest, decoded.ciphertextDigest)
        assertArrayEquals(value.signature, decoded.signature)
    }

    @Test fun rejectsAudienceMismatchAndNon24HourLifetime() {
        assertThrows(IllegalArgumentException::class.java) {
            sample().copy(audienceDigest = ByteArray(32)).encode()
        }
        assertThrows(IllegalArgumentException::class.java) {
            sample().copy(expiresAtSeconds = 87_399).encode()
        }
    }

    @Test fun rejectsExpiredAndMalformedManifests() {
        assertThrows(IllegalArgumentException::class.java) {
            StatusManifest.decode(sample().encode(), 87_400)
        }
        val badMagic = sample().encode().also { it[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) { StatusManifest.decode(badMagic, 1_100) }
        assertThrows(IllegalArgumentException::class.java) {
            StatusManifest.decode(sample().encode().copyOf(184), 1_100)
        }
    }

    @Test fun allTrustedContactsUseZeroAudienceDigest() {
        val all = sample().copy(
            audience = StatusManifest.Audience.ALL_TRUSTED_CONTACTS,
            audienceDigest = ByteArray(32)
        )
        assertEquals(all.audience, StatusManifest.decode(all.encode(), 1_100).audience)
    }
}
