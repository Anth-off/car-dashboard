package fr.cockpit.dashboard.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.lifecycle.Lifecycle
import fr.cockpit.dashboard.MainActivity
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.telemetry.TripRepository
import java.io.File
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Starts the real Activity and executes Compose's draw path without granting location permission,
 * starting a trip, or injecting demo data. Optional PNGs use Robolectric's native Android graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityRenderTest {
    @Test
    @Config(qualifiers = "w412dp-h915dp-port-mdpi")
    fun realDashboardStartsAndDrawsInPortrait() = startAndDraw("cockpit-portrait.png")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun realDashboardStartsAndDrawsInLandscape() = startAndDraw("cockpit-landscape.png")

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-mdpi")
    fun realDashboardFitsCompactLandscape() = startAndDraw("cockpit-compact-landscape.png")

    private fun startAndDraw(filename: String) {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.setup().visible()
            val activity = controller.get()
            val decor = activity.window.decorView
            val display = activity.resources.displayMetrics
            val width = display.widthPixels
            val height = display.heightPixels
            val mainLooper = shadowOf(Looper.getMainLooper())

            // Bounded frame advances allow Compose to compose and lay out without draining the
            // process-wide repositories' intentionally recurring timer coroutines forever.
            mainLooper.idleFor(Duration.ofMillis(250))
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, width, height)
            mainLooper.idleFor(Duration.ofMillis(250))

            assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            assertFalse(activity.isFinishing)
            assertFalse(TripRepository.get(activity).state.value.recording)
            assertFalse(NavigationRepository.get(activity).state.value.active)
            assertTrue("The Activity must have a measured viewport", decor.width > 0 && decor.height > 0)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val sampledColors = mutableSetOf<Int>()
                for (y in 0 until height step 13) {
                    for (x in 0 until width step 13) sampledColors += bitmap.getPixel(x, y)
                }
                assertTrue("The native draw must contain content, not a blank surface", sampledColors.size > 8)
                System.getProperty("cockpit.screenshot.dir")?.takeIf { it.isNotBlank() }?.let { path ->
                    val directory = File(path)
                    check(directory.isDirectory || directory.mkdirs()) { "Cannot create screenshot output directory" }
                    File(directory, filename).outputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Cannot encode screenshot" }
                    }
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
