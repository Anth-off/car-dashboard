package fr.cockpit.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.cockpit.dashboard.navigation.NavigationState
import fr.cockpit.dashboard.destinations.Destination
import java.text.NumberFormat
import java.util.Locale

val Ink = Color(0xFF101310)
val Surface = Color(0xFF1C211D)
val SurfaceRaised = Color(0xFF252C26)
val Lime = Color(0xFFB5F36E)
val Mist = Color(0xFFF0F4EB)
val Muted = Color(0xFF9DA99B)

@Composable
fun CockpitTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink,
        surface = Surface, onSurface = Mist, secondary = Muted, outline = Color(0xFF3B453A)),
    content = { CompositionLocalProvider(LocalContentColor provides Mist, content = content) },
)

data class DashboardData(
    val speedKmh: Float?, val totalMeters: Double, val tripMeters: Double,
    val recording: Boolean, val temperatureC: Double?, val weatherSummary: String,
    val weatherEnabled: Boolean, val weatherUpdatedAt: Long?, val musicTitle: String,
    val musicArtist: String, val musicConnected: Boolean, val musicPlaying: Boolean,
    val canPlayPause: Boolean, val canPrevious: Boolean, val canNext: Boolean,
    val destinations: List<Destination>, val latitude: Double?, val longitude: Double?,
    val navigation: NavigationState,
    val bearing: Float? = null, val accuracyMeters: Float? = null,
)

/** Every dashboard control stays in one viewport; the map receives all remaining space. */
@Composable
fun Dashboard(
    data: DashboardData, onTracking: () -> Unit, onReset: () -> Unit,
    onSettings: () -> Unit, onDestinations: () -> Unit, onStopNavigation: () -> Unit, onReroute: () -> Unit,
    onMusic: () -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onVoiceEnabled: (Boolean) -> Unit = {},
    onSelectRoute: (fr.cockpit.dashboard.navigation.Route) -> Boolean = { false },
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()
        .padding(8.dp).testTag("dashboard_viewport")) {
        val viewportWidth = maxWidth
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        val largeText = LocalDensity.current.fontScale > 1.15f
        val musicHeight = if (largeText) 116.dp else 108.dp
        val metricsHeight = if (largeText) 128.dp else 116.dp
        val header: @Composable () -> Unit = { DashboardHeader(data, onTracking, onSettings) }
        val map: @Composable (Modifier) -> Unit = { modifier ->
            NavigationPanel(data, onDestinations, onStopNavigation, onReroute, onTracking,
                onVoiceEnabled, modifier.testTag("dashboard_map"), onSelectRoute)
        }
        val music: @Composable (Modifier) -> Unit = { modifier ->
            MusicCard(data, onMusic, onPlayPause, onPrevious, onNext, modifier)
        }
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                map(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(if (viewportWidth >= 1_000.dp) 320.dp else 280.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    header()
                    DriveSummary(data, onReset, Modifier.weight(1f))
                    music(Modifier.height(musicHeight))
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                header()
                map(Modifier.fillMaxWidth().weight(1f))
                DriveSummary(data, onReset, Modifier.height(metricsHeight))
                music(Modifier.height(musicHeight))
            }
        }
    }
}

@Composable
private fun DashboardHeader(data: DashboardData, onTracking: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Lime),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Route, null, tint = Ink, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("COCKPIT", fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp)
            Text(when { data.recording && data.speedKmh != null -> "GPS ACTIF"
                data.recording -> "SIGNAL GPS…"; else -> "GPS EN PAUSE" },
                color = if (data.recording && data.speedKmh != null) Lime else Muted,
                fontSize = 9.sp, maxLines = 1)
        }
        FilledTonalIconButton(onClick = onTracking, modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (data.recording) SurfaceRaised else Lime,
                contentColor = if (data.recording) Mist else Ink)) {
            Icon(if (data.recording) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                if (data.recording) "Arrêter le suivi GPS" else "Démarrer le suivi GPS")
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).background(Surface, CircleShape)) {
            Icon(Icons.Rounded.Tune, "Réglages et autorisations")
        }
    }
}

