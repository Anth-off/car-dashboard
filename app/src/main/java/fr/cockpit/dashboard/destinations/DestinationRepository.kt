package fr.cockpit.dashboard.destinations

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** Favorites are local to this phone. Calling [search] never saves a place. */
class DestinationRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(readSavedDestinations())
    val state: StateFlow<List<Destination>> = mutableState.asStateFlow()

    @Synchronized
    fun add(name: String, latitude: Double, longitude: Double): Destination {
        val destination = Destination(UUID.randomUUID().toString(), name.trim(), latitude, longitude)
        save(mutableState.value + destination)
        return destination
    }

    @Synchronized
    fun remove(id: String) {
        save(mutableState.value.filterNot { it.id == id })
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val label = name.trim()
        require(label.isNotEmpty()) { "Donnez un nom à cette destination." }
        save(mutableState.value.map { if (it.id == id) it.copy(name = label) else it })
    }

    /**
     * Explicit, user-triggered address search using Android's installed geocoding provider.
     * No key or background location permission is needed. The provider can require a network
     * connection; unavailable providers and request failures are surfaced to the caller.
     */
    suspend fun search(query: String): List<Destination> {
        val addressQuery = query.trim()
        require(addressQuery.isNotEmpty()) { "Saisissez une adresse ou un lieu." }
        if (!Geocoder.isPresent()) {
            throw DestinationSearchException("La recherche d’adresses n’est pas disponible sur ce téléphone.")
        }
        val geocoder = Geocoder(appContext, Locale.getDefault())
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                geocoder.getFromLocationName(addressQuery, MAX_RESULTS, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resumeWith(Result.success(addresses.toList()))
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(
                            DestinationSearchException("La recherche d’adresses a échoué. Vérifiez votre connexion et réessayez.")
                        ))
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(addressQuery, MAX_RESULTS).orEmpty()
                } catch (error: IOException) {
                    throw DestinationSearchException(
                        "La recherche d’adresses a échoué. Vérifiez votre connexion et réessayez.", error
                    )
                }
            }
        }
        return addresses.asSequence()
            .filter { it.hasLatitude() && it.hasLongitude() }
            .filter { it.latitude.isFinite() && it.latitude in -90.0..90.0 }
            .filter { it.longitude.isFinite() && it.longitude in -180.0..180.0 }
            .map { address ->
                val label = address.getAddressLine(0)?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(address.featureName, address.thoroughfare, address.locality, address.countryName)
                        .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { addressQuery }
                Destination(UUID.randomUUID().toString(), label, address.latitude, address.longitude)
            }
            .distinctBy { Triple(it.name, it.latitude, it.longitude) }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun save(destinations: List<Destination>) {
        val records = JSONArray()
        destinations.forEach { destination ->
            records.put(JSONObject().apply {
                put("id", destination.id)
                put("name", destination.name)
                put("latitude", destination.latitude)
                put("longitude", destination.longitude)
            })
        }
        preferences.edit().putString(SAVED_DESTINATIONS, records.toString()).apply()
        mutableState.value = destinations
    }

    private fun readSavedDestinations(): List<Destination> {
        val raw = preferences.getString(SAVED_DESTINATIONS, null) ?: return emptyList()
        val records = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                // One invalid record must not hide the other saved places.
                val destination = runCatching {
                    Destination(
                        id = record.getString("id"),
                        name = record.getString("name").trim(),
                        latitude = record.getDouble("latitude"),
                        longitude = record.getDouble("longitude"),
                    )
                }.getOrNull() ?: continue
                add(destination)
            }
        }.distinctBy { it.id }
    }

    companion object {
        private const val PREFERENCES = "cockpit_destinations"
        private const val SAVED_DESTINATIONS = "favorites_v1"
        private const val MAX_RESULTS = 5

        @Volatile private var instance: DestinationRepository? = null

        fun get(context: Context): DestinationRepository = instance ?: synchronized(this) {
            instance ?: DestinationRepository(context).also { instance = it }
        }
    }
}

class DestinationSearchException(message: String, cause: Throwable? = null) : IOException(message, cause)
