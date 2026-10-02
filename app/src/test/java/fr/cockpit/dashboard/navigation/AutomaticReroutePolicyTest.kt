package fr.cockpit.dashboard.navigation

import org.junit.Assert.*
import org.junit.Test

class AutomaticReroutePolicyTest {
    private fun AutomaticReroutePolicy.start(now: Long) = tryStart(
        offRoute = true, freshFix = true, simulation = false, nowMillis = now,
    )

    @Test fun `needs confirmed departure fresh GPS and real drive`() {
        val policy = AutomaticReroutePolicy()
        assertFalse(policy.tryStart(false, true, false, 1_000))
        assertFalse(policy.tryStart(true, false, false, 1_000))
        assertFalse(policy.tryStart(true, true, true, 1_000))
        assertTrue(policy.start(1_000))
    }

    @Test fun `slow request cannot spawn simultaneous network calls`() {
        val policy = AutomaticReroutePolicy()
        assertTrue(policy.start(1_000))
        assertFalse(policy.start(3_000))
        // Even after the normal cooldown, the first request is still in flight.
        assertFalse(policy.start(180_000))
    }

    @Test fun `successful reroute waits fifteen seconds before another attempt`() {
        val policy = AutomaticReroutePolicy()
        assertTrue(policy.start(1_000))
        policy.requestFinished(success = true, nowMillis = 5_000)
        assertFalse(policy.start(19_999))
        assertTrue(policy.start(20_000))
    }

    @Test fun `network failures back off exponentially up to two minutes`() {
        val policy = AutomaticReroutePolicy()
        var now = 1_000L
        assertTrue(policy.start(now))
        for (delay in listOf(30_000L, 60_000L, 120_000L, 120_000L)) {
            now += 1_000
            policy.requestFinished(success = false, nowMillis = now)
            assertFalse(policy.start(now + delay - 1))
            now += delay
            assertTrue(policy.start(now))
        }
    }

    @Test fun `success clears failure backoff`() {
        val policy = AutomaticReroutePolicy()
        assertTrue(policy.start(0))
        policy.requestFinished(success = false, nowMillis = 1_000)
        assertTrue(policy.start(31_000))
        policy.requestFinished(success = false, nowMillis = 32_000)
        assertTrue(policy.start(92_000))
        policy.requestFinished(success = true, nowMillis = 93_000)
        assertTrue(policy.start(108_000))
        policy.requestFinished(success = false, nowMillis = 109_000)
        assertFalse(policy.start(138_999))
        assertTrue(policy.start(139_000))
    }

    @Test fun `user can request immediately during backoff and blocks automatic duplicates`() {
        val policy = AutomaticReroutePolicy()
        assertTrue(policy.start(0))
        policy.requestFinished(success = false, nowMillis = 1_000)
        policy.requestStarted(2_000)
        assertFalse(policy.start(31_000))
        policy.requestFinished(success = true, nowMillis = 40_000)
        assertFalse(policy.start(54_999))
        assertTrue(policy.start(55_000))
    }

    @Test fun `new destination clears old cooldown and cancellation releases reservation`() {
        val policy = AutomaticReroutePolicy()
        assertTrue(policy.start(1_000))
        policy.requestCancelled()
        assertFalse(policy.start(2_000))
        assertTrue(policy.start(16_000))
        policy.requestFinished(success = false, nowMillis = 17_000)
        policy.reset()
        assertTrue(policy.start(18_000))
    }
}
