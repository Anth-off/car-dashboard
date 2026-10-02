package fr.cockpit.dashboard.integrations

import android.content.Context
import android.os.SystemClock
import fr.cockpit.dashboard.telemetry.TelemetryState
import fr.cockpit.dashboard.telemetry.TripRepository
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.round

data class WeatherState(
    val temperatureC: Double? = null,
    val summary: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAtMillis: Long? = null,
    val enabled: Boolean = false,
)

/**
 * Optional regional weather, not a vehicle's outside-temperature sensor.
 * Only rounded coordinates are sent to Open-Meteo, at most once every 15 minutes.
 * Readings and coordinates are not persisted. The consent preference is persisted.
 */
class WeatherRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext
        .getSharedPreferences("weather_preferences", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        WeatherState(enabled = preferences.getBoolean("enabled", false)),
    )
    val state: StateFlow<WeatherState> = mutableState.asStateFlow()
    val enabled: Boolean get() = mutableState.value.enabled

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var requestJob: Job? = null
    private var lastAttemptElapsed: Long? = null
    private var generation = 0L
    private var telemetryJob: Job? = null

    init {
        startObservingTelemetry()
    }

    /** Independent of Activity lifetimes; does not start recording or request location access. */
    fun startObservingTelemetry() {
        synchronized(lock) {
            if (telemetryJob?.isActive == true) return
            telemetryJob = scope.launch {
                TripRepository.get(applicationContext).state.collect { telemetry ->
                    updateFromTelemetry(telemetry)
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (mutableState.value.enabled == enabled) return
            preferences.edit().putBoolean("enabled", enabled).apply()
            generation += 1L
            requestJob?.cancel()
            requestJob = null
            // Clearing also avoids displaying a reading after its consent was withdrawn.
            mutableState.value = WeatherState(enabled = enabled)
        }
        if (enabled) updateFromTelemetry(TripRepository.get(applicationContext).state.value)
    }

    private fun updateFromTelemetry(telemetry: TelemetryState) {
        if (!enabled || !telemetry.recording || telemetry.error != null) return
        val fixedAt = telemetry.lastFixEpochMillis ?: return
        val ageMillis = System.currentTimeMillis() - fixedAt
        if (ageMillis !in 0L until MAX_FIX_AGE_MS) return
        val latitude = telemetry.latitude ?: return
        val longitude = telemetry.longitude ?: return
        updateLocation(latitude, longitude)
    }

    fun updateLocation(lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        synchronized(lock) {
            if (!enabled || requestJob?.isActive == true) return
            val now = SystemClock.elapsedRealtime()
            if (lastAttemptElapsed?.let { now - it < MIN_REQUEST_INTERVAL_MS } == true) return
            val roundedLatitude = round(lat * 100.0) / 100.0
            val roundedLongitude = round(lon * 100.0) / 100.0
            val requestGeneration = generation
            lastAttemptElapsed = now
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            requestJob = scope.launch {
                try {
                    val result = fetch(roundedLatitude, roundedLongitude)
                    ensureActive()
                    synchronized(lock) {
                        if (enabled && generation == requestGeneration) {
                            mutableState.value = mutableState.value.copy(
                                temperatureC = result.temperatureC,
                                summary = result.summary,
                                loading = false,
                                error = null,
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ensureActive()
                    synchronized(lock) {
                        if (enabled && generation == requestGeneration) {
                            // The timestamp remains unchanged so the UI can identify an old reading.
                            mutableState.value = mutableState.value.copy(
                                loading = false,
                                error = when (error) {
                                    is SocketTimeoutException -> "Le service météo met trop de temps à répondre."
                                    is UnknownHostException -> "Connexion météo indisponible."
                                    else -> "Météo indisponible pour le moment."
                                },
                            )
                        }
                    }
                } finally {
                    synchronized(lock) {
                        if (generation == requestGeneration) {
                            mutableState.value = mutableState.value.copy(loading = false)
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetch(latitude: Double, longitude: Double): Reading = suspendCancellableCoroutine { continuation ->
        val lat = String.format(Locale.US, "%.2f", latitude)
        val lon = String.format(Locale.US, "%.2f", longitude)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code&temperature_unit=celsius&timezone=UTC",
        )
        val connection = url.openConnection() as HttpURLConnection
        // Turning weather off also closes an in-flight connection, rather than waiting for its timeout.
        continuation.invokeOnCancellation { runCatching { connection.disconnect() } }
        try {
            if (!continuation.isActive) return@suspendCancellableCoroutine
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "CockpitDashboard/1.0 (Android)")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Weather service unavailable")
            }
            // A forecast response should be small; bound reads even for a malformed upstream response.
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > 65_536) throw IOException("Weather response too large")
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            val current = JSONObject(response).getJSONObject("current")
            val temperature = current.optDouble("temperature_2m", Double.NaN)
            if (!temperature.isFinite()) throw IOException("Missing weather temperature")
            continuation.resume(Reading(temperature, weatherSummary(current.optInt("weather_code", -1))))
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally {
            connection.disconnect()
        }
    }

    private data class Reading(val temperatureC: Double, val summary: String)

    companion object {
        private const val MIN_REQUEST_INTERVAL_MS = 15 * 60 * 1000L
        private const val MAX_FIX_AGE_MS = 10_000L
        @Volatile private var instance: WeatherRepository? = null

        fun get(context: Context): WeatherRepository = instance ?: synchronized(this) {
            instance ?: WeatherRepository(context).also { instance = it }
        }

        internal fun weatherSummary(code: Int): String = when (code) {
            0 -> "Ciel dégagé"
            1 -> "Peu nuageux"
            2 -> "Partiellement nuageux"
            3 -> "Couvert"
            45, 48 -> "Brouillard"
            51, 53, 55 -> "Bruine"
            56, 57 -> "Bruine verglaçante"
            61, 63, 65 -> "Pluie"
            66, 67 -> "Pluie verglaçante"
            71, 73, 75, 77 -> "Neige"
            80, 81, 82 -> "Averses"
            85, 86 -> "Averses de neige"
            95 -> "Orage"
            96, 99 -> "Orage avec grêle"
            else -> "Conditions indisponibles"
        }
    }
}
