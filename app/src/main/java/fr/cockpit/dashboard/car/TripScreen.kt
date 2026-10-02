@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import fr.cockpit.dashboard.integrations.WeatherRepository
import fr.cockpit.dashboard.telemetry.TripRepository
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal class TripScreen(carContext: CarContext) : LiveCarScreen(carContext) {
    private val trip = TripRepository.get(carContext)
    private val weather = WeatherRepository.get(carContext)

    init {
        observe(trip.state, weather.state)
    }

    override fun onGetTemplate(): Template {
        val data = trip.state.value
        val conditions = weather.state.value
        val speed = data.speedKmh?.let { String.format(Locale.FRANCE, "%.0f km/h", it) }
            ?: "— km/h"
        val recording = if (data.recording) "Mesure GPS active" else "Mesure arrêtée sur le téléphone"
        val temperature = when {
            !conditions.enabled -> "À activer sur le téléphone"
            conditions.temperatureC != null -> {
                val degrees = String.format(Locale.FRANCE, "%.1f °C", conditions.temperatureC)
                val summary = conditions.summary?.takeIf { it.isNotBlank() }
                val measuredAt = conditions.updatedAtMillis?.let {
                    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.FRANCE).format(Date(it))
                }
                listOfNotNull(
                    degrees,
                    summary,
                    measuredAt?.let { "mesure à $it" },
                    if (conditions.error != null) "actualisation indisponible" else null,
                ).joinToString(" · ")
            }
            conditions.loading -> "Actualisation…"
            else -> "Température météo indisponible"
        }

        // Fixed row titles/count are essential: changing them consumes the host's template quota.
        val pane = Pane.Builder()
            .addRow(row("Vitesse GPS", "$speed · $recording"))
            .addRow(row("Distance du trajet", formatDistance(data.tripMeters)))
            .addRow(row("Distance cumulée de l’application", formatDistance(data.totalMeters)))
            .addRow(row("Météo locale", temperature))
            .addAction(
                Action.Builder()
                    .setTitle("Musique")
                    .setOnClickListener { screenManager.push(MusicScreen(carContext)) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Destinations")
                    .setOnClickListener { screenManager.popToRoot() }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("Informations du trajet")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun row(title: String, text: String): Row = Row.Builder()
        .setTitle(title)
        .addText(text)
        .build()

    private fun formatDistance(meters: Double): String =
        String.format(Locale.FRANCE, "%.2f km", meters / 1_000.0)
}
