package fr.cockpit.dashboard.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fr.cockpit.dashboard.navigation.GeoPoint
import fr.cockpit.dashboard.navigation.Route
import fr.cockpit.dashboard.navigation.RouteStep
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertSame
import fr.cockpit.dashboard.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullscreenCanBeOpenedAndSearchReturnsToDestinationPicker() {
        var searches = 0
        compose.setContent {
            CockpitTheme { NavigationPanel(data(NavigationState()), { searches++ }, {}, {}, {}, {}, Modifier.fillMaxSize()) }
        }
        compose.onNodeWithContentDescription("Carte en plein écran").performClick()
        compose.onNodeWithContentDescription("Quitter le plein écran").assertIsDisplayed()
        compose.onNode(isDialog()).assertExists()
        compose.onAllNodesWithContentDescription("Rechercher une destination").onLast().performClick()
        compose.onNodeWithContentDescription("Quitter le plein écran").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, searches) }
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-mdpi")
    fun voiceToggleAndStopControlTheActiveNavigation() {
        val navigation = mutableStateOf(NavigationState(active = true, instruction = "Continuez tout droit",
            remainingSeconds = 720, remainingMeters = 6500.0, distanceToTurnMeters = 250.0))
        var stops = 0
        compose.setContent {
            CockpitTheme {
                NavigationPanel(data(navigation.value), {}, { stops++ }, {}, {},
                    { navigation.value = navigation.value.copy(voiceEnabled = it) }, Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithContentDescription("Zoom avant").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zoom arrière").assertIsDisplayed()
        compose.onNodeWithText("12 min").assertIsDisplayed()
        compose.onNodeWithText("250 m").assertIsDisplayed()
        compose.onNodeWithContentDescription("Couper le guidage vocal").performClick()
        compose.onNodeWithContentDescription("Activer le guidage vocal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Terminer le guidage").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-mdpi")
    fun alternativesAndDirectionsUsePagesAndSelectTheDisplayedRoute() {
        val steps = (1..5).map { RouteStep("Instruction $it", "turn", "right", "D17", GeoPoint(48.0, 2.0),
            it * 100.0, 100.0, 30.0) }
        val fastest = Route(emptyList(), steps, 17_500.0, 1380.0, "D5 · D17")
        val alternative = Route(emptyList(), steps, 18_500.0, 1500.0, "D17 · D838")
        var selected: Route? = null
        val nav = NavigationState(active = true, route = fastest, routeOptions = listOf(fastest, alternative),
            instruction = "Continuez", nextStepIndex = 0)
        compose.setContent {
            CockpitTheme { NavigationPanel(data(nav), {}, {}, {}, {}, {}, Modifier.fillMaxSize(), { selected = it; true }) }
        }
        compose.onNodeWithContentDescription("Choisir un trajet ou voir les étapes").performClick()
        compose.onNodeWithText("23 min").assertIsDisplayed()
        compose.onNodeWithContentDescription("Trajet suivant").performClick()
        compose.onNodeWithText("D17 · D838").assertIsDisplayed()
        compose.onNodeWithText("25 min").assertIsDisplayed()
        compose.onNodeWithText("Étapes").performClick()
        repeat(4) { compose.onNodeWithContentDescription("Étape suivante").assertIsDisplayed().performClick() }
        compose.onNodeWithText("Instruction 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Étape suivante").assertIsNotEnabled()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Trajets").performClick()
        compose.onNodeWithContentDescription("Choisir cet itinéraire").performClick()
        compose.runOnIdle { assertSame(alternative, selected) }
    }

    @Test fun rejectedAlternativeKeepsTheComparisonOpenAndShowsItsReason() {
        val original = Route(emptyList(), emptyList(), 1000.0, 300.0, "Trajet actuel")
        val alternative = original.copy(summary = "Autre route", totalSeconds = 360.0)
        val nav = mutableStateOf(NavigationState(active = true, route = original,
            routeOptions = listOf(original, alternative)))
        compose.setContent {
            CockpitTheme {
                NavigationPanel(data(nav.value), {}, {}, {}, {}, {}, Modifier.fillMaxSize(), {
                    nav.value = nav.value.copy(error = "GPS récent nécessaire pour changer de trajet.")
                    false
                })
            }
        }
        compose.onNodeWithContentDescription("Choisir un trajet ou voir les étapes").performClick()
        compose.onNodeWithContentDescription("Trajet suivant").performClick()
        compose.onNodeWithContentDescription("Choisir cet itinéraire").performClick()
        compose.onNodeWithText("Choisir votre trajet").assertIsDisplayed()
        compose.onAllNodesWithText("GPS récent nécessaire pour changer de trajet.").onLast().assertIsDisplayed()
    }

    private fun data(navigation: NavigationState) = DashboardData(
        speedKmh = null, totalMeters = 0.0, tripMeters = 0.0, recording = false,
        temperatureC = null, weatherSummary = "", weatherEnabled = false, weatherUpdatedAt = null,
        musicTitle = "", musicArtist = "", musicConnected = false, musicPlaying = false,
        canPlayPause = false, canPrevious = false, canNext = false,
        destinations = emptyList(), latitude = null, longitude = null, navigation = navigation,
    )
}
