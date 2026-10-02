package fr.cockpit.dashboard.navigation

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationVoiceTest {
    @Test fun `the Android adapter never flushes playing speech when another guidance cue arrives`() {
        val milestones = mutableListOf<GuidanceAnnouncement>()
        ShadowTextToSpeech.addLanguageAvailability(Locale.FRANCE)
        val voice = NavigationVoice(ApplicationProvider.getApplicationContext<Context>()) { milestones += it }
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        voice.setContext("step:1")
        fun cue(text: String) = GuidanceAnnouncement("step:1", text, SystemClock.elapsedRealtime() + 15_000)
        voice.speak(cue("Dans 300 mètres, tournez à droite"))
        voice.speak(cue("Tournez à droite"))
        // Shadow TTS completion is posted, so the first sentence is still in progress here.
        assertEquals(listOf("Dans 300 mètres, tournez à droite"), tts.spokenTextList)
        assertEquals(TextToSpeech.QUEUE_ADD, tts.queueMode)
        assertEquals(1, milestones.size)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Dans 300 mètres, tournez à droite", "Tournez à droite"), tts.spokenTextList)
        assertEquals(TextToSpeech.QUEUE_ADD, tts.queueMode)
        assertEquals(2, milestones.size)
        voice.stop()
    }

    @Test fun `muting before the TTS engine is ready prevents delayed speech at initialization`() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.FRANCE)
        val voice = NavigationVoice(ApplicationProvider.getApplicationContext<Context>()) {}
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        voice.setContext("step:1")
        voice.speak(GuidanceAnnouncement("step:1", "Tournez à droite", SystemClock.elapsedRealtime() + 15_000))
        voice.stop()
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(tts.spokenTextList.isEmpty())
    }
}
