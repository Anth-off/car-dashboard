@file:Suppress("DEPRECATION") // The legacy header setters support Car App API levels below 7.

package fr.cockpit.dashboard.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import fr.cockpit.dashboard.destinations.Destination
import fr.cockpit.dashboard.destinations.DestinationRepository
import fr.cockpit.dashboard.navigation.NavigationRepository
import java.util.Locale

internal class DestinationListScreen(carContext: CarContext) : LiveCarScreen(carContext) {
    private val destinations = DestinationRepository.get(carContext)
    init {
        observe(destinations.state)
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Carte et navigation")
                    .addText("Carte intégrée et guidage Cockpit")
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(NavigationScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Informations du trajet")
                    .addText("Vitesse, distance et météo • prototype")
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(TripScreen(carContext)) }
                    .build()
            )

        val favorites = destinations.state.value.take(15)
        if (favorites.isEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle("Aucune destination enregistrée")
                    .addText("Ajoutez vos lieux sur le téléphone, une fois à l’arrêt.")
                    .build()
            )
        } else {
            favorites.forEach { destination ->
                list.addItem(
                    Row.Builder()
                        .setTitle(destination.name)
                        .addText("Destination favorite")
                        .setBrowsable(true)
                        .setOnClickListener {
                            screenManager.push(DestinationScreen(carContext, destination))
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("Cockpit · Destinations")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }
}

internal class DestinationScreen(
    carContext: CarContext,
    private val destination: Destination,
) : androidx.car.app.Screen(carContext) {
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("Destination")
                    .addText(destination.name)
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Position")
                    .addText(
                        String.format(
                            Locale.FRANCE,
                            "%.5f°, %.5f°",
                            destination.latitude,
                            destination.longitude,
                        )
                    )
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Guidage")
                    .addText("Guidage Cockpit avec carte et instructions vocales.")
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Y aller")
                    .setOnClickListener { startNavigation() }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("Destination")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun startNavigation() {
        if (!canStartCarNavigation(carContext)) return
        NavigationRepository.get(carContext).start(destination)
        screenManager.push(NavigationScreen(carContext))
    }
}
