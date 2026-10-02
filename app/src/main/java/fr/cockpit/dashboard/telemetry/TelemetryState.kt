package fr.cockpit.dashboard.telemetry

data class TelemetryState(
    val speedKmh: Float? = null,
    /** Distance estimated only during explicitly recorded trips; not the vehicle odometer. */
    val totalMeters: Double = 0.0,
    val tripMeters: Double = 0.0,
    val recording: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    /** Wall-clock timestamp of the last accepted fix; coordinates may be older than the speed. */
    val lastFixEpochMillis: Long? = null,
    val error: String? = null,
)
