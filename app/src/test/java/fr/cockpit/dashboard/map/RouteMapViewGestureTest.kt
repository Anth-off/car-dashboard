package fr.cockpit.dashboard.map

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteMapViewGestureTest {
    @Test fun invalidHostGesturesLeaveTheCameraFollowingGps() {
        val map = positionedMap()
        val initial = map.mapState
        assertFalse(map.panBy(Float.NaN, 1f))
        assertFalse(map.panBy(1f, Float.POSITIVE_INFINITY))
        assertFalse(map.zoomAt(Float.NaN, 10f, 2f))
        assertFalse(map.zoomAt(10f, 10f, Float.POSITIVE_INFINITY))
        assertFalse(map.zoomAt(10f, 10f, 0f))
        assertFalse(map.zoomAt(10f, 10f, -1f))
        assertEquals(initial, map.mapState)
    }

    @Test fun hostZoomWithoutFocusAndPanCanBeFollowedByRecentering() {
        val map = positionedMap()
        val initialZoom = map.mapState.zoom
        assertTrue(map.zoomAt(-1f, -1f, 2f))
        assertEquals(initialZoom + 1f, map.mapState.zoom, .001f)
        assertFalse(map.mapState.following)
        assertTrue(map.panBy(80f, -30f))
        map.recenter()
        assertTrue(map.mapState.following)
        assertFalse(map.mapState.overview)
    }

    @Test fun extremeZoomFactorsRemainWithinSupportedMapLevels() {
        val map = positionedMap()
        assertTrue(map.zoomAt(20_000f, 20_000f, Float.MAX_VALUE))
        assertEquals(MapProjection.MAX_ZOOM, map.mapState.zoom, .001f)
        assertTrue(map.zoomAt(-1f, -1f, Float.MIN_VALUE))
        assertEquals(MapProjection.MIN_ZOOM, map.mapState.zoom, .001f)
    }

    private fun positionedMap() = RouteMapView(ApplicationProvider.getApplicationContext()).apply {
        layout(0, 0, 800, 400)
        updateLocation(48.4149, 1.8802)
    }
}
