package fr.cockpit.dashboard.car

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import fr.cockpit.dashboard.navigation.NavigationState
import fr.cockpit.dashboard.navigation.RouteStep
import java.time.ZonedDateTime

internal fun carDistance(meters: Double): Distance {
    val valid = meters.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    return if (valid >= 1_000.0) {
        Distance.create(valid / 1_000.0, Distance.UNIT_KILOMETERS_P1)
    } else {
        Distance.create(valid, Distance.UNIT_METERS)
    }
}

internal fun carTravelEstimate(meters: Double, seconds: Long): TravelEstimate {
    val remainingSeconds = seconds.coerceAtLeast(0)
    return TravelEstimate.Builder(carDistance(meters), ZonedDateTime.now().plusSeconds(remainingSeconds))
        .setRemainingTimeSeconds(remainingSeconds)
        .build()
}

internal fun carStep(state: NavigationState): Step {
    return carRouteStep(state.nextStep, state.instruction, state.arrived)
}

internal fun carRouteStep(
    routeStep: RouteStep?,
    instruction: String = routeStep?.instruction.orEmpty(),
    arrived: Boolean = false,
): Step {
    val step = Step.Builder(instruction.ifBlank { "Suivez l’itinéraire" })
        .setManeuver(Maneuver.Builder(maneuverType(routeStep, arrived)).build())
    routeStep?.roadName?.takeIf { it.isNotBlank() }?.let { step.setRoad(it) }
    return step.build()
}

/** OSRM's current leg estimate scaled by the remaining distance to its maneuver. */
internal fun secondsToStep(state: NavigationState): Long {
    val distance = state.distanceToTurnMeters ?: return 0L
    val steps = state.route?.steps.orEmpty()
    val index = state.nextStepIndex ?: steps.indexOf(state.nextStep)
    val leg = steps.getOrNull(index - 1)
    if (leg != null && leg.distanceMeters > 0.0) {
        return (leg.durationSeconds * (distance / leg.distanceMeters).coerceIn(0.0, 1.0))
            .toLong().coerceAtLeast(0L)
    }
    val total = state.remainingMeters ?: return 0L
    return if (total > 0.0) {
        ((state.remainingSeconds ?: 0L) * (distance / total).coerceIn(0.0, 1.0)).toLong()
    } else 0L
}

private fun maneuverType(step: RouteStep?, arrived: Boolean): Int {
    if (arrived || step?.maneuverType == "arrive") return Maneuver.TYPE_DESTINATION
    if (step == null) return Maneuver.TYPE_UNKNOWN
    if (step.maneuverType == "depart") return Maneuver.TYPE_DEPART
    // Text remains authoritative for roundabouts: the route contract does not expose driving side.
    if (step.maneuverType.contains("roundabout") || step.maneuverType == "rotary") {
        return Maneuver.TYPE_UNKNOWN
    }
    return when (step.modifier) {
        "uturn" -> Maneuver.TYPE_U_TURN_LEFT
        "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT
        "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
        "slight left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT
        "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        "slight right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        "straight" -> Maneuver.TYPE_STRAIGHT
        else -> Maneuver.TYPE_UNKNOWN
    }
}
