package fr.cockpit.dashboard.map

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import fr.cockpit.dashboard.navigation.GeoPoint

/** Phone wrapper suitable for Compose AndroidView; location is supplied by trip telemetry. */
class RouteMapView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null) : View(context, attributes) {
    private var renderer: RouteMapRenderer? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var bearing: Float? = null
    private var routePoints: List<GeoPoint> = emptyList()
    private var desiredZoom = 15

    init {
        contentDescription = "Carte OpenStreetMap, position GPS et itinéraire"
    }

    fun updateLocation(latitude: Double?, longitude: Double?, bearing: Float? = null) {
        this.latitude = latitude
        this.longitude = longitude
        this.bearing = bearing
        invalidate()
    }

    fun updateRoute(points: List<GeoPoint>) {
        routePoints = points
        invalidate()
    }

    fun updateState(latitude: Double?, longitude: Double?, routePoints: List<GeoPoint>, bearing: Float? = null) {
        this.latitude = latitude
        this.longitude = longitude
        this.routePoints = routePoints
        this.bearing = bearing
        invalidate()
    }

    fun zoomIn() = setZoom(desiredZoom + 1)
    fun zoomOut() = setZoom(desiredZoom - 1)

    private fun setZoom(value: Int) {
        desiredZoom = value.coerceIn(3, 18)
        renderer?.zoom = desiredZoom
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = RouteMapRenderer(context) { postInvalidateOnAnimation() }.also { it.zoom = desiredZoom }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer?.render(canvas, width, height, latitude, longitude, routePoints, bearing)
    }

    override fun onDetachedFromWindow() {
        renderer?.close()
        renderer = null
        super.onDetachedFromWindow()
    }
}
