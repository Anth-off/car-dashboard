package fr.cockpit.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import fr.cockpit.dashboard.destinations.Destination
import fr.cockpit.dashboard.destinations.DestinationRepository
import fr.cockpit.dashboard.integrations.MusicRepository
import fr.cockpit.dashboard.integrations.WeatherRepository
import fr.cockpit.dashboard.navigation.NavigationRepository
import fr.cockpit.dashboard.telemetry.TripRepository
import fr.cockpit.dashboard.ui.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val trips by lazy { TripRepository.get(this) }
    private val music by lazy { MusicRepository.get(this) }
    private val weather by lazy { WeatherRepository.get(this) }
    private val navigation by lazy { NavigationRepository.get(this) }
    private val places by lazy { DestinationRepository.get(this) }
    private var pendingDestination: Destination? = null
    private var externalDestination by mutableStateOf<Destination?>(null)
    private var musicAccess by mutableStateOf(false)

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasPreciseLocation()) {
            beginTrackingAndPendingNavigation()
        } else {
            pendingDestination = null
            message("Autorisez la localisation précise pour mesurer la vitesse et les distances.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CockpitTheme {
                val trip by trips.state.collectAsState()
                val audio by music.state.collectAsState()
                val conditions by weather.state.collectAsState()
                val destinations by places.state.collectAsState()
                val route by navigation.state.collectAsState()
                var settingsOpen by remember { mutableStateOf(false) }
                var destinationsOpen by remember { mutableStateOf(false) }
                var resetOpen by remember { mutableStateOf(false) }
                var navigationConsent by remember { mutableStateOf<Destination?>(null) }

                fun selectDestination(destination: Destination) {
                    if (getSharedPreferences("navigation_preferences", MODE_PRIVATE).getBoolean("provider_acknowledged", false)) {
                        startNavigation(destination)
                    } else navigationConsent = destination
                }

                LaunchedEffect(trip.error) { trip.error?.let(::message) }
                Dashboard(
                    data = DashboardData(
                        speedKmh = trip.speedKmh, totalMeters = trip.totalMeters,
                        tripMeters = trip.tripMeters, recording = trip.recording,
                        temperatureC = conditions.temperatureC,
                        weatherSummary = conditions.error ?: conditions.summary ?: if (conditions.loading) "Actualisation…" else "",
                        weatherEnabled = conditions.enabled, weatherUpdatedAt = conditions.updatedAtMillis,
                        musicTitle = audio.title, musicArtist = audio.artist,
                        musicConnected = audio.connected, musicPlaying = audio.playing,
                        canPlayPause = audio.canPlayPause, canPrevious = audio.canPrevious, canNext = audio.canNext,
                        destinations = destinations, latitude = trip.latitude, longitude = trip.longitude,
                        navigation = route, bearing = trip.bearingDegrees, accuracyMeters = trip.accuracyMeters,
                    ),
                    onTracking = {
                        if (trip.recording) {
                            navigation.stop()
                            trips.stopTracking(this)
                        } else requestTracking()
                    },
                    onReset = { resetOpen = true },
                    onSettings = { settingsOpen = true },
                    onDestinations = { destinationsOpen = true },
                    onStopNavigation = { navigation.stop() },
                    onReroute = { navigation.reroute() },
                    onVoiceEnabled = navigation::setVoiceEnabled,
                    onMusic = {
                        if (!music.hasAccess()) settingsOpen = true
                        else if (!music.launchAppleMusic()) message("Apple Music n’est pas installé sur ce téléphone.")
                    },
                    onPlayPause = { if (!music.togglePlayback()) message("Lancez d’abord un morceau dans Apple Music.") },
                    onPrevious = { music.previous() }, onNext = { music.next() },
                )
                if (settingsOpen) SettingsDialog(
                    weather = conditions, musicAccess = musicAccess, voiceEnabled = route.voiceEnabled,
                    onWeatherEnabled = weather::setEnabled,
                    onMusicAccess = {
                        runCatching { startActivity(music.requestAccessIntent()) }
                            .onFailure { message("Les réglages d’accès aux notifications sont indisponibles.") }
                    },
                    onVoiceEnabled = navigation::setVoiceEnabled,
                    onDismiss = { settingsOpen = false },
                )
                if (destinationsOpen) DestinationsDialog(
                    places, destinations,
                    onNavigate = { destinationsOpen = false; selectDestination(it) },
                    onDismiss = { destinationsOpen = false },
                )
                if (resetOpen) AlertDialog(
                    onDismissRequest = { resetOpen = false },
                    title = { Text("Remettre le trajet à zéro ?") },
                    text = { Text("La distance totale enregistrée sera conservée.") },
                    confirmButton = { TextButton(onClick = {
                        if (!trips.state.value.recording) trips.resetTrip()
                        resetOpen = false
                    }) { Text("Réinitialiser") } },
                    dismissButton = { TextButton(onClick = { resetOpen = false }) { Text("Annuler") } },
                )
                navigationConsent?.let { destination ->
                    AlertDialog(
                        onDismissRequest = { navigationConsent = null },
                        title = { Text("Navigation en ligne") },
                        text = { Text("La carte utilise OpenStreetMap. Le calcul d’itinéraire envoie votre position et la destination au serveur public de démonstration OSRM. Cette première version n’intègre ni trafic en direct ni cartes hors ligne.") },
                        confirmButton = { TextButton(onClick = {
                            getSharedPreferences("navigation_preferences", MODE_PRIVATE).edit()
                                .putBoolean("provider_acknowledged", true).apply()
                            navigationConsent = null
                            startNavigation(destination)
                        }) { Text("Démarrer") } },
                        dismissButton = { TextButton(onClick = { navigationConsent = null }) { Text("Annuler") } },
                    )
                }
                externalDestination?.let { destination ->
                    AlertDialog(onDismissRequest = { externalDestination = null },
                        title = { Text("Ouvrir cette destination ?") }, text = { Text(destination.name) },
                        confirmButton = { TextButton(onClick = { externalDestination = null; selectDestination(destination) }) { Text("Naviguer") } },
                        dismissButton = { TextButton(onClick = { externalDestination = null }) { Text("Annuler") } },
                    )
                }
            }
        }
        acceptNavigationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        music.refresh()
        musicAccess = music.hasAccess()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNavigationIntent(intent)
    }

    private fun acceptNavigationIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW || intent.data?.scheme != "geo") return
        val uri = intent.data ?: return
        // geo: URIs are opaque; getQueryParameter directly on them throws.
        val query = Uri.parse("https://local.invalid/?" + uri.encodedSchemeSpecificPart.substringAfter('?', ""))
            .getQueryParameter("q")
        val coordinates = (query ?: uri.schemeSpecificPart.substringBefore('?')).substringBefore('(').split(',')
        val lat = coordinates.getOrNull(0)?.trim()?.toDoubleOrNull()
        val lon = coordinates.getOrNull(1)?.trim()?.toDoubleOrNull()
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            message("Choisissez cette adresse dans la recherche de destinations.")
            return
        }
        val name = query?.substringAfter('(', "")?.substringBeforeLast(')')?.takeIf { it.isNotBlank() } ?: "Destination partagée"
        externalDestination = Destination(UUID.randomUUID().toString(), name, lat, lon)
    }

    private fun startNavigation(destination: Destination) {
        pendingDestination = destination
        requestTracking()
    }

    private fun requestTracking() {
        if (hasPreciseLocation()) beginTrackingAndPendingNavigation()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun beginTrackingAndPendingNavigation() {
        trips.startTracking(this)
        pendingDestination?.let { navigation.start(it) }
        pendingDestination = null
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasPreciseLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
