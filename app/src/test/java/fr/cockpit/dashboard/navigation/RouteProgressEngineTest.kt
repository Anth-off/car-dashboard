package fr.cockpit.dashboard.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteProgressEngineTest {
    private fun route(points: List<GeoPoint>, turns: List<Int>): Route {
        val offsets = RouteProgressEngine.cumulativeDistances(points)
        return Route(points, turns.mapIndexed { index, pointIndex ->
            RouteStep("Instruction $index", if (index == turns.lastIndex) "arrive" else "turn", "right", "Rue", points[pointIndex], offsets[pointIndex], 100.0, 30.0)
        }, offsets.last(), 90.0)
    }

    @Test fun `arriving near the start of a round trip is not an arrival`() {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.001, 2.0), GeoPoint(48.001, 2.001), GeoPoint(48.0, 2.001), GeoPoint(48.0, 2.0))
        val engine = RouteProgressEngine(route(points, listOf(0, 1, 2, 3, 4)))
        val first = engine.update(points.first(), 3.0, 1_000)
        assertFalse(first.arrived)
        assertTrue(first.remainingMeters > 350)
        assertEquals(1, first.nextStepIndex)
    }

    @Test fun `turn progression and arrival require traversing the route`() {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.001, 2.0), GeoPoint(48.002, 2.0), GeoPoint(48.003, 2.0))
        val engine = RouteProgressEngine(route(points, listOf(0, 1, 3)))
        val start = engine.update(points[0], 3.0, 1_000)
        assertEquals(1, start.nextStepIndex)
        assertFalse(start.arrived)
        val turned = engine.update(points[1], 3.0, 11_000)
        assertEquals(2, turned.nextStepIndex)
        assertTrue(turned.remainingMeters < start.remainingMeters)
        engine.update(points[2], 3.0, 21_000)
        val end = engine.update(points[3], 3.0, 31_000)
        assertTrue(end.arrived)
        assertEquals(0.0, end.remainingMeters, 0.1)
    }

    @Test fun `persistent off route fixes never advance distance or announce arrival`() {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.005, 2.0), GeoPoint(48.01, 2.0))
        val engine = RouteProgressEngine(route(points, listOf(0, 1, 2)))
        val initial = engine.update(points[0], 3.0, 1_000)
        var offRoute = initial
        repeat(3) { index -> offRoute = engine.update(GeoPoint(49.0, 3.0), 3.0, 2_000L + index * 1000) }
        assertTrue(offRoute.offRoute)
        assertFalse(offRoute.arrived)
        assertEquals(initial.remainingMeters, offRoute.remainingMeters, 0.1)
        assertFalse(engine.update(points[0], 3.0, 6_000).offRoute)
    }

    @Test fun `teleport to far future route segment cannot skip navigation`() {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.02, 2.0), GeoPoint(48.04, 2.0))
        val engine = RouteProgressEngine(route(points, listOf(0, 1, 2)))
        val initial = engine.update(points.first(), 3.0, 1_000)
        val jumped = engine.update(points.last(), 3.0, 2_000)
        assertFalse(jumped.arrived)
        assertEquals(initial.remainingMeters, jumped.remainingMeters, 0.1)
    }
}
