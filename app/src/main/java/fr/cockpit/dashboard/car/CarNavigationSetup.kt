package fr.cockpit.dashboard.car

import android.content.Context
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import fr.cockpit.dashboard.telemetry.TripRepository

/** Car actions never bypass the phone's provider explanation or its explicit GPS service start. */
internal fun canStartCarNavigation(context: CarContext): Boolean {
    val acknowledged = context.getSharedPreferences("navigation_preferences", Context.MODE_PRIVATE)
        .getBoolean("provider_acknowledged", false)
    val message = when {
        !acknowledged -> "À l’arrêt, préparez votre premier itinéraire sur le téléphone."
        !TripRepository.get(context).state.value.recording ->
            "À l’arrêt, démarrez le suivi GPS sur le téléphone avant le guidage."
        else -> return true
    }
    CarToast.makeText(context, message, CarToast.LENGTH_LONG).show()
    return false
}
