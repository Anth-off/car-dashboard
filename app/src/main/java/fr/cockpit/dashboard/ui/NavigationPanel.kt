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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.cockpit.dashboard.map.RouteMapView
import fr.cockpit.dashboard.navigation.NavigationState
import fr.cockpit.dashboard.navigation.RouteStep
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Map-first navigation; a fullscreen view keeps the same actions and real guidance state. */
@Composable
fun NavigationPanel(
    data: DashboardData, onSearch: () -> Unit, onStop: () -> Unit, onReroute: () -> Unit,
    onTracking: () -> Unit, onVoiceEnabled: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var directions by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, data.navigation.active) {
        val previous = view.keepScreenOn
        if (data.navigation.active) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    NavigationSurface(data, onSearch, onStop, onReroute, onTracking, onVoiceEnabled,
        onExpand = { fullscreen = true }, onDirections = { directions = true }, expanded = false,
        modifier = modifier)
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        NavigationSurface(data, onSearch = { fullscreen = false; onSearch() }, onStop, onReroute,
            onTracking, onVoiceEnabled, onExpand = { fullscreen = false },
            onDirections = { directions = true }, expanded = true,
            modifier = Modifier.fillMaxSize().background(Ink).safeDrawingPadding())
    }
    if (directions) DirectionsDialog(data.navigation) { directions = false }
}

@Composable
private fun NavigationSurface(
    data: DashboardData, onSearch: () -> Unit, onStop: () -> Unit, onReroute: () -> Unit,
    onTracking: () -> Unit, onVoiceEnabled: (Boolean) -> Unit, onExpand: () -> Unit,
    onDirections: () -> Unit, expanded: Boolean, modifier: Modifier,
) {
    val nav = data.navigation
    var map by remember { mutableStateOf<RouteMapView?>(null) }
    var following by remember { mutableStateOf(true) }
    var overview by remember { mutableStateOf(false) }
    var headingUp by remember { mutableStateOf(false) }
    var night by rememberSaveable { mutableStateOf(true) }
    val hasPosition = data.latitude != null && data.longitude != null
    BoxWithConstraints(modifier) {
    val compact = maxHeight < 450.dp
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(if (expanded) 0.dp else 26.dp))
        .background(Surface).border(1.dp, Color(0xFF344032), RoundedCornerShape(if (expanded) 0.dp else 26.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = if (compact) 0.dp else 6.dp, bottom = if (compact) 0.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Explore, null, tint = Lime, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(nav.destination?.name ?: "Où va-t-on ?", fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact) Text(if (nav.active) "GUIDAGE EN COURS" else "VOTRE CARTE, VOTRE ROUTE", color = Muted,
                    fontSize = 9.sp, letterSpacing = 1.sp)
            }
            IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Rechercher une destination") }
            IconButton(onClick = onExpand) {
                Icon(if (expanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    if (expanded) "Quitter le plein écran" else "Carte en plein écran")
            }
        }
        if (nav.active || nav.loading || nav.arrived) ManeuverBanner(nav, onVoiceEnabled, compact)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { context ->
                RouteMapView(context).also { created ->
                    created.setNightMode(night)
                    created.onMapStateChanged = {
                        following = it.following; overview = it.overview; headingUp = it.headingUp
                    }
                    map = created
                }
            }, modifier = Modifier.fillMaxSize(), update = { view ->
                val point = if (nav.isSimulation) nav.currentPosition else null
                view.updateState(point?.latitude ?: data.latitude, point?.longitude ?: data.longitude,
                    nav.route?.points.orEmpty(), data.bearing,
                    data.accuracyMeters.takeIf { data.speedKmh != null },
                    nav.destination?.let { fr.cockpit.dashboard.navigation.GeoPoint(it.latitude, it.longitude) },
                    nav.progressFraction.toFloat())
                view.setNightMode(night)
            })
            Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapButton(if (night) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    if (night) "Passer la carte en mode jour" else "Passer la carte en mode nuit",
                    onClick = { night = !night })
                MapButton(Icons.Rounded.Explore,
                    if (headingUp) "Orienter la carte vers le nord" else "Orienter la carte dans le sens de la marche",
                    active = headingUp, onClick = { map?.toggleOrientation() })
            }
            val mapControls: @Composable () -> Unit = {
                MapButton(Icons.Rounded.Add, "Zoom avant", onClick = { map?.zoomIn() })
                MapButton(Icons.Rounded.Remove, "Zoom arrière", onClick = { map?.zoomOut() })
                MapButton(Icons.Rounded.MyLocation, "Recentrer sur ma position", active = following && !overview,
                    enabled = hasPosition, onClick = { map?.recenter() })
                if (nav.route != null) MapButton(Icons.Rounded.Route, "Afficher tout l’itinéraire",
                    active = overview, onClick = { map?.showRouteOverview() })
            }
            if (compact) Row(Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) { mapControls() }
            else Column(Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) { mapControls() }
            if (hasPosition) Surface(color = Ink.copy(alpha = .9f), shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 36.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(if (data.speedKmh != null) Lime else Muted, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(when { nav.isSimulation -> "Simulation"; !data.recording || data.speedKmh == null -> "Dernière position connue"
                        overview -> "Vue d’ensemble"; !following -> "Exploration libre"; headingUp -> "Sens de la marche"; else -> "Suivi GPS · nord en haut" },
                        color = Mist, fontSize = 10.sp)
                }
            }
        }
        nav.error?.let { Text(it, color = Color(0xFFF3C786), fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF322B20)).padding(horizontal = 16.dp, vertical = 8.dp)) }
        if (nav.active || nav.arrived) {
            NavigationFooter(nav, onStop, onReroute, onDirections, compact)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (nav.loading) "Préparation du trajet…" else "Votre prochaine escapade", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(if (data.recording) "Glissez et zoomez pour explorer" else "Activez le GPS pour vous situer", color = Muted, fontSize = 10.sp)
                }
                if (!data.recording) TextButton(onClick = onTracking) { Text("Activer GPS", fontSize = 12.sp) }
                if (nav.error != null && nav.destination != null) TextButton(onClick = onReroute) { Text("Réessayer", fontSize = 12.sp) }
                else FilledIconButton(onClick = onSearch) { Icon(Icons.Rounded.NearMe, "Choisir une destination") }
            }
        }
    }
    }
}

