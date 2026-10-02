@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Template
import fr.cockpit.dashboard.integrations.WeatherRepository
import fr.cockpit.dashboard.telemetry.TripRepository
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal class TripScreen(carContext: CarContext) : LiveCarScreen(carContext) {
    private val trip = TripRepository.get(carContext)

    init { observe(trip.state) }

    override fun onGetTemplate(): Template {
        val data = trip.state.value
        val speed = data.speedKmh?.let { String.format(Locale.FRANCE, "%.0f km/h", it) } ?: "— km/h"
        val recording = if (data.recording) "GPS actif" else "Mesure arrêtée"
        val pane = Pane.Builder()
            .addRow(compactCarRow("Vitesse GPS", "$speed · $recording"))
            .addRow(compactCarRow("Distances GPS", "Trajet ${formatDistance(data.tripMeters)} · Total ${formatDistance(data.totalMeters)}"))
            .addAction(Action.Builder().setTitle("Musique")
                .setOnClickListener { screenManager.push(MusicScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("Météo")
                .setOnClickListener { screenManager.push(WeatherScreen(carContext)) }.build())
            .build()
        return PaneTemplate.Builder(pane).setTitle("Informations du trajet")
            .setHeaderAction(Action.BACK).build()
    }

    private fun formatDistance(meters: Double): String =
        String.format(Locale.FRANCE, "%.2f km", meters / 1_000.0)
}

/** Weather details have their own fixed card so the driving metrics need no scrolling. */
internal class WeatherScreen(carContext: CarContext) : LiveCarScreen(carContext) {
    private val weather = WeatherRepository.get(carContext)

    init { observe(weather.state) }

    override fun onGetTemplate(): Template {
        val conditions = weather.state.value
        val temperature = when {
            !conditions.enabled -> "À activer sur le téléphone"
            conditions.temperatureC != null -> String.format(Locale.FRANCE, "%.1f °C", conditions.temperatureC)
            conditions.loading -> "Actualisation…"
            else -> "Température indisponible"
        }
        val measuredAt = conditions.updatedAtMillis?.let {
            DateFormat.getTimeInstance(DateFormat.SHORT, Locale.FRANCE).format(Date(it))
        }
        val summary = listOfNotNull(
            conditions.summary?.takeIf { it.isNotBlank() },
            measuredAt?.let { "à $it" },
            if (conditions.error != null) "Actualisation indisponible" else null,
        ).joinToString(" · ").ifBlank { "Météo locale" }
        return PaneTemplate.Builder(Pane.Builder()
            .addRow(compactCarRow("Température extérieure", temperature))
            .addRow(compactCarRow("Conditions", summary))
            .build()).setTitle("Météo").setHeaderAction(Action.BACK).build()
    }
}
