package fr.cockpit.dashboard.map

import fr.cockpit.dashboard.navigation.GeoPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MapProjectionTest {
    @Test fun `projection round trips real positions and wraps the dateline`() {
        listOf(GeoPoint(48.8566, 2.3522), GeoPoint(-33.87, 151.21), GeoPoint(0.0, -179.9)).forEach { point ->
            val world = MapProjection.worldSize(15.5f, 2f)
            val projected = MapProjection.project(point, world)
            val restored = MapProjection.unproject(projected.x, projected.y, world)
            assertEquals(point.latitude, restored.latitude, 1e-8)
            assertEquals(point.longitude, restored.longitude, 1e-8)
            val wrapped = MapProjection.unproject(projected.x + world, projected.y, world)
            assertEquals(point.longitude, wrapped.longitude, 1e-8)
        }
    }

    @Test fun `overview leaves padding around every route point on narrow and wide screens`() {
        val route = listOf(GeoPoint(48.8566, 2.3522), GeoPoint(47.2184, -1.5536), GeoPoint(43.2965, 5.3698))
        listOf(360 to 520, 1400 to 480).forEach { (width, height) ->
            val padding = 48f
            val camera = MapProjection.fitRoute(route, width, height, 1f, padding)!!
            val world = MapProjection.worldSize(camera.zoom)
            val center = MapProjection.project(camera.center, world)
            route.forEach { point ->
                val position = MapProjection.project(point, world)
                assertTrue(abs(MapProjection.wrappedDelta(position.x, center.x, world)) <= width / 2 - padding + .01)
                assertTrue(abs(position.y - center.y) <= height / 2 - padding + .01)
            }
        }
    }

    @Test fun `a route crossing 180 degrees gets a close overview centered on the dateline`() {
        val camera = MapProjection.fitRoute(listOf(GeoPoint(-17.0, 179.5), GeoPoint(-17.1, -179.5)), 800, 500)!!
        assertTrue(abs(camera.center.longitude) > 179.9)
        assertTrue(camera.zoom > 8f)
        assertEquals(-17.05, camera.center.latitude, .001)
    }

    @Test fun `pinch focus remains over the same ground position at rotated headings`() {
        val original = MapCamera(GeoPoint(48.8566, 2.3522), 15f)
        listOf(0f, 45f, 90f, 275f).forEach { bearing ->
            val before = MapProjection.pan(original, 210f, -90f, 2f, bearing).center
            val zoomed = MapProjection.zoomAt(original, 16.7f, 610f, 210f, 800, 600, 2f, bearing)
            val after = MapProjection.pan(zoomed, 210f, -90f, 2f, bearing).center
            assertEquals(before.latitude, after.latitude, 1e-8)
            assertEquals(before.longitude, after.longitude, 1e-8)
        }
    }

    @Test fun `pan uses the displayed heading and remains valid at the poles`() {
        val camera = MapCamera(GeoPoint(0.0, 0.0), 10f)
        val northUp = MapProjection.pan(camera, 100f, 0f)
        val eastUp = MapProjection.pan(camera, 100f, 0f, bearing = 90f)
        assertTrue(northUp.center.longitude > 0)
        assertEquals(0.0, northUp.center.latitude, 1e-8)
        assertTrue(eastUp.center.latitude < 0)
        assertEquals(0.0, eastUp.center.longitude, 1e-8)
        assertTrue(MapProjection.isValid(MapProjection.pan(camera, 0f, -1e9f).center))
    }

    @Test fun `invalid routes have no camera and a single point has a bounded detailed zoom`() {
        assertNull(MapProjection.fitRoute(listOf(GeoPoint(Double.NaN, 2.0)), 800, 600))
        assertNull(MapProjection.fitRoute(listOf(GeoPoint(48.0, 2.0)), 0, 600))
        val camera = MapProjection.fitRoute(listOf(GeoPoint(48.0, 2.0)), 800, 600)!!
        assertEquals(18f, camera.zoom, 0f)
        assertEquals(48.0, camera.center.latitude, 1e-8)
        assertEquals(2.0, camera.center.longitude, 1e-8)
    }
}