@Composable
private fun MapButton(icon: ImageVector, description: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp), shape = RoundedCornerShape(14.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (active) Lime else Ink.copy(alpha = .94f),
            contentColor = if (active) Ink else Mist, disabledContainerColor = Ink.copy(alpha = .75f))) {
        Icon(icon, description, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ManeuverBanner(nav: NavigationState, onVoiceEnabled: (Boolean) -> Unit, compact: Boolean) {
    val paused = nav.gpsPaused || nav.offRoute
    Row(Modifier.fillMaxWidth().background(if (paused) Color(0xFF403820) else Lime)
        .padding(start = 16.dp, end = 6.dp, top = if (compact) 6.dp else 12.dp, bottom = if (compact) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val foreground = if (paused) Mist else Ink
        Icon(if (nav.arrived) Icons.Rounded.Flag else if (paused) Icons.Rounded.GpsOff else maneuverIcon(nav.nextStep),
            null, modifier = Modifier.size(38.dp), tint = foreground)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            if (!nav.loading && !paused && !nav.arrived) nav.distanceToTurnMeters?.let {
                Text(formatRouteDistance(it), color = foreground, fontWeight = FontWeight.Bold, fontSize = 25.sp)
            }
            Text(if (nav.loading) "Calcul de l’itinéraire…" else nav.instruction, color = foreground,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!compact && !paused && !nav.loading && !nav.arrived) nav.followingStep?.let {
                Text("Puis · ${it.instruction}", color = foreground.copy(alpha = .72f), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { onVoiceEnabled(!nav.voiceEnabled) }) {
            Icon(if (nav.voiceEnabled) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                if (nav.voiceEnabled) "Couper le guidage vocal" else "Activer le guidage vocal", tint = foreground)
        }
    }
}

@Composable
private fun NavigationFooter(nav: NavigationState, onStop: () -> Unit, onReroute: () -> Unit, onDirections: () -> Unit, compact: Boolean) {
    // Re-evaluate ETA even while stationary; the estimate excludes live traffic.
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); clock = System.currentTimeMillis() } }
    val eta = nav.remainingSeconds?.let {
        java.time.Instant.ofEpochMilli(clock).atZone(java.time.ZoneId.systemDefault()).plusSeconds(it)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    if (!nav.gpsPaused && !nav.offRoute) LinearProgressIndicator(progress = { nav.progressFraction.toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(3.dp), color = Lime, trackColor = SurfaceRaised)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = if (compact) 3.dp else 10.dp, bottom = if (compact) 3.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                Text(if (nav.arrived) "Arrivée" else nav.remainingSeconds?.let(::formatRouteDuration) ?: "— min",
                    color = Lime, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                if (!nav.arrived) Text(nav.remainingMeters?.let(::formatRouteDistance) ?: "— km", fontSize = 13.sp)
            }
            Text(if (nav.arrived) "Vous êtes à destination" else eta?.let { "Arrivée $it · hors trafic" } ?: "Estimation en attente du GPS",
                color = Muted, fontSize = 10.sp)
        }
        if (nav.offRoute && !nav.loading) IconButton(onClick = onReroute) { Icon(Icons.Rounded.Refresh, "Recalculer l’itinéraire") }
        IconButton(onClick = onDirections) { Icon(Icons.Rounded.FormatListBulleted, "Voir les étapes du trajet") }
        IconButton(onClick = onStop) { Icon(Icons.Rounded.Close, "Terminer le guidage", tint = Color(0xFFF3B3A6)) }
    }
}

@Composable
private fun DirectionsDialog(nav: NavigationState, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Surface,
        icon = { Icon(Icons.Rounded.Route, null, tint = Lime) },
        title = { Text("Votre itinéraire") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(nav.destination?.name.orEmpty(), fontWeight = FontWeight.SemiBold)
                if (nav.gpsPaused) Text("Signal GPS perdu · progression en pause", color = Muted, fontSize = 12.sp)
                nav.route?.steps?.drop(nav.nextStepIndex ?: 0)?.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(maneuverIcon(step), null, tint = if (index == 0) Lime else Muted, modifier = Modifier.size(25.dp))
                        Column(Modifier.weight(1f)) {
                            Text(step.instruction, color = if (index == 0) Lime else Mist, fontSize = 14.sp)
                            Text(if (index == 0 && nav.distanceToTurnMeters != null) "Dans ${formatRouteDistance(nav.distanceToTurnMeters)}"
                                else "Puis continuer sur ${formatRouteDistance(step.distanceMeters)}", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
}

internal fun maneuverIcon(step: RouteStep?): ImageVector = when {
    step?.maneuverType == "arrive" -> Icons.Rounded.Flag
    step?.maneuverType in listOf("roundabout", "rotary", "roundabout turn") -> Icons.Rounded.RoundaboutRight
    step?.modifier == "uturn" -> Icons.Rounded.UTurnLeft
    step?.modifier?.contains("left") == true -> Icons.Rounded.TurnLeft
    step?.modifier?.contains("right") == true -> Icons.Rounded.TurnRight
    else -> Icons.Rounded.Straight
}

internal fun formatRouteDistance(meters: Double): String = when {
    meters >= 10_000 -> "%.0f km".format(Locale.FRANCE, meters / 1000)
    meters >= 1000 -> "%.1f km".format(Locale.FRANCE, meters / 1000)
    else -> "${(kotlin.math.round(meters.coerceAtLeast(0.0) / 10) * 10).toInt()} m"
}

internal fun formatRouteDuration(seconds: Long): String {
    val minutes = ((seconds.coerceAtLeast(0) + 59) / 60).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h %02d".format(minutes % 60)
}
