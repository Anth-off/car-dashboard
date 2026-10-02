@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import fr.cockpit.dashboard.navigation.NavigationRepository

/** All map/guidance controls fit on one compact card, without a scrolling settings list. */
internal class NavigationOptionsScreen(
    carContext: CarContext,
    private val recenter: () -> Unit,
    private val overview: () -> Boolean,
) : LiveCarScreen(carContext) {
    private val navigation = NavigationRepository.get(carContext)

    init { observe(navigation.state) }

    override fun onGetTemplate(): Template {
        val state = navigation.state.value
        val pane = Pane.Builder()
            .addRow(compactCarRow("Prochaine manœuvre", state.instruction))
            .addRow(compactCarRow("Ensuite", state.followingStep?.instruction ?: "Aucune autre manœuvre"))
            .addAction(Action.Builder().setTitle(if (state.voiceEnabled) "Couper la voix" else "Activer la voix")
                .setOnClickListener { navigation.setVoiceEnabled(!navigation.state.value.voiceEnabled) }
                .build())
            .addAction(Action.Builder().setTitle("Trajets").setOnClickListener {
                screenManager.push(RouteChoicesScreen(carContext, overview) {
                    screenManager.pop()
                    screenManager.pop()
                })
            }.build())
            .build()
        val actions = ActionStrip.Builder()
            .addAction(iconAction(android.R.drawable.ic_menu_mylocation) { recenter(); screenManager.pop() })
            .addAction(iconAction(android.R.drawable.ic_popup_sync) {
                val current = navigation.state.value
                when {
                    current.destination == null -> message("Choisissez d’abord une destination.")
                    current.loading -> message("Le calcul est déjà en cours.")
                    canStartCarNavigation(carContext) -> {
                        navigation.reroute()
                        screenManager.pop()
                    }
                }
            })
            .build()
        return PaneTemplate.Builder(pane).setTitle("Guidage et carte")
            .setHeaderAction(Action.BACK).setActionStrip(actions).build()
    }

    private fun iconAction(icon: Int, callback: () -> Unit): Action = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
        .setOnClickListener(callback).build()

    private fun message(text: String) =
        CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()
}
