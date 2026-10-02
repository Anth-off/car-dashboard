package fr.cockpit.dashboard.navigation

import fr.cockpit.dashboard.destinations.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal data class RouteAlternatives(val routes: List<Route>, val warning: String? = null)

/** Public demo router: no uptime, live traffic, offline routing, or access-restriction guarantee. */
internal class OsrmRoutingClient {
    suspend fun routes(origin: GeoPoint, destination: Destination, bearingDegrees: Float? = null): RouteAlternatives = withContext(Dispatchers.IO) {
        val connection = (requestUrl(origin, destination, bearingDegrees).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Cockpit-Android/0.3 (personal navigation prototype)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            check(status == 200 || status == 400) { "Le service d’itinéraires est indisponible ($status)." }
            val input = if (status == 200) connection.inputStream else connection.errorStream
            checkNotNull(input) { "Le service d’itinéraires n’a pas répondu." }
            val body = input.bufferedReader().use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                    check(output.length <= 8_000_000) { "L’itinéraire est trop long pour ce prototype." }
                }
                output.toString()
            }
            coroutineContext.ensureActive()
            parseResponse(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    internal fun requestUrl(origin: GeoPoint, destination: Destination, bearingDegrees: Float?): URL {
        require(validPoint(origin) && validPoint(GeoPoint(destination.latitude, destination.longitude))) {
            "Les coordonnées de départ ou d’arrivée sont invalides."
        }
        // Limit snapping: an address must not silently resolve to a distant road across a river or
        // on the opposite side of a site. Heading disambiguates carriageways while actually moving.
        val bearing = bearingDegrees?.takeIf { it.isFinite() }?.let {
            val normalized = ((it % 360 + 360) % 360).roundToInt() % 360
            "&bearings=$normalized,90;"
        }.orEmpty()
        return URL("https://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?steps=true&geometries=geojson&overview=full&alternatives=true&radiuses=100;200$bearing")
    }

    internal fun parseResponse(response: JSONObject): RouteAlternatives {
        check(response.optString("code") == "Ok") {
            when (response.optString("code")) {
                "NoSegment" -> "Aucune route accessible près du départ ou de l’arrivée. Choisis l’entrée routière de la destination."
                "NoRoute" -> "Aucun trajet en voiture ne relie ces points. Vérifie l’entrée de la destination."
                else -> "Aucun itinéraire routier trouvé vers cette destination."
            }
        }
        val jsonRoutes = response.getJSONArray("routes")
        val routes = (0 until minOf(jsonRoutes.length(), 10)).map { parseRoute(jsonRoutes.getJSONObject(it)) }
            // OSRM's routing weight also reflects road preferences. Compare actual travel times
            // instead of assuming the first route returned is the fastest estimated alternative.
            .sortedWith(compareBy<Route> { it.totalSeconds }.thenBy { it.totalMeters })
            .distinctBy { it.points }
            .take(3)
        check(routes.isNotEmpty()) { "Aucun itinéraire routier trouvé vers cette destination." }
        val waypoints = response.optJSONArray("waypoints")
        val startGap = waypoints?.optJSONObject(0)?.optDouble("distance", 0.0) ?: 0.0
        val endGap = waypoints?.optJSONObject(1)?.optDouble("distance", 0.0) ?: 0.0
        val warnings = buildList {
            if (startGap > 50.0) add("Départ routier à ${startGap.roundToInt()} m du GPS")
            if (endGap > 50.0) add("Arrivée routière à ${endGap.roundToInt()} m du point choisi : vérifie l’entrée")
        }
        return RouteAlternatives(routes, warnings.takeIf { it.isNotEmpty() }?.joinToString(". "))
    }

    private fun parseRoute(jsonRoute: JSONObject): Route {
        val coordinates = jsonRoute.getJSONObject("geometry").getJSONArray("coordinates")
        check(coordinates.length() in 2..100_000) { "La géométrie de l’itinéraire est indisponible." }
        val points = (0 until coordinates.length()).map { i ->
            val pair = coordinates.getJSONArray(i)
            GeoPoint(pair.getDouble(1), pair.getDouble(0)).also {
                check(validPoint(it)) { "L’itinéraire contient des coordonnées invalides." }
            }
        }
        val meters = jsonRoute.getDouble("distance")
        val seconds = jsonRoute.getDouble("duration")
        check(meters.isFinite() && meters > 0.0 && seconds.isFinite() && seconds > 0.0) {
            "La distance ou la durée de l’itinéraire est indisponible."
        }
        val offsets = RouteProgressEngine.cumulativeDistances(points)
        var lastGeometryIndex = 0
        val steps = mutableListOf<RouteStep>()
        val summaries = mutableListOf<String>()
        val legs = jsonRoute.getJSONArray("legs")
        for (legIndex in 0 until legs.length()) {
            val leg = legs.getJSONObject(legIndex)
            leg.optString("summary").takeIf { it.isNotBlank() }?.let(summaries::add)
            val jsonSteps = leg.getJSONArray("steps")
            for (stepIndex in 0 until jsonSteps.length()) {
                val step = jsonSteps.getJSONObject(stepIndex)
                check(step.optString("mode") in setOf("driving", "ferry")) {
                    "Le service n’a pas renvoyé un trajet en voiture. Relance le calcul."
                }
                val maneuver = step.getJSONObject("maneuver")
                val coordinate = maneuver.getJSONArray("location")
                val location = GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0))
                check(validPoint(location)) { "Le guidage contient une position invalide." }
                // OSRM maneuvers are geometry vertices; forward search handles repeated crossings.
                var closestIndex = lastGeometryIndex
                var closestDistance = Double.POSITIVE_INFINITY
                for (index in lastGeometryIndex..points.lastIndex) {
                    val distance = RouteProgressEngine.distanceMeters(points[index], location)
                    if (distance < closestDistance) { closestDistance = distance; closestIndex = index }
                    if (distance < 0.5) break
                }
                if (maneuver.optString("type") == "arrive") closestIndex = points.lastIndex
                lastGeometryIndex = closestIndex
                val type = maneuver.getString("type")
                val modifier = maneuver.optString("modifier").takeIf { it.isNotEmpty() }
                val road = step.optString("name").ifBlank { step.optString("ref") }
                val exit = if (maneuver.has("exit")) maneuver.getInt("exit") else null
                val stepMeters = step.getDouble("distance")
                val stepSeconds = step.getDouble("duration")
                check(stepMeters.isFinite() && stepMeters >= 0.0 && stepSeconds.isFinite() && stepSeconds >= 0.0) {
                    "Le guidage contient une distance ou une durée invalide."
                }
                steps += RouteStep(
                    instruction = maneuverInstruction(type, modifier, road, exit),
                    maneuverType = type, modifier = modifier, roadName = road, location = location,
                    routeOffsetMeters = offsets[closestIndex], distanceMeters = stepMeters,
                    durationSeconds = stepSeconds, exit = exit,
                )
            }
        }
        check(steps.isNotEmpty() && offsets.last() > 0.0) { "Cet itinéraire ne contient pas de guidage exploitable." }
        val summary = summaries.distinct().joinToString(" · ").ifBlank {
            steps.sortedByDescending { it.distanceMeters }.map { it.roadName }
                .filter { it.isNotBlank() }.distinct().take(2).joinToString(" · ")
        }
        return Route(points, steps, meters, seconds, summary)
    }

    private fun validPoint(point: GeoPoint): Boolean =
        point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
            point.longitude.isFinite() && point.longitude in -180.0..180.0

    private fun maneuverInstruction(type: String, modifier: String?, road: String, exit: Int?): String {
        val direction = when (modifier) {
            "left" -> "à gauche"
            "right" -> "à droite"
            "slight left" -> "légèrement à gauche"
            "slight right" -> "légèrement à droite"
            "sharp left" -> "franchement à gauche"
            "sharp right" -> "franchement à droite"
            else -> "tout droit"
        }
        val action = when {
            type == "arrive" -> "Vous êtes arrivé à destination"
            modifier == "uturn" -> "Faites demi-tour"
            type in setOf("roundabout", "rotary", "roundabout turn") ->
                if (exit != null) "Au rond-point, prenez la sortie $exit" else "Entrez dans le rond-point"
            type in setOf("exit roundabout", "exit rotary") -> "Sortez du rond-point"
            type == "depart" -> "Prenez la route $direction"
            type == "merge" -> "Insérez-vous $direction"
            type == "on ramp" -> "Prenez la bretelle $direction"
            type == "off ramp" -> "Prenez la sortie $direction"
            type == "fork" -> "Restez $direction"
            type == "end of road" -> "Au bout de la route, tournez $direction"
            type == "turn" -> "Tournez $direction"
            else -> "Continuez $direction"
        }
        return if (road.isNotBlank() && type != "arrive") "$action sur $road" else action
    }
}
