package fr.cockpit.dashboard.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import fr.cockpit.dashboard.navigation.NavigationState
import fr.cockpit.dashboard.map.RouteMapView
import fr.cockpit.dashboard.destinations.Destination
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

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
)

@Composable
fun Dashboard(
    data: DashboardData, onTracking: () -> Unit, onReset: () -> Unit,
    onSettings: () -> Unit, onDestinations: () -> Unit, onStopNavigation: () -> Unit, onReroute: () -> Unit,
    onMusic: () -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        val wide = maxWidth >= 740.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (wide) 30.dp else 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Lime), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Route, null, tint = Ink, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("COCKPIT", color = Mist, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = 3.sp)
                    Text("Chaque trajet, en clair.", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = onSettings, modifier = Modifier.background(Surface, CircleShape)) {
                    Icon(Icons.Rounded.Tune, "Réglages et autorisations", tint = Mist)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(0.44f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SpeedCard(data, onReset)
                        MusicCard(data, onMusic, onPlayPause, onPrevious, onNext)
                    }
                    Column(Modifier.weight(0.56f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            WeatherCard(data, onSettings, Modifier.weight(1f))
                            TrackingCard(data.recording, onTracking, Modifier.weight(1f))
                        }
                        NavigationCard(data, onDestinations, onStopNavigation, onReroute)
                    }
                }
            } else {
                SpeedCard(data, onReset)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WeatherCard(data, onSettings, Modifier.weight(1f))
                    TrackingCard(data.recording, onTracking, Modifier.weight(1f))
                }
                NavigationCard(data, onDestinations, onStopNavigation, onReroute)
                MusicCard(data, onMusic, onPlayPause, onPrevious, onNext)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SatelliteAlt, null, tint = Muted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(8.dp))
                Text("DISTANCES GPS  ·  MÉTÉO EXTÉRIEURE", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
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
private fun SpeedCard(data: DashboardData, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("VITESSE", Icons.Rounded.Speed)
            val fix = data.recording && data.speedKmh != null
            Row(Modifier.clip(CircleShape).background(if (fix) Lime.copy(alpha = .1f) else SurfaceRaised)
                .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(if (fix) Lime else Muted, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(if (fix) "GPS ACTIF" else if (data.recording) "RECHERCHE GPS" else "EN PAUSE",
                    fontSize = 9.sp, color = if (fix) Lime else Muted, letterSpacing = .6.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(255.dp)) {
                val inset = 18.dp.toPx()
                val diameter = size.minDimension - 2 * inset
                val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                drawArc(Color(0xFF333D31), 140f, 260f, false, origin, Size(diameter, diameter), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                if (data.speedKmh != null) drawArc(Lime, 140f, (data.speedKmh / 180f).coerceIn(0f, 1f) * 260f,
                    false, origin, Size(diameter, diameter), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                val radius = diameter / 2 - 17.dp.toPx()
                for (tick in 0..36) {
                    val angle = Math.toRadians((140 + tick * 260.0 / 36))
                    val length = if (tick % 6 == 0) 9.dp.toPx() else 4.dp.toPx()
                    val start = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
                    val end = Offset(center.x + cos(angle).toFloat() * (radius - length), center.y + sin(angle).toFloat() * (radius - length))
                    drawLine(if (tick % 6 == 0) Muted else Color(0xFF45513F), start, end, 1.dp.toPx(), StrokeCap.Round)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = 5.dp)) {
                Text(data.speedKmh?.let { "%.0f".format(Locale.FRANCE, it) } ?: "—", color = Mist,
                    fontSize = 76.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif, letterSpacing = (-3).sp)
                Text("km/h", color = Muted, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                Text("Vitesse mesurée par GPS", color = Muted, fontSize = 10.sp)
            }
        }
        HorizontalDivider(color = Color(0xFF343D32))
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Eyebrow("TOTAL ENREGISTRÉ")
                Spacer(Modifier.height(8.dp))
                Distance(data.totalMeters)
                Text("Depuis l’installation", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Box(Modifier.width(1.dp).height(68.dp).background(Color(0xFF343D32)))
            Column(Modifier.weight(1f)) {
                Eyebrow("TRAJET")
                Spacer(Modifier.height(8.dp))
                Distance(data.tripMeters)
                TextButton(onClick = onReset, enabled = !data.recording,
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Réinitialiser", fontSize = 11.sp)
                }
            }
        }
        if (data.recording) Text("Arrêtez le suivi pour réinitialiser le trajet.", color = Muted, fontSize = 10.sp,
            modifier = Modifier.padding(top = 10.dp))
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
private fun WeatherCard(data: DashboardData, onSettings: () -> Unit, modifier: Modifier) {
    Panel(modifier) {
        Eyebrow("EXTÉRIEUR", Icons.Rounded.WbCloudy)
        Spacer(Modifier.height(12.dp))
        Text(data.temperatureC?.let { "%.0f°".format(Locale.FRANCE, it) } ?: "—°", fontSize = 35.sp, color = Mist, fontWeight = FontWeight.Light)
        Text(data.weatherSummary.ifBlank { if (data.weatherEnabled) "En attente du GPS" else "Météo désactivée" },
            color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!data.weatherEnabled) TextButton(onClick = onSettings, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(28.dp)) {
            Text("Activer", fontSize = 11.sp)
        } else {
            val label = data.weatherUpdatedAt?.let {
                val minutes = ((System.currentTimeMillis() - it).coerceAtLeast(0) / 60_000)
                if (minutes < 1) "À l’instant" else "Il y a $minutes min"
            } ?: "Open-Meteo"
            Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun TrackingCard(recording: Boolean, onTracking: () -> Unit, modifier: Modifier) {
    Panel(modifier) {
        Eyebrow("MON TRAJET", Icons.Rounded.Route)
        Spacer(Modifier.height(14.dp))
        Text(if (recording) "En route." else "On y va ?", color = Mist, fontWeight = FontWeight.Medium, fontSize = 19.sp)
        Spacer(Modifier.height(4.dp))
        Text(if (recording) "Distance enregistrée" else "Démarrer le suivi GPS", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onTracking, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (recording) SurfaceRaised else Lime,
                contentColor = if (recording) Mist else Ink), modifier = Modifier.height(38.dp)) {
            Icon(if (recording) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (recording) "Arrêter" else "Démarrer", fontSize = 12.sp)
        }
    }
}

@Composable
private fun NavigationCard(data: DashboardData, onManage: () -> Unit, onStop: () -> Unit, onReroute: () -> Unit) {
    val nav = data.navigation
    val turnDistance = nav.distanceToTurnMeters?.let {
        if (it >= 1000) "%.1f km".format(Locale.FRANCE, it / 1000)
        else "${(it / 10).toInt() * 10} m"
    }
    val instruction = if (nav.active && turnDistance != null) "Dans $turnDistance · ${nav.instruction}" else nav.instruction
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("NAVIGATION", Icons.Rounded.NearMe)
            TextButton(onClick = onManage, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(26.dp)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(14.dp))
                Text("Destination", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (nav.loading) "Calcul de l’itinéraire…" else if (nav.active || nav.arrived) instruction else "La route vous attend.",
            color = Mist, fontSize = 20.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(nav.destination?.name ?: "Votre carte et votre guidage, au même endroit.", color = Muted,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(14.dp))
        AndroidView(
            factory = { context -> RouteMapView(context) },
            modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)),
            update = { view -> view.updateState(data.latitude, data.longitude, nav.route?.points.orEmpty()) },
        )
        nav.error?.let { Text(it, color = Color(0xFFF3C786), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
        if (nav.active) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(nav.remainingMeters?.let { "%.1f km".format(Locale.FRANCE, it / 1000) } ?: "GPS en attente", color = Lime, fontSize = 17.sp)
                    Text(nav.remainingSeconds?.let { "${(it / 60).coerceAtLeast(1)} min · estimation hors trafic" } ?: "Guidage en pause", color = Muted, fontSize = 10.sp)
                }
                if (nav.offRoute) TextButton(onClick = onReroute) { Text("Recalculer", fontSize = 12.sp) }
                IconButton(onClick = onStop) { Icon(Icons.Rounded.Close, "Arrêter le guidage") }
            }
        } else if (!nav.loading) {
            val retry = nav.error != null && nav.destination != null
            Button(onClick = if (retry) onReroute else onManage, modifier = Modifier.padding(top = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceRaised, contentColor = Mist)) {
                Icon(Icons.Rounded.Navigation, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (retry) "Réessayer le calcul" else "Choisir une destination", fontSize = 12.sp)
            }
        }
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
