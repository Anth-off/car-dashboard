package fr.cockpit.dashboard.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import fr.cockpit.dashboard.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Checks complete visible controls and data, rather than only whether the Activity can draw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w412dp-h915dp-port-mdpi")
    fun s23PortraitKeepsEveryPanelAndControlInTheViewport() = assertDashboardFits()

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-mdpi")
    fun s23LandscapeKeepsEveryPanelAndControlInTheViewport() = assertDashboardFits()

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    fun smallPhoneWithLargerTextHasNoScrollOrHiddenControls() = assertDashboardFits(fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-mdpi")
    fun landscapeWithLargerTextHasNoScrollOrHiddenControls() = assertDashboardFits(fontScale = 1.3f)

    private fun assertDashboardFits(fontScale: Float = 1f) {
        var resets = 0
        var settings = 0
        var starts = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CockpitTheme {
                    Dashboard(data(), { starts++ }, { resets++ }, { settings++ }, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true).assertCountEquals(0)

        val viewport = compose.onNodeWithTag("dashboard_viewport").getUnclippedBoundsInRoot()
        listOf("dashboard_map", "dashboard_metrics", "dashboard_speed", "dashboard_weather",
            "dashboard_total", "dashboard_trip", "dashboard_music").forEach { tag ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag must fit the viewport horizontally", bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue("$tag must fit the viewport vertically", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        val map = compose.onNodeWithTag("dashboard_map").getUnclippedBoundsInRoot()
        assertTrue("Even the smallest layout reserves a useful map", (map.right - map.left).value >= 300f && (map.bottom - map.top).value >= 220f)

        // Every action remains reachable without swiping, including disabled media controls.
        listOf("Démarrer le suivi GPS", "Réglages et autorisations", "Réinitialiser le trajet",
            "Ouvrir Apple Music", "Morceau précédent", "Lire", "Morceau suivant",
            "Rechercher une destination", "Carte en plein écran", "Terminer le guidage").forEach { description ->
            val node = compose.onNodeWithContentDescription(description).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$description must have a full touch target", (bounds.right - bounds.left).value >= 48f && (bounds.bottom - bounds.top).value >= 48f)
            assertTrue("$description cannot be clipped", bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        compose.onNodeWithTag("dashboard_total", useUnmergedTree = true).onChildren().filter(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(2)
        compose.onNodeWithContentDescription("Réinitialiser le trajet").performClick()
        compose.onNodeWithContentDescription("Réglages et autorisations").performClick()
        compose.onNodeWithContentDescription("Démarrer le suivi GPS").performClick()
        compose.runOnIdle {
            assertEquals(1, resets)
            assertEquals(1, settings)
            assertEquals(1, starts)
        }
    }

    private fun data() = DashboardData(
        speedKmh = 138f, totalMeters = 1_234_567_800.0, tripMeters = 1_234_500.0, recording = false,
        temperatureC = 18.0, weatherSummary = "Partiellement nuageux", weatherEnabled = true,
        weatherUpdatedAt = null, musicTitle = "Un titre assez long pour occuper toute la ligne",
        musicArtist = "Artiste", musicConnected = true, musicPlaying = false,
        canPlayPause = true, canPrevious = true, canNext = true, destinations = emptyList(),
        latitude = null, longitude = null, navigation = NavigationState(active = true,
            instruction = "Tournez à droite", distanceToTurnMeters = 250.0,
            remainingMeters = 6500.0, remainingSeconds = 720),
    )
}
