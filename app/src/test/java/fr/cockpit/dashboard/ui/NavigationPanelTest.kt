package fr.cockpit.dashboard.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

    private fun data(navigation: NavigationState) = DashboardData(
        speedKmh = null, totalMeters = 0.0, tripMeters = 0.0, recording = false,
        temperatureC = null, weatherSummary = "", weatherEnabled = false, weatherUpdatedAt = null,
        musicTitle = "", musicArtist = "", musicConnected = false, musicPlaying = false,
        canPlayPause = false, canPrevious = false, canNext = false,
        destinations = emptyList(), latitude = null, longitude = null, navigation = navigation,
    )
}
