@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.HandshakeInfo
import androidx.car.app.Screen
import androidx.car.app.OnDoneCallback
import androidx.car.app.serialization.Bundleable
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import fr.cockpit.dashboard.destinations.Destination
import fr.cockpit.dashboard.destinations.DestinationRepository
import fr.cockpit.dashboard.navigation.GeoPoint
import fr.cockpit.dashboard.navigation.NavigationState
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.navigation.RouteStep
import fr.cockpit.dashboard.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Executes AndroidX template builders against Robolectric's Android runtime, without a route fetch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class CarTemplateSmokeTest {
    @Test
    fun allScreensBuildValidTemplatesOnOlderAndNewerHosts() = onMain {
        listOf(1, 7).forEach { api ->
            val destinations = render(DestinationListScreen(carContext(api))) as PaneTemplate
            assertEquals(2, destinations.pane.rows.size)

            val destination = Destination("test", "Lieu de test", 48.8566, 2.3522)
            val detail = render(DestinationScreen(carContext(api), destination)) as PaneTemplate
            assertEquals(1, detail.pane.actions.size)

            val trip = render(TripScreen(carContext(api))) as PaneTemplate
            assertEquals(2, trip.pane.rows.size)
            assertEquals(2, trip.pane.actions.size)

            val music = render(MusicScreen(carContext(api))) as PaneTemplate
            assertEquals(2, music.pane.rows.size)

            val navigationContext = carContext(api)
            NavigationRepository.get(navigationContext).stop()
            val navigation = render(NavigationScreen(navigationContext)) as NavigationTemplate
            assertNotNull(navigation.navigationInfo)
            assertEquals(3, navigation.actionStrip!!.actions.size)
            if (api >= 2) {
                assertEquals(4, navigation.mapActionStrip!!.actions.size)
                assertTrue(navigation.mapActionStrip!!.actions.contains(Action.PAN))
                assertNotNull(navigation.panModeDelegate)
            }

            val options = render(NavigationOptionsScreen(carContext(api), {}, { false })) as PaneTemplate
            assertEquals(2, options.pane.rows.size)
            assertEquals(2, options.pane.actions.size)
            assertEquals(2, options.actionStrip!!.actions.size)

            val weather = render(WeatherScreen(carContext(api))) as PaneTemplate
            assertEquals(2, weather.pane.rows.size)

            val routeChoices = render(RouteChoicesScreen(carContext(api), { false }, {})) as PaneTemplate
            assertEquals(2, routeChoices.pane.rows.size)
        }
    }

    @Test
    fun allFavoritesCanBeBrowsedInBothDirectionsWithoutGrowingTheCard() = onMain {
        listOf(1, 7).forEach { api ->
            val context = carContext(api)
            val repository = DestinationRepository.get(context)
            val saved = repository.state.value.toList()
            saved.forEach { repository.remove(it.id) }
            try {
                repeat(25) { index -> repository.add("Lieu ${index + 1}", 48.0 + index / 100.0, 2.0) }
                val screen = DestinationListScreen(context)
                val controller = ScreenController(screen)
                controller.moveToState(Lifecycle.State.CREATED)
                try {
                    var template = screen.onGetTemplate() as PaneTemplate
                    repeat(25) { index ->
                        assertEquals(2, template.pane.rows.size)
                        assertEquals(listOf("Destination favorite", "Adresse"),
                            template.pane.rows.map { it.title.toString() })
                        assertEquals("${index + 1}/25 · Lieu ${index + 1}",
                            template.pane.rows.first().texts.single().toString())
                        assertEquals(2, template.pane.actions.size)
                        assertEquals(2, template.actionStrip!!.actions.size)
                        click(template.actionStrip!!.actions[1])
                        template = screen.onGetTemplate() as PaneTemplate
                    }
                    assertEquals("1/25 · Lieu 1", template.pane.rows.first().texts.single().toString())
                    click(template.actionStrip!!.actions[0])
                    template = screen.onGetTemplate() as PaneTemplate
                    assertEquals("25/25 · Lieu 25", template.pane.rows.first().texts.single().toString())

                    // Deleting saved places while a later page is open must safely clamp its index.
                    repository.state.value.drop(1).forEach { repository.remove(it.id) }
                    template = screen.onGetTemplate() as PaneTemplate
                    assertEquals("1/1 · Lieu 1", template.pane.rows.first().texts.single().toString())
                    assertEquals(null, template.actionStrip)
                } finally {
                    controller.moveToState(Lifecycle.State.DESTROYED)
                }
            } finally {
                repository.state.value.toList().forEach { repository.remove(it.id) }
                saved.forEach(repository::addFavorite)
            }
        }
    }

    @Test
    fun alternativeRoutesShowTimeDistanceAndSelectTheDisplayedRoute() = onMain {
        val primary = Route(emptyList(), emptyList(), 12_500.0, 1_140.0, summary = "Avenue principale")
        val alternative = Route(emptyList(), emptyList(), 9_200.0, 1_380.0, summary = "Par le centre")
        val state = NavigationState(route = primary, routeOptions = listOf(primary, alternative))
        var selected: Route? = null
        var template = routeChoicesTemplate(state, 0, { selected = it }, {}, {}, {})
        assertEquals(2, template.pane.rows.size)
        assertEquals("1/2 · Avenue principale", template.pane.rows.first().texts.single().toString())
        assertEquals("19 min · 12,5 km · Actuel", template.pane.rows[1].texts.single().toString())
        assertEquals("Estimation sans trafic", template.pane.rows[1].title.toString())
        assertEquals(2, template.actionStrip!!.actions.size)

        template = routeChoicesTemplate(state, 1, { selected = it }, {}, {}, {})
        assertEquals("2/2 · Par le centre", template.pane.rows.first().texts.single().toString())
        assertEquals("23 min · 9,2 km", template.pane.rows[1].texts.single().toString())
        click(template.pane.actions.first())
        assertEquals(alternative, selected)
    }

    @Test
    fun routeWarningsStayVisibleWhileThePreviousRouteStillExists() = onMain {
        val route = Route(emptyList(), emptyList(), 12_500.0, 1_140.0, summary = "Avenue principale")
        val initial = NavigationState(route = route, routeOptions = listOf(route))
        val warning = "Position modifiée : choisissez à nouveau un trajet."
        val error = "Position GPS trop ancienne : attendez un nouveau signal."
        listOf(
            initial.copy(routeWarning = warning) to warning,
            initial.copy(routeWarning = warning, error = error) to error,
            initial.copy(loading = true) to "Calcul en cours…",
        ).forEach { (state, expected) ->
            val template = routeChoicesTemplate(state, 0, {}, {}, {}, {})
            assertEquals(2, template.pane.rows.size)
            assertEquals(listOf("Itinéraire", "Estimation sans trafic"),
                template.pane.rows.map { it.title.toString() })
            assertEquals(expected, template.pane.rows[1].texts.single().toString())
            assertTrue(template.pane.rows.first().texts.single().toString().contains("19 min"))
            if (state.loading) assertTrue(template.pane.actions.none { it.title.toString() == "Choisir" })
        }
    }

    @Test
    fun externalTextCannotExpandCardsIntoParagraphs() {
        val row = compactCarRow("Destination", "Très longue adresse\n".repeat(30))
        assertEquals(1, row.texts.size)
        val text = row.texts.single().toString()
        assertTrue(text.length <= 72)
        assertTrue(text.endsWith("…"))
        assertTrue('\n' !in text)
    }

    private fun click(action: Action) {
        var callbackError: Any? = null
        action.onClickDelegate!!.sendClick(object : OnDoneCallback {
            override fun onFailure(response: Bundleable) { callbackError = response }
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(null, callbackError)
    }

    @Test
    fun incomingNavigationIntentKeepsDestinationsAsBackStackRoot() = onMain {
        val context = carContext(7)
        val preferences = context.getSharedPreferences("navigation_preferences", Context.MODE_PRIVATE)
        val wasAcknowledged = preferences.getBoolean("provider_acknowledged", false)
        preferences.edit().putBoolean("provider_acknowledged", false).commit()
        val session = CockpitCarSession()
        val intent = Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:48.8566,2.3522"))
        // Bind the test context, then drive the INTERNAL lifecycle as the real host does.
        // app-testing 1.7's SessionController.moveToState drives only Session's public registry;
        // ScreenManager observes the separate internal registry introduced by app 1.7.
        SessionController(session, context, intent)
        val hostLifecycle = context.lifecycleOwner.registry
        try {
            val screens = context.getCarService(TestScreenManager::class.java)
            hostLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            screens.push(session.onCreateScreen(intent))
            hostLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            hostLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(screens.top is NavigationScreen)
            assertEquals(Lifecycle.State.RESUMED, screens.top.lifecycle.currentState)
            screens.popToRoot()
            assertTrue(screens.top is DestinationListScreen)
        } finally {
            hostLifecycle.currentState = Lifecycle.State.DESTROYED
            preferences.edit().putBoolean("provider_acknowledged", wasAcknowledged).commit()
        }
    }

    @Test
    fun routeInstructionMapsToTheCorrectHostManeuver() = onMain {
        val step = RouteStep(
            instruction = "Tournez à gauche sur Rue des Écoles",
            maneuverType = "turn",
            modifier = "left",
            roadName = "Rue des Écoles",
            location = GeoPoint(48.85, 2.35),
            routeOffsetMeters = 300.0,
            distanceMeters = 200.0,
            durationSeconds = 40.0,
            exit = null,
        )
        val state = NavigationState(active = true, instruction = step.instruction, nextStep = step)
        val maneuver = carStep(state).maneuver
        assertNotNull(maneuver)
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, maneuver!!.type)
        assertEquals(Maneuver.TYPE_DESTINATION, carStep(state.copy(arrived = true)).maneuver!!.type)
    }

    @Test
    fun navigationSupportsFourTitledActionsIncludingGuidanceOptions() = onMain {
        val actions = ActionStrip.Builder()
        listOf("Destinations", "Infos", "Guidage", "Arrêter").forEach { title ->
            actions.addAction(Action.Builder().setTitle(title).setOnClickListener {}.build())
        }
        val template = NavigationTemplate.Builder().setActionStrip(actions.build()).build()
        assertEquals(4, template.actionStrip!!.actions.size)
    }

    @Test
    fun mapViewportUsesStableSpaceAndRespectsHostOcclusion() {
        val stable = Rect(200, 40, 900, 550)
        assertEquals(stable, carMapViewport(1_000, 600, Rect(100, 0, 1_000, 600), stable))
        assertEquals(Rect(250, 40, 900, 500),
            carMapViewport(1_000, 600, Rect(250, 0, 1_000, 500), stable))
        assertEquals(Rect(0, 0, 1_000, 600), carMapViewport(1_000, 600, null, null))
        assertTrue(carMapViewport(1_000, 600, Rect(), stable).isEmpty)
        assertTrue(carMapViewport(1_000, 600, Rect(1_100, 0, 1_200, 600), stable).isEmpty)
    }

    @SuppressLint("RestrictedApi")
    private fun carContext(apiLevel: Int): TestCarContext =
        TestCarContext.createCarContext(ApplicationProvider.getApplicationContext()).apply {
            updateHandshakeInfo(HandshakeInfo("fr.cockpit.dashboard.test", apiLevel))
        }

    private fun render(screen: Screen): Template {
        val controller = ScreenController(screen)
        return try {
            controller.moveToState(Lifecycle.State.CREATED)
            screen.onGetTemplate()
        } finally {
            controller.moveToState(Lifecycle.State.DESTROYED)
        }
    }

    // With PAUSED looper mode, Robolectric runs each test on its Android main thread.
    private fun onMain(block: () -> Unit) = block()
}
