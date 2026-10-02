package fr.cockpit.dashboard.map

import fr.cockpit.dashboard.navigation.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class DrivingMapCameraTest {
    @Test fun `real GPS position is anchored below centre at every heading and screen shape`() {
        val position = GeoPoint(48.8566, 2.3522)
        listOf(360 to 640, 800 to 320).forEach { (width, height) ->
            listOf(1f, 2f, 3f).forEach { density ->
                listOf(0f, 45f, 90f, 180f, 275f).forEach { bearing ->
                    val camera = DrivingMapCamera.follow(position, 16.8f, width, height, density, bearing)
                    val world = MapProjection.worldSize(camera.zoom, density)
                    val gps = MapProjection.project(position, world)
                    val center = MapProjection.project(camera.center, world)
                    val dx = MapProjection.wrappedDelta(gps.x, center.x, world)
                    val dy = gps.y - center.y
                    val angle = bearing * PI / 180
                    val screenX = dx * cos(angle) + dy * sin(angle) + width / 2.0
                    val screenY = -dx * sin(angle) + dy * cos(angle) + height / 2.0
                    assertEquals(width * .5, screenX, .01)
                    assertEquals(height * .62, screenY, .01)
                }
            }
        }
    }

    @Test fun `following across the dateline keeps the real fix in view`() {
        val position = GeoPoint(0.0, 179.9999)
        val camera = DrivingMapCamera.follow(position, 15f, 800, 600, bearing = 90f)
        assertTrue(MapProjection.isValid(camera.center))
        assertTrue(camera.center.longitude < -179.9)
        val world = MapProjection.worldSize(camera.zoom)
        val fix = MapProjection.project(position, world)
        val center = MapProjection.project(camera.center, world)
        assertEquals(-72.0, MapProjection.wrappedDelta(fix.x, center.x, world), .01)
    }

    @Test fun `cruising zoom widens with speed and progressively closes in on turns`() {
        val city = DrivingMapCamera.recommendedZoom(30f, 1500.0)
        val motorway = DrivingMapCamera.recommendedZoom(130f, 1500.0)
        assertTrue(city > motorway)
        val approach = listOf(500.0, 400.0, 250.0, 100.0, 20.0).map {
            DrivingMapCamera.recommendedZoom(90f, it)
        }
        approach.zipWithNext().forEach { (farther, nearer) -> assertTrue(nearer > farther) }
        assertTrue(approach.last() > 17.4f)
        assertTrue(DrivingMapCamera.recommendedZoom(300f, 0.0) <= MapProjection.MAX_ZOOM)
    }

    @Test fun `missing and invalid telemetry never produces an invalid camera zoom`() {
        listOf<Float?>(null, Float.NaN, Float.POSITIVE_INFINITY, -20f, 0f, 150f).forEach { speed ->
            listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 100.0).forEach { distance ->
                val zoom = DrivingMapCamera.recommendedZoom(speed, distance)
                assertTrue(zoom.isFinite())
                assertTrue(zoom in 15.2f..17.7f)
            }
        }
    }

    @Test fun `north crossing rotates two degrees instead of spinning around`() {
        assertEquals(0f, DrivingMapCamera.interpolateBearing(359f, 1f, .5f), .001f)
        assertEquals(0f, DrivingMapCamera.interpolateBearing(1f, 359f, .5f), .001f)
        assertEquals(2f, DrivingMapCamera.bearingDifference(359f, 1f), .001f)
        assertEquals(110f, DrivingMapCamera.interpolateBearing(100f, 140f, .25f), .001f)
    }
}
