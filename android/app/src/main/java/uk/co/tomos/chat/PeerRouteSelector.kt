package uk.co.tomos.chat

/**
 * Automatic, direct-only route selection. A route is eligible only after a
 * transport adapter has discovered it and completed a bounded reachability
 * probe. Identity authentication and the Noise handshake still happen after
 * selection; this class never treats discovery as peer authentication.
 */
enum class PeerTransportKind {
    WIFI_LAN,
    WIFI_DIRECT,
    BLUETOOTH_CLASSIC,
    BLUETOOTH_LE,
    IPV6_DIRECT
}

enum class PeerTrafficClass { CHAT, VOICE, FILE_TRANSFER, BACKGROUND_SYNC }

data class PeerRouteCandidate(
    val id: String,
    val transport: PeerTransportKind,
    val measuredLatencyMs: Int,
    val reliability: Double,
    val throughputKbps: Int,
    val batteryCost: Double,
    val reachable: Boolean,
    val requiresMeteredNetwork: Boolean = false
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(measuredLatencyMs >= 0)
        require(reliability in 0.0..1.0)
        require(throughputKbps >= 0)
        require(batteryCost in 0.0..1.0)
    }
}

data class PeerRouteDecision(
    val selected: PeerRouteCandidate?,
    val ranked: List<PeerRouteCandidate>,
    val reason: String
)

/**
 * Ranks only currently reachable candidates. Scores are deliberately
 * deterministic and activity-specific; callers re-evaluate after link changes
 * or failed connection attempts rather than pinning a stale route.
 */
object PeerRouteSelector {
    fun select(
        candidates: List<PeerRouteCandidate>,
        traffic: PeerTrafficClass,
        allowMetered: Boolean = true,
        excludedRouteIds: Set<String> = emptySet()
    ): PeerRouteDecision {
        val eligible = candidates
            .filter { it.reachable && it.id !in excludedRouteIds }
            .filter { allowMetered || !it.requiresMeteredNetwork }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<PeerRouteCandidate> { score(it, traffic) }
                    .thenBy { it.measuredLatencyMs }
                    .thenByDescending { it.reliability }
                    .thenBy { it.id }
            )

        val selected = eligible.firstOrNull()
        val reason = when {
            selected == null -> "No reachable direct route is currently available."
            eligible.size == 1 -> "Selected the only reachable direct route."
            else -> "Selected the best measured route for $traffic."
        }
        return PeerRouteDecision(selected, eligible, reason)
    }

    private fun score(route: PeerRouteCandidate, traffic: PeerTrafficClass): Double {
        val latency = 1.0 / (1.0 + route.measuredLatencyMs.coerceAtMost(60_000))
        val throughput = route.throughputKbps.toDouble() /
            (route.throughputKbps.toDouble() + 2_000.0).coerceAtLeast(1.0)
        val reliability = route.reliability
        val battery = 1.0 - route.batteryCost
        val transportBonus = when (route.transport) {
            PeerTransportKind.WIFI_LAN -> 0.08
            PeerTransportKind.WIFI_DIRECT -> 0.07
            PeerTransportKind.BLUETOOTH_CLASSIC -> 0.02
            PeerTransportKind.BLUETOOTH_LE -> -0.08
            PeerTransportKind.IPV6_DIRECT -> 0.05
        }

        val weighted = when (traffic) {
            PeerTrafficClass.CHAT ->
                latency * 0.30 + reliability * 0.48 + throughput * 0.08 + battery * 0.14
            PeerTrafficClass.VOICE ->
                latency * 0.48 + reliability * 0.42 + throughput * 0.06 + battery * 0.04
            PeerTrafficClass.FILE_TRANSFER ->
                throughput * 0.58 + reliability * 0.25 + latency * 0.07 + battery * 0.10
            PeerTrafficClass.BACKGROUND_SYNC ->
                battery * 0.45 + reliability * 0.35 + latency * 0.10 + throughput * 0.10
        }
        return weighted + transportBonus
    }
}
