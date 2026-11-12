package uk.co.tomos.chat

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class InternetPeerDiscoveryTest {
    private fun signingKeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    @Test fun rendezvousQueryUsesFixedBoundedWireRecord() {
        val query = RendezvousQuery(ByteArray(16) { 1 }, ByteArray(16) { 2 },
            ByteArray(16) { 3 }, 1234)
        val encoded = query.encode()
        assertEquals(59, encoded.size)
        assertEquals(query, RendezvousQuery.decode(encoded))\n        assertEquals(0x54.toByte(), encoded[0])
        assertEquals(2.toByte(), encoded[2])
    }

    @Test fun signedUdpProbeRoundTripsAndBindsTrustedDevice() {
        val pair = signingKeyPair()
        val deviceId = DeviceSignatureCodec.deviceId(pair.public.encoded)
        val probe = HolePunchProbe(ByteArray(16) { 1 }, ByteArray(16) { 2 },
            deviceId, 1_000, ByteArray(64)).sign(pair.private)
        val decoded = HolePunchProbe.decode(probe.encode())
        assertTrue(decoded.verify(1_001, pair.public.encoded, deviceId))
        assertFalse(decoded.verify(1_001, pair.public.encoded, ByteArray(16) { 9 }))
        assertFalse(decoded.verify(1_400, pair.public.encoded, deviceId))
    }

    @Test fun ipv6RoutesArePreferredAndRetryBackoffIsBounded() {
        val v4 = PeerEndpointCandidate(PeerEndpointCandidate.Transport.UDP, 100, 200,
            ByteArray(16) { 1 }, ByteArray(16) { 2 }, InetAddress.getByName("8.8.8.8"), 4000, ByteArray(64))
        val v6 = PeerEndpointCandidate(PeerEndpointCandidate.Transport.UDP, 100, 200,
            ByteArray(16) { 1 }, ByteArray(16) { 3 }, InetAddress.getByName("2606:4700:4700::1111"), 4000, ByteArray(64))
        assertTrue(PeerRoutePolicy.prioritise(listOf(v4, v6)).first().address is java.net.Inet6Address)
        assertTrue(PeerRoutePolicy.reconnectDelayMillis(100, 42) <= 75_000)
    }

    @Test fun natPmpLeaseIsCapped() {
        val gateway = InetAddress.getByName("192.168.1.1") as java.net.Inet4Address
        val client = NatPmpClient(gateway)
        assertThrows(IllegalArgumentException::class.java) { client.mapUdp(4000, 4000, 601) }
    }
}
