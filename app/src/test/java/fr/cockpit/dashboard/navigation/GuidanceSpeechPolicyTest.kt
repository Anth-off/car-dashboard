package fr.cockpit.dashboard.navigation

import org.junit.Assert.*
import org.junit.Test

class GuidanceSpeechPolicyTest {
    private val step = RouteStep("Tournez à droite sur D 5", "turn", "right", "D 5", GeoPoint(48.0, 2.0), 1_000.0, 250.0, 30.0)

    @Test fun `a complete maneuver has only preparation and action despite GPS recovery and distance fluctuations`() {
        val policy = GuidanceSpeechPolicy()
        val spoken = mutableListOf<GuidanceAnnouncement>()
        listOf(1_500.0, 700.0, 501.0, 499.0, 301.0, 299.0, 250.0, 110.0, 100.0, 101.0, 70.0, 39.0, 42.0, 20.0).forEachIndexed { i, distance ->
            val now = i * 5_000L
            policy.maneuver(1, step, distance, 50f, now)?.let { spoken += it; policy.onSpoken(it, now) }
            if (i == 6) policy.event("gps-lost", now + 1_000)?.let { policy.onSpoken(it, now + 1_000) }
        }
        assertEquals(2, spoken.size)
        assertEquals(listOf(0, 1), spoken.map { it.phase })
        assertTrue(spoken.first().text.startsWith("Dans 300 mètres"))
        assertEquals(step.instruction, spoken.last().text)
    }

    @Test fun `motorway speed anticipates a junction and no name-only or straight continuation is spoken`() {
        val policy = GuidanceSpeechPolicy()
        assertNotNull(policy.maneuver(1, step, 600.0, 130f, 0))
        assertNull(policy.maneuver(2, step.copy(maneuverType = "new name"), 20.0, 50f, 0))
        assertNull(policy.maneuver(3, step.copy(maneuverType = "notification"), 20.0, 50f, 0))
        assertNull(policy.maneuver(4, step.copy(maneuverType = "continue", modifier = "straight"), 20.0, 50f, 0))
        assertNotNull(policy.maneuver(5, step.copy(maneuverType = "continue", modifier = "right"), 20.0, 50f, 0))
        assertNotNull(policy.maneuver(6, step.copy(maneuverType = "fork", modifier = "straight"), 20.0, 50f, 0))
    }

    @Test fun `a nearby next turn gets its action without repeating the same turn too soon`() {
        val policy = GuidanceSpeechPolicy()
        val preparation = policy.maneuver(1, step, 60.0, 50f, 0)!!
        policy.onSpoken(preparation, 0)
        assertNull(policy.maneuver(1, step, 30.0, 50f, 2_000))
        assertNotNull(policy.maneuver(2, step, 25.0, 50f, 2_000))
        assertNull(policy.maneuver(1, step, 20.0, 50f, 20_000))
    }

    @Test fun `GPS and off-route episodes cannot reset maneuver milestones or repeat continually`() {
        val policy = GuidanceSpeechPolicy()
        policy.onSpoken(policy.maneuver(1, step, 200.0, 50f, 0)!!, 0)
        val lost = policy.event("gps-lost", 10_000)!!
        policy.onSpoken(lost, 10_000)
        assertNull(policy.event("gps-lost", 80_000)) // Same outage, even long after cooldown.
        assertNull(policy.maneuver(1, step, 180.0, 50f, 20_000))
        assertNull(policy.event("gps-lost", 21_000)) // A brief recovery is not a new warning.
        val off = policy.event("off-route", 30_000)!!
        policy.onSpoken(off, 30_000)
        assertNull(policy.maneuver(1, step, 170.0, 50f, 40_000))
        assertNull(policy.event("off-route", 41_000))
        assertNotNull(policy.event("gps-lost", 80_000)) // Genuine later outage after recovery.
    }

    @Test fun `an expired or replaced pending cue has not consumed the useful maneuver`() {
        val policy = GuidanceSpeechPolicy()
        assertNotNull(policy.maneuver(1, step, 200.0, 50f, 0))
        val action = policy.maneuver(1, step, 30.0, 50f, 20_000)!!
        assertEquals(1, action.phase)
        assertNotNull(policy.maneuver(1, step, 20.0, 50f, 28_000))
        policy.onSpoken(action, 28_000)
        assertNull(policy.maneuver(1, step, 15.0, 50f, 40_000))
    }

    @Test fun `automatic route replacement keeps cadence while a new journey starts fresh`() {
        val policy = GuidanceSpeechPolicy()
        policy.onSpoken(policy.maneuver(5, step, 200.0, 50f, 0)!!, 0)
        policy.reset(keepCadence = true)
        assertNull(policy.maneuver(1, step, 200.0, 50f, 2_000))
        assertNotNull(policy.maneuver(1, step, 180.0, 50f, 8_000))
        policy.reset()
        assertNotNull(policy.maneuver(0, step, 200.0, 50f, 2_000))
    }

    @Test fun `arrival is said only by the actual terminal event`() {
        val policy = GuidanceSpeechPolicy()
        val arrival = step.copy(maneuverType = "arrive", instruction = "Vous êtes arrivé à destination")
        val preview = policy.maneuver(1, arrival, 200.0, 50f, 0)!!
        assertTrue(preview.text.contains("vous arriverez"))
        policy.onSpoken(preview, 0)
        assertNull(policy.maneuver(1, arrival, 20.0, 50f, 15_000))
        val terminal = policy.event("arrived", 15_000)!!
        policy.onSpoken(terminal, 15_000)
        assertEquals("Vous êtes arrivé à destination.", terminal.text)
        assertNull(policy.event("arrived", 90_000))
    }
}
