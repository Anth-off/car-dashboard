package fr.cockpit.dashboard.telemetry

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Process-wide state; no location history is stored. Only two counters are persisted locally. */
class TripRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences("recorded_trips", Context.MODE_PRIVATE)
    private val lock = Any()
    private val accumulator = DistanceAccumulator()
    private var lastAcceptedElapsedMillis: Long? = null
    private var lastPersistenceElapsedMillis = 0L
    private val mutableState = MutableStateFlow(
        TelemetryState(
            totalMeters = restoredMeters("total_meters"),
            tripMeters = restoredMeters("trip_meters"),
        ),
    )
    val state: StateFlow<TelemetryState> = mutableState.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (isActive) {
                delay(1_000)
                synchronized(lock) {
                    val last = lastAcceptedElapsedMillis
                    if (last != null && SystemClock.elapsedRealtime() - last > STALE_AFTER_MS) {
                        mutableState.value = mutableState.value.copy(speedKmh = null, bearingDegrees = null)
                        accumulator.reset()
                        lastAcceptedElapsedMillis = null
                    }
                }
            }
        }
    }

    /**
     * Call only after an explicit user action in a visible Activity and after precise location
     * permission has been granted. A location foreground service keeps recording in the background.
     * This API does not request permissions or start recording automatically in the background.
     */
    fun startTracking(context: Context) {
        if (state.value.recording) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TripTrackingService::class.java).setAction(TripTrackingService.ACTION_START),
            )
        } catch (_: RuntimeException) {
            onTrackingError("Impossible de démarrer le GPS. Ouvre l’application et autorise la localisation précise.")
        }
    }

    /** Stops sampling and persists both counters. The trip counter is kept until [resetTrip]. */
    @android.annotation.SuppressLint("ImplicitSamInstance") // Android matches this explicit Intent by service component, not object identity.
    fun stopTracking(context: Context) {
        onTrackingStopped()
        context.stopService(Intent(context, TripTrackingService::class.java))
    }

    /** Resets only the trip counter. The lifetime recorded distance is retained. */
    fun resetTrip() = synchronized(lock) {
        accumulator.reset()
        mutableState.value = mutableState.value.copy(tripMeters = 0.0)
        persist()
    }

    internal fun onTrackingStarted() = synchronized(lock) {
        accumulator.reset()
        lastAcceptedElapsedMillis = null
        mutableState.value = mutableState.value.copy(recording = true, speedKmh = null, bearingDegrees = null, error = null)
    }

    internal fun onTrackingStopped() = synchronized(lock) {
        accumulator.reset()
        lastAcceptedElapsedMillis = null
        mutableState.value = mutableState.value.copy(recording = false, speedKmh = null, bearingDegrees = null)
        persist()
    }

    internal fun onTrackingError(message: String) = synchronized(lock) {
        onTrackingStopped()
        mutableState.value = mutableState.value.copy(error = message)
    }

    internal fun onProviderUnavailable() = synchronized(lock) {
        accumulator.reset()
        lastAcceptedElapsedMillis = null
        mutableState.value = mutableState.value.copy(speedKmh = null, bearingDegrees = null, error = "GPS désactivé. Active la localisation du téléphone.")
    }

    internal fun accept(location: Location) = synchronized(lock) {
        if (!state.value.recording || !location.hasAccuracy()) return@synchronized
        val now = SystemClock.elapsedRealtime()
        val sample = accumulator.accept(
            GpsFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                elapsedRealtimeMillis = location.elapsedRealtimeNanos / 1_000_000,
                speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
                speedAccuracyMetersPerSecond = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
            ),
            now,
        ) ?: return@synchronized
        lastAcceptedElapsedMillis = sample.fix.elapsedRealtimeMillis
        val previous = mutableState.value
        mutableState.value = previous.copy(
            speedKmh = sample.speedMetersPerSecond?.times(3.6f),
            bearingDegrees = location.bearing.takeIf {
                location.hasBearing() && it.isFinite() && (sample.speedMetersPerSecond ?: 0f) > 1f &&
                    (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees <= 45f)
            },
            totalMeters = previous.totalMeters + sample.distanceMeters,
            tripMeters = previous.tripMeters + sample.distanceMeters,
            latitude = sample.fix.latitude,
            longitude = sample.fix.longitude,
            accuracyMeters = sample.fix.accuracyMeters,
            lastFixEpochMillis = System.currentTimeMillis() - (now - sample.fix.elapsedRealtimeMillis),
            error = null,
        )
        // Commit a snapshot every five seconds; clean stop/reset always saves immediately.
        if (now - lastPersistenceElapsedMillis >= 5_000) persist()
    }

    private fun restoredMeters(key: String): Double =
        Double.fromBits(preferences.getLong(key, 0L)).takeIf { it.isFinite() && it >= 0 } ?: 0.0

    private fun persist() {
        val value = mutableState.value
        preferences.edit()
            .putLong("total_meters", value.totalMeters.toBits())
            .putLong("trip_meters", value.tripMeters.toBits())
            .apply()
        lastPersistenceElapsedMillis = SystemClock.elapsedRealtime()
    }

    companion object {
        private const val STALE_AFTER_MS = 7_000L
        @Volatile private var instance: TripRepository? = null

        fun get(context: Context): TripRepository = instance ?: synchronized(this) {
            instance ?: TripRepository(context).also { instance = it }
        }
    }
}
