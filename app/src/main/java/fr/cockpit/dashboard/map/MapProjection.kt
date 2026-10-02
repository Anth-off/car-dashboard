package fr.cockpit.dashboard.map

import fr.cockpit.dashboard.navigation.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.sin
import kotlin.math.tan

/** A camera independent of the GPS fix: inspecting the map never moves the vehicle marker. */
data class MapCamera(val center: GeoPoint, val zoom: Float)

internal data class WorldPoint(val x: Double, val y: Double)

/** Web Mercator math shared by gestures, route framing and rendering, including the dateline. */
object MapProjection {
    const val MIN_ZOOM = 0f
    const val MAX_ZOOM = 18f
    private const val MAX_LATITUDE = 85.05112878
    private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686

    fun isValid(point: GeoPoint): Boolean = point.latitude.isFinite() && point.longitude.isFinite()
        && point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

    fun worldSize(zoom: Float, density: Float = 1f): Double = 256.0 * density * 2.0.pow(zoom.toDouble())

    internal fun project(point: GeoPoint, world: Double): WorldPoint {
        val latitude = point.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180
        return WorldPoint((point.longitude + 180) / 360 * world,
            (1 - ln(tan(PI / 4 + latitude / 2)) / PI) / 2 * world)
    }

    internal fun unproject(x: Double, y: Double, world: Double): GeoPoint = GeoPoint(
        atan(sinh(PI * (1 - 2 * y.coerceIn(0.0, world) / world))) * 180 / PI,
        ((x / world * 360) % 360 + 360) % 360 - 180,
    )

    internal fun wrappedDelta(value: Double, center: Double, world: Double): Double =
        ((value - center + world / 2) % world + world) % world - world / 2

    fun metersPerPixel(latitude: Double, zoom: Float, density: Float = 1f): Double =
        cos(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180) *
            EARTH_CIRCUMFERENCE_METERS / worldSize(zoom, density)

    /** Scroll distances are screen pixels; bearing is the clockwise direction kept at the top. */
    fun pan(camera: MapCamera, distanceX: Float, distanceY: Float, density: Float = 1f, bearing: Float = 0f): MapCamera {
        val world = worldSize(camera.zoom, density)
        val center = project(camera.center, world)
        val angle = bearing * PI / 180
        return camera.copy(center = unproject(
            center.x + distanceX * cos(angle) - distanceY * sin(angle),
            center.y + distanceX * sin(angle) + distanceY * cos(angle), world))
    }

    /** Keeps the ground point under a pinch or double-tap fixed when the zoom changes. */
    fun zoomAt(
        camera: MapCamera, zoom: Float, focusX: Float, focusY: Float, width: Int, height: Int,
        density: Float = 1f, bearing: Float = 0f,
    ): MapCamera {
        val nextZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val ratio = 2.0.pow((nextZoom - camera.zoom).toDouble())
        val dx = (focusX - width / 2f) * (1 - 1 / ratio)
        val dy = (focusY - height / 2f) * (1 - 1 / ratio)
        return pan(camera, dx.toFloat(), dy.toFloat(), density, bearing).copy(zoom = nextZoom)
    }

    /** Frames the shortest longitude interval, so a route across 180° does not span the world. */
    fun fitRoute(
        points: List<GeoPoint>, width: Int, height: Int, density: Float = 1f,
        padding: Float = 52f * density,
    ): MapCamera? {
        val valid = points.filter(::isValid)
        if (valid.isEmpty() || width <= 0 || height <= 0) return null
        val projected = valid.map { project(it, 1.0) }
        val sortedX = projected.map { it.x }.sorted()
        var largestGap = -1.0
        var first = sortedX.first()
        for (index in sortedX.indices) {
            val next = if (index == sortedX.lastIndex) sortedX.first() + 1 else sortedX[index + 1]
            if (next - sortedX[index] > largestGap) {
                largestGap = next - sortedX[index]
                first = next % 1.0
            }
        }
        val minY = projected.minOf { it.y }
        val maxY = projected.maxOf { it.y }
        val spanX = (1 - largestGap).coerceAtLeast(0.0)
        val availableWidth = (width - 2 * padding).coerceAtLeast(width * .25f)
        val availableHeight = (height - 2 * padding).coerceAtLeast(height * .25f)
        val zoomX = if (spanX < 1e-12) MAX_ZOOM.toDouble() else log2(availableWidth / (256 * density * spanX))
        val zoomY = if (maxY - minY < 1e-12) MAX_ZOOM.toDouble() else log2(availableHeight / (256 * density * (maxY - minY)))
        return MapCamera(unproject(first + spanX / 2, (minY + maxY) / 2, 1.0),
            minOf(zoomX, zoomY).toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM))
    }
}
