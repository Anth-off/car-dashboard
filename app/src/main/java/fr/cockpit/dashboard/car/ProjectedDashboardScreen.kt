@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import android.graphics.Rect
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import fr.cockpit.dashboard.destinations.DestinationRepository
import fr.cockpit.dashboard.integrations.MusicRepository
import fr.cockpit.dashboard.integrations.WeatherRepository
import fr.cockpit.dashboard.map.RouteMapView
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.ui.CockpitTheme
import fr.cockpit.dashboard.ui.Dashboard
import fr.cockpit.dashboard.ui.DashboardData
import fr.cockpit.dashboard.ui.DashboardProjection
import fr.cockpit.dashboard.telemetry.TripRepository

/** The phone dashboard itself, rendered on Android Auto's app surface (Car API 5+). */
internal class ProjectedDashboardScreen(carContext: CarContext) : LiveCarScreen(carContext), SavedStateRegistryOwner {
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    private val navigation = NavigationRepository.get(carContext)
    private val trips = TripRepository.get(carContext)
    private val music = MusicRepository.get(carContext)
    private val weather = WeatherRepository.get(carContext)
    private val destinations = DestinationRepository.get(carContext)
    private val appManager = carContext.getCarService(AppManager::class.java)
    private val projection = ProjectedDashboardSurface(carContext, this) { Content() }
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var dpi = 160
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    private var visible = false
    private var attachmentFailed = false
    private var viewportFits by mutableStateOf(true)
    private var map: RouteMapView? = null
    private var expanded by mutableStateOf(false)
    private var mapNight = true
    private var mapHeadingUp: Boolean? = null
    private var pendingMapCommand: ((RouteMapView) -> Unit)? = null
    private var callbackGeneration = 0

    private fun surfaceCallback(generation: Int) = object : SurfaceCallback {
        private fun current() = visible && generation == callbackGeneration

        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val next = container.surface
            if (!current()) { next?.release(); return }
            if (next !== surface) releaseSurface()
            surface = next
            width = container.width
            height = container.height
            dpi = container.dpi.coerceAtLeast(1)
            attachmentFailed = !projection.attach(container)
            updateViewport()
            invalidate()
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            // Binder can deserialize a different Java wrapper for the same underlying surface.
            // Generation protects callbacks from an earlier screen visit; reference equality
            // must not be used as the test for a valid surface-destroy notification.
            val previous = surface
            if (current()) releaseSurface()
            if (container.surface !== previous || !current()) container.surface?.release()
        }

        override fun onVisibleAreaChanged(area: Rect) { if (current()) { visibleArea = Rect(area); updateViewport() } }
        override fun onStableAreaChanged(area: Rect) { if (current()) { stableArea = Rect(area); updateViewport() } }

