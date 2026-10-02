package fr.cockpit.dashboard.navigation

import fr.cockpit.dashboard.destinations.Destination

data class GeoPoint(val latitude: Double, val longitude: Double)

data class RouteStep(
    val instruction: String,
    val maneuverType: String,
    val modifier: String?,
    val roadName: String,
    val location: GeoPoint,
    /** Distance along the route geometry, in metres, where this maneuver starts. */
    val routeOffsetMeters: Double,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val exit: Int? = null,
)

data class Route(
    val points: List<GeoPoint>,
    val steps: List<RouteStep>,
    val totalMeters: Double,
    val totalSeconds: Double,
)

data class NavigationState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val destination: Destination? = null,
    val route: Route? = null,
    val instruction: String = "Choisis une destination",
    val distanceToTurnMeters: Double? = null,
    val remainingMeters: Double? = null,
    val remainingSeconds: Long? = null,
    val arrived: Boolean = false,
    val offRoute: Boolean = false,
    val nextStep: RouteStep? = null,
    val voiceEnabled: Boolean = true,
    /** True only following an explicit Android Auto host/DHU simulation callback. */
    val isSimulation: Boolean = false,
    /** Last position used by guidance; simulated only when [isSimulation] is true. */
    val currentPosition: GeoPoint? = null,
)
