package fr.cockpit.dashboard.navigation

import kotlin.math.roundToInt

internal data class GuidanceAnnouncement(
    val context: String,
    val text: String,
    val expiresAtMillis: Long,
    val stepIndex: Int? = null,
    val phase: Int = 0,
    val event: String? = null,
)

/** Spoken milestones survive GPS/status changes; only actually submitted speech consumes one. */
internal class GuidanceSpeechPolicy {
    private val spokenSteps = mutableMapOf<Int, Pair<Int, Long>>()
    private val spokenEvents = mutableMapOf<String, Long>()
    private var highestStep = -1
    private var activeEvent: String? = null
    private var lastSpokenAt: Long? = null

    fun reset(keepCadence: Boolean = false) {
        spokenSteps.clear()
        highestStep = -1
        activeEvent = null
        if (!keepCadence) {
            spokenEvents.clear()
            lastSpokenAt = null
        }
    }

    fun maneuver(index: Int, step: RouteStep, meters: Double, speedKmh: Float?, now: Long): GuidanceAnnouncement? {
        activeEvent = null
        if (index < highestStep || !meters.isFinite() || meters < 0) return null
        highestStep = index
        // A street changing its name is still shown on the map, without talking over the music.
        if (step.maneuverType in setOf("new name", "notification") ||
            (step.maneuverType == "continue" && step.modifier in setOf(null, "straight"))) return null
        val speed = speedKmh?.takeIf { it.isFinite() && it >= 0 } ?: 50f
        val preparationDistance = (speed / 3.6 * 18).coerceIn(300.0, 650.0)
        val actionDistance = (speed / 3.6 * 3).coerceIn(35.0, 100.0)
        val phase = when {
            meters <= actionDistance -> 1
            meters <= preparationDistance -> 0
            else -> return null
        }
        // The actual arrival event is authoritative; do not say "you have arrived" before it.
        if (step.maneuverType == "arrive" && phase == 1) return null
        val previous = spokenSteps[index]
        if (previous != null && phase <= previous.first) return null
        // Closely spaced turns still get their action cue. Repeating the SAME maneuver shortly
        // after its preparation adds no useful information, and previews never form a chatter chain.
        if (previous != null && now - previous.second < 6_000) return null
        if (phase == 0 && lastSpokenAt?.let { now - it < 8_000 } == true) return null
        val instruction = if (step.maneuverType == "arrive") "vous arriverez à destination" else
            step.instruction.replaceFirstChar { it.lowercase() }
        return GuidanceAnnouncement(
            context = "step:$index",
            text = if (phase == 1) step.instruction else "Dans ${spokenDistance(meters)}, $instruction",
            expiresAtMillis = now + if (phase == 1) 6_000 else 15_000,
            stepIndex = index, phase = phase,
        )
    }

    fun event(name: String, now: Long): GuidanceAnnouncement? {
        if (activeEvent == name || spokenEvents[name]?.let { now - it < 60_000 } == true) return null
        if (name != "arrived" && lastSpokenAt?.let { now - it < 8_000 } == true) return null
        val text = when (name) {
            "gps-lost" -> "Signal GPS perdu. Le guidage est en pause."
            "off-route" -> "Recalcul de l’itinéraire."
            "arrived" -> "Vous êtes arrivé à destination."
            else -> return null
        }
        return GuidanceAnnouncement(name, text, now + 15_000, event = name)
    }

    fun onSpoken(announcement: GuidanceAnnouncement, now: Long) {
        announcement.stepIndex?.let { spokenSteps[it] = announcement.phase to now }
        announcement.event?.let { spokenEvents[it] = now; activeEvent = it }
        lastSpokenAt = now
    }

    private fun spokenDistance(meters: Double): String = if (meters >= 1_000) {
        "${((meters / 100).roundToInt() / 10.0).toString().replace('.', ',')} kilomètres"
    } else "${((meters / 10).roundToInt() * 10).coerceAtLeast(10)} mètres"
}
