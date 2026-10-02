package fr.cockpit.dashboard.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import fr.cockpit.dashboard.MainActivity
import java.time.Duration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityPermissionTest {
    @Test fun reopeningWithGrantedPermissionsNeverAsksAgain() {
        grantLocation()
        shadowOf(application()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        repeat(2) {
            withActivity { activity ->
                assertNull(shadowOf(activity).lastRequestedPermission)
                startTracking(activity)
                assertNull(shadowOf(activity).lastRequestedPermission)
            }
        }
    }

    @Test fun notificationQuestionIsNotRepeatedOnAnotherTripOrReopening() {
        grantLocation()
        withActivity { activity ->
            startTracking(activity)
            assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                shadowOf(activity).lastRequestedPermission.requestedPermissions)
        }
        // No grant follows the request: reopening and starting another trip must respect that.
        withActivity { activity ->
            startTracking(activity)
            startTracking(activity)
            assertNull(shadowOf(activity).lastRequestedPermission)
        }
    }

    @Test fun missingLocationIsStillRequestedOnlyAfterAnExplicitStart() {
        withActivity { activity ->
            assertNull(shadowOf(activity).lastRequestedPermission)
            startTracking(activity)
            assertArrayEquals(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                shadowOf(activity).lastRequestedPermission.requestedPermissions)
        }
    }

    private fun application(): Application = ApplicationProvider.getApplicationContext()

    private fun grantLocation() = shadowOf(application()).grantPermissions(
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
    )

    private fun startTracking(activity: MainActivity) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "requestTracking")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun withActivity(test: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { test(controller.get()) } finally { controller.pause().stop().destroy() }
    }
}