        override fun onClick(x: Float, y: Float) {
            if (current() && !attachmentFailed) projection.click(x, y)
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (current() && !attachmentFailed && viewportFits) map?.panBy(distanceX, distanceY)
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            val view = map?.takeIf { current() && !attachmentFailed && viewportFits } ?: return
            if (!focusX.isFinite() || !focusY.isFinite()) return
            val position = IntArray(2)
            view.getLocationInWindow(position)
            // Android Auto uses -1 when the host cannot provide a gesture focal point.
            val x = if (focusX < 0) -1f else focusX - position[0]
            val y = if (focusY < 0) -1f else focusY - position[1]
            if (focusX >= 0 && focusY >= 0 && (x < 0 || y < 0 || x >= view.width || y >= view.height)) return
            view.zoomAt(x, y, scaleFactor)
        }
    }

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                visible = true
                music.refresh()
                callbackGeneration++
                appManager.setSurfaceCallback(surfaceCallback(callbackGeneration))
            }

            override fun onPause(owner: LifecycleOwner) {
                visible = false
                callbackGeneration++
                runCatching { appManager.setSurfaceCallback(null) }
                releaseSurface()
            }

            override fun onDestroy(owner: LifecycleOwner) { visible = false; releaseSurface() }
        })
    }

    override fun onGetTemplate(): Template {
        // Route information is already in the shared dashboard. NavigationManager continues
        // publishing maneuvers to the host/cluster, without duplicating its large route panel.
        val builder = NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder()
                .addAction(Action.Builder().setTitle("Menu").setOnClickListener(::openMenu).build())
                .addAction(Action.Builder().setTitle("Carte seule").setOnClickListener {
                    screenManager.push(NavigationScreen(carContext))
                }.build()).build())
            // Required by the host to deliver surface taps and map gestures. Touch hosts can
            // hide PAN themselves; the app must not omit it even when all controls are Compose.
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { }
        if (attachmentFailed) builder.setNavigationInfo(MessageInfo.Builder("Affichage indisponible")
            .setText("Utilisez Carte seule sur cet écran.").build())
        return builder.build()
    }

    @Composable
    private fun Content() {
        val trip by trips.state.collectAsState()
        val audio by music.state.collectAsState()
        val conditions by weather.state.collectAsState()
        val places by destinations.state.collectAsState()
        val route by navigation.state.collectAsState()
        CockpitTheme {
            if (!viewportFits) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Cet écran est trop petit pour le tableau de bord. Ouvrez « Carte seule ».")
                }
                return@CockpitTheme
            }
            Dashboard(
                data = DashboardData(
                    speedKmh = trip.speedKmh, totalMeters = trip.totalMeters, tripMeters = trip.tripMeters,
                    recording = trip.recording, temperatureC = conditions.temperatureC,
                    weatherSummary = conditions.error ?: conditions.summary ?: if (conditions.loading) "Actualisation…" else "",
                    weatherEnabled = conditions.enabled, weatherUpdatedAt = conditions.updatedAtMillis,
                    musicTitle = audio.title, musicArtist = audio.artist, musicConnected = audio.connected,
                    musicPlaying = audio.playing, canPlayPause = audio.canPlayPause,
                    canPrevious = audio.canPrevious, canNext = audio.canNext,
                    destinations = places, latitude = trip.latitude, longitude = trip.longitude,
                    navigation = route, bearing = trip.bearingDegrees, accuracyMeters = trip.accuracyMeters,
                ),
                onTracking = ::toggleTracking,
                onReset = { screenManager.push(ResetCarTripScreen(carContext)) },
                onSettings = ::openMenu,
                onDestinations = ::openDestinations,
                onStopNavigation = navigation::stop,
                onReroute = { if (canStartCarNavigation(carContext)) navigation.reroute() },
                onVoiceEnabled = navigation::setVoiceEnabled,
                onSelectRoute = navigation::selectRoute,
                onMusic = { screenManager.push(MusicScreen(carContext)) },
                onPlayPause = { musicCommand(music::togglePlayback) },
                onPrevious = { musicCommand(music::previous) },
                onNext = { musicCommand(music::next) },
                projection = DashboardProjection(
                    mapExpanded = expanded,
                    onToggleMap = { expanded = !expanded },
                    onMapReady = ::onMapReady,
                    onMapOptions = ::openMapOptions,
                    onJourney = ::openRoutes,
                ),
            )
        }
    }

    private fun updateViewport() {
        if (width <= 0 || height <= 0) return
        val area = carMapViewport(width, height, visibleArea, stableArea)
        // Empty areas are transient while the host switches templates. Hide, don't resize the
        // dashboard into an unusable strip or send touches to controls covered by host UI.
        val density = dpi / 160f
        val fontScale = projection.contentView?.resources?.configuration?.fontScale ?: 1f
        viewportFits = area.isEmpty || projectedDashboardFits(area.width() / density, area.height() / density, fontScale)
        // Do not add a host MessageInfo for a transiently small viewport: that extra panel would
        // shrink the stable area further and prevent recovery. The template stays unchanged.
        projection.updateViewport(area)
    }

    private fun onMapReady(view: RouteMapView?) {
        if (view == null) {
            map?.let { mapNight = it.isNightMode; mapHeadingUp = it.mapState.headingUp }
            map = null
            return
        }
        map = view
        view.setNightMode(mapNight)
        mapHeadingUp?.let(view::setHeadingUp)
        view.post {
            if (map === view) {
                pendingMapCommand?.invoke(view)
                pendingMapCommand = null
            }
        }
    }

    private fun returnToDashboard(command: ((RouteMapView) -> Unit)? = null) {
        pendingMapCommand = command
        screenManager.popToRoot()
    }

    private fun openDestinations() = screenManager.push(DestinationListScreen(carContext) { returnToDashboard() })

    private fun openRoutes() {
        screenManager.push(RouteChoicesScreen(carContext, {
            if (navigation.state.value.route == null) false
            else { pendingMapCommand = { it.showRouteOverview() }; true }
        }) { screenManager.popToRoot() })
    }

    private fun openMapOptions() {
        val night = map?.isNightMode ?: mapNight
        val heading = map?.mapState?.headingUp ?: mapHeadingUp ?: false
        screenManager.push(ProjectedMapOptionsScreen(carContext, night, heading,
            onNight = { returnToDashboard { it.setNightMode(!night) } },
            onHeading = { returnToDashboard { it.setHeadingUp(!heading) } },
            onRecenter = { returnToDashboard { it.recenter() } },
            onOverview = { returnToDashboard { it.showRouteOverview() } },
        ))
    }

    private fun openMenu() = screenManager.push(DashboardMenuScreen(carContext, ::openDestinations,
        ::openRoutes, ::openMapOptions))

    private fun toggleTracking() {
        if (trips.state.value.recording) {
            navigation.stop()
            trips.stopTracking(carContext)
        } else {
            // Location permission and while-in-use foreground-service startup remain on the phone.
            message("À l’arrêt, démarrez le suivi GPS dans Cockpit GPS sur le téléphone.")
        }
    }

    private fun musicCommand(command: () -> Boolean) {
        if (!command()) message("Lancez d’abord un morceau dans Apple Music sur le téléphone.")
    }

    private fun message(text: String) = CarToast.makeText(carContext, text, CarToast.LENGTH_LONG).show()

    private fun releaseSurface() {
        projection.close()
        surface?.release()
        surface = null
        map = null
        width = 0
        height = 0
        // Keep the last host areas until new callbacks arrive after a menu returns.
    }
}

