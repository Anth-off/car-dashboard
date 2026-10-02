package fr.cockpit.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun Dashboard(
    data: DashboardData, onTracking: () -> Unit, onReset: () -> Unit,
    onSettings: () -> Unit, onDestinations: () -> Unit, onStopNavigation: () -> Unit, onReroute: () -> Unit,
    onMusic: () -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onVoiceEnabled: (Boolean) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        val wide = maxWidth >= 740.dp
        val compactLandscape = wide && maxHeight < 550.dp
        val mapHeight = if (compactLandscape) (maxHeight - 28.dp).coerceAtLeast(220.dp)
            else if (wide) (maxHeight - 100.dp).coerceAtLeast(460.dp) else 540.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!compactLandscape) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Lime), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Route, null, tint = Ink, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("COCKPIT", color = Mist, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 3.sp)
                    Text("Le plaisir de prendre la route.", color = Muted, fontSize = 11.sp)
                }
                IconButton(onClick = onSettings, modifier = Modifier.background(Surface, CircleShape)) {
                    Icon(Icons.Rounded.Tune, "Réglages et autorisations", tint = Mist)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    NavigationPanel(data, onDestinations, onStopNavigation, onReroute, onTracking, onVoiceEnabled,
                        Modifier.weight(.68f).height(mapHeight))
                    Column(Modifier.weight(.32f).then(if (compactLandscape)
                        Modifier.height(mapHeight).verticalScroll(rememberScrollState()) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (compactLandscape) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("COCKPIT", Modifier.weight(1f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 14.sp)
                            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Tune, "Réglages et autorisations") }
                        }
                        DriveSummary(data, onReset, onTracking)
                        MusicCard(data, onMusic, onPlayPause, onPrevious, onNext)
                        CompactWeather(data, onSettings)
                    }
                }
            } else {
                NavigationPanel(data, onDestinations, onStopNavigation, onReroute, onTracking, onVoiceEnabled,
                    Modifier.fillMaxWidth().height(mapHeight))
                DriveSummary(data, onReset, onTracking)
                MusicCard(data, onMusic, onPlayPause, onPrevious, onNext)
                CompactWeather(data, onSettings)
            }
        }
    }
}

@Composable
private fun DriveSummary(data: DashboardData, onReset: () -> Unit, onTracking: () -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("MON TRAJET", Icons.Rounded.Speed)
            Text(if (data.recording && data.speedKmh != null) "GPS ACTIF" else if (data.recording) "SIGNAL GPS…" else "EN PAUSE",
                color = if (data.recording && data.speedKmh != null) Lime else Muted, fontSize = 9.sp)
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 10.dp)) {
            Text(data.speedKmh?.let { "%.0f".format(Locale.FRANCE, it) } ?: "—", fontSize = 64.sp,
                fontWeight = FontWeight.Light, letterSpacing = (-3).sp)
            Text("km/h", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp, bottom = 13.dp))
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(onClick = onTracking, modifier = Modifier.padding(bottom = 10.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (data.recording) SurfaceRaised else Lime,
                    contentColor = if (data.recording) Mist else Ink)) {
                Icon(if (data.recording) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    if (data.recording) "Arrêter le suivi GPS" else "Démarrer le suivi GPS")
            }
        }
        HorizontalDivider(color = Muted.copy(alpha = .16f))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Eyebrow("TOTAL GPS")
                Spacer(Modifier.height(6.dp))
                Distance(data.totalMeters)
            }
            Column(Modifier.weight(1f)) {
                Eyebrow("TRAJET")
                Spacer(Modifier.height(6.dp))
                Distance(data.tripMeters)
            }
        }
        TextButton(onClick = onReset, enabled = !data.recording, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Rounded.RestartAlt, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (data.recording) "Réinitialisation à l’arrêt du suivi" else "Réinitialiser le trajet", fontSize = 11.sp)
        }
    }
}

@Composable
private fun CompactWeather(data: DashboardData, onSettings: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.WbCloudy, null, tint = Lime, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("EXTÉRIEUR")
                Text(data.weatherSummary.ifBlank { if (data.weatherEnabled) "En attente du GPS" else "Météo désactivée" },
                    color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(data.temperatureC?.let { "%.0f°".format(Locale.FRANCE, it) } ?: "—°", fontSize = 30.sp)
        }
        if (!data.weatherEnabled) TextButton(onClick = onSettings, contentPadding = PaddingValues(0.dp)) {
            Text("Activer la météo", fontSize = 11.sp)
        } else data.weatherUpdatedAt?.let {
            val minutes = ((System.currentTimeMillis() - it).coerceAtLeast(0) / 60_000)
            Text(if (minutes < 1) "Actualisée à l’instant" else "Actualisée il y a $minutes min", color = Muted,
                fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Surface)
        .border(1.dp, Color(0xFF2C342B), RoundedCornerShape(26.dp)).padding(22.dp), content = content)
}

@Composable
private fun Eyebrow(text: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = Muted)
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = Muted)
    }
}

@Composable
private fun Distance(meters: Double) {
    val fmt = remember { NumberFormat.getNumberInstance(Locale.FRANCE).apply { maximumFractionDigits = 1; minimumFractionDigits = 1 } }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(fmt.format(meters / 1000), fontSize = 24.sp, color = Mist, fontWeight = FontWeight.Medium, maxLines = 1)
        Text("km", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun MusicCard(data: DashboardData, onMusic: () -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("APPLE MUSIC", Icons.Rounded.MusicNote)
            IconButton(onClick = onMusic, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.OpenInNew, "Ouvrir Apple Music", Modifier.size(17.dp), tint = Muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(
                Brush.linearGradient(listOf(Color(0xFF526444), Color(0xFF2B3628)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Lime, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(if (data.musicConnected) data.musicTitle.ifBlank { "Apple Music" } else "Votre bande-son",
                    color = Mist, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (data.musicConnected) data.musicArtist.ifBlank { "Session Apple Music" } else "Lancez un morceau dans Apple Music",
                    color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = data.canPrevious, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipPrevious, "Morceau précédent", modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(18.dp))
            FilledIconButton(onClick = onPlayPause, enabled = data.canPlayPause, modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Lime, contentColor = Ink)) {
                Icon(if (data.musicPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (data.musicPlaying) "Mettre en pause" else "Lire", modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(18.dp))
            IconButton(onClick = onNext, enabled = data.canNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipNext, "Morceau suivant", modifier = Modifier.size(28.dp))
            }
        }
    }
}
