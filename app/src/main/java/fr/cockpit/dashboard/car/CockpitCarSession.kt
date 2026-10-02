@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Trip
import androidx.car.app.CarToast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import fr.cockpit.dashboard.destinations.Destination
import fr.cockpit.dashboard.destinations.DestinationRepository
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.navigation.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Navigation focus and cluster data survive switching from the map to another app screen. */
internal class CockpitCarSession : Session() {
    private var navigation: NavigationRepository? = null
    private var manager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var announcedNavigation = false
    private var incomingIntentJob: Job? = null

    override fun onCreateScreen(intent: Intent): Screen {
        initialize()
        if (carContext.carAppApiLevel >= 5) {
            if (isNavigationIntent(intent)) acceptNavigationIntent(intent)
            return ProjectedDashboardScreen(carContext)
        }
        if (isNavigationIntent(intent)) {
            acceptNavigationIntent(intent)
            // Pre-seed the back stack so the map's Destinations action always has a real home.
            carContext.getCarService(androidx.car.app.ScreenManager::class.java)
                .push(DestinationListScreen(carContext))
            return NavigationScreen(carContext)
        }
        return DestinationListScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        if (!isNavigationIntent(intent)) return
        acceptNavigationIntent(intent)
        val screens = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
        if (carContext.carAppApiLevel >= 5) {
            screens.popToRoot()
            return
        }
        if (screens.top !is NavigationScreen) screens.push(NavigationScreen(carContext))
    }

    private fun initialize() {
        if (navigation != null) return
        val repository = NavigationRepository.get(carContext)
        val navigationManager = carContext.getCarService(NavigationManager::class.java)
        navigation = repository
        manager = navigationManager
        navigationManager.setNavigationManagerCallback(
            carContext.mainExecutor,
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    repository.stop()
                    endNavigation()
                }

                override fun onAutoDriveEnabled() {
                    repository.setAutoDriveEnabled(true)
                }
            },
        )
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = sessionScope
        sessionScope.launch {
            repository.state.collect { publishNavigation(it) }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope?.cancel()
                scope = null
                endNavigation()
                navigationManager.clearNavigationManagerCallback()
                repository.setAutoDriveEnabled(false)
            }
        })
    }

    private fun publishNavigation(state: NavigationState) {
        val navigationManager = manager ?: return
        if (!state.active) {
            endNavigation()
            return
        }
        try {
            if (!announcedNavigation) {
                navigationManager.navigationStarted()
                announcedNavigation = true
            }
            val trip = Trip.Builder()
            if (state.loading) {
                trip.setLoading(true)
            } else {
                val distance = state.distanceToTurnMeters
                if (distance != null) {
                    trip.addStep(carStep(state), carTravelEstimate(distance, secondsToStep(state)))
                    val following = state.followingStep
                    val next = state.nextStep
                    if (following != null && next != null) {
                        val followingDistance = distance +
                            (following.routeOffsetMeters - next.routeOffsetMeters).coerceAtLeast(0.0)
                        trip.addStep(carRouteStep(following), carTravelEstimate(followingDistance,
                            secondsToStep(state) + next.durationSeconds.toLong().coerceAtLeast(0)))
                    }
                }
            }
            val destination = state.destination
            if (!state.loading && !state.gpsPaused && !state.offRoute && destination != null &&
                state.remainingMeters != null && state.remainingSeconds != null) {
                trip.addDestination(
                    androidx.car.app.navigation.model.Destination.Builder()
                        .setName(destination.name).build(),
                    carTravelEstimate(state.remainingMeters, state.remainingSeconds),
                )
            }
            navigationManager.updateTrip(trip.build())
        } catch (_: RuntimeException) {
            // Losing the host/focus must stop voice guidance and avoid two competing navigators.
            navigation?.stop()
            endNavigation()
        }
    }

    private fun endNavigation() {
        if (!announcedNavigation) return
        announcedNavigation = false
        runCatching { manager?.navigationEnded() }
    }

    private fun isNavigationIntent(intent: Intent): Boolean =
        intent.action == CarContext.ACTION_NAVIGATE ||
            (intent.action == Intent.ACTION_VIEW && intent.data?.scheme in setOf("geo", "google.navigation"))

    private fun acceptNavigationIntent(intent: Intent) {
        if (!canStartCarNavigation(carContext)) return
        val data = intent.data ?: return
        incomingIntentJob?.cancel()
        incomingIntentJob = scope?.launch {
            val destination = withContext(Dispatchers.IO) {
                runCatching {
                    val encodedPart = data.encodedSchemeSpecificPart
                    val rawQuery = if (data.scheme == "google.navigation") encodedPart
                        else encodedPart.substringAfter("?", "")
                    val query = rawQuery
                        .split('&').firstOrNull { it.startsWith("q=") }
                        ?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    val coordinateText = query?.substringBefore('(')?.trim()
                        ?: data.schemeSpecificPart.substringBefore('?')
                    val coordinates = coordinateText.split(',').mapNotNull { it.trim().toDoubleOrNull() }
                    if (coordinates.size == 2 && coordinates[0] in -90.0..90.0 && coordinates[1] in -180.0..180.0) {
                        Destination(UUID.randomUUID().toString(), "Destination demandée", coordinates[0], coordinates[1])
                    } else {
                        DestinationRepository.get(carContext).search(query ?: coordinateText).firstOrNull()
                    }
                }.getOrNull()
            }
            if (destination == null) message("Destination introuvable. Choisissez un lieu enregistré.")
            else navigation?.start(destination)
        }
    }

    private fun message(text: String) =
        CarToast.makeText(carContext, text, CarToast.LENGTH_LONG).show()
}
