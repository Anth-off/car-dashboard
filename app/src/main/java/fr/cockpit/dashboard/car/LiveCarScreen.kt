package fr.cockpit.dashboard.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Observe data only while visible and coalesce host refreshes to at most once per second. */
internal abstract class LiveCarScreen(carContext: CarContext) : Screen(carContext) {
    private val handler = Handler(Looper.getMainLooper())
    private var collectingScope: CoroutineScope? = null
    private var refreshPending = false
    private val refresh = Runnable {
        refreshPending = false
        if (collectingScope != null) invalidate()
    }

    protected fun observe(vararg states: StateFlow<*>) {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                collectingScope?.cancel()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                collectingScope = scope
                states.forEach { state ->
                    scope.launch {
                        state.collect { requestRefresh() }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) = stopObserving()

            override fun onDestroy(owner: LifecycleOwner) = stopObserving()
        })
    }

    protected fun requestRefresh() {
        if (collectingScope == null || refreshPending) return
        refreshPending = true
        handler.postDelayed(refresh, 1_000L)
    }

    private fun stopObserving() {
        collectingScope?.cancel()
        collectingScope = null
        handler.removeCallbacks(refresh)
        refreshPending = false
    }
}
