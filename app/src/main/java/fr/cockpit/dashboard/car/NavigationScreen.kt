package fr.cockpit.dashboard.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.RoutingInfo
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import fr.cockpit.dashboard.map.MapCamera
import fr.cockpit.dashboard.map.DrivingMapCamera
import fr.cockpit.dashboard.map.MapProjection
import fr.cockpit.dashboard.map.RouteMapRenderer
import fr.cockpit.dashboard.navigation.GeoPoint
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.telemetry.TripRepository
import fr.cockpit.dashboard.telemetry.TelemetryState
import java.util.Locale
import kotlin.math.log2

internal class NavigationScreen(carContext: CarContext) : LiveCarScreen(carContext) {
    private val navigation = NavigationRepository.get(carContext)
    private val trip = TripRepository.get(carContext)
    private val appManager = carContext.getCarService(AppManager::class.java)
    private val map = RouteMapRenderer(carContext) { drawMap() }
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDensity = 1f
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    private var camera: MapCamera? = null
    private var customCameraBearing = 0f
    private var followZoom: Float? = null
    private var lastCourse = 0f
    private var visible = false
    private val zoomInIcon by lazy { symbolIcon("+") }
    private val zoomOutIcon by lazy { symbolIcon("−") }
    private val recenterIcon by lazy { positionIcon() }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val nextSurface = surfaceContainer.surface
            if (!visible) {
                nextSurface?.release()
                return
            }
            if (surface !== nextSurface) surface?.release()
            surface = nextSurface
            surfaceWidth = surfaceContainer.width
            surfaceHeight = surfaceContainer.height
            surfaceDensity = (surfaceContainer.dpi / 160f).coerceIn(1f, 3f)
            drawMap()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            val previous = surface
            releaseSurface()
            if (surfaceContainer.surface !== previous) surfaceContainer.surface?.release()
        }

        override fun onVisibleAreaChanged(area: Rect) {
            visibleArea = Rect(area)
            drawMap()
        }

        override fun onStableAreaChanged(area: Rect) {
            stableArea = Rect(area)
            drawMap()
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (!distanceX.isFinite() || !distanceY.isFinite()) return
            val current = currentCamera() ?: return
            val orientation = mapOrientation()
            camera = MapProjection.pan(current, distanceX, distanceY, surfaceDensity, orientation)
            customCameraBearing = orientation
            drawMap()
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
            val current = currentCamera() ?: return
            val area = mapViewport()
            if (area.isEmpty) return
            // A host may supply -1 when it cannot identify the gesture's focal point.
            val x = if (focusX.isFinite() && focusX >= 0f) focusX - area.left else area.width() / 2f
            val y = if (focusY.isFinite() && focusY >= 0f) focusY - area.top else area.height() / 2f
            val orientation = mapOrientation()
            camera = MapProjection.zoomAt(current, current.zoom + log2(scaleFactor),
                x, y, area.width(), area.height(), surfaceDensity, orientation)
            customCameraBearing = orientation
            drawMap()
        }
    }

    init {
        observe(navigation.state, trip.state)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                visible = true
                appManager.setSurfaceCallback(surfaceCallback)
            }

            override fun onPause(owner: LifecycleOwner) {
                visible = false
                runCatching { appManager.setSurfaceCallback(null) }
                releaseSurface()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                visible = false
                releaseSurface()
                map.close()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = navigation.state.value
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("Destinations")
                    .setOnClickListener {
                        if (carContext.carAppApiLevel >= 5) screenManager.push(
                            DestinationListScreen(carContext) { screenManager.popToRoot() },
                        ) else screenManager.popToRoot()
                    }.build()
            )
            .addAction(
                Action.Builder().setTitle("Infos")
                    .setOnClickListener { screenManager.push(TripScreen(carContext)) }.build()
            )
            .addAction(
                Action.Builder().setTitle("Guidage")
                    .setOnClickListener {
                        screenManager.push(NavigationOptionsScreen(carContext, ::recenter, ::showOverview))
                    }.build()
            )
        if (state.active || state.loading) {
            actions.addAction(
                Action.Builder().setTitle("Arrêter")
                    .setOnClickListener { navigation.stop() }.build()
            )
        }
        val routing: NavigationTemplate.NavigationInfo = when {
            state.loading -> RoutingInfo.Builder().setLoading(true).build()
            state.active && (state.gpsPaused || state.distanceToTurnMeters == null) -> MessageInfo.Builder(
                if (state.offRoute) "Hors itinéraire" else "Guidage en pause")
                .setText(state.instruction).build()
            state.active || state.arrived -> RoutingInfo.Builder()
                .setCurrentStep(carStep(state), carDistance(state.distanceToTurnMeters ?: 0.0))
                .build()
            else -> {
                val message = state.error
                    ?: when {
                        !trip.state.value.recording -> "Démarrez le suivi GPS sur le téléphone, à l’arrêt."
                        trip.state.value.latitude == null || trip.state.value.longitude == null ->
                            "Signal GPS en attente. Le suivi est actif."
                        else -> "Choisissez une destination pour démarrer."
                    }
                MessageInfo.Builder("Cockpit").setText(message).build()
            }
        }

        val template = NavigationTemplate.Builder()
            .setNavigationInfo(routing)
            .setActionStrip(actions.build())

        if (state.active && !state.loading && !state.gpsPaused && !state.offRoute &&
            state.remainingMeters != null && state.remainingSeconds != null) {
            template.setDestinationTravelEstimate(
                carTravelEstimate(state.remainingMeters, state.remainingSeconds)
            )
        }
        if (carContext.carAppApiLevel >= 2) {
            template.setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .addAction(
                        Action.Builder().setIcon(zoomInIcon).setOnClickListener {
                            changeZoom(1)
                        }.build()
                    )
                    .addAction(
                        Action.Builder().setIcon(zoomOutIcon).setOnClickListener {
                            changeZoom(-1)
                        }.build()
                    )
                    .addAction(Action.Builder().setIcon(recenterIcon)
                        .setOnClickListener(::recenter).build())
                    .build()
            )
            template.setPanModeListener { inPanMode -> if (!inPanMode) recenter() }
        }
        drawMap()
        return template.build()
    }

    private fun drawMap() {
        if (!visible) return
        val target = surface?.takeIf { it.isValid } ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        var canvas: Canvas? = null
        try {
            canvas = target.lockCanvas(null)
            canvas.drawColor(Color.rgb(12, 18, 24))
            val area = carMapViewport(surfaceWidth, surfaceHeight, visibleArea, null)
            val safeArea = mapViewport()
            if (area.isEmpty || safeArea.isEmpty) return
            lastMapWidth = safeArea.width()
            lastMapHeight = safeArea.height()
            val saved = canvas.save()
            canvas.clipRect(area)
            canvas.translate(area.left.toFloat(), area.top.toFloat())
            val position = trip.state.value
            val guidance = navigation.state.value
            val latitude = if (guidance.isSimulation) guidance.currentPosition?.latitude else position.latitude
            val longitude = if (guidance.isSimulation) guidance.currentPosition?.longitude else position.longitude
            // Fill all visible space, while positioning the GPS marker in the stable area. This
            // keeps the map large and stops host controls appearing over the vehicle marker.
            val orientation = mapOrientation()
            val mapCamera = currentCamera()?.let {
                MapProjection.pan(it, area.exactCenterX() - safeArea.exactCenterX(),
                    area.exactCenterY() - safeArea.exactCenterY(), surfaceDensity, orientation)
            }
            map.render(
                canvas, area.width(), area.height(), latitude, longitude,
                guidance.route?.points.orEmpty(),
                bearing = if (guidance.isSimulation) null else position.bearingDegrees,
                camera = mapCamera,
                headingUp = orientation != 0f,
                orientationBearing = orientation,
                nightMode = carContext.isDarkMode,
                accuracyMeters = if (guidance.isSimulation) null else position.accuracyMeters,
                destination = guidance.destination?.let { GeoPoint(it.latitude, it.longitude) },
                progressFraction = guidance.progressFraction.toFloat(),
                densityOverride = surfaceDensity,
                trackingActive = position.recording,
                locationFresh = guidance.isSimulation || (position.recording && position.error == null &&
                    position.lastFixEpochMillis?.let { System.currentTimeMillis() - it in 0L..7_000L } == true),
            )
            val infoSaved = canvas.save()
            canvas.translate((safeArea.left - area.left).toFloat(), (safeArea.top - area.top).toFloat())
            drawDrivingInfo(canvas, safeArea.width(), safeArea.height(), position, guidance.isSimulation)
            canvas.restoreToCount(infoSaved)
            canvas.restoreToCount(saved)
        } catch (_: RuntimeException) {
            // A projected surface may be replaced between a frame request and canvas locking.
        } finally {
            if (canvas != null) runCatching { target.unlockCanvasAndPost(canvas) }
        }
    }

    private fun releaseSurface() {
        surface?.release()
        surface = null
        surfaceWidth = 0
        surfaceHeight = 0
        visibleArea = null
        stableArea = null
    }

    private fun mapViewport(): Rect = carMapViewport(surfaceWidth, surfaceHeight, visibleArea, stableArea)

    private fun currentCamera(): MapCamera? {
        camera?.let { return it }
        val guidance = navigation.state.value
        val position = trip.state.value
        val point = if (guidance.isSimulation) guidance.currentPosition
            else position.latitude?.let { latitude -> position.longitude?.let { GeoPoint(latitude, it) } }
        return point?.takeIf(MapProjection::isValid)?.let {
            val zoom = followZoom ?: if (guidance.active)
                DrivingMapCamera.recommendedZoom(position.speedKmh, guidance.distanceToTurnMeters)
            else map.zoom.toFloat()
            if (guidance.active) {
                val area = mapViewport()
                DrivingMapCamera.follow(it, zoom,
                    area.width().takeIf { width -> width > 0 } ?: lastMapWidth,
                    area.height().takeIf { height -> height > 0 } ?: lastMapHeight,
                    surfaceDensity, mapOrientation())
            } else MapCamera(it, zoom)
        }
    }

    /** Manual inspection freezes orientation; the overview remains north-up. */
    private fun mapOrientation(): Float {
        if (camera != null) return customCameraBearing
        val guidance = navigation.state.value
        if (!guidance.active || guidance.isSimulation) return 0f
        trip.state.value.bearingDegrees?.takeIf(Float::isFinite)?.let { lastCourse = it }
        return lastCourse
    }

    private fun changeZoom(delta: Int) {
        val custom = camera
        if (custom != null) camera = custom.copy(zoom = (custom.zoom + delta)
            .coerceIn(MapProjection.MIN_ZOOM, MapProjection.MAX_ZOOM))
        else followZoom = ((currentCamera()?.zoom ?: map.zoom.toFloat()) + delta)
            .coerceIn(MapProjection.MIN_ZOOM, MapProjection.MAX_ZOOM)
        drawMap()
    }

    private fun recenter() {
        camera = null
        followZoom = null
        customCameraBearing = 0f
        drawMap()
    }

    private fun showOverview(): Boolean {
        val points = navigation.state.value.route?.points ?: return false
        val area = mapViewport()
        // The surface may be temporarily released while the options screen covers the map.
        // Its last stable dimensions remain available for framing when the map returns.
        val width = area.width().takeIf { it > 0 } ?: lastMapWidth
        val height = area.height().takeIf { it > 0 } ?: lastMapHeight
        val overview = MapProjection.fitRoute(points, width, height, surfaceDensity) ?: return false
        camera = overview
        customCameraBearing = 0f
        drawMap()
        return true
    }

    private var lastMapWidth = 0
    private var lastMapHeight = 0

    /** Driving information is kept in the host's visible safe area, clear of map attribution. */
    private fun drawDrivingInfo(canvas: Canvas, width: Int, height: Int, data: TelemetryState, simulation: Boolean) {
        val density = surfaceDensity
        val margin = 12f * density
        val cardWidth = 88f * density
        val cardHeight = 72f * density
        if (width < cardWidth + margin * 2 || height < cardHeight + margin * 3 + 28f * density) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(230, 13, 23, 33)
        canvas.drawRoundRect(
            RectF(margin, margin, margin + cardWidth, margin + cardHeight),
            12f * density,
            12f * density,
            paint,
        )
        val left = margin + 10f * density
        paint.color = Color.rgb(153, 178, 192)
        paint.textSize = 10f * density
        canvas.drawText(if (simulation) "SIMULATION" else "VITESSE GPS", left, margin + 17f * density, paint)
        paint.color = Color.rgb(121, 238, 224)
        paint.textSize = if (simulation) 23f * density else 32f * density
        paint.isFakeBoldText = true
        val speed = if (simulation) "DÉMO" else data.speedKmh?.let { String.format(Locale.FRANCE, "%.0f", it) } ?: "—"
        canvas.drawText(speed, left, margin + 48f * density, paint)
        paint.isFakeBoldText = false
        paint.textSize = 10f * density
        paint.color = Color.WHITE
        canvas.drawText(if (simulation) "GPS simulé" else "km/h", left, margin + 63f * density, paint)
    }

    private fun positionIcon(): CarIcon {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(32f, 32f, 17f, paint)
        canvas.drawLine(32f, 4f, 32f, 19f, paint)
        canvas.drawLine(32f, 45f, 32f, 60f, paint)
        canvas.drawLine(4f, 32f, 19f, 32f, paint)
        canvas.drawLine(45f, 32f, 60f, 32f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(32f, 32f, 5f, paint)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private fun symbolIcon(symbol: String): CarIcon {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        Canvas(bitmap).drawText(symbol, 32f, 50f, paint)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }
}

/** Host overlays must not hide the GPS marker, map attribution, or compact speed indicator. */
internal fun carMapViewport(width: Int, height: Int, visible: Rect?, stable: Rect?): Rect {
    val area = Rect(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
    if (visible != null && (visible.isEmpty || !area.intersect(visible))) return Rect()
    if (stable != null && !stable.isEmpty) {
        val intersection = Rect(area)
        if (intersection.intersect(stable)) return intersection
    }
    return area
}
