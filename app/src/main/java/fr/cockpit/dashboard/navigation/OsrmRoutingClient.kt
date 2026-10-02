package fr.cockpit.dashboard.navigation

import fr.cockpit.dashboard.destinations.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Public demo router: no uptime, live traffic, offline routing, or access-restriction guarantee. */
internal class OsrmRoutingClient {
    suspend fun route(origin: GeoPoint, destination: Destination): Route = withContext(Dispatchers.IO) {
        val url = URL("https://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?steps=true&geometries=geojson&overview=full&alternatives=false")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Cockpit-Android/0.1 (personal navigation prototype)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            check(connection.responseCode == 200) { "Le service d’itinéraires est indisponible (${connection.responseCode})." }
            val body = connection.inputStream.bufferedReader().use { reader ->
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
            val response = JSONObject(body)
            check(response.optString("code") == "Ok") { "Aucun itinéraire routier trouvé vers cette destination." }
            val jsonRoute = response.getJSONArray("routes").getJSONObject(0)
            val coordinates = jsonRoute.getJSONObject("geometry").getJSONArray("coordinates")
            check(coordinates.length() in 2..100_000) { "La géométrie de l’itinéraire est indisponible." }
            val points = (0 until coordinates.length()).map { i ->
                val pair = coordinates.getJSONArray(i)
                GeoPoint(pair.getDouble(1), pair.getDouble(0))
            }
            val offsets = RouteProgressEngine.cumulativeDistances(points)
            var lastGeometryIndex = 0
            val steps = mutableListOf<RouteStep>()
            val legs = jsonRoute.getJSONArray("legs")
            for (legIndex in 0 until legs.length()) {
                val jsonSteps = legs.getJSONObject(legIndex).getJSONArray("steps")
                for (stepIndex in 0 until jsonSteps.length()) {
                    val step = jsonSteps.getJSONObject(stepIndex)
                    val maneuver = step.getJSONObject("maneuver")
                    val coordinate = maneuver.getJSONArray("location")
                    val location = GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0))
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
                    steps += RouteStep(
                        instruction = maneuverInstruction(type, modifier, road, exit),
                        maneuverType = type, modifier = modifier, roadName = road, location = location,
                        routeOffsetMeters = offsets[closestIndex], distanceMeters = step.getDouble("distance"),
                        durationSeconds = step.getDouble("duration"), exit = exit,
                    )
                }
            }
            check(steps.isNotEmpty() && offsets.last() > 0.0) { "Cet itinéraire ne contient pas de guidage exploitable." }
            Route(points, steps, jsonRoute.getDouble("distance"), jsonRoute.getDouble("duration"))
        } finally {
            connection.disconnect()
        }
    }

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
