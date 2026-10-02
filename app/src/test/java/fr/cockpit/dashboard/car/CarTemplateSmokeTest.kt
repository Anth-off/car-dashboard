@file:Suppress("DEPRECATION")

package fr.cockpit.dashboard.car

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.HandshakeInfo
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ListTemplate
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
import fr.cockpit.dashboard.navigation.GeoPoint
import fr.cockpit.dashboard.navigation.NavigationState
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.navigation.RouteStep
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
            val destinations = render(DestinationListScreen(carContext(api))) as ListTemplate
            assertTrue(destinations.singleList!!.items.size >= 2)

            val destination = Destination("test", "Lieu de test", 48.8566, 2.3522)
            val detail = render(DestinationScreen(carContext(api), destination)) as PaneTemplate
            assertEquals(1, detail.pane.actions.size)

            val trip = render(TripScreen(carContext(api))) as PaneTemplate
            assertEquals(4, trip.pane.rows.size)
            assertEquals(2, trip.pane.actions.size)

            val music = render(MusicScreen(carContext(api))) as PaneTemplate
            assertEquals(3, music.pane.rows.size)

            val navigationContext = carContext(api)
            NavigationRepository.get(navigationContext).stop()
            val navigation = render(NavigationScreen(navigationContext)) as NavigationTemplate
            assertNotNull(navigation.navigationInfo)
            assertEquals(2, navigation.actionStrip!!.actions.size)
            if (api >= 2) assertEquals(2, navigation.mapActionStrip!!.actions.size)
        }
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
    fun navigationSupportsFourTitledActionsIncludingReroute() = onMain {
        val actions = ActionStrip.Builder()
        listOf("Destinations", "Infos", "Arrêter", "Recalculer").forEach { title ->
            actions.addAction(Action.Builder().setTitle(title).setOnClickListener {}.build())
        }
        val template = NavigationTemplate.Builder().setActionStrip(actions.build()).build()
        assertEquals(4, template.actionStrip!!.actions.size)
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
