@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Template
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.navigation.NavigationState
import fr.cockpit.dashboard.navigation.Route
import java.util.Locale
import kotlin.math.ceil

/** One route per page keeps comparison available on small hosts without a scrolling list. */
internal class RouteChoicesScreen(
    carContext: CarContext,
    private val overview: () -> Boolean,
    private val returnToMap: () -> Unit,
) : LiveCarScreen(carContext) {
    private val navigation = NavigationRepository.get(carContext)
    private var selectedIndex = navigation.state.value.selectedRouteIndex

    init { observe(navigation.state) }

    override fun onGetTemplate(): Template {
        val state = navigation.state.value
        selectedIndex = selectedIndex.coerceIn(0, (availableRoutes(state).size - 1).coerceAtLeast(0))
        return routeChoicesTemplate(state, selectedIndex,
            onSelect = { route ->
                // Use the displayed object: a recalculation may have replaced the numbered list.
                if (navigation.selectRoute(route)) {
                    returnToMap()
                } else {
                    // A stale GPS fix or a refreshed route list requires a new choice. Keep
                    // this screen visible so a refused selection cannot look successful.
                    invalidate()
                    val current = navigation.state.value
                    val message = current.error ?: current.routeWarning
                    message?.let { CarToast.makeText(carContext, it, CarToast.LENGTH_LONG).show() }
                }
            },
            onOverview = {
                if (overview()) returnToMap()
                else CarToast.makeText(carContext, "Calculez d’abord un itinéraire.", CarToast.LENGTH_SHORT).show()
            },
            onPrevious = { changePage(-1) },
            onNext = { changePage(1) },
        )
    }

    private fun changePage(direction: Int) {
        val count = availableRoutes(navigation.state.value).size
        if (count <= 1) return
        selectedIndex = Math.floorMod(selectedIndex + direction, count)
        invalidate()
    }
}

internal fun routeChoicesTemplate(
    state: NavigationState,
    pageIndex: Int,
    onSelect: (Route) -> Unit,
    onOverview: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): PaneTemplate {
    val routes = availableRoutes(state)
    val index = pageIndex.coerceIn(0, (routes.size - 1).coerceAtLeast(0))
    val route = routes.getOrNull(index)
    val description = route?.let {
        val summary = it.summary.ifBlank { "Itinéraire ${index + 1}" }
        "${index + 1}/${routes.size} · $summary"
    } ?: if (state.loading) "Calcul en cours…" else "Aucun itinéraire disponible"
    val estimate = route?.let {
        val minutes = ceil(it.totalSeconds / 60).toLong().coerceAtLeast(1)
        val duration = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        val distance = String.format(Locale.FRANCE, "%.1f km", it.totalMeters / 1_000)
        val selected = if (it == state.route) " · Actuel" else ""
        "$duration · $distance$selected"
    } ?: "Choisissez une destination."
    val notice = state.error?.takeIf { it.isNotBlank() }
        ?: state.routeWarning?.takeIf { it.isNotBlank() }
        ?: if (state.loading) "Calcul en cours…" else null
    // Warnings must take precedence even while the previous route remains available.
    // Keep fixed row titles/count so presenting a notice remains a host refresh.
    val routeDescription = if (notice != null && route != null)
        "$estimate · ${compactCarText(description, 34)}" else description
    val pane = Pane.Builder()
        .addRow(compactCarRow("Itinéraire", routeDescription))
        .addRow(compactCarRow("Estimation sans trafic", notice ?: estimate))
    if (route != null) {
        if (!state.loading) pane.addAction(Action.Builder().setTitle("Choisir")
            .setOnClickListener { onSelect(route) }.build())
        pane.addAction(Action.Builder().setTitle("Vue du trajet actuel")
            .setOnClickListener(onOverview).build())
    }
    val template = PaneTemplate.Builder(pane.build()).setTitle("Choix du trajet")
        .setHeaderAction(Action.BACK)
    if (routes.size > 1) template.setActionStrip(carPageActions(onPrevious, onNext))
    return template.build()
}

private fun availableRoutes(state: NavigationState): List<Route> =
    state.routeOptions.ifEmpty { listOfNotNull(state.route) }
