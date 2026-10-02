package fr.cockpit.dashboard.navigation

/**
 * Limits automatic requests to confirmed departures from a route with a fresh physical GPS fix.
 * Times are monotonic elapsed-realtime values, never wall-clock or GPS timestamps. Reserving a
 * request prevents subsequent fixes from starting duplicate calls while the network is slow.
 */
internal class AutomaticReroutePolicy(
    private val minimumIntervalMillis: Long = 15_000,
    private val initialFailureDelayMillis: Long = 30_000,
    private val maximumFailureDelayMillis: Long = 120_000,
) {
    private var requestInFlight = false
    private var nextAllowedMillis = 0L
    private var failures = 0

    fun tryStart(offRoute: Boolean, freshFix: Boolean, simulation: Boolean, nowMillis: Long): Boolean {
        if (!offRoute || !freshFix || simulation || requestInFlight || nowMillis < nextAllowedMillis) return false
        requestStarted(nowMillis)
        return true
    }

    /** Explicit user requests may bypass the cooldown; they still prevent automatic duplicates. */
    fun requestStarted(nowMillis: Long) {
        requestInFlight = true
        nextAllowedMillis = nowMillis + minimumIntervalMillis
    }

    fun requestFinished(success: Boolean, nowMillis: Long) {
        requestInFlight = false
        if (success) {
            failures = 0
            nextAllowedMillis = maxOf(nextAllowedMillis, nowMillis + minimumIntervalMillis)
        } else {
            failures = (failures + 1).coerceAtMost(16)
            val delay = (initialFailureDelayMillis * (1L shl (failures - 1)))
                .coerceAtMost(maximumFailureDelayMillis)
            nextAllowedMillis = nowMillis + maxOf(minimumIntervalMillis, delay)
        }
    }

    fun requestCancelled() { requestInFlight = false }

    fun reset() {
        requestInFlight = false
        nextAllowedMillis = 0L
        failures = 0
    }
}
