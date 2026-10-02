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
import fr.cockpit.dashboard.map.RouteMapRenderer
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.telemetry.TripRepository
import fr.cockpit.dashboard.telemetry.TelemetryState
import java.util.Locale

internal class NavigationScreen(carContext: CarContext) : LiveCarScreen(carContext) {
    private val navigation = NavigationRepository.get(carContext)
    private val trip = TripRepository.get(carContext)
    private val appManager = carContext.getCarService(AppManager::class.java)
    private val map = RouteMapRenderer(carContext) { drawMap() }
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDensity = 1f
    private var visibleArea = Rect()
    private var visible = false
    private val zoomInIcon by lazy { symbolIcon("+") }
    private val zoomOutIcon by lazy { symbolIcon("−") }

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
            surfaceDensity = (surfaceContainer.dpi / 160f).coerceAtLeast(1f)
            drawMap()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            releaseSurface()
        }

        override fun onVisibleAreaChanged(area: Rect) {
            visibleArea = Rect(area)
            drawMap()
        }
    }

    init {
        observe(navigation.state, trip.state)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                visible = true
                appManager.setSurfaceCallback(surfaceCallback)
            }

            override fun onStop(owner: LifecycleOwner) {
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
                    .setOnClickListener { screenManager.popToRoot() }.build()
            )
            .addAction(
                Action.Builder().setTitle("Infos")
                    .setOnClickListener { screenManager.push(TripScreen(carContext)) }.build()
            )
        if (state.active || state.loading) {
            actions.addAction(
                Action.Builder().setTitle("Arrêter")
                    .setOnClickListener { navigation.stop() }.build()
            )
        }
        if (!state.loading && state.destination != null && (state.offRoute || state.error != null)) {
            actions.addAction(
                Action.Builder().setTitle("Recalculer")
                    .setOnClickListener {
                        if (canStartCarNavigation(carContext)) navigation.reroute()
                    }.build()
            )
        }

        val routing: NavigationTemplate.NavigationInfo = when {
            state.loading -> RoutingInfo.Builder().setLoading(true).build()
            state.active && state.distanceToTurnMeters == null -> MessageInfo.Builder("Guidage en pause")
                .setText(state.instruction).build()
            state.active || state.arrived -> RoutingInfo.Builder()
                .setCurrentStep(carStep(state), carDistance(state.distanceToTurnMeters ?: 0.0))
                .build()
            else -> {
                val message = state.error
                    ?: if (trip.state.value.latitude == null) "Démarrez le suivi GPS sur le téléphone, à l’arrêt."
                    else "Choisissez une destination pour démarrer."
                MessageInfo.Builder("Cockpit").setText(message).build()
            }
        }

        val template = NavigationTemplate.Builder()
            .setNavigationInfo(routing)
            .setActionStrip(actions.build())

        if (state.active && state.remainingMeters != null && state.remainingSeconds != null) {
            template.setDestinationTravelEstimate(
                carTravelEstimate(state.remainingMeters, state.remainingSeconds)
            )
        }
        if (carContext.carAppApiLevel >= 2) {
            template.setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder().setIcon(zoomInIcon).setOnClickListener {
                            map.zoom = (map.zoom + 1).coerceAtMost(18)
                            drawMap()
                        }.build()
                    )
                    .addAction(
                        Action.Builder().setIcon(zoomOutIcon).setOnClickListener {
                            map.zoom = (map.zoom - 1).coerceAtLeast(3)
                            drawMap()
                        }.build()
                    )
                    .build()
            )
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
            val area = Rect(0, 0, surfaceWidth, surfaceHeight)
            if (!visibleArea.isEmpty && !area.intersect(visibleArea)) return
            val saved = canvas.save()
            canvas.clipRect(area)
            canvas.translate(area.left.toFloat(), area.top.toFloat())
            val position = trip.state.value
            val guidance = navigation.state.value
            val latitude = if (guidance.isSimulation) guidance.currentPosition?.latitude else position.latitude
            val longitude = if (guidance.isSimulation) guidance.currentPosition?.longitude else position.longitude
            // The canvas is exclusively map content; setup/error messages belong in the template.
            if (latitude != null && longitude != null) {
                map.render(
                    canvas,
                    area.width(),
                    area.height(),
                    latitude,
                    longitude,
                    guidance.route?.points.orEmpty(),
                )
                drawDrivingInfo(canvas, area.width(), area.height(), position, guidance.isSimulation)
            }
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
        visibleArea = Rect()
    }

    /** Driving information is kept in the host's visible safe area, clear of map attribution. */
    private fun drawDrivingInfo(canvas: Canvas, width: Int, height: Int, data: TelemetryState, simulation: Boolean) {
        val density = surfaceDensity
        val margin = 12f * density
        val cardWidth = 164f * density
        val cardHeight = 108f * density
        if (width < cardWidth + margin * 2 || height < cardHeight + margin * 3 + 28f * density) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(230, 13, 23, 33)
        canvas.drawRoundRect(
            RectF(margin, margin, margin + cardWidth, margin + cardHeight),
            12f * density,
            12f * density,
            paint,
        )
        val left = margin + 14f * density
        paint.color = Color.rgb(153, 178, 192)
        paint.textSize = 11f * density
        canvas.drawText(if (simulation) "SIMULATION" else "VITESSE GPS", left, margin + 23f * density, paint)
        paint.color = Color.rgb(121, 238, 224)
        paint.textSize = 34f * density
        paint.isFakeBoldText = true
        val speed = if (simulation) "DÉMO" else data.speedKmh?.let { String.format(Locale.FRANCE, "%.0f", it) } ?: "—"
        canvas.drawText(speed, left, margin + 62f * density, paint)
        val speedWidth = paint.measureText(speed)
        paint.isFakeBoldText = false
        paint.textSize = 13f * density
        paint.color = Color.WHITE
        if (!simulation) canvas.drawText("km/h", left + speedWidth + 6f * density, margin + 61f * density, paint)
        paint.color = Color.rgb(204, 218, 227)
        paint.textSize = 12f * density
        val trip = if (simulation) "Compteurs inchangés" else String.format(Locale.FRANCE, "Trajet GPS · %.1f km", data.tripMeters / 1_000.0)
        canvas.drawText(trip, left, margin + 87f * density, paint)
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
