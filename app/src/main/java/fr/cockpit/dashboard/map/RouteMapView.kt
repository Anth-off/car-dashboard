package fr.cockpit.dashboard.map

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import fr.cockpit.dashboard.navigation.GeoPoint
import kotlin.math.log2

/** UI state is exposed so Compose controls accurately reflect gestures as well as button presses. */
data class MapViewState(
    val following: Boolean = true,
    val overview: Boolean = false,
    val headingUp: Boolean = false,
    val zoom: Float = 15.5f,
)

/** Phone map with independent camera, focal-point pinch zoom and a persistent GPS marker. */
class RouteMapView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null) : View(context, attributes) {
    private val density = context.resources.displayMetrics.density.coerceIn(1f, 2f)
    private var renderer: RouteMapRenderer? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var bearing: Float? = null
    private var lastKnownBearing: Float? = null
    private var accuracyMeters: Float? = null
    private var destination: GeoPoint? = null
    private var progressFraction: Float = 0f
    private var routePoints: List<GeoPoint> = emptyList()
    private var desiredZoom = 15.5f
    private var freeCamera: MapCamera? = null
    private var following = true
    private var overview = false
    private var headingUp = false
    private var nightMode = true
    private var scalingGesture = false
    private var framingDirty = true
    private var framedCamera: MapCamera? = null

    var onMapStateChanged: ((MapViewState) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(mapState)
        }

    val mapState: MapViewState
        get() = MapViewState(following, overview, headingUp, currentCamera()?.zoom ?: desiredZoom)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scalingGesture = true
            return currentCamera() != null
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val camera = currentCamera() ?: return false
            val factor = detector.scaleFactor.takeIf { it.isFinite() && it > 0f } ?: return false
            setFreeCamera(MapProjection.zoomAt(camera, camera.zoom + log2(factor), detector.focusX, detector.focusY,
                width, height, density, mapBearing()))
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scalingGesture || scaleDetector.isInProgress) return true
            val camera = currentCamera() ?: return false
            setFreeCamera(MapProjection.pan(camera, distanceX, distanceY, density, mapBearing()))
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            val camera = currentCamera() ?: return false
            setFreeCamera(MapProjection.zoomAt(camera, camera.zoom + 1f, event.x, event.y,
                width, height, density, mapBearing()))
            return true
        }

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean = performClick()
    })

    init {
        contentDescription = "Carte OpenStreetMap. Déplacez avec un doigt, zoomez avec deux doigts ou un double appui."
        isClickable = true
    }

    fun updateLocation(latitude: Double?, longitude: Double?, bearing: Float? = null) {
        updateState(latitude, longitude, routePoints, bearing, accuracyMeters, destination, progressFraction)
    }

    fun updateRoute(points: List<GeoPoint>) {
        updateState(latitude, longitude, points, bearing, accuracyMeters, destination, progressFraction)
    }

    fun updateState(
        latitude: Double?, longitude: Double?, routePoints: List<GeoPoint>, bearing: Float? = null,
        accuracyMeters: Float? = null, destination: GeoPoint? = null, progressFraction: Float = 0f,
    ) {
        val changed = this.latitude != latitude || this.longitude != longitude || this.routePoints != routePoints ||
            this.bearing != bearing || this.accuracyMeters != accuracyMeters || this.destination != destination ||
            this.progressFraction != progressFraction
        this.latitude = latitude
        this.longitude = longitude
        this.routePoints = routePoints
        this.bearing = bearing
        bearing?.takeIf(Float::isFinite)?.let { lastKnownBearing = it }
        this.accuracyMeters = accuracyMeters
        this.destination = destination
        this.progressFraction = progressFraction
        if (changed) {
            framingDirty = true
            invalidate()
            notifyState()
        }
    }

    fun zoomIn() = zoomBy(1f)
    fun zoomOut() = zoomBy(-1f)

    private fun zoomBy(amount: Float) {
        if (following) {
            desiredZoom = (desiredZoom + amount).coerceIn(MapProjection.MIN_ZOOM, MapProjection.MAX_ZOOM)
            invalidate()
            notifyState()
        } else {
            currentCamera()?.let { setFreeCamera(it.copy(zoom = (it.zoom + amount).coerceIn(MapProjection.MIN_ZOOM, MapProjection.MAX_ZOOM))) }
        }
    }

    /** Resume GPS following; an overview never overwrites the driver's preferred follow zoom. */
    fun recenter() {
        following = true
        overview = false
        freeCamera = null
        invalidate()
        notifyState()
    }

    fun showRouteOverview() {
        if (routePoints.none(MapProjection::isValid) && destination == null) return
        following = false
        overview = true
        freeCamera = null
        invalidate()
        notifyState()
    }

    fun setHeadingUp(value: Boolean) {
        if (headingUp == value) return
        headingUp = value
        invalidate()
        notifyState()
    }

    fun toggleOrientation() = setHeadingUp(!headingUp)

    fun setNightMode(value: Boolean) {
        if (nightMode == value) return
        nightMode = value
        invalidate()
    }

    private fun location(): GeoPoint? = if (latitude != null && longitude != null)
        GeoPoint(latitude!!, longitude!!).takeIf(MapProjection::isValid) else null

    private fun currentCamera(): MapCamera? {
        if (overview) return routeCamera()
        return if (following) location()?.let { MapCamera(it, desiredZoom) }
            ?: routeCamera()
        else freeCamera
    }

    private fun routeCamera(): MapCamera? {
        if (framingDirty) {
            framedCamera = MapProjection.fitRoute(routePoints + listOfNotNull(location(), destination), width, height, density)
            framingDirty = false
        }
        return framedCamera
    }

    private fun mapBearing(): Float = if (headingUp && !overview) lastKnownBearing ?: 0f else 0f

    private fun setFreeCamera(camera: MapCamera) {
        freeCamera = camera
        following = false
        overview = false
        invalidate()
        notifyState()
    }

    private fun notifyState() = onMapStateChanged?.invoke(mapState)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = RouteMapRenderer(context) { postInvalidateOnAnimation() }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        framingDirty = true
        notifyState()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer?.render(canvas, width, height, latitude, longitude, routePoints, bearing,
            camera = currentCamera(), headingUp = headingUp && !overview, nightMode = nightMode,
            accuracyMeters = accuracyMeters, destination = destination, progressFraction = progressFraction,
            orientationBearing = lastKnownBearing)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scalingGesture = false
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scalingGesture = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        renderer?.close()
        renderer = null
        super.onDetachedFromWindow()
    }
}
