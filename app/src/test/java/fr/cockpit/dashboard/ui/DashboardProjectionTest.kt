package fr.cockpit.dashboard.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import fr.cockpit.dashboard.map.RouteMapView
import fr.cockpit.dashboard.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w851dp-h393dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardProjectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w560dp-h320dp-land-mdpi")
    fun narrowCarViewportKeepsTheMapAndSeparateControlsVisibleWithoutScrolling() {
        compose.setContent {
            CockpitTheme {
                Dashboard(data(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    projection = DashboardProjection(false, {}, {}, {}, {}))
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true).assertCountEquals(0)
        val viewport = compose.onNodeWithTag("dashboard_viewport").getUnclippedBoundsInRoot()
        val map = compose.onNodeWithTag("navigation_map_surface").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The map must retain a useful width", (map.right - map.left).value >= 300f)
        assertTrue("The map must have positive height", map.bottom > map.top)
        listOf("dashboard_map", "dashboard_metrics", "dashboard_music").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag must fit the viewport", bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        val controls = listOf("Démarrer le suivi GPS", "Réglages et autorisations", "Réinitialiser le trajet",
            "Ouvrir Apple Music", "Morceau précédent", "Lire", "Morceau suivant", "Options de la carte",
            "Zoom avant", "Zoom arrière", "Rechercher une destination", "Carte en plein écran",
            "Choisir un trajet ou voir les étapes", "Couper le guidage vocal", "Terminer le guidage").map { description ->
            val bounds = compose.onNodeWithContentDescription(description).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$description must keep its full touch target", (bounds.right - bounds.left).value >= 48f &&
                (bounds.bottom - bounds.top).value >= 48f)
            assertTrue("$description must fit the viewport", bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            description to bounds
        }
        controls.forEachIndexed { index, (description, bounds) ->
            controls.drop(index + 1).forEach { (otherDescription, otherBounds) ->
                assertTrue("$description must not overlap $otherDescription", bounds.right <= otherBounds.left ||
                    otherBounds.right <= bounds.left || bounds.bottom <= otherBounds.top || otherBounds.bottom <= bounds.top)
            }
        }
    }

    @Test fun projectedMenusReachTheHostAndExpandedMapStaysInTheSameWindow() {
        val expanded = mutableStateOf(false)
        var options = 0
        var journeys = 0
        compose.setContent {
            CockpitTheme {
                Dashboard(data(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, projection = DashboardProjection(
                    expanded.value, { expanded.value = !expanded.value }, {}, { options++ }, { journeys++ },
                ))
            }
        }
        compose.onNodeWithTag("dashboard_music").assertIsDisplayed()
        compose.onNodeWithTag("dashboard_metrics").assertIsDisplayed()
        compose.onNodeWithContentDescription("Options de la carte").performClick()
        compose.onNodeWithContentDescription("Choisir un trajet ou voir les étapes").performClick()
        compose.runOnIdle {
            assertEquals(1, options)
            assertEquals(1, journeys)
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Carte en plein écran").performClick()
        compose.onNodeWithTag("dashboard_music").assertDoesNotExist()
        compose.onNodeWithTag("dashboard_metrics").assertDoesNotExist()
        compose.onNodeWithContentDescription("Quitter le plein écran").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Quitter le plein écran").performClick()
        compose.onNodeWithTag("dashboard_music").assertIsDisplayed()
        compose.onNodeWithTag("dashboard_metrics").assertIsDisplayed()
    }

    @Test fun projectedMapIsReleasedAndHostNightModeSurvivesDataUpdates() {
        val visible = mutableStateOf(true)
        val speed = mutableStateOf(50f)
        var map: RouteMapView? = null
        compose.setContent {
            if (visible.value) CockpitTheme {
                Dashboard(data().copy(speedKmh = speed.value), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    projection = DashboardProjection(false, {}, { map = it }, {}, {}))
            }
        }
        compose.runOnIdle {
            assertNotNull(map)
            map!!.setNightMode(false)
            speed.value = 60f
        }
        compose.runOnIdle {
            assertFalse(map!!.isNightMode)
            visible.value = false
        }
        compose.runOnIdle { assertNull(map) }
    }

    private fun data() = DashboardData(
        speedKmh = null, totalMeters = 0.0, tripMeters = 0.0, recording = false,
        temperatureC = null, weatherSummary = "", weatherEnabled = false, weatherUpdatedAt = null,
        musicTitle = "", musicArtist = "", musicConnected = false, musicPlaying = false,
        canPlayPause = false, canPrevious = false, canNext = false,
        destinations = emptyList(), latitude = null, longitude = null,
        navigation = NavigationState(active = true, instruction = "Continuez tout droit"),
    )
}
