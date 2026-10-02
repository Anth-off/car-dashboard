package fr.cockpit.dashboard.map

import fr.cockpit.dashboard.navigation.GeoPoint
import kotlin.math.abs

/** Camera decisions are visual only: they never change or extrapolate the GPS position. */
object DrivingMapCamera {
    /** Keep more road ahead visible, with room for a maneuver card and the arrival strip. */
    fun follow(
        position: GeoPoint, zoom: Float, width: Int, height: Int,
        density: Float = 1f, bearing: Float = 0f,
    ): MapCamera {
        val camera = MapCamera(position, zoom.coerceIn(MapProjection.MIN_ZOOM, MapProjection.MAX_ZOOM))
        if (width <= 0 || height <= 0) return camera
        return MapProjection.pan(camera, 0f, -height * .12f, density, bearing)
    }

    /** Widen at speed and progressively reveal the upcoming junction at a closer scale. */
    fun recommendedZoom(speedKmh: Float?, distanceToTurnMeters: Double?): Float {
        val speed = speedKmh?.takeIf { it.isFinite() && it >= 0f }?.coerceAtMost(150f) ?: 0f
        val cruisingZoom = (17.2f - speed / 70f).coerceAtLeast(15.2f)
        val distance = distanceToTurnMeters?.takeIf { it.isFinite() && it >= 0.0 } ?: return cruisingZoom
        val approach = (1.0 - distance / 500.0).coerceIn(0.0, 1.0).toFloat()
        return cruisingZoom + (17.7f - cruisingZoom) * approach * approach
    }

    /** Follow the short arc, including a course change from 359° to 1°. */
    fun interpolateBearing(from: Float, to: Float, fraction: Float): Float {
        val delta = ((to - from + 540f) % 360f) - 180f
        return ((from + delta * fraction.coerceIn(0f, 1f)) % 360f + 360f) % 360f
    }

    fun bearingDifference(first: Float, second: Float): Float =
        abs(((second - first + 540f) % 360f) - 180f)
}
