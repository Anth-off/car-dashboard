package fr.cockpit.dashboard.navigation

/** One playing phrase and one replaceable next phrase: never an unbounded TTS backlog. */
internal class NavigationSpeechQueue(
    private val now: () -> Long,
    private val start: (GuidanceAnnouncement, String) -> Boolean,
    private val halt: () -> Unit,
    private val releaseFocus: () -> Unit,
    private val onSpoken: (GuidanceAnnouncement) -> Unit,
) {
    private var ready = false
    private var context: String? = null
    private var pending: GuidanceAnnouncement? = null
    private var playingId: String? = null
    private var nextId = 0L

    fun setReady(value: Boolean) {
        ready = value
        if (value) drain()
    }

    fun setContext(value: String) {
        if (context != value) {
            context = value
            pending = null
        }
    }

    fun offer(announcement: GuidanceAnnouncement) {
        if (announcement.context != context) return
        pending = announcement
        drain()
    }

    /** Called on the main thread; late callbacks cannot release a newer phrase's audio focus. */
    fun completed(id: String?) {
        if (id == null || id != playingId) return
        playingId = null
        releaseFocus()
        drain()
    }

    fun stop() {
        pending = null
        playingId = null
        context = null
        halt()
        releaseFocus()
    }

    private fun drain() {
        if (!ready || playingId != null) return
        val announcement = pending ?: return
        pending = null
        if (announcement.context != context || now() >= announcement.expiresAtMillis) return
        val id = (++nextId).toString()
        playingId = id
        val accepted = try { start(announcement, id) } catch (_: RuntimeException) { false }
        if (accepted) onSpoken(announcement)
        else {
            playingId = null
            releaseFocus()
        }
    }
}
