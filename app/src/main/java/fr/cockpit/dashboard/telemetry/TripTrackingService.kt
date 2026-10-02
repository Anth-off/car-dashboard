package fr.cockpit.dashboard.telemetry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.navigation.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** User-started GPS recorder. Never restarted automatically after process death or device boot. */
class TripTrackingService : Service(), LocationListener {
    private lateinit var repository: TripRepository
    private lateinit var locationManager: LocationManager
    private var sampling = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observingNavigation = false
    private var lastNotificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        repository = TripRepository.get(this)
        locationManager = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NavigationRepository.get(this).stop()
            repository.onTrackingStopped()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (sampling) return START_NOT_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            repository.onTrackingError("La localisation précise est nécessaire pour enregistrer un trajet.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startRecordingNotification()
            if (!locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                repository.onTrackingError("Aucun récepteur GPS disponible sur cet appareil.")
                stopSelf()
                return START_NOT_STICKY
            }
            repository.onTrackingStarted()
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            sampling = true
            observeNavigation()
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) repository.onProviderUnavailable()
        } catch (_: SecurityException) {
            repository.onTrackingError("Accès GPS refusé. Autorise la localisation précise depuis le téléphone.")
            stopSelf()
        } catch (_: RuntimeException) {
            repository.onTrackingError("Le GPS n’a pas pu démarrer. Relance l’enregistrement depuis l’application.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecordingNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Enregistrement des trajets", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, buildNotification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun buildNotification(navigation: NavigationState?): android.app.Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TripTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val navigating = navigation?.active == true
        val instruction = if (navigating) {
            val meters = navigation?.distanceToTurnMeters
            val distance = meters?.let { if (it >= 1000) String.format(java.util.Locale.FRANCE, "%.1f km", it / 1000) else "${(it / 10).toInt() * 10} m" }
            listOfNotNull(distance, navigation?.instruction).joinToString(" · ")
        } else "GPS actif · distances estimées enregistrées sur ce téléphone"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (navigating) "Cockpit · ${navigation?.destination?.name ?: "Navigation"}" else "Cockpit · trajet en cours")
            .setContentText(instruction)
            .setStyle(NotificationCompat.BigTextStyle().bigText(instruction))
            .setCategory(if (navigating) NotificationCompat.CATEGORY_NAVIGATION else NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Arrêter", stop)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            notification.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return notification.build()
    }

    private fun observeNavigation() {
        if (observingNavigation) return
        observingNavigation = true
        scope.launch {
            NavigationRepository.get(this@TripTrackingService).state.collect { state ->
                val key = "${state.active}:${state.destination?.name}:${state.instruction}:${state.distanceToTurnMeters?.div(10)?.toInt()}"
                if (lastNotificationText != key) {
                    lastNotificationText = key
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onLocationChanged(location: Location) = repository.accept(location)
    override fun onProviderDisabled(provider: String) = repository.onProviderUnavailable()
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Required by Android versions before API 30")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        scope.cancel()
        sampling = false
        repository.onTrackingStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "fr.cockpit.dashboard.START_TRIP"
        const val ACTION_STOP = "fr.cockpit.dashboard.STOP_TRIP"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 1001
    }
}
