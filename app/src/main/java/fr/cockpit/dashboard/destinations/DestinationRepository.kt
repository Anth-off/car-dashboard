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
import kotlin.math.abs

/** Favorites and navigation history stay on this phone. A search never saves a place. */
class DestinationRepository internal constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(readDestinations(SAVED_DESTINATIONS))
    private val mutableRecents = MutableStateFlow(readDestinations(RECENT_DESTINATIONS).take(MAX_RECENTS))
    val state: StateFlow<List<Destination>> = mutableState.asStateFlow()
    val recents: StateFlow<List<Destination>> = mutableRecents.asStateFlow()

    @Synchronized
    fun add(name: String, latitude: Double, longitude: Double): Destination =
        addFavorite(Destination(UUID.randomUUID().toString(), name.trim(), latitude, longitude))

    @Synchronized
    fun addFavorite(destination: Destination): Destination {
        mutableState.value.firstOrNull { samePlace(it, destination) }?.let { return it }
        val favorite = withUniqueId(destination, mutableState.value)
        save(SAVED_DESTINATIONS, mutableState.value + favorite, mutableState)
        return favorite
    }

    fun isFavorite(destination: Destination): Boolean = mutableState.value.any { samePlace(it, destination) }

    @Synchronized
    fun toggleFavorite(destination: Destination) {
        val existing = mutableState.value.firstOrNull { samePlace(it, destination) }
        if (existing == null) addFavorite(destination) else remove(existing.id)
    }

    @Synchronized
    fun remove(id: String) {
        save(SAVED_DESTINATIONS, mutableState.value.filterNot { it.id == id }, mutableState)
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val label = name.trim()
        require(label.isNotEmpty()) { "Donnez un nom à cette destination." }
        save(SAVED_DESTINATIONS, mutableState.value.map { if (it.id == id) it.copy(name = label) else it }, mutableState)
    }

    /** Call only when the user starts navigation, including journeys started in Android Auto. */
    @Synchronized
    fun recordVisit(destination: Destination) {
        val previous = mutableRecents.value.firstOrNull { samePlace(it, destination) }
        val selected = mutableState.value.firstOrNull { samePlace(it, destination) }
            ?: previous?.let { destination.copy(id = it.id) } ?: destination
        val remaining = mutableRecents.value.filterNot { samePlace(it, selected) }
        val recent = withUniqueId(selected, remaining)
        save(RECENT_DESTINATIONS,
            (listOf(recent) + remaining).take(MAX_RECENTS), mutableRecents)
    }

    @Synchronized
    fun clearRecents() {
        save(RECENT_DESTINATIONS, emptyList(), mutableRecents)
    }

    /** Explicit search through Android's geocoding provider; coordinates work without a network. */
    suspend fun search(query: String): List<Destination> {
        val addressQuery = query.trim()
        require(addressQuery.isNotEmpty()) { "Saisissez une adresse ou un lieu." }
        parseCoordinates(addressQuery)?.let { (latitude, longitude) ->
            return listOf(Destination(UUID.randomUUID().toString(), "Point GPS", latitude, longitude,
                String.format(Locale.FRANCE, "%.5f°, %.5f°", latitude, longitude)))
        }
        if (!Geocoder.isPresent()) {
            throw DestinationSearchException("La recherche d’adresses n’est pas disponible sur ce téléphone. Vous pouvez saisir des coordonnées GPS.")
        }
        val geocoder = Geocoder(appContext, Locale.getDefault())
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                geocoder.getFromLocationName(addressQuery, MAX_RESULTS, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resumeWith(Result.success(addresses.toList()))
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(searchFailure()))
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(addressQuery, MAX_RESULTS).orEmpty()
                } catch (error: IOException) {
                    throw searchFailure(error)
                }
            }
        }
        return addresses.asSequence()
            .filter { it.hasLatitude() && it.hasLongitude() }
            .filter { it.latitude.isFinite() && it.latitude in -90.0..90.0 }
            .filter { it.longitude.isFinite() && it.longitude in -180.0..180.0 }
            .map { address ->
                val fullAddress = address.getAddressLine(0)?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(address.featureName, address.thoroughfare, address.locality, address.countryName)
                        .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { addressQuery }
                val feature = address.featureName?.takeIf { it.any(Char::isLetter) && it != address.subThoroughfare }
                val street = listOfNotNull(address.subThoroughfare, address.thoroughfare)
                    .filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
                val label = feature ?: street ?: address.locality ?: fullAddress
                Destination(UUID.randomUUID().toString(), label, address.latitude, address.longitude,
                    fullAddress.takeUnless { it == label }.orEmpty())
            }
            .distinctBy { Pair((it.latitude * 100_000).toLong(), (it.longitude * 100_000).toLong()) }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun save(key: String, destinations: List<Destination>, state: MutableStateFlow<List<Destination>>) {
        val records = JSONArray()
        destinations.forEach { destination ->
            records.put(JSONObject().apply {
                put("id", destination.id)
                put("name", destination.name)
                put("latitude", destination.latitude)
                put("longitude", destination.longitude)
                put("address", destination.address)
            })
        }
        preferences.edit().putString(key, records.toString()).apply()
        state.value = destinations
    }

    private fun readDestinations(key: String): List<Destination> {
        val raw = preferences.getString(key, null) ?: return emptyList()
        val records = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                // One invalid record must not hide the other saved places; v1 has no address field.
                val destination = runCatching {
                    Destination(
                        id = record.getString("id"),
                        name = record.getString("name").trim(),
                        latitude = record.getDouble("latitude"),
                        longitude = record.getDouble("longitude"),
                        address = record.optString("address", ""),
                    )
                }.getOrNull() ?: continue
                if (none { samePlace(it, destination) }) add(withUniqueId(destination, this))
            }
        }
    }

    /** External callers can reuse an ID for different places; list keys must remain unique. */
    private fun withUniqueId(destination: Destination, existing: List<Destination>): Destination {
        val occupied = existing.mapTo(mutableSetOf()) { it.id }
        var id = destination.id
        var attempt = 0
        while (id in occupied) {
            // Deterministic repair also keeps IDs stable when loading older duplicate records.
            val identity = "${destination.id}:${destination.latitude}:${destination.longitude}:${attempt++}"
            id = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
        }
        return if (id == destination.id) destination else destination.copy(id = id)
    }

    companion object {
        private const val PREFERENCES = "cockpit_destinations"
        private const val SAVED_DESTINATIONS = "favorites_v1"
        private const val RECENT_DESTINATIONS = "recents_v1"
        private const val MAX_RESULTS = 8
        private const val MAX_RECENTS = 12

        internal fun samePlace(first: Destination, second: Destination): Boolean =
            abs(first.latitude - second.latitude) < 0.0001 && abs(first.longitude - second.longitude) < 0.0001

        /** Decimal points with comma/space separation; French decimal commas use a semicolon. */
        internal fun parseCoordinates(value: String): Pair<Double, Double>? {
            val normalized = value.trim().removePrefix("geo:")
            val parts = if (';' in normalized) normalized.split(';').map { it.trim().replace(',', '.') }
                else normalized.split(Regex("\\s*,\\s*|\\s+"))
            if (parts.size != 2) return null
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0) return null
            return latitude to longitude
        }

        private fun searchFailure(cause: Throwable? = null) = DestinationSearchException(
            "La recherche d’adresses a échoué. Vérifiez votre connexion et réessayez.", cause)

        @Volatile private var instance: DestinationRepository? = null

        fun get(context: Context): DestinationRepository = instance ?: synchronized(this) {
            instance ?: DestinationRepository(context).also { instance = it }
        }
    }
}

class DestinationSearchException(message: String, cause: Throwable? = null) : IOException(message, cause)
