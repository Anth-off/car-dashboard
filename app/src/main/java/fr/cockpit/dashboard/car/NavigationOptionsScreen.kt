@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import fr.cockpit.dashboard.navigation.NavigationRepository

/** Stable titles and row count allow live state changes without consuming the template quota. */
internal class NavigationOptionsScreen(
    carContext: CarContext,
    private val recenter: () -> Unit,
    private val overview: () -> Boolean,
) : LiveCarScreen(carContext) {
    private val navigation = NavigationRepository.get(carContext)

    init { observe(navigation.state) }

    override fun onGetTemplate(): Template {
        val state = navigation.state.value
        val list = ItemList.Builder()
            .addItem(
                Row.Builder().setTitle("Instructions vocales")
                    .addText(if (state.voiceEnabled) "Guidage vocal activé" else "Guidage vocal coupé")
                    .setToggle(Toggle.Builder { enabled -> navigation.setVoiceEnabled(enabled) }
                        .setChecked(state.voiceEnabled).build())
                    .build()
            )
            .addItem(
                Row.Builder().setTitle("Recentrer la carte")
                    .addText("Retrouver ma position et suivre le GPS")
                    .setOnClickListener { recenter(); screenManager.pop() }
                    .build()
            )
            .addItem(
                Row.Builder().setTitle("Vue d’ensemble du trajet")
                    .addText(state.destination?.name ?: "Disponible après le calcul d’un itinéraire")
                    .setOnClickListener {
                        if (overview()) screenManager.pop()
                        else message("Calculez d’abord un itinéraire pour afficher le trajet.")
                    }
                    .build()
            )
            .addItem(
                Row.Builder().setTitle("Prochaine manœuvre")
                    .addText(state.instruction)
                    .build()
            )
            .addItem(
                Row.Builder().setTitle("Manœuvre suivante")
                    .addText(state.followingStep?.instruction ?: "Aucune manœuvre supplémentaire disponible")
                    .build()
            )
            .addItem(
                Row.Builder().setTitle("Recalculer l’itinéraire")
                    .addText(if (state.loading) "Calcul en cours…" else "Depuis la position GPS actuelle")
                    .setOnClickListener {
                        when {
                            state.destination == null -> message("Choisissez d’abord une destination.")
                            state.loading -> message("Le calcul est déjà en cours.")
                            canStartCarNavigation(carContext) -> {
                                navigation.reroute()
                                screenManager.pop()
                            }
                        }
                    }
                    .build()
            )
        return ListTemplate.Builder().setTitle("Guidage et carte")
            .setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }

    private fun message(text: String) =
        CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()
}
