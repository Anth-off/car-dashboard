package fr.cockpit.dashboard.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.car.app.SurfaceContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Projects the shared phone composable onto the surface supplied by Android Auto.
 *
 * A Presentation supplies the host display's real size and density to Compose and AndroidView.
 * Its only window belongs to this app's virtual display; no phone screen capture or system input
 * injection is involved. The caller retains ownership of the supplied Surface wrapper and must
 * close this bridge before releasing it.
 * See https://developer.android.com/training/cars/apps/library/draw-maps#compose-virtual-display .
 *
 * The owner must have restored its SavedStateRegistry before attaching a surface. All methods
 * are called on the main thread, as are Android Auto SurfaceCallback methods.
 */
@MainThread
internal class ProjectedDashboardSurface(
    private val context: Context,
    private val owner: SavedStateRegistryOwner,
    private val content: @Composable () -> Unit,
) : AutoCloseable {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var composeView: ComposeView? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var viewport = Rect()

    /** The tree also contains the shared RouteMapView, allowing the host map actions to find it. */
    val contentView: View?
        get() = composeView

    /** A failed or replaced host surface never leaves an orphaned presentation or composition. */
    fun attach(container: SurfaceContainer): Boolean {
        close()
        val surface = container.surface ?: return false
        if (!surface.isValid || container.width <= 0 || container.height <= 0 || container.dpi <= 0) return false
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return false

        return try {
            surfaceWidth = container.width
            surfaceHeight = container.height
            viewport = Rect(0, 0, surfaceWidth, surfaceHeight)
            val display = displayManager.createVirtualDisplay(
                "Cockpit GPS dashboard",
                surfaceWidth,
                surfaceHeight,
                container.dpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            if (display == null) {
                close()
                return false
            }
            virtualDisplay = display

            val nextPresentation = Presentation(context, display.display)
            presentation = nextPresentation
            nextPresentation.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            val nextView = ComposeView(nextPresentation.context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent(content)
            }
            composeView = nextView
            val root = FrameLayout(nextPresentation.context).apply {
                // WindowRecomposer looks up owners from the window's content root, not only
                // the ComposeView. Keep the same Screen owner at every root it can resolve.
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setBackgroundColor(Color.rgb(16, 19, 16))
                clipChildren = true
                clipToPadding = true
                addView(nextView, viewportLayoutParams(viewport))
            }
            nextPresentation.setContentView(root)
            nextPresentation.window?.decorView?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
            nextPresentation.show()
            nextPresentation.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            true
        } catch (error: RuntimeException) {
            // Android Auto may destroy or replace the remote display while it is being attached.
            Log.w("CockpitProjection", "Could not attach the Android Auto dashboard surface", error)
            close()
            false
        }
    }

    /** Keeps every dashboard card inside the host's safe area, without changing scale or density. */
    fun updateViewport(area: Rect) {
        val bounded = projectedDashboardViewport(surfaceWidth, surfaceHeight, area)
        if (viewport == bounded) return
        viewport = bounded
        composeView?.apply {
            visibility = if (bounded.isEmpty) View.INVISIBLE else View.VISIBLE
            layoutParams = viewportLayoutParams(bounded)
        }
    }

    /**
     * Android Auto API 5+ reports surface taps, rather than delivering a View MotionEvent.
     * Translate a tap to the shared view tree so Compose handles bounds, disabled controls and
     * click semantics itself. Coordinates outside the safe area cannot activate a hidden control.
     */
    fun click(x: Float, y: Float): Boolean {
        val view = composeView ?: return false
        if (!x.isFinite() || !y.isFinite() || viewport.isEmpty ||
            x < viewport.left || x >= viewport.right || y < viewport.top || y >= viewport.bottom ||
            view.visibility != View.VISIBLE || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return false

        val downTime = SystemClock.uptimeMillis()
        fun dispatch(action: Int, eventTime: Long): Boolean {
            val event = MotionEvent.obtain(downTime, eventTime, action,
                x - viewport.left, y - viewport.top, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            return try {
                view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        val downHandled = dispatch(MotionEvent.ACTION_DOWN, downTime)
        val upHandled = dispatch(MotionEvent.ACTION_UP, downTime + 1L)
        return downHandled || upHandled
    }

    override fun close() {
        val oldView = composeView
        val oldPresentation = presentation
        val oldDisplay = virtualDisplay
        composeView = null
        presentation = null
        virtualDisplay = null
        viewport = Rect()
        surfaceWidth = 0
        surfaceHeight = 0
        // Disposal detaches map resources and coroutine effects before the display disappears.
        runCatching { oldView?.disposeComposition() }
        runCatching { oldPresentation?.dismiss() }
        runCatching { oldDisplay?.release() }
    }

    private fun viewportLayoutParams(area: Rect) = FrameLayout.LayoutParams(
        area.width(), area.height(), Gravity.TOP or Gravity.LEFT,
    ).apply {
        leftMargin = area.left
        topMargin = area.top
    }
}

/** Intersects host pixel coordinates without mutating the Rect owned by the host. */
internal fun projectedDashboardViewport(width: Int, height: Int, area: Rect): Rect {
    val bounded = Rect(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
    if (bounded.isEmpty || area.isEmpty || !bounded.intersect(area)) return Rect()
    return bounded
}
