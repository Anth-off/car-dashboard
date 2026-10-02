package fr.cockpit.dashboard.navigation

import android.content.Context
import android.os.SystemClock
import fr.cockpit.dashboard.destinations.Destination
import fr.cockpit.dashboard.destinations.DestinationRepository
import fr.cockpit.dashboard.telemetry.TelemetryState
import fr.cockpit.dashboard.telemetry.TripRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * User-started online navigation. The caller explains the public routing service and coordinate
 * transfer, obtains location permission, then starts TripRepository from a visible phone Activity.
 * This repository never starts a foreground service or grants itself background location access.
 */
class NavigationRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val trips = TripRepository.get(applicationContext)
    private val router = OsrmRoutingClient()
    private val voice = NavigationVoice(applicationContext)
    private val preferences = applicationContext.getSharedPreferences("navigation_preferences", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(NavigationState(voiceEnabled = preferences.getBoolean("voice_enabled", true)))
    val state: StateFlow<NavigationState> = mutableState.asStateFlow()
    private var request: Job? = null
    private var requestGeneration = 0L
    private var requestedDestination: Destination? = null
    private val reroutePolicy = AutomaticReroutePolicy()
    private var progress: RouteProgressEngine? = null
    private var lastSpeechKey: String? = null
    private var lastConsumedFixTime: Long? = null
    private var simulationEnabled = false
    private var simulationDistance = 0.0

    init {
        scope.launch { trips.state.collect { updateTelemetry(it) } }
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (simulationEnabled && state.value.active) simulateNextFix()
                else updateTelemetry(trips.state.value)
            }
        }
    }

    /** Requests a genuine driving route. Cancels any older pending route request. */
    fun start(destination: Destination) {
        if (destination != state.value.destination) reroutePolicy.reset()
        requestRoute(destination, automatic = false)
    }

    private fun requestRoute(destination: Destination, automatic: Boolean) {
        request?.cancel()
        val generation = ++requestGeneration
        requestedDestination = destination
        if (!automatic) reroutePolicy.requestStarted(SystemClock.elapsedRealtime())
        request = scope.launch {
            if (!automatic) voice.stop()
            mutableState.value = mutableState.value.copy(
                loading = true, error = null,
                rerouting = state.value.active,
                destination = if (state.value.active) state.value.destination else destination,
                arrived = false,
                instruction = if (hasFreshFix(trips.state.value)) {
                    if (state.value.active) "Recalcul de l’itinéraire…" else "Calcul de l’itinéraire…"
                } else "Recherche du signal GPS…",
            )
            try {
                val telemetry = withTimeoutOrNull(20_000) { trips.state.first(::hasFreshFix) }
                if (telemetry == null) {
                    mutableState.value = mutableState.value.copy(
                        loading = false, rerouting = false, gpsPaused = true,
                        distanceToTurnMeters = null, remainingMeters = null, remainingSeconds = null,
                        nextStep = null, followingStep = null,
                        error = "Pas de position GPS récente. Démarre l’enregistrement et attends le signal.",
                        instruction = if (state.value.active) "Guidage en pause · GPS nécessaire" else "GPS nécessaire pour calculer l’itinéraire",
                    )
                    reroutePolicy.requestFinished(success = false, SystemClock.elapsedRealtime())
                    return@launch
                }
                mutableState.value = mutableState.value.copy(
                    gpsPaused = false,
                    instruction = if (state.value.active) "Recalcul de l’itinéraire…" else "Calcul de l’itinéraire…",
                )
                val route = router.route(GeoPoint(telemetry.latitude!!, telemetry.longitude!!), destination)
                ensureActive()
                progress = RouteProgressEngine(route)
                lastSpeechKey = null
                lastConsumedFixTime = null
                simulationDistance = 0.0
                mutableState.value = NavigationState(
                    active = true, destination = destination, route = route,
                    instruction = "Itinéraire prêt", remainingMeters = route.totalMeters,
                    remainingSeconds = route.totalSeconds.toLong(), voiceEnabled = state.value.voiceEnabled,
                    isSimulation = simulationEnabled,
                )
                DestinationRepository.get(applicationContext).recordVisit(destination)
                reroutePolicy.requestFinished(success = true, SystemClock.elapsedRealtime())
                if (simulationEnabled) simulateNextFix() else updateTelemetry(trips.state.value)
            } catch (cancelled: CancellationException) {
                if (generation == requestGeneration) reroutePolicy.requestCancelled()
                throw cancelled
            } catch (failure: Exception) {
                // An HTTP failure may arrive after cancellation of its blocking socket read.
                // It must never replace the state of a newer destination request.
                ensureActive()
                mutableState.value = mutableState.value.copy(
                    loading = false, rerouting = false,
                    error = failure.message?.take(220) ?: "Le calcul de l’itinéraire a échoué. Vérifie la connexion internet.",
                    instruction = if (state.value.active) "Itinéraire précédent conservé" else "Itinéraire indisponible",
                )
                reroutePolicy.requestFinished(success = false, SystemClock.elapsedRealtime())
            }
        }
    }

    fun reroute() { state.value.destination?.let(::start) }

    fun stop() {
        request?.cancel()
        requestGeneration++
        reroutePolicy.reset()
        scope.launch {
            voice.stop()
            progress = null
            requestedDestination = null
            lastConsumedFixTime = null
            lastSpeechKey = null
            simulationEnabled = false
            mutableState.value = NavigationState(voiceEnabled = state.value.voiceEnabled)
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        scope.launch {
            preferences.edit().putBoolean("voice_enabled", enabled).apply()
            mutableState.value = mutableState.value.copy(voiceEnabled = enabled)
            if (!enabled) voice.stop()
            lastSpeechKey = null
        }
    }

    /** Explicit host/DHU-only simulation. Uses an actual route and never writes GPS trip counters. */
    fun setAutoDriveEnabled(enabled: Boolean) {
        scope.launch {
            // A normal car disconnect sends false too; it must not rewind real phone navigation.
            if (simulationEnabled == enabled) return@launch
            simulationEnabled = enabled
            simulationDistance = 0.0
            lastConsumedFixTime = null
            lastSpeechKey = null
            if (!enabled) {
                // Simulated progress cannot be reused with a physical GPS fix. Require a new route
                // from the current real position instead of silently resetting an active route.
                request?.cancel()
                requestGeneration++
                reroutePolicy.reset()
                voice.stop()
                progress = null
                mutableState.value = NavigationState(
                    destination = state.value.destination,
                    voiceEnabled = state.value.voiceEnabled,
                    instruction = "Simulation terminée · recalcule le trajet avec le GPS",
                )
                return@launch
            }
            state.value.route?.let { progress = RouteProgressEngine(it) }
            mutableState.value = mutableState.value.copy(isSimulation = enabled)
        }
    }

    private fun updateTelemetry(telemetry: TelemetryState) {
        if (!state.value.active || simulationEnabled) return
        if (!hasFreshFix(telemetry)) {
            mutableState.value = mutableState.value.copy(
                instruction = if (telemetry.recording) "Signal GPS perdu · guidage en pause" else "Guidage en pause · démarre le GPS sur le téléphone",
                distanceToTurnMeters = null, remainingMeters = null, remainingSeconds = null,
                nextStep = null, followingStep = null, gpsPaused = true,
            )
            if (lastSpeechKey != "gps-lost") {
                announce("Signal GPS perdu. Le guidage est en pause.")
                lastSpeechKey = "gps-lost"
            }
            return
        }
        val time = telemetry.lastFixEpochMillis!!
        if (lastConsumedFixTime?.let { time <= it } == true) return
        lastConsumedFixTime = time
        applyFix(GeoPoint(telemetry.latitude!!, telemetry.longitude!!), telemetry.accuracyMeters!!.toDouble(), time)
    }

    private fun applyFix(point: GeoPoint, accuracy: Double, timestamp: Long) {
        val route = state.value.route ?: return
        val result = progress?.update(point, accuracy, timestamp) ?: return
        val step = route.steps[result.nextStepIndex]
        val completedPendingDestination = result.arrived && state.value.loading && requestedDestination == state.value.destination
        if (completedPendingDestination) {
            request?.cancel()
            requestGeneration++
            reroutePolicy.reset()
        }
        val instruction = when {
            result.arrived -> "Vous êtes arrivé à destination"
            result.offRoute && state.value.rerouting -> "Recalcul de l’itinéraire…"
            result.offRoute -> "Hors itinéraire · recalcul automatique"
            else -> step.instruction
        }
        mutableState.value = mutableState.value.copy(
            active = !result.arrived, arrived = result.arrived, offRoute = result.offRoute,
            loading = state.value.loading && !completedPendingDestination,
            rerouting = state.value.rerouting && !completedPendingDestination,
            instruction = if (simulationEnabled) "Simulation · $instruction" else instruction,
            distanceToTurnMeters = if (result.offRoute) null else result.distanceToTurnMeters,
            remainingMeters = if (result.offRoute) null else result.remainingMeters,
            remainingSeconds = if (result.offRoute) null else result.remainingSeconds,
            nextStep = if (result.offRoute) null else step,
            followingStep = if (result.arrived || result.offRoute) null else route.steps.getOrNull(result.nextStepIndex + 1),
            nextStepIndex = result.nextStepIndex,
            progressFraction = result.progressFraction,
            gpsPaused = false,
            error = if (state.value.offRoute && !result.offRoute) null else state.value.error,
            currentPosition = point,
        )
        val phase = when {
            result.distanceToTurnMeters > 500 -> 3
            result.distanceToTurnMeters > 100 -> 2
            result.distanceToTurnMeters > 25 -> 1
            else -> 0
        }
        val key = when {
            result.arrived -> "arrived"
            result.offRoute -> "off-route"
            else -> "${result.nextStepIndex}:$phase"
        }
        if (lastSpeechKey != key) {
            val text = when {
                result.arrived -> "Vous êtes arrivé à destination."
                result.offRoute -> "Vous avez quitté l’itinéraire. Recalcul automatique du trajet."
                result.distanceToTurnMeters < 25 -> step.instruction
                else -> "Dans ${spokenDistance(result.distanceToTurnMeters)}, ${step.instruction.replaceFirstChar { it.lowercase() }}"
            }
            announce(if (simulationEnabled) "Simulation. $text" else text)
            lastSpeechKey = key
        }
        if (state.value.active && reroutePolicy.tryStart(
                offRoute = result.offRoute,
                freshFix = hasFreshFix(trips.state.value),
                simulation = simulationEnabled,
                nowMillis = SystemClock.elapsedRealtime(),
            )) {
            state.value.destination?.let { requestRoute(it, automatic = true) }
                ?: reroutePolicy.requestCancelled()
        }
    }

    private fun simulateNextFix() {
        val route = state.value.route ?: return
        val offsets = RouteProgressEngine.cumulativeDistances(route.points)
        val index = offsets.indexOfFirst { it >= simulationDistance }.coerceAtLeast(1)
        val end = index.coerceAtMost(route.points.lastIndex)
        val length = offsets[end] - offsets[end - 1]
        val fraction = if (length <= 0) 0.0 else ((simulationDistance - offsets[end - 1]) / length).coerceIn(0.0, 1.0)
        applyFix(RouteProgressEngine.interpolate(route.points[end - 1], route.points[end], fraction), 3.0, System.currentTimeMillis())
        simulationDistance = (simulationDistance + 13.9).coerceAtMost(offsets.last())
    }

    private fun announce(text: String) { if (state.value.voiceEnabled) voice.speak(text) }

    private fun hasFreshFix(telemetry: TelemetryState): Boolean {
        val age = telemetry.lastFixEpochMillis?.let { System.currentTimeMillis() - it } ?: return false
        return telemetry.recording && age in 0..7_000 && telemetry.latitude != null && telemetry.longitude != null &&
            telemetry.latitude in -90.0..90.0 && telemetry.longitude in -180.0..180.0 &&
            telemetry.accuracyMeters?.let { it.isFinite() && it in 0f..25f } == true
    }

    private fun spokenDistance(meters: Double): String = if (meters >= 1_000) {
        val kilometers = (meters / 1000.0 * 10).roundToInt() / 10.0
        "${kilometers.toString().replace('.', ',')} kilomètres"
    } else "${((meters / 10).roundToInt() * 10).coerceAtLeast(10)} mètres"

    companion object {
        @Volatile private var instance: NavigationRepository? = null
        fun get(context: Context): NavigationRepository = instance ?: synchronized(this) {
            instance ?: NavigationRepository(context).also { instance = it }
        }
    }
}
