package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PeerEndpointCandidateTest {
    private fun sample(ip: String = "8.8.8.8") = PeerEndpointCandidate(
        transport = PeerEndpointCandidate.Transport.TCP,
        issuedAtSeconds = 1_000,
        expiresAtSeconds = 1_240,
        deviceId = ByteArray(16) { 7 },
        nonce = ByteArray(16) { 9 },
        address = InetAddress.getByName(ip),
        port = 4242,
        signature = ByteArray(64) { 3 }
    )

    @Test fun signsAndVerifiesCandidateAgainstTrustedContactIdentity() {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val id = DeviceSignatureCodec.deviceId(pair.public.encoded)
        val unsigned = sample().copy(deviceId = id)
        val signed = unsigned.sign(pair.private)
        assert(signed.verifySignature(pair.public.encoded, id))
        assert(!signed.verifySignature(pair.public.encoded, ByteArray(16)))
    }

    @Test fun roundTripsIpv4CandidateAndRustWireLayout() {
        val candidate = sample()
        val bytes = candidate.encode()
        assertEquals(123, bytes.size)
        val decoded = PeerEndpointCandidate.decode(bytes, 1_100)
        assertEquals(candidate.transport, decoded.transport)
        assertEquals(candidate.issuedAtSeconds, decoded.issuedAtSeconds)
        assertEquals(candidate.expiresAtSeconds, decoded.expiresAtSeconds)
        assertEquals(candidate.address, decoded.address)
        assertEquals(candidate.port, decoded.port)
        assertArrayEquals(candidate.deviceId, decoded.deviceId)
        assertArrayEquals(candidate.nonce, decoded.nonce)
        assertArrayEquals(candidate.signature, decoded.signature)
    }

    @Test fun roundTripsIpv6Candidate() {
        val candidate = sample("2606:4700:4700::1111")
        val bytes = candidate.encode()
        assertEquals(135, bytes.size)
        assertEquals(candidate.address, PeerEndpointCandidate.decode(bytes, 1_100).address)
    }

    @Test fun rejectsPrivateAddressesAndInvalidExpiry() {
        listOf("10.0.0.1", "192.168.1.2", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "224.0.0.1", "::1", "fe80::1", "fc00::1", "2001:db8::1")
            .forEach { ip ->
                assertThrows(IllegalArgumentException::class.java) { sample(ip).encode() }
            }
        assertThrows(IllegalArgumentException::class.java) {
            PeerEndpointCandidate.decode(sample().encode(), 1_240)
        }
    }

    @Test fun rejectsTamperedHeaderLengthAndOversizedLifetime() {
        val valid = sample().encode()
        assertThrows(IllegalArgumentException::class.java) {
            PeerEndpointCandidate.decode(valid.copyOf().also { it[0] = 0 }, 1_100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerEndpointCandidate.decode(valid + byteArrayOf(0), 1_100)
        }
        val longLife = sample().copy(expiresAtSeconds = 1_301)
        assertThrows(IllegalArgumentException::class.java) { longLife.encode() }
    }

    @Test fun signatureCoversEveryFieldExceptDetachedSignature() {
        val value = sample()
        val signed = value.signingBytes()
        val encoded = value.encode()
        assertArrayEquals(signed, encoded.copyOfRange(0, signed.size))
        assertEquals(64, encoded.size - signed.size)
        assertEquals(4242, ByteBuffer.wrap(signed).order(ByteOrder.BIG_ENDIAN).getShort(signed.size - 2).toInt() and 0xffff)
    }
}
