package fr.cockpit.dashboard.destinations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DestinationRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: DestinationRepository

    @Before fun resetPlaces() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cockpit_destinations", Context.MODE_PRIVATE).edit().clear().commit()
        repository = DestinationRepository(context)
    }

    @Test fun `revisited place moves to the front and stays distinct from favorites`() {
        val home = repository.add("Maison", 48.0, 2.0)
        val work = Destination("work", "Bureau", 49.0, 3.0, "12 rue du Test")
        repository.recordVisit(home)
        repository.recordVisit(work)
        repository.recordVisit(home.copy(id = "new-search", name = "Une autre étiquette"))

        assertEquals(listOf(home, work), repository.recents.value)
        assertEquals(listOf(home), repository.state.value)
        val reopened = DestinationRepository(context)
        assertEquals(listOf(home, work), reopened.recents.value)
        assertEquals("12 rue du Test", reopened.recents.value[1].address)
        reopened.clearRecents()
        assertTrue(DestinationRepository(context).recents.value.isEmpty())
        assertEquals(listOf(home), reopened.state.value)
    }

    @Test fun `history retains only twelve latest distinct destinations`() {
        repeat(15) { index -> repository.recordVisit(Destination("$index", "Lieu $index", index.toDouble(), 2.0)) }
        assertEquals(12, repository.recents.value.size)
        assertEquals("14", repository.recents.value.first().id)
        assertEquals("3", repository.recents.value.last().id)
    }

    @Test fun `different places sharing an external id stay unique in favorites and recents after reload`() {
        val first = Destination("external", "Paris", 48.8566, 2.3522)
        val second = Destination("external", "Lyon", 45.7640, 4.8357)
        repository.recordVisit(first)
        repository.recordVisit(second)
        repository.addFavorite(first)
        repository.addFavorite(second)

        val reopened = DestinationRepository(context)
        assertEquals(listOf("Lyon", "Paris"), reopened.recents.value.map { it.name })
        assertEquals(2, reopened.recents.value.map { it.id }.toSet().size)
        assertEquals(repository.recents.value, reopened.recents.value)
        assertEquals(listOf("Paris", "Lyon"), reopened.state.value.map { it.name })
        assertEquals(2, reopened.state.value.map { it.id }.toSet().size)
        assertEquals(repository.state.value, reopened.state.value)
        reopened.toggleFavorite(second)
        assertEquals(listOf("Paris"), reopened.state.value.map { it.name })
    }

    @Test fun `loading old duplicate ids repairs them without losing destinations`() {
        val records = """[{"id":"external","name":"Paris","latitude":48.8566,"longitude":2.3522},{"id":"external","name":"Lyon","latitude":45.7640,"longitude":4.8357}]"""
        context.getSharedPreferences("cockpit_destinations", Context.MODE_PRIVATE).edit()
            .putString("favorites_v1", records).putString("recents_v1", records).commit()
        val firstLoad = DestinationRepository(context)
        val secondLoad = DestinationRepository(context)
        assertEquals(listOf("Paris", "Lyon"), firstLoad.state.value.map { it.name })
        assertEquals(2, firstLoad.state.value.map { it.id }.toSet().size)
        assertEquals(firstLoad.state.value, secondLoad.state.value)
        assertEquals(firstLoad.recents.value, secondLoad.recents.value)
        assertEquals(2, firstLoad.recents.value.map { it.id }.toSet().size)
    }

    @Test fun `favoriting the same place twice preserves its custom name and can toggle it off`() {
        val saved = repository.add("Maison", 48.0, 2.0)
        val result = Destination("search", "Adresse", 48.00001, 2.00001)
        assertEquals(saved, repository.addFavorite(result))
        assertEquals(1, repository.state.value.size)
        assertTrue(repository.isFavorite(result))
        repository.toggleFavorite(result)
        assertTrue(repository.state.value.isEmpty())
    }

    @Test fun `existing v1 favorites and valid records survive a malformed record`() {
        context.getSharedPreferences("cockpit_destinations", Context.MODE_PRIVATE).edit().putString("favorites_v1",
            """[{"id":"old","name":"Ancien favori","latitude":48.0,"longitude":2.0},{"id":"broken","name":"Invalide","latitude":190.0,"longitude":2.0}]""").commit()
        val reopened = DestinationRepository(context)
        assertEquals(1, reopened.state.value.size)
        assertEquals("Ancien favori", reopened.state.value.first().name)
        assertEquals("", reopened.state.value.first().address)
    }

    @Test fun `coordinate search supports normal and French notation without a geocoding provider`() = runBlocking {
        listOf("48.8566, 2.3522", "48.8566 2.3522", "48,8566; 2,3522", "geo:48.8566,2.3522").forEach { query ->
            val place = repository.search(query).single()
            assertEquals(48.8566, place.latitude, 0.000001)
            assertEquals(2.3522, place.longitude, 0.000001)
        }
        assertTrue(repository.state.value.isEmpty())
        assertTrue(repository.recents.value.isEmpty())
        assertNull(DestinationRepository.parseCoordinates("91, 2"))
        assertNull(DestinationRepository.parseCoordinates("NaN, 2"))
        assertNull(DestinationRepository.parseCoordinates("12 rue du Test"))
    }
}
