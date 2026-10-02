package fr.cockpit.dashboard.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteOptionSelectionTest {
    private val origin = GeoPoint(48.0, 2.0)

    @Test fun `alternatives remain available while positioning at departure`() {
        assertFalse(RouteOptionSelection.needsRefresh(origin, origin))
        assertFalse(RouteOptionSelection.needsRefresh(origin, GeoPoint(48.0005, 2.0)))
    }

    @Test fun `choices from an earlier departure must be refreshed after driving away`() {
        assertTrue(RouteOptionSelection.needsRefresh(origin, GeoPoint(48.002, 2.0)))
        assertTrue(RouteOptionSelection.needsRefresh(null, origin))
    }
}
