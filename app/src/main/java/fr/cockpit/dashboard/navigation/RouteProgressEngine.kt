package fr.cockpit.dashboard.navigation

import kotlin.math.*

data class RouteProgress(
    val nextStepIndex: Int,
    val distanceToTurnMeters: Double,
    val remainingMeters: Double,
    val remainingSeconds: Long,
    val arrived: Boolean,
    val offRoute: Boolean,
    val progressFraction: Double = 0.0,
)

/**
 * Projects fixes onto nearby route segments. A bounded forward window prevents a crossing or a
 * round trip ending near its start from skipping to a distant future segment or announcing arrival.
 * An off-route fix never advances progress; the repository decides when to request a new route.
 */
class RouteProgressEngine(private val route: Route) {
    private val offsets = cumulativeDistances(route.points)
    private val geometryMeters = offsets.lastOrNull() ?: 0.0
    private var progressMeters = 0.0
    private var lastTimestamp: Long? = null
    private var badFixCount = 0
    private var lastResult: RouteProgress? = null

    init {
        require(route.points.size >= 2 && route.steps.isNotEmpty() && geometryMeters > 0)
    }

    fun update(point: GeoPoint, accuracyMeters: Double, timestampMillis: Long): RouteProgress {
        // Re-delivered or older fixes cannot confirm a departure or advance a maneuver twice.
        if (lastTimestamp?.let { timestampMillis <= it } == true) return lastResult!!
        val elapsed = lastTimestamp?.let { ((timestampMillis - it) / 1000.0).coerceIn(0.0, 20.0) } ?: 0.0
        val forwardWindow = max(180.0, elapsed * 55.0)
        val lower = max(0.0, progressMeters - 35.0)
        val upper = min(geometryMeters, progressMeters + forwardWindow)
        var closestDistance = Double.POSITIVE_INFINITY
        var closestOffset = progressMeters
        var bestScore = Double.POSITIVE_INFINITY
        for (i in 0 until route.points.lastIndex) {
            if (offsets[i + 1] < lower || offsets[i] > upper) continue
            val segmentLength = offsets[i + 1] - offsets[i]
            if (segmentLength <= 0) continue
            val projection = project(point, route.points[i], route.points[i + 1])
            val minimumFraction = ((lower - offsets[i]) / segmentLength).coerceIn(0.0, 1.0)
            val maximumFraction = ((upper - offsets[i]) / segmentLength).coerceIn(0.0, 1.0)
            val fraction = projection.first.coerceIn(minimumFraction, maximumFraction)
            val projected = interpolate(route.points[i], route.points[i + 1], fraction)
            val distance = distanceMeters(point, projected)
            val along = offsets[i] + fraction * segmentLength
            val score = distance + abs(along - progressMeters) * 0.015
            if (score < bestScore) {
                bestScore = score
                closestDistance = distance
                closestOffset = along
            }
        }
        val isOffRoute = closestDistance > 45.0 + accuracyMeters.coerceIn(0.0, 25.0)
        badFixCount = if (isOffRoute) badFixCount + 1 else 0
        if (!isOffRoute) progressMeters = max(progressMeters, closestOffset)
        lastTimestamp = timestampMillis
        val remainingGeometry = (geometryMeters - progressMeters).coerceAtLeast(0.0)
        val routeScale = route.totalMeters / geometryMeters
        val nextIndex = route.steps.indexOfFirst { it.routeOffsetMeters > progressMeters + 4.0 }
            .takeIf { it >= 0 } ?: route.steps.lastIndex
        val next = route.steps[nextIndex]
        val arrived = !isOffRoute && remainingGeometry <= 30.0 &&
            distanceMeters(point, route.points.last()) <= 35.0
        var seconds = 0.0
        route.steps.forEachIndexed { index, step ->
            val end = route.steps.getOrNull(index + 1)?.routeOffsetMeters ?: geometryMeters
            val length = end - step.routeOffsetMeters
            if (length > 0 && end > progressMeters) {
                seconds += step.durationSeconds * ((end - max(progressMeters, step.routeOffsetMeters)) / length).coerceIn(0.0, 1.0)
            }
        }
        return RouteProgress(
            nextStepIndex = nextIndex,
            distanceToTurnMeters = ((next.routeOffsetMeters - progressMeters) * routeScale).coerceAtLeast(0.0),
            remainingMeters = remainingGeometry * routeScale,
            remainingSeconds = seconds.roundToLong().coerceAtLeast(0),
            arrived = arrived,
            offRoute = badFixCount >= 3,
            progressFraction = (progressMeters / geometryMeters).coerceIn(0.0, 1.0),
        ).also { lastResult = it }
    }

    companion object {
        fun cumulativeDistances(points: List<GeoPoint>): List<Double> {
            var total = 0.0
            return points.mapIndexed { index, point ->
                if (index > 0) total += distanceMeters(points[index - 1], point)
                total
            }
        }

        fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
            val lat = Math.toRadians(b.latitude - a.latitude)
            val lon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
                cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
            return 12_742_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint = GeoPoint(
            a.latitude + (b.latitude - a.latitude) * fraction,
            a.longitude + (b.longitude - a.longitude) * fraction,
        )

        private fun project(point: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<Double, Double> {
            val latitudeScale = cos(Math.toRadians((a.latitude + b.latitude) / 2))
            val dx = (b.longitude - a.longitude) * latitudeScale
            val dy = b.latitude - a.latitude
            val px = (point.longitude - a.longitude) * latitudeScale
            val py = point.latitude - a.latitude
            val lengthSquared = dx * dx + dy * dy
            return Pair(if (lengthSquared == 0.0) 0.0 else (px * dx + py * dy) / lengthSquared, lengthSquared)
        }
    }
}
