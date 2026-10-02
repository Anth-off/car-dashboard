package fr.cockpit.dashboard.ui

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.unit.dp
import fr.cockpit.dashboard.destinations.Destination
import fr.cockpit.dashboard.destinations.DestinationRepository
import fr.cockpit.dashboard.integrations.WeatherState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w851dp-h393dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FixedDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allFavoritesRemainReachableByButtonsInCompactLandscape() {
        val repository = repository()
        (1..11).forEach { repository.add("Lieu $it", 48.0 + it / 100.0, 2.0) }
        var selected: Destination? = null
        compose.setContent {
            val saved by repository.state.collectAsState()
            CockpitTheme { DestinationsDialog(repository, saved, { selected = it }, {}) }
        }
        compose.onNodeWithText("Lieu 1").assertIsDisplayed()
        assertNoVerticalScroll()
        var turns = 0
        while (compose.onAllNodesWithText("Lieu 11").fetchSemanticsNodes().isEmpty()) {
            check(turns++ < 11) { "The last favorite must be accessible" }
            compose.onNodeWithContentDescription("Page suivante").assertIsDisplayed().performClick()
        }
        compose.onNodeWithText("Lieu 11").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Lieu 11", selected?.name) }
        compose.onNodeWithContentDescription("Page précédente").assertIsDisplayed().performClick()
        assertNoVerticalScroll()
    }

    @Test fun gpsCoordinatesAreValidatedAndSavedThroughFixedSteps() {
        val repository = repository()
        compose.setContent {
            val saved by repository.state.collectAsState()
            CockpitTheme { DestinationsDialog(repository, saved, {}, {}) }
        }
        compose.onNodeWithContentDescription("Fermer la saisie").performClick()
        compose.onNodeWithContentDescription("Saisir des coordonnées GPS").performClick()
        compose.onNodeWithText("Nom du lieu (facultatif)").performTextInput("Maison")
        compose.onNodeWithText("Suivant").performClick()
        compose.onNodeWithText("Latitude : −90 à 90").performTextInput("95")
        compose.onNodeWithText("Suivant").performClick()
        compose.onNodeWithText("2 / 4").assertIsDisplayed()
        compose.onNodeWithText("Latitude : −90 à 90").performTextReplacement("48,8566")
        compose.onNodeWithText("Suivant").performClick()
        compose.onNodeWithText("Longitude : −180 à 180").performTextInput("2,3522")
        compose.onNodeWithText("Vérifier").performClick()
        compose.onNodeWithText("Maison").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enregistrer en favori").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Maison", repository.state.value.single().name)
            assertEquals(48.8566, repository.state.value.single().latitude, .000001)
        }
        assertNoVerticalScroll()
    }

    @Test fun everySettingsSectionAndMusicConsentFitsCompactLandscape() {
        var accessRequests = 0
        compose.setContent {
            CockpitTheme { SettingsDialog(WeatherState(), false, true, {}, { accessRequests++ }, {}, {}) }
        }
        compose.onNodeWithText("Autoriser les commandes musicales").assertIsDisplayed().performClick()
        compose.onNodeWithText("Une autorisation Android étendue").assertIsDisplayed()
        compose.onNodeWithText("Continuer").assertIsDisplayed().performClick()
        compose.onNodeWithText("Ouvrir les réglages").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, accessRequests) }
        val titles = listOf("Météo extérieure", "Guidage vocal", "La carte", "Les itinéraires", "Vos compteurs", "Sur Android Auto")
        titles.forEach { title ->
            compose.onNodeWithContentDescription("Réglage suivant").assertIsDisplayed().performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText("Terminé").assertIsDisplayed()
            assertNoVerticalScroll()
        }
        compose.onNodeWithContentDescription("Réglage suivant").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-port-mdpi")
    fun coordinateSearchStillFindsAndOpensAResultWithoutScrolling() {
        val repository = repository()
        var selected: Destination? = null
        compose.setContent { CockpitTheme { DestinationsDialog(repository, emptyList(), { selected = it }, {}) } }
        compose.onNodeWithText("Adresse, ville ou lieu").performTextInput("48.8566, 2.3522")
        compose.onNodeWithContentDescription("Rechercher").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Point GPS").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Point GPS").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(48.8566, selected!!.latitude, .000001) }
        assertNoVerticalScroll()
    }

    @Test fun searchAndGpsEntryKeepTheirControlsAboveTheLandscapeKeyboard() {
        val repository = repository()
        compose.setContent { CockpitTheme { DestinationsDialog(repository, emptyList(), {}, {}) } }
        showKeyboardInsets()
        compose.onNodeWithText("Adresse, ville ou lieu").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithContentDescription("Rechercher").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Fermer la saisie").performClick()
        showKeyboardInsets(0)
        compose.onNodeWithContentDescription("Saisir des coordonnées GPS").performClick()
        showKeyboardInsets()
        compose.onNodeWithText("Nom du lieu (facultatif)").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Suivant").assertIsDisplayed().performClick()
        compose.onNodeWithText("Latitude : −90 à 90").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Retour").assertIsDisplayed()
        assertNoVerticalScroll()
    }

    private fun showKeyboardInsets(bottom: Int = 220) {
        compose.runOnIdle {
            val decor = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            ViewCompat.dispatchApplyWindowInsets(decor, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                .build())
        }
    }

    private fun assertNoVerticalScroll() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private fun repository(): DestinationRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("destinations", Context.MODE_PRIVATE).edit().clear().commit()
        return DestinationRepository(context).also { repo ->
            repo.state.value.forEach { repo.remove(it.id) }
            repo.clearRecents()
        }
    }
}
