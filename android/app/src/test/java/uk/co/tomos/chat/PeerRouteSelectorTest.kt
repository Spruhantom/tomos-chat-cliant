package uk.co.tomos.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRouteSelectorTest {
    private fun route(
        id: String,
        transport: PeerTransportKind,
        latency: Int,
        reliability: Double,
        throughput: Int,
        battery: Double = 0.2,
        reachable: Boolean = true,
        metered: Boolean = false
    ) = PeerRouteCandidate(id, transport, latency, reliability, throughput, battery, reachable, metered)

    @Test
    fun voicePrefersLowLatencyReliablePath() {
        val wifi = route("wifi", PeerTransportKind.WIFI_LAN, 12, 0.99, 80_000)
        val bluetooth = route("bt", PeerTransportKind.BLUETOOTH_CLASSIC, 38, 0.97, 1_800)
        assertEquals("wifi", PeerRouteSelector.select(listOf(bluetooth, wifi), PeerTrafficClass.VOICE).selected?.id)
    }

    @Test
    fun fileTransferPrefersHighThroughputPath() {
        val wifi = route("wifi", PeerTransportKind.WIFI_LAN, 24, 0.98, 100_000)
        val bluetooth = route("bt", PeerTransportKind.BLUETOOTH_CLASSIC, 8, 0.99, 2_000)
        assertEquals("wifi", PeerRouteSelector.select(listOf(bluetooth, wifi), PeerTrafficClass.FILE_TRANSFER).selected?.id)
    }

    @Test
    fun unreachableAndExcludedRoutesAreNeverSelected() {
        val dead = route("dead", PeerTransportKind.WIFI_LAN, 1, 1.0, 100_000, reachable = false)
        val excluded = route("excluded", PeerTransportKind.IPV6_DIRECT, 2, 1.0, 100_000)
        val result = PeerRouteSelector.select(
            listOf(dead, excluded),
            PeerTrafficClass.CHAT,
            excludedRouteIds = setOf("excluded")
        )
        assertNull(result.selected)
        assertTrue(result.ranked.isEmpty())
    }

    @Test
    fun meteredRoutesCanBeRejectedByPolicy() {
        val metered = route("cellular", PeerTransportKind.IPV6_DIRECT, 20, 0.99, 50_000, metered = true)
        val wifi = route("wifi", PeerTransportKind.WIFI_LAN, 30, 0.98, 20_000)
        assertEquals("wifi", PeerRouteSelector.select(
            listOf(metered, wifi), PeerTrafficClass.CHAT, allowMetered = false
        ).selected?.id)
    }

    @Test
    fun duplicateRouteIdsKeepTheBestMeasuredCandidate() {
        val poor = route("same", PeerTransportKind.WIFI_LAN, 300, 0.70, 1_000)
        val good = route("same", PeerTransportKind.WIFI_LAN, 8, 0.99, 80_000)
        val result = PeerRouteSelector.select(listOf(poor, good), PeerTrafficClass.CHAT)
        assertEquals(8, result.selected?.measuredLatencyMs)
    }

    @Test
    fun bluetoothLeIsNotSelectedForVoiceMedia() {
        val ble = route("ble", PeerTransportKind.BLUETOOTH_LE, 4, 0.99, 500)
        assertNull(PeerRouteSelector.select(listOf(ble), PeerTrafficClass.VOICE).selected)
    }

    @Test
    fun directPeerRoleIsComplementaryForBothDevices() {
        val first = "0000 AAAA"
        val second = "FFFF BBBB"
        assertTrue(PeerConnectionRole.shouldInitiate(first, second))
        assertTrue(!PeerConnectionRole.shouldInitiate(second, first))
    }

    @Test
    fun deterministicTieBreakUsesRouteId() {
        val a = route("a", PeerTransportKind.WIFI_LAN, 20, 0.98, 20_000)
        val b = route("b", PeerTransportKind.WIFI_LAN, 20, 0.98, 20_000)
        assertEquals("a", PeerRouteSelector.select(listOf(b, a), PeerTrafficClass.CHAT).selected?.id)
    }
}