internal fun projectedDashboardFits(widthDp: Float, heightDp: Float, fontScale: Float = 1f): Boolean =
    if (widthDp > heightDp) widthDp >= 440f && heightDp >= if (fontScale > 1.15f) 324f else 304f
    else widthDp >= 320f && heightDp >= 520f

/** Fixed pages, so opening a menu never introduces a scrollable screen. */
private class DashboardMenuScreen(
    context: CarContext, private val destinations: () -> Unit,
    private val routes: () -> Unit, private val mapOptions: () -> Unit,
) : Screen(context) {
    private var page = 0
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(compactCarRow("Cockpit GPS", if (page == 0) "Navigation et carte" else "Musique et compteurs"))
            .addRow(compactCarRow("Réglages", "Les autorisations et la recherche d’adresse se préparent sur le téléphone."))
        if (page == 0) {
            pane.addAction(Action.Builder().setTitle("Destinations").setOnClickListener(destinations).build())
            pane.addAction(Action.Builder().setTitle("Trajets").setOnClickListener(routes).build())
        } else {
            pane.addAction(Action.Builder().setTitle("Musique").setOnClickListener {
                screenManager.push(MusicScreen(carContext))
            }.build())
            pane.addAction(Action.Builder().setTitle("Compteurs").setOnClickListener {
                screenManager.push(TripScreen(carContext))
            }.build())
        }
        val actions = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(
                carContext, android.R.drawable.ic_menu_mapmode)).build()).setOnClickListener(mapOptions).build())
            .addAction(Action.Builder().setTitle(if (page == 0) "Suite" else "Précédent")
                .setOnClickListener { page = 1 - page; invalidate() }.build()).build()
        return PaneTemplate.Builder(pane.build()).setTitle("Cockpit GPS")
            .setHeaderAction(Action.BACK).setActionStrip(actions).build()
    }
}

private class ProjectedMapOptionsScreen(
    context: CarContext, private val night: Boolean, private val heading: Boolean,
    private val onNight: () -> Unit, private val onHeading: () -> Unit,
    private val onRecenter: () -> Unit, private val onOverview: () -> Unit,
) : Screen(context) {
    override fun onGetTemplate(): Template = PaneTemplate.Builder(Pane.Builder()
        .addRow(compactCarRow("Votre carte", if (night) "Mode nuit" else "Mode jour"))
        .addRow(compactCarRow("Orientation", if (heading) "Sens de la marche" else "Nord en haut"))
        .addAction(Action.Builder().setTitle(if (night) "Mode jour" else "Mode nuit").setOnClickListener(onNight).build())
        .addAction(Action.Builder().setTitle(if (heading) "Nord en haut" else "Sens de marche").setOnClickListener(onHeading).build())
        .build()).setTitle("Votre carte").setHeaderAction(Action.BACK)
        .setActionStrip(ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(
                carContext, android.R.drawable.ic_menu_mylocation)).build()).setOnClickListener(onRecenter).build())
            .addAction(Action.Builder().setTitle("Vue du trajet").setOnClickListener(onOverview).build()).build())
        .build()
}

private class ResetCarTripScreen(context: CarContext) : Screen(context) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder("La distance totale sera conservée.")
        .setTitle("Remettre le trajet à zéro ?").setHeaderAction(Action.BACK)
        .addAction(Action.Builder().setTitle("Annuler").setOnClickListener { screenManager.pop() }.build())
        .addAction(Action.Builder().setTitle("Réinitialiser").setOnClickListener {
            val trip = TripRepository.get(carContext)
            if (!trip.state.value.recording) { trip.resetTrip(); screenManager.pop() }
            else CarToast.makeText(carContext, "Arrêtez d’abord le suivi GPS.", CarToast.LENGTH_SHORT).show()
        }.build()).build()
}
