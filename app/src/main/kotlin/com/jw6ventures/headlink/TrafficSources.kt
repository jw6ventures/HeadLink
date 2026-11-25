package com.jw6ventures.headlink

/**
 * Live traffic from the providers the card chose ([TrafficSettings.selection]), asked in
 * its order of preference. With two, [TrafficTandem.COMBINED] asks both every time and
 * [TrafficTandem.FAILOVER] asks the second only when the first has no answer.
 *
 * Combined takes flow from one provider only, the first that answered (the preferred one
 * unless it failed): flow from both would double a jam's weight in [TrafficNogos].
 * Incidents and closures come from every provider that answered.
 *
 * Each client gates and counts itself, so a provider with no key, or at its limit, costs no
 * request. **Threading:** as each client's: off the main thread.
 */
class TrafficSources(
    private val settings: () -> TrafficSettings,
    private val clients: Map<TrafficProvider, TrafficProviderClient>,
) {
    fun fetch(box: TrafficBox): TrafficFetch {
        val s = settings()
        val order = s.selection.order
        if (!s.enabled) return TrafficFetch.Skipped(TrafficSkip.OFF, TrafficSkip.OFF.message(order.first()))
        val results = ArrayList<TrafficFetch>(order.size)
        for (p in order) {
            // One provider throwing must neither defeat failover nor lose the other's answer.
            val result = try {
                clients.getValue(p).fetch(box)
            } catch (e: RuntimeException) {
                TrafficFetch.Failed("traffic fetch threw ${e.javaClass.simpleName}", null, TrafficFailure.OTHER)
            }
            results += result
            if (s.tandem == TrafficTandem.FAILOVER && result is TrafficFetch.Fetched) break
        }
        val got = results.filterIsInstance<TrafficFetch.Fetched>().map { it.snapshot }
        if (got.isEmpty()) {
            // A failure makes the feed retry, a limit makes it wait; off or keyless stops it,
            // which happens only when no provider was usable.
            return results.firstOrNull { it is TrafficFetch.Failed }
                ?: results.firstOrNull { it is TrafficFetch.Skipped && it.why == TrafficSkip.LIMIT }
                ?: results.first()
        }
        if (got.size == 1) return results.first { it is TrafficFetch.Fetched }
        return TrafficFetch.Fetched(
            TrafficSnapshot(
                box,
                got.maxOf { it.receivedAtMs },
                got[0].flow,
                got.flatMap { it.incidents },
                got.flatMapTo(LinkedHashSet()) { it.providers },
            ),
        )
    }

    /**
     * The card's **Test**: each chosen provider with a key, in order, once. With one
     * provider chosen, its own answer; with two, both, named.
     */
    fun test(): TrafficCheck {
        val s = settings()
        if (!s.enabled) return TrafficCheck(false, "Live traffic is off")
        val keyed = s.keyed()
        if (keyed.isEmpty()) return TrafficCheck(false, "No API key is saved")
        if (s.selection.order.size == 1) return clients.getValue(s.selection.order[0]).test()
        val checks = keyed.map { it to clients.getValue(it).test() }
        return TrafficCheck(
            checks.all { it.second.works },
            checks.joinToString("; ") { (p, check) -> "${p.label}: ${check.message}" },
        )
    }
}
