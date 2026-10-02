package fr.cockpit.dashboard.map

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.animation.ValueAnimator
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import fr.cockpit.dashboard.navigation.GeoPoint
import kotlin.math.log2

/** UI state is exposed so Compose controls accurately reflect gestures as well as button presses. */
data class MapViewState(
    val following: Boolean = true,
    val overview: Boolean = false,
    val headingUp: Boolean = false,
    val zoom: Float = 15.5f,
)

/** Shared map with independent camera, focal-point pinch zoom and a persistent GPS marker. */
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
    private var freeBearing: Float? = null
    private var following = true
    private var overview = false
    private var headingUp = false
    private var nightMode = true
    private var scalingGesture = false
    private var framingDirty = true
    private var framedCamera: MapCamera? = null
    private var navigationActive = false
    private var locationFresh = true
    private var trackingActive = false
    private var speedKmh: Float? = null
    private var distanceToTurnMeters: Double? = null
    private var automaticZoom = true
    private var displayedZoom = desiredZoom
    private var displayedBearing = 0f
    private var cameraAnimator: ValueAnimator? = null

    var onMapStateChanged: ((MapViewState) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(mapState)
        }

    val mapState: MapViewState
        get() = MapViewState(following, overview, headingUp, currentCamera()?.zoom ?: desiredZoom)

    val isNightMode: Boolean get() = nightMode

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scalingGesture = true
            return currentCamera() != null
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return zoomAt(detector.focusX, detector.focusY, detector.scaleFactor)
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scalingGesture || scaleDetector.isInProgress) return true
            return panBy(distanceX, distanceY)
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            return zoomAt(event.x, event.y, 2f)
        }

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean = performClick()
    })

    init {
        contentDescription = "Carte OpenStreetMap. Déplacez avec un doigt, zoomez avec deux doigts ou un double appui."
        isClickable = true
    }

    fun updateLocation(latitude: Double?, longitude: Double?, bearing: Float? = null) {
        updateState(latitude, longitude, routePoints, bearing, accuracyMeters, destination, progressFraction,
            speedKmh, distanceToTurnMeters, navigationActive, locationFresh, trackingActive)
    }

    fun updateRoute(points: List<GeoPoint>) {
        updateState(latitude, longitude, points, bearing, accuracyMeters, destination, progressFraction,
            speedKmh, distanceToTurnMeters, navigationActive, locationFresh, trackingActive)
    }

    fun updateState(
        latitude: Double?, longitude: Double?, routePoints: List<GeoPoint>, bearing: Float? = null,
        accuracyMeters: Float? = null, destination: GeoPoint? = null, progressFraction: Float = 0f,
        speedKmh: Float? = null, distanceToTurnMeters: Double? = null,
        navigationActive: Boolean = false, locationFresh: Boolean = true,
        trackingActive: Boolean = false,
    ) {
        val enteringNavigation = navigationActive && !this.navigationActive
        val changed = this.latitude != latitude || this.longitude != longitude || this.routePoints != routePoints ||
            this.bearing != bearing || this.accuracyMeters != accuracyMeters || this.destination != destination ||
            this.progressFraction != progressFraction || this.speedKmh != speedKmh ||
            this.distanceToTurnMeters != distanceToTurnMeters || this.navigationActive != navigationActive ||
            this.locationFresh != locationFresh || this.trackingActive != trackingActive
        this.latitude = latitude
        this.longitude = longitude
        this.routePoints = routePoints
        this.bearing = bearing
        if (locationFresh) bearing?.takeIf(Float::isFinite)?.let { lastKnownBearing = ((it % 360) + 360) % 360 }
        this.accuracyMeters = accuracyMeters
        this.destination = destination
        this.progressFraction = progressFraction
        this.speedKmh = speedKmh
        this.distanceToTurnMeters = distanceToTurnMeters
        this.navigationActive = navigationActive
        this.locationFresh = locationFresh
        this.trackingActive = trackingActive
        if (enteringNavigation) {
            following = true
            overview = false
            freeCamera = null
            freeBearing = null
            headingUp = true
            automaticZoom = true
        }
        if (changed) {
            framingDirty = true
            updateCameraMotion()
            invalidate()
            notifyState()
        }
    }

    fun zoomIn() = zoomBy(1f)
    fun zoomOut() = zoomBy(-1f)

    /** Scroll distances use the same pixel convention as Android Auto SurfaceCallback.onScroll. */
    fun panBy(distanceX: Float, distanceY: Float): Boolean {
        if (!distanceX.isFinite() || !distanceY.isFinite() || width <= 0 || height <= 0) return false
        val camera = currentCamera() ?: return false
        if (distanceX == 0f && distanceY == 0f) return true
        setFreeCamera(MapProjection.pan(camera, distanceX, distanceY, density, mapBearing()))
        return true
    }

    /** A car host may omit the focus with -1; use the map center in that case. */
    fun zoomAt(focusX: Float, focusY: Float, scaleFactor: Float): Boolean {
        if (!focusX.isFinite() || !focusY.isFinite() || !scaleFactor.isFinite() || scaleFactor <= 0f ||
            width <= 0 || height <= 0) return false
        val camera = currentCamera() ?: return false
        if (scaleFactor == 1f) return true
        val x = if (focusX < 0f) width / 2f else focusX.coerceIn(0f, width.toFloat())
        val y = if (focusY < 0f) height / 2f else focusY.coerceIn(0f, height.toFloat())
        setFreeCamera(MapProjection.zoomAt(camera, camera.zoom + log2(scaleFactor), x, y,
            width, height, density, mapBearing()))
        return true
    }

    private fun zoomBy(amount: Float) {
        if (following) {
            automaticZoom = false
            desiredZoom = (displayedZoom + amount).coerceIn(MapProjection.MIN_ZOOM, MapProjection.MAX_ZOOM)
            updateCameraMotion()
            invalidate()
            notifyState()
        } else {
            currentCamera()?.let { setFreeCamera(it.copy(zoom = (it.zoom + amount).coerceIn(MapProjection.MIN_ZOOM, MapProjection.MAX_ZOOM))) }
        }
    }

    /** Resume GPS following and the adaptive guidance zoom after inspecting the map. */
    fun recenter() {
        following = true
        overview = false
        freeCamera = null
        freeBearing = null
        automaticZoom = true
        updateCameraMotion()
        invalidate()
        notifyState()
    }

    fun showRouteOverview() {
        if (routePoints.none(MapProjection::isValid) && destination == null) return
        following = false
        overview = true
        freeCamera = null
        freeBearing = null
        updateCameraMotion()
        invalidate()
        notifyState()
    }

    fun setHeadingUp(value: Boolean) {
        if (headingUp == value) return
        headingUp = value
        if (!following && !overview) freeBearing = if (value) lastKnownBearing ?: 0f else 0f
        updateCameraMotion()
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
        return if (following) location()?.let {
            if (navigationActive) DrivingMapCamera.follow(it, displayedZoom, width, height, density, mapBearing())
            else MapCamera(it, displayedZoom)
        }
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

    private fun mapBearing(): Float = if (headingUp && !overview) freeBearing ?: displayedBearing else 0f

    private fun updateCameraMotion() {
        // No dead reckoning: when a fix goes stale, stop camera animation at the last real fix.
        // The renderer also receives the original coordinates, never a smoothed vehicle position.
        cameraAnimator?.cancel()
        cameraAnimator = null
        val targetZoom = when {
            navigationActive && automaticZoom && !locationFresh -> displayedZoom
            navigationActive && automaticZoom -> DrivingMapCamera.recommendedZoom(speedKmh, distanceToTurnMeters)
            else -> desiredZoom
        }
        val targetBearing = if (headingUp && !overview) lastKnownBearing ?: 0f else 0f
        val initialZoom = displayedZoom
        val initialBearing = displayedBearing
        if (!isAttachedToWindow || !following || !locationFresh ||
            (kotlin.math.abs(targetZoom - initialZoom) < .02f &&
                DrivingMapCamera.bearingDifference(initialBearing, targetBearing) < .5f)) {
            displayedZoom = targetZoom
            displayedBearing = targetBearing
            return
        }
        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                displayedZoom = initialZoom + (targetZoom - initialZoom) * fraction
                displayedBearing = DrivingMapCamera.interpolateBearing(initialBearing, targetBearing, fraction)
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun setFreeCamera(camera: MapCamera) {
        if (following || overview) freeBearing = mapBearing()
        freeCamera = camera
        following = false
        overview = false
        cameraAnimator?.cancel()
        cameraAnimator = null
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
            orientationBearing = mapBearing(), locationFresh = locationFresh, trackingActive = trackingActive)
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
        cameraAnimator?.cancel()
        cameraAnimator = null
        renderer?.close()
        renderer = null
        super.onDetachedFromWindow()
    }
}
