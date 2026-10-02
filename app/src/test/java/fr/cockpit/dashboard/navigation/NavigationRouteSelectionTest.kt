package fr.cockpit.dashboard.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.cockpit.dashboard.destinations.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationRouteSelectionTest {
    @Test fun `choosing the current route keeps guidance progress and remaining time`() = withRepository { repository, state ->
        val route = route("D 5")
        val current = NavigationState(active = true, route = route, routeOptions = listOf(route),
            progressFraction = 0.6, nextStepIndex = 2, remainingSeconds = 92)
        state.value = current
        repository.selectRoute(route)
        assertSame(current, repository.state.value)
        assertEquals(0.6, repository.state.value.progressFraction, 0.0)
        assertEquals(92L, repository.state.value.remainingSeconds)
    }

    @Test fun `a route button from an earlier request cannot select a newer matching index`() = withRepository { repository, state ->
        val oldAlternative = route("D 17")
        val newRoute = route("D 5")
        val newAlternative = oldAlternative.copy()
        val current = NavigationState(active = true, route = newRoute,
            routeOptions = listOf(newRoute, newAlternative), progressFraction = 0.4)
        state.value = current
        repository.selectRoute(oldAlternative)
        assertSame(current, repository.state.value)
        assertSame(newRoute, repository.state.value.route)
    }

    @Test fun `switching to another route with no fresh GPS leaves current guidance intact`() = withRepository { repository, state ->
        val currentRoute = route("D 5")
        val alternative = route("D 17")
        state.value = NavigationState(active = true,
            destination = Destination("test", "Dourdan", 48.53, 2.01),
            route = currentRoute, routeOptions = listOf(currentRoute, alternative), progressFraction = 0.4)
        repository.selectRoute(alternative)
        assertSame(currentRoute, repository.state.value.route)
        assertEquals(0.4, repository.state.value.progressFraction, 0.0)
        assertTrue(repository.state.value.error!!.contains("GPS récent"))
    }

    private fun route(summary: String) = Route(emptyList(), emptyList(), 500.0, 90.0, summary)

    @Suppress("UNCHECKED_CAST")
    private fun withRepository(block: (NavigationRepository, MutableStateFlow<NavigationState>) -> Unit) {
        val constructor = NavigationRepository::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val repository = constructor.newInstance(ApplicationProvider.getApplicationContext<Context>())
        val state = NavigationRepository::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(repository) as MutableStateFlow<NavigationState>
        try { block(repository, state) } finally {
            val scope = NavigationRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(repository) as CoroutineScope
            scope.cancel()
        }
    }
}
