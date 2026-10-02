@file:Suppress("DEPRECATION") // Legacy header setters support Car App API levels below 7.

package fr.cockpit.dashboard.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Template
import fr.cockpit.dashboard.destinations.Destination
import fr.cockpit.dashboard.destinations.DestinationRepository
import fr.cockpit.dashboard.navigation.NavigationRepository
import java.util.Locale

/** One favorite per fixed card, regardless of how many places are saved on the phone. */
internal class DestinationListScreen(
    carContext: CarContext,
    private val returnToDashboard: (() -> Unit)? = null,
) : LiveCarScreen(carContext) {
    private val destinations = DestinationRepository.get(carContext)
    private var selectedIndex = 0

    init { observe(destinations.state) }

    override fun onGetTemplate(): Template {
        val favorites = destinations.state.value
        selectedIndex = selectedIndex.coerceIn(0, (favorites.size - 1).coerceAtLeast(0))
        val destination = favorites.getOrNull(selectedIndex)
        val pane = Pane.Builder()
            // Keep both titles and the row count stable: page changes remain refreshes, so
            // browsing more than five favorites never exhausts the host's template quota.
            .addRow(compactCarRow("Destination favorite", destination?.let {
                "${selectedIndex + 1}/${favorites.size} · ${it.name}"
            } ?: "Ajoutez un lieu sur le téléphone, à l’arrêt."))
            .addRow(compactCarRow("Adresse", destination?.let(::destinationAddress) ?: "Aucun favori enregistré"))
            .addAction(Action.Builder().setTitle("Carte")
                .setOnClickListener(::showMap).build())
        if (destination != null) {
            pane.addAction(Action.Builder().setTitle("Y aller").setOnClickListener {
                if (canStartCarNavigation(carContext)) {
                    NavigationRepository.get(carContext).start(destination)
                    showMap()
                }
            }.build())
        }
        val template = PaneTemplate.Builder(pane.build())
            .setTitle("Cockpit · Destinations")
            .setHeaderAction(if (returnToDashboard != null) Action.BACK else Action.APP_ICON)
        if (favorites.size > 1) template.setActionStrip(carPageActions(
            previous = { changePage(-1) }, next = { changePage(1) },
        ))
        return template.build()
    }

    private fun showMap() {
        if (returnToDashboard != null) returnToDashboard.invoke()
        else screenManager.push(NavigationScreen(carContext))
    }

    private fun changePage(direction: Int) {
        val count = destinations.state.value.size
        if (count <= 1) return
        selectedIndex = Math.floorMod(selectedIndex + direction, count)
        invalidate()
    }
}

internal class DestinationScreen(
    carContext: CarContext,
    private val destination: Destination,
) : androidx.car.app.Screen(carContext) {
    override fun onGetTemplate(): Template = PaneTemplate.Builder(
        Pane.Builder()
            .addRow(compactCarRow("Destination", destination.name))
            .addRow(compactCarRow("Adresse", destinationAddress(destination)))
            .addAction(Action.Builder().setTitle("Y aller").setOnClickListener {
                if (canStartCarNavigation(carContext)) {
                    NavigationRepository.get(carContext).start(destination)
                    screenManager.push(NavigationScreen(carContext))
                }
            }.build())
            .build(),
    ).setTitle("Destination").setHeaderAction(Action.BACK).build()
}

private fun destinationAddress(destination: Destination): String = destination.address.ifBlank {
    String.format(Locale.FRANCE, "%.5f°, %.5f°", destination.latitude, destination.longitude)
}
