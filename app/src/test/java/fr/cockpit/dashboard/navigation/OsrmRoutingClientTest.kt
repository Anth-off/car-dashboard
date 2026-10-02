package fr.cockpit.dashboard.navigation

import fr.cockpit.dashboard.destinations.Destination
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OsrmRoutingClientTest {
    private val client = OsrmRoutingClient()

    @Test fun `fastest estimated driving alternative wins even when provider lists it second`() {
        val first = route("D 17", 1_000.0, 120.0, 2.001)
        val fastest = route("D 5", 1_500.0, 90.0, 2.002)
        val result = client.parseResponse(response(first, fastest))
        assertEquals(listOf("D 5", "D 17"), result.routes.map { it.summary })
        assertEquals(90.0, result.routes.first().totalSeconds, 0.0)
        assertEquals(1_500.0, result.routes.first().totalMeters, 0.0)
        assertEquals("driving", first.getJSONArray("legs").getJSONObject(0)
            .getJSONArray("steps").getJSONObject(0).getString("mode"))
        assertEquals("arrive", result.routes.first().steps.last().maneuverType)
        assertTrue(result.routes.first().steps.last().routeOffsetMeters > 0)
    }

    @Test fun `equally quick alternatives prefer shorter roads and identical geometry appears once`() {
        val result = client.parseResponse(response(
            route("Long", 1_500.0, 90.0, 2.001),
            route("Court", 1_000.0, 90.0, 2.002),
            route("Copie lente", 1_000.0, 140.0, 2.002),
        ))
        assertEquals(listOf("Court", "Long"), result.routes.map { it.summary })
    }

    @Test fun `route details keep destination road access gap visible`() {
        val body = response(route("D 5", 100.0, 15.0, 2.001)).put("waypoints", JSONArray()
            .put(JSONObject().put("distance", 6.0))
            .put(JSONObject().put("distance", 143.6)))
        val result = client.parseResponse(body)
        assertTrue(result.warning!!.contains("144 m"))
        assertTrue(result.warning!!.contains("vérifie l’entrée"))
    }

    @Test fun `nearby road snap does not show a misleading warning`() {
        val body = response(route("D 5", 100.0, 15.0, 2.001)).put("waypoints", JSONArray()
            .put(JSONObject().put("distance", 7.0))
            .put(JSONObject().put("distance", 12.0)))
        assertNull(client.parseResponse(body).warning)
    }

    @Test fun `no road near destination asks for the road entrance`() {
        val error = assertThrows(IllegalStateException::class.java) {
            client.parseResponse(JSONObject().put("code", "NoSegment"))
        }
        assertTrue(error.message!!.contains("entrée routière"))
    }

    @Test fun `pedestrian route is never accepted as driving guidance`() {
        val route = route("Chemin", 100.0, 15.0, 2.001)
        route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            .getJSONObject(0).put("mode", "walking")
        val error = assertThrows(IllegalStateException::class.java) { client.parseResponse(response(route)) }
        assertTrue(error.message!!.contains("trajet en voiture"))
    }

    @Test fun `invalid estimates cannot become the fastest route`() {
        val error = assertThrows(IllegalStateException::class.java) {
            client.parseResponse(response(route("D 5", 100.0, -10.0, 2.001)))
        }
        assertTrue(error.message!!.contains("durée"))
    }

    @Test fun `request includes alternatives bounded snapping and normalized moving heading`() {
        val destination = Destination("test", "Dourdan", 48.5295, 2.0115)
        val url = client.requestUrl(GeoPoint(48.4149, 1.8802), destination, -90f).toString()
        assertTrue(url.contains("/driving/1.8802,48.4149;2.0115,48.5295"))
        assertTrue(url.contains("alternatives=true"))
        assertTrue(url.contains("radiuses=100;200"))
        assertTrue(url.contains("bearings=270,90;"))
        assertFalse(client.requestUrl(GeoPoint(48.4149, 1.8802), destination, null).toString().contains("bearings"))
    }

    private fun response(vararg routes: JSONObject) = JSONObject()
        .put("code", "Ok").put("routes", JSONArray().also { array -> routes.forEach(array::put) })

    private fun route(summary: String, meters: Double, seconds: Double, middleLongitude: Double): JSONObject {
        val points = listOf(listOf(2.0, 48.0), listOf(middleLongitude, 48.001), listOf(2.003, 48.002))
        val steps = JSONArray()
        listOf(0, 2).forEach { index ->
            steps.put(JSONObject().put("mode", "driving").put("name", summary)
                .put("distance", if (index == 0) meters else 0.0)
                .put("duration", if (index == 0) seconds else 0.0)
                .put("maneuver", JSONObject().put("type", if (index == 0) "depart" else "arrive")
                    .put("location", JSONArray(points[index]))))
        }
        return JSONObject().put("distance", meters).put("duration", seconds)
            .put("geometry", JSONObject().put("coordinates", JSONArray(points)))
            .put("legs", JSONArray().put(JSONObject().put("summary", summary).put("steps", steps)))
    }
}
