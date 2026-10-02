package fr.cockpit.dashboard.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationSpeechQueueTest {
    private class VoiceHarness {
        var time = 0L
        var accepts = true
        var failWithException = false
        var stops = 0
        var releases = 0
        val spoken = mutableListOf<Pair<GuidanceAnnouncement, String>>()
        val milestones = mutableListOf<GuidanceAnnouncement>()
        val queue = NavigationSpeechQueue(
            now = { time },
            start = { cue, id ->
                if (failWithException) throw IllegalStateException("TTS service disconnected")
                if (accepts) spoken.add(cue to id)
                accepts
            },
            halt = { stops++ }, releaseFocus = { releases++ }, onSpoken = { milestones += it },
        ).apply { setReady(true); setContext("step:1") }
        fun cue(text: String, context: String = "step:1", ttl: Long = 15_000) =
            GuidanceAnnouncement(context, text, time + ttl)
        fun complete(index: Int) = queue.completed(spoken[index].second)
    }

    @Test fun `rapid updates finish the current sentence and keep only the newest useful next cue`() {
        val h = VoiceHarness()
        h.queue.offer(h.cue("Dans 300 mètres, tournez à droite"))
        h.queue.offer(h.cue("Dans 100 mètres, tournez à droite"))
        h.queue.offer(h.cue("Tournez à droite"))
        assertEquals(1, h.spoken.size)
        assertEquals(0, h.stops)
        assertEquals(1, h.milestones.size)
        h.complete(0)
        assertEquals(listOf("Dans 300 mètres, tournez à droite", "Tournez à droite"), h.spoken.map { it.first.text })
        assertEquals(0, h.stops)
    }

    @Test fun `a passed turn or lost GPS invalidates queued guidance without needing a new announcement`() {
        for (context in listOf("step:2", "gps-lost", "off-route")) {
            val h = VoiceHarness()
            h.queue.offer(h.cue("Current phrase"))
            h.queue.offer(h.cue("Old turn"))
            h.queue.setContext(context)
            h.complete(0)
            assertEquals(1, h.spoken.size)
            assertEquals(1, h.milestones.size)
        }
    }

    @Test fun `expired speech is discarded and can be replaced by a fresh actionable instruction`() {
        val h = VoiceHarness()
        h.queue.offer(h.cue("Current phrase"))
        h.queue.offer(h.cue("Turn soon", ttl = 6_000))
        h.time = 6_000
        h.complete(0)
        assertEquals(1, h.spoken.size)
        assertEquals(1, h.milestones.size)
        h.queue.offer(h.cue("Turn now"))
        assertEquals("Turn now", h.spoken.last().first.text)
    }

    @Test fun `stop mute and route replacement discard pending and ignore late callbacks`() {
        val h = VoiceHarness()
        h.queue.offer(h.cue("Old phrase"))
        h.queue.offer(h.cue("Old next turn"))
        h.queue.stop()
        h.queue.setContext("step:1") // The new route may reuse step numbers.
        h.queue.offer(h.cue("New route"))
        h.queue.offer(h.cue("New next turn"))
        val released = h.releases
        h.complete(0)
        assertEquals(released, h.releases)
        assertEquals(2, h.spoken.size)
        h.complete(1)
        assertEquals("New next turn", h.spoken.last().first.text)
        assertEquals(1, h.stops)
    }

    @Test fun `arrival replaces any stale guidance and is not left waiting for an abandoned phrase`() {
        val h = VoiceHarness()
        h.queue.offer(h.cue("Old phrase"))
        h.queue.offer(h.cue("Old next turn"))
        h.queue.stop()
        h.queue.setContext("arrived")
        h.queue.offer(h.cue("Vous êtes arrivé à destination.", "arrived"))
        h.complete(0)
        assertEquals(listOf("Old phrase", "Vous êtes arrivé à destination."), h.spoken.map { it.first.text })
    }

    @Test fun `delayed TTS initialization uses only fresh speech and stop clears initialization backlog`() {
        val h = VoiceHarness()
        h.queue.setReady(false)
        h.queue.offer(h.cue("Old turn"))
        h.queue.setContext("step:2")
        h.queue.offer(h.cue("New turn", "step:2"))
        h.queue.setReady(true)
        assertEquals(listOf("New turn"), h.spoken.map { it.first.text })
        h.queue.stop()
        h.queue.setReady(false)
        h.queue.setContext("step:3")
        h.queue.offer(h.cue("Muted turn", "step:3"))
        h.queue.stop()
        h.queue.setReady(true)
        assertEquals(1, h.spoken.size)
    }

    @Test fun `denied focus or a failed engine submission does not consume speech or lock the queue`() {
        val h = VoiceHarness()
        h.accepts = false
        h.queue.offer(h.cue("Turn now"))
        assertTrue(h.spoken.isEmpty())
        assertTrue(h.milestones.isEmpty())
        assertEquals(1, h.releases)
        h.accepts = true
        h.queue.offer(h.cue("Turn now"))
        assertEquals(1, h.spoken.size)
        assertEquals(1, h.milestones.size)
    }
    @Test fun `a disconnected speech service releases focus and leaves later instructions usable`() {
        val h = VoiceHarness()
        h.failWithException = true
        h.queue.offer(h.cue("Turn now"))
        assertEquals(1, h.releases)
        assertTrue(h.milestones.isEmpty())
        h.failWithException = false
        h.queue.offer(h.cue("Turn now"))
        assertEquals(1, h.spoken.size)
    }

}