@Composable
private fun DriveSummary(data: DashboardData, onReset: () -> Unit, modifier: Modifier) {
    Panel(modifier.testTag("dashboard_metrics")) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FittedValue(data.speedKmh?.let { "%.0f".format(Locale.FRANCE, it) } ?: "—",
                    Modifier.weight(1f).testTag("dashboard_speed"), 42.sp, FontWeight.Light)
                Text("km/h", color = Muted, fontSize = 10.sp)
            }
            Column(Modifier.weight(1f).testTag("dashboard_weather"), horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.WbCloudy, null, tint = Lime, modifier = Modifier.size(20.dp))
                    Text(data.temperatureC?.let { "%.0f°".format(Locale.FRANCE, it) } ?: "—°", fontSize = 24.sp,
                        modifier = Modifier.semantics { contentDescription = "Température extérieure" })
                }
                Text(data.weatherSummary.ifBlank { if (data.weatherEnabled) "Météo en attente" else "Météo désactivée" },
                    color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = Muted.copy(alpha = .16f))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Distance(data.totalMeters, "TOTAL GPS", Modifier.weight(1f).testTag("dashboard_total"))
            Distance(data.tripMeters, "TRAJET", Modifier.weight(1f).testTag("dashboard_trip"))
            IconButton(onClick = onReset, enabled = !data.recording, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.RestartAlt,
                    if (data.recording) "Réinitialiser le trajet après l’arrêt du suivi" else "Réinitialiser le trajet",
                    tint = if (data.recording) Muted else Lime)
            }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface)
        .border(1.dp, Color(0xFF2C342B), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        content = content)
}

@Composable
private fun Distance(meters: Double, label: String, modifier: Modifier) {
    val fmt = remember { NumberFormat.getNumberInstance(Locale.FRANCE).apply {
        maximumFractionDigits = 1; minimumFractionDigits = 1
    } }
    Column(modifier) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Muted, maxLines = 1)
        FittedValue("${fmt.format(meters / 1000)} km", Modifier.fillMaxWidth(), 17.sp, FontWeight.Medium)
    }
}

/** Keeps full odometer values visible instead of ellipsizing significant digits. */
@Composable
private fun FittedValue(text: String, modifier: Modifier, fontSize: TextUnit, weight: FontWeight) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val measured = measurer.measure(AnnotatedString(text), TextStyle(fontSize = fontSize, fontWeight = weight),
            maxLines = 1, softWrap = false)
        val available = with(density) { maxWidth.toPx() }
        val scale = (available / measured.size.width.coerceAtLeast(1)).coerceAtMost(1f)
        Text(text, color = Mist, fontWeight = weight, fontSize = (fontSize.value * scale).sp,
            maxLines = 1, softWrap = false)
    }
}

@Composable
private fun MusicCard(data: DashboardData, onMusic: () -> Unit, onPlayPause: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    Panel(modifier.testTag("dashboard_music")) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (data.musicConnected) data.musicTitle.ifBlank { "Apple Music" } else "Apple Music",
                    color = Mist, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (data.musicConnected) data.musicArtist.ifBlank { "Session Apple Music" } else "Lancez votre musique",
                    color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onMusic, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.OpenInNew, "Ouvrir Apple Music", modifier = Modifier.size(20.dp), tint = Muted)
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = data.canPrevious, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipPrevious, "Morceau précédent", modifier = Modifier.size(28.dp))
            }
            FilledIconButton(onClick = onPlayPause, enabled = data.canPlayPause, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Ink)) {
                Icon(if (data.musicPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (data.musicPlaying) "Mettre en pause" else "Lire", modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = onNext, enabled = data.canNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipNext, "Morceau suivant", modifier = Modifier.size(28.dp))
            }
        }
    }
}
