package fr.cockpit.dashboard.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Looper
import android.view.View
import androidx.car.app.SurfaceContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.core.app.ApplicationProvider
import fr.cockpit.dashboard.navigation.NavigationState
import fr.cockpit.dashboard.ui.CockpitTheme
import fr.cockpit.dashboard.ui.Dashboard
import fr.cockpit.dashboard.ui.DashboardData
import fr.cockpit.dashboard.ui.DashboardProjection
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/** Exercises the actual Presentation/Compose bridge, including pixel-coordinate touch delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectedDashboardSurfaceTest {
    @Test
    fun hostViewportIsClippedWithoutMutatingHostRectangle() {
        val host = Rect(-40, 25, 900, 600)
        assertEquals(Rect(0, 25, 800, 480), projectedDashboardViewport(800, 480, host))
        assertEquals(Rect(-40, 25, 900, 600), host)
        assertTrue(projectedDashboardViewport(800, 480, Rect(900, 0, 1000, 60)).isEmpty)
        assertTrue(projectedDashboardViewport(800, 480, Rect()).isEmpty)
        assertTrue(projectedDashboardViewport(0, 480, host).isEmpty)
    }

    @Test
    fun hostTapUsesSafeAreaCoordinatesAndRespectsDisabledControls() = withSurface(800, 480) { context, owner, container ->
        var enabledClicks = 0
        var disabledClicks = 0
        val bridge = ProjectedDashboardSurface(context, owner) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(24.dp, 18.dp).size(80.dp, 48.dp)
                    .background(Color.Green).clickable { enabledClicks++ })
                Box(Modifier.offset(120.dp, 18.dp).size(80.dp, 48.dp)
                    .background(Color.Gray).clickable(enabled = false) { disabledClicks++ })
            }
        }
        try {
            attachOrFail(bridge, container)
            bridge.updateViewport(Rect(100, 40, 700, 440))
            layOut(bridge, 800, 480)
            val view = bridge.contentView!!
            assertEquals(600, view.width)
            assertEquals(400, view.height)
            assertEquals(100, view.left)
            assertEquals(40, view.top)

            assertTrue(bridge.click(160f, 82f))
            advanceFrames()
            assertEquals("Tap coordinates must be translated by the safe-area origin", 1, enabledClicks)
            bridge.click(260f, 82f)
            advanceFrames()
            assertEquals("Compose disabled controls must stay disabled", 0, disabledClicks)
            assertFalse(bridge.click(60f, 82f))
            assertFalse(bridge.click(700f, 82f))
            assertFalse(bridge.click(Float.NaN, 82f))
            assertFalse(bridge.click(160f, Float.POSITIVE_INFINITY))
            bridge.updateViewport(Rect())
            assertFalse(bridge.click(160f, 82f))
            assertEquals(1, enabledClicks)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun replacingAndClosingProjectionReleasesOldCompositionAndDisplay() = withSurface(800, 480) { context, owner, container ->
        val displays = context.getSystemService(DisplayManager::class.java)
        val previousIds = displays.displays.map { it.displayId }.toSet()
        val bridge = ProjectedDashboardSurface(context, owner) { Box(Modifier.fillMaxSize()) }
        try {
            attachOrFail(bridge, container)
            layOut(bridge, 800, 480)
            val firstView = bridge.contentView!!
            val firstDisplay = firstView.display.displayId
            assertNotNull(displays.getDisplay(firstDisplay))
            attachOrFail(bridge, container)
            layOut(bridge, 800, 480)
            assertFalse(firstView.isAttachedToWindow)
            assertNull(displays.getDisplay(firstDisplay))
            assertTrue(bridge.contentView!!.isAttachedToWindow)
            val lastView = bridge.contentView!!
            bridge.close()
            bridge.close()
            advanceFrames()
            assertNull(bridge.contentView)
            assertFalse(lastView.isAttachedToWindow)
            assertFalse(bridge.click(40f, 40f))
            assertEquals(previousIds, displays.displays.map { it.displayId }.toSet())
        } finally {
            bridge.close()
        }
    }

    @Test
    fun invalidSurfaceCannotCreateProjection() = withSurface(800, 480) { context, owner, container ->
        val bridge = ProjectedDashboardSurface(context, owner) { Box(Modifier.fillMaxSize()) }
        try {
            assertFalse(bridge.attach(SurfaceContainer(container.surface, 0, 480, 160)))
            assertFalse(bridge.attach(SurfaceContainer(container.surface, 800, 480, 0)))
            assertNull(bridge.contentView)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun projectsSharedDashboardAt1280By720() = renderDashboard(1280, 720, "cockpit-auto-dashboard.png")

    @Test
    fun projectsSharedDashboardAt1024By600() = renderDashboard(1024, 600, "cockpit-auto-dashboard-1024.png")

    @Test
    fun projectsSharedDashboardAt800By480() = renderDashboard(800, 480, "cockpit-auto-dashboard-800.png")

    @Test
    fun projectsSharedDashboardWithLargerText() = renderDashboard(800, 480, "cockpit-auto-dashboard-large-text.png", 1.3f)

    private fun renderDashboard(width: Int, height: Int, filename: String, fontScale: Float = 1f) =
        withSurface(width, height) { context, owner, container ->
            var seenDensity = 0f
            val bridge = ProjectedDashboardSurface(context, owner) {
                val density = LocalDensity.current
                seenDensity = density.density
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    CockpitTheme {
                        Dashboard(idleData(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                            projection = DashboardProjection(false, {}, {}, {}, {}))
                    }
                }
            }
            try {
                attachOrFail(bridge, container)
                layOut(bridge, width, height)
                assertEquals("The host 160 dpi density must be preserved", 1f, seenDensity, 0f)
                assertEquals(width, bridge.contentView!!.width)
                assertEquals(height, bridge.contentView!!.height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bridge.contentView!!.rootView.draw(Canvas(bitmap))
                    val colors = mutableSetOf<Int>()
                    for (y in 0 until height step 11) for (x in 0 until width step 11) colors += bitmap.getPixel(x, y)
                    assertTrue("The projected dashboard must draw actual map controls and panels", colors.size > 8)
                    System.getProperty("cockpit.screenshot.dir")?.takeIf(String::isNotBlank)?.let { path ->
                        val directory = File(path)
                        check(directory.isDirectory || directory.mkdirs())
                        File(directory, filename).outputStream().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                bridge.close()
            }
        }

    private fun layOut(bridge: ProjectedDashboardSurface, width: Int, height: Int) {
        advanceFrames()
        val root = bridge.contentView!!.rootView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        advanceFrames()
    }

    private fun advanceFrames() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

    private fun attachOrFail(bridge: ProjectedDashboardSurface, container: SurfaceContainer) {
        if (!bridge.attach(container)) {
            val cause = ShadowLog.getLogsForTag("CockpitProjection").lastOrNull()?.throwable
            throw AssertionError("The virtual display and shared dashboard must attach", cause)
        }
    }

    private fun withSurface(width: Int, height: Int, body: (Context, Owner, SurfaceContainer) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = Owner()
        // Robolectric's native SurfaceTexture has no host buffer producer. ImageReader supplies
        // a real native graphics surface, so the production isValid guard remains exercised.
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val surface = imageReader.surface
        try {
            assertTrue("ImageReader must provide a valid projected surface", surface.isValid)
            body(context, owner, SurfaceContainer(surface, width, height, 160))
        } finally {
            owner.destroy()
            imageReader.close()
        }
    }

    private class Owner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    /** Idle preview uses no injected vehicle, route, weather or music data. */
    private fun idleData() = DashboardData(
        speedKmh = null, totalMeters = 0.0, tripMeters = 0.0, recording = false,
        temperatureC = null, weatherSummary = "", weatherEnabled = false, weatherUpdatedAt = null,
        musicTitle = "", musicArtist = "", musicConnected = false, musicPlaying = false,
        canPlayPause = false, canPrevious = false, canNext = false, destinations = emptyList(),
        latitude = null, longitude = null, navigation = NavigationState(),
    )
}
