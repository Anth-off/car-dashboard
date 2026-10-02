@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.CarToast
import androidx.core.graphics.drawable.IconCompat
import fr.cockpit.dashboard.integrations.MusicRepository

internal class MusicScreen(carContext: CarContext) : LiveCarScreen(carContext) {
    private val music = MusicRepository.get(carContext)

    init {
        observe(music.state)
    }

    override fun onGetTemplate(): Template {
        val state = music.state.value
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("Morceau")
                    .addText(state.title?.takeIf { it.isNotBlank() } ?: "Aucun morceau disponible")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Artiste")
                    .addText(state.artist?.takeIf { it.isNotBlank() } ?: "—")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Lecture")
                    .addText(
                        when {
                            !state.connected -> "Ouvrez Apple Music et configurez l’accès sur le téléphone, à l’arrêt."
                            state.playing -> "En cours"
                            else -> "En pause"
                        }
                    )
                    .build()
            )

        if (state.connected && state.canPlayPause) {
            pane.addAction(
                Action.Builder()
                    .setTitle(if (state.playing) "Pause" else "Lecture")
                    .setOnClickListener { command { music.togglePlayback() } }
                    .build()
            )
        }

        val template = PaneTemplate.Builder(pane.build())
            .setTitle("Musique")
            .setHeaderAction(Action.BACK)
        val actions = ActionStrip.Builder()
        var hasActions = false
        if (state.connected && state.canPrevious) {
            actions.addAction(
                iconAction(android.R.drawable.ic_media_previous) { command { music.previous() } }
            )
            hasActions = true
        }
        if (state.connected && state.canNext) {
            actions.addAction(
                iconAction(android.R.drawable.ic_media_next) { command { music.next() } }
            )
            hasActions = true
        }
        if (hasActions) template.setActionStrip(actions.build())
        return template.build()
    }

    private fun iconAction(icon: Int, action: () -> Unit): Action = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
        .setOnClickListener(action)
        .build()

    private fun command(action: () -> Boolean) {
        if (!action()) {
            CarToast.makeText(carContext, "Commande musicale indisponible.", CarToast.LENGTH_SHORT).show()
        }
        requestRefresh()
    }
}
