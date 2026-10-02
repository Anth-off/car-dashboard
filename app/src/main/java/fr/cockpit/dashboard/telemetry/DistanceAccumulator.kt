package fr.cockpit.dashboard.telemetry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A location sample in monotonic time. Kept Android-free so filtering can be tested on the JVM. */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val elapsedRealtimeMillis: Long,
    val speedMetersPerSecond: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
)

data class DistanceSample(
    val fix: GpsFix,
    val distanceMeters: Double,
    val speedMetersPerSecond: Float?,
)

/**
 * Estimates recorded ground distance, never a vehicle's odometer.
 *
 * Reliable GNSS speed is integrated between consecutive fixes: this avoids adding stationary
 * position jitter to the counters. Without speed, only displacement exceeding position uncertainty
 * is counted. Missing/poor fixes and pauses deliberately lose distance instead of inventing a route.
 */
class DistanceAccumulator {
    private var previous: GpsFix? = null

    fun reset() { previous = null }

    fun accept(fix: GpsFix, nowElapsedRealtimeMillis: Long): DistanceSample? {
        val age = nowElapsedRealtimeMillis - fix.elapsedRealtimeMillis
        if (age !in 0..MAX_FIX_AGE_MS || !fix.latitude.isFinite() ||
            !fix.longitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            fix.longitude !in -180.0..180.0 || !fix.accuracyMeters.isFinite() ||
            fix.accuracyMeters !in 0f..MAX_ACCURACY_METERS
        ) return null

        val old = previous
        if (old != null && fix.elapsedRealtimeMillis <= old.elapsedRealtimeMillis) return null
        val speed = reliableSpeed(fix)
        if (old == null || fix.elapsedRealtimeMillis - old.elapsedRealtimeMillis > MAX_GAP_MS) {
            previous = fix
            return DistanceSample(fix, 0.0, speed)
        }

        val seconds = (fix.elapsedRealtimeMillis - old.elapsedRealtimeMillis) / 1000.0
        val displacement = distanceBetween(old, fix)
        val uncertainty = old.accuracyMeters + fix.accuracyMeters
        // A single bad fix must neither inflate distance nor move the accepted anchor.
        if (displacement > MAX_SPEED_METERS_PER_SECOND * seconds + uncertainty) return null

        val oldSpeed = reliableSpeed(old)
        val distance = when {
            speed != null && oldSpeed != null -> (speed + oldSpeed) / 2.0 * seconds
            speed == 0f -> 0.0
            displacement > max(3.0, uncertainty.toDouble()) -> displacement
            else -> 0.0
        }
        val displayedSpeed = speed ?: if (distance > 0) (distance / seconds).toFloat() else null
        previous = fix
        return DistanceSample(fix, distance, displayedSpeed)
    }

    private fun reliableSpeed(fix: GpsFix): Float? {
        val speed = fix.speedMetersPerSecond ?: return null
        val accuracy = fix.speedAccuracyMetersPerSecond
        if (!speed.isFinite() || speed !in 0f..MAX_SPEED_METERS_PER_SECOND ||
            (accuracy != null && (!accuracy.isFinite() || accuracy < 0f || accuracy > 2.5f))
        ) return null
        return if (speed < STATIONARY_SPEED_METERS_PER_SECOND) 0f else speed
    }

    private fun distanceBetween(a: GpsFix, b: GpsFix): Double {
        val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
        val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
        val h = sin(latitudeDelta / 2).let { it * it } +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(longitudeDelta / 2).let { it * it }
        val clamped = h.coerceIn(0.0, 1.0)
        return 6_371_000.0 * 2 * atan2(sqrt(clamped), sqrt(1 - clamped))
    }

    companion object {
        const val MAX_FIX_AGE_MS = 8_000L
        const val MAX_GAP_MS = 8_000L
        const val MAX_ACCURACY_METERS = 25f
        const val MAX_SPEED_METERS_PER_SECOND = 85f
        const val STATIONARY_SPEED_METERS_PER_SECOND = 0.8f
    }
}
