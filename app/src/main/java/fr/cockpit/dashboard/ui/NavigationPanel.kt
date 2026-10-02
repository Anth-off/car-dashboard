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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.cockpit.dashboard.map.RouteMapView
import fr.cockpit.dashboard.navigation.NavigationState
import fr.cockpit.dashboard.navigation.Route
import fr.cockpit.dashboard.navigation.RouteStep
import java.time.format.DateTimeFormatter
import java.util.Locale

private val NavigationBlue = Color(0xFF45C9F5)
private val GuidancePanel = Color(0xFF112F41)

/** A fixed driving viewport: map, next maneuver and arrival information stay visible together. */
@Composable
fun NavigationPanel(
    data: DashboardData, onSearch: () -> Unit, onStop: () -> Unit, onReroute: () -> Unit,
    onTracking: () -> Unit, onVoiceEnabled: (Boolean) -> Unit, modifier: Modifier = Modifier,
    onSelectRoute: (Route) -> Boolean = { false },
    projection: DashboardProjection? = null,
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var journey by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, data.navigation.active) {
        val previous = view.keepScreenOn
        if (data.navigation.active) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    NavigationSurface(data, onSearch, onStop, onReroute, onTracking, onVoiceEnabled,
        onExpand = projection?.onToggleMap ?: { fullscreen = true },
        onJourney = projection?.onJourney ?: { journey = true },
        expanded = projection?.mapExpanded == true, modifier, projection)
    if (projection == null && fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        NavigationSurface(data, onSearch = { fullscreen = false; onSearch() }, onStop, onReroute,
            onTracking, onVoiceEnabled, onExpand = { fullscreen = false }, onJourney = { journey = true },
            expanded = true, modifier = Modifier.fillMaxSize().background(Ink).safeDrawingPadding())
    }
    if (projection == null && journey) JourneyDialog(data.navigation, onSelectRoute, onReroute) { journey = false }
}

@Composable
private fun NavigationSurface(
    data: DashboardData, onSearch: () -> Unit, onStop: () -> Unit, onReroute: () -> Unit,
    onTracking: () -> Unit, onVoiceEnabled: (Boolean) -> Unit, onExpand: () -> Unit,
    onJourney: () -> Unit, expanded: Boolean, modifier: Modifier,
    projection: DashboardProjection? = null,
) {
    val nav = data.navigation
    var map by remember { mutableStateOf<RouteMapView?>(null) }
    var following by remember { mutableStateOf(true) }
    var overview by remember { mutableStateOf(false) }
    var headingUp by remember { mutableStateOf(false) }
    var night by rememberSaveable { mutableStateOf(true) }
    var toolsOpen by remember { mutableStateOf(false) }
    val onMapReady by rememberUpdatedState(projection?.onMapReady)
    DisposableEffect(map, projection != null) {
        onMapReady?.invoke(map)
        onDispose { onMapReady?.invoke(null) }
    }
    val hasPosition = data.latitude != null && data.longitude != null
    val guidance = nav.active || nav.loading || nav.arrived
    BoxWithConstraints(modifier.clip(RoundedCornerShape(if (expanded) 0.dp else 22.dp))
        .border(1.dp, Color(0xFF34434B), RoundedCornerShape(if (expanded) 0.dp else 22.dp))) {
        val viewportHeight = maxHeight
        val compact = viewportHeight < 430.dp
        Column(Modifier.fillMaxSize()) {
            // The driving banner replaces the idle search bar rather than pushing the map down.
            if (guidance) GuidanceBanner(nav, onSearch, onExpand, expanded, compact)
            else Row(Modifier.fillMaxWidth().height(52.dp).background(GuidancePanel).padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = NavigationBlue, modifier = Modifier.size(24.dp))
                TextButton(onClick = onSearch, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Où allons-nous ?", color = Mist, maxLines = 1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Search, "Rechercher une destination", tint = Mist) }
                ExpandButton(expanded, onExpand)
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("navigation_map_surface")) {
                AndroidView(factory = { context ->
                    RouteMapView(context).also { created ->
                        created.setNightMode(night)
                        created.onMapStateChanged = { following = it.following; overview = it.overview; headingUp = it.headingUp }
                        map = created
                    }
                }, modifier = Modifier.fillMaxSize(), update = { view ->
                    val point = if (nav.isSimulation) nav.currentPosition else null
                    view.updateState(point?.latitude ?: data.latitude, point?.longitude ?: data.longitude,
                        nav.route?.points.orEmpty(), data.bearing,
                        data.accuracyMeters.takeIf { data.speedKmh != null },
                        nav.destination?.let { fr.cockpit.dashboard.navigation.GeoPoint(it.latitude, it.longitude) },
                        nav.progressFraction.toFloat(), data.speedKmh, nav.distanceToTurnMeters,
                        navigationActive = nav.active, locationFresh = !nav.gpsPaused && data.speedKmh != null)
                    if (projection == null) view.setNightMode(night)
                })
                Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MapButton(Icons.Rounded.Layers, "Options de la carte", active = toolsOpen,
                        onClick = projection?.onMapOptions ?: { toolsOpen = true })
                    if (!following || overview) MapButton(Icons.Rounded.MyLocation, "Recentrer sur ma position",
                        enabled = hasPosition, onClick = { map?.recenter() })
                }
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MapButton(Icons.Rounded.Add, "Zoom avant", onClick = { map?.zoomIn() })
                    MapButton(Icons.Rounded.Remove, "Zoom arrière", onClick = { map?.zoomOut() })
                }
                // A full-screen driving map retains the speed which is otherwise in the dashboard.
                if (expanded && viewportHeight >= 330.dp) Surface(color = Ink.copy(alpha = .94f), shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 32.dp).size(64.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(data.speedKmh?.let { "%.0f".format(Locale.FRANCE, it) } ?: "—", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("km/h GPS", fontSize = 8.sp, color = Muted)
                    }
                }
                if (nav.gpsPaused || (hasPosition && (!data.recording || data.speedKmh == null))) Surface(
                    color = Ink.copy(alpha = .94f), shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 25.dp)) {
                    Text("Dernière position · GPS en attente", color = Mist, fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), maxLines = 1)
                }
            }
            if (guidance) {
                if (!nav.gpsPaused && !nav.offRoute && !nav.loading) LinearProgressIndicator(
                    progress = { nav.progressFraction.toFloat().coerceIn(0f, 1f) }, color = NavigationBlue,
                    trackColor = GuidancePanel, modifier = Modifier.fillMaxWidth().height(2.dp))
                ArrivalBar(nav, onStop, onVoiceEnabled, onJourney)
            } else Row(Modifier.fillMaxWidth().height(52.dp).background(GuidancePanel).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (hasPosition) "Prêt à partir" else "Activez le GPS", fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(nav.error ?: "Votre route, en un coup d’œil", fontSize = 10.sp, lineHeight = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!data.recording) IconButton(onClick = onTracking, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.MyLocation, "Activer le GPS depuis la carte", tint = NavigationBlue) }
                if (nav.error != null && nav.destination != null) IconButton(onClick = onReroute, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Refresh, "Réessayer le calcul", tint = NavigationBlue)
                } else FilledIconButton(onClick = onSearch, colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = NavigationBlue, contentColor = Ink), modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.NearMe, "Choisir une destination")
                }
            }
        }
    }
    if (projection == null && toolsOpen) FixedNavigationDialog("Votre carte", onDismiss = { toolsOpen = false }, footer = {
        TextButton(onClick = { toolsOpen = false }) { Text("Terminé") }
    }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MapTool(if (night) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, if (night) "Mode jour" else "Mode nuit") { night = !night }
                MapTool(Icons.Rounded.Explore, if (headingUp) "Nord en haut" else "Sens de marche") { map?.toggleOrientation() }
                MapTool(Icons.Rounded.MyLocation, "Recentrer", hasPosition) { map?.recenter(); toolsOpen = false }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MapTool(Icons.Rounded.Route, "Vue du trajet", nav.route != null) { map?.showRouteOverview(); toolsOpen = false }
                MapTool(Icons.Rounded.AltRoute, "Itinéraires", nav.route != null) { toolsOpen = false; onJourney() }
                MapTool(Icons.Rounded.Refresh, "Recalculer", nav.destination != null && !nav.loading) { onReroute(); toolsOpen = false }
            }
        }
    }
}

@Composable
private fun GuidanceBanner(nav: NavigationState, onSearch: () -> Unit, onExpand: () -> Unit, expanded: Boolean, compact: Boolean) {
    val paused = nav.gpsPaused || nav.offRoute
    Row(Modifier.fillMaxWidth().background(if (paused) Color(0xFF493C24) else GuidancePanel)
        .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (nav.arrived) Icons.Rounded.Flag else if (paused) Icons.Rounded.GpsOff else maneuverIcon(nav.nextStep),
            null, tint = NavigationBlue, modifier = Modifier.size(if (compact) 34.dp else 42.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(when { nav.arrived -> "Vous êtes arrivé"; nav.loading -> "Calcul du trajet…"; paused -> "Guidage en pause"
                else -> nav.distanceToTurnMeters?.let(::formatRouteDistance) ?: "En route" },
                color = Mist, fontWeight = FontWeight.Bold, fontSize = if (compact) 22.sp else 28.sp, maxLines = 1)
            Text(nav.instruction, color = if (paused) Mist else NavigationBlue, fontSize = 12.sp,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (!compact && !paused && !nav.loading) nav.followingStep?.let {
                Text("Puis · ${it.instruction}", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Search, "Rechercher une destination", tint = Mist) }
        ExpandButton(expanded, onExpand)
    }
}

@Composable
private fun ExpandButton(expanded: Boolean, onExpand: () -> Unit) {
    IconButton(onClick = onExpand, modifier = Modifier.size(48.dp)) {
        Icon(if (expanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            if (expanded) "Quitter le plein écran" else "Carte en plein écran", tint = Mist)
    }
}

@Composable
private fun MapButton(icon: ImageVector, description: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(16.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (active) NavigationBlue else GuidancePanel.copy(alpha = .95f),
            contentColor = if (active) Ink else Mist, disabledContainerColor = Ink.copy(alpha = .75f))) {
        Icon(icon, description, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun MapTool(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MapButton(icon, label, enabled = enabled, onClick = onClick)
        Text(label, color = Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ArrivalBar(nav: NavigationState, onStop: () -> Unit, onVoiceEnabled: (Boolean) -> Unit, onJourney: () -> Unit) {
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); clock = System.currentTimeMillis() } }
    val eta = nav.remainingSeconds?.let {
        java.time.Instant.ofEpochMilli(clock).atZone(java.time.ZoneId.systemDefault()).plusSeconds(it).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    Row(Modifier.fillMaxWidth().height(60.dp).background(GuidancePanel).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (nav.arrived) "Arrivée" else nav.remainingSeconds?.let(::formatRouteDuration) ?: "— min",
                    color = NavigationBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                Text(nav.remainingMeters?.let(::formatRouteDistance) ?: "", fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically), maxLines = 1)
            }
            Text(nav.error ?: nav.routeWarning ?: eta?.let { "Arrivée $it · hors trafic" } ?: "En attente du GPS",
                color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onJourney, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.AltRoute, "Choisir un trajet ou voir les étapes", tint = Mist) }
        IconButton(onClick = { onVoiceEnabled(!nav.voiceEnabled) }, modifier = Modifier.size(48.dp)) {
            Icon(if (nav.voiceEnabled) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                if (nav.voiceEnabled) "Couper le guidage vocal" else "Activer le guidage vocal", tint = Mist)
        }
        IconButton(onClick = onStop, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "Terminer le guidage", tint = Color(0xFFFFB7A8)) }
    }
}

@Composable
private fun FixedNavigationDialog(title: String, onDismiss: () -> Unit,
    footer: @Composable RowScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 500.dp).fillMaxHeight(),
                color = Surface, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "Fermer les options de navigation") }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), content = content)
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End, content = footer)
                }
            }
        }
    }
}

/** Explicit pages replace scrollable route and direction lists. Selection uses the displayed object. */
@Composable
private fun JourneyDialog(nav: NavigationState, onSelectRoute: (Route) -> Boolean, onReroute: () -> Unit, onDismiss: () -> Unit) {
    val routes = nav.routeOptions.ifEmpty { listOfNotNull(nav.route) }
    var routePage by remember(nav.routeOptions) { mutableIntStateOf(nav.selectedRouteIndex.coerceAtLeast(0)) }
    var stepsMode by remember { mutableStateOf(false) }
    var stepPage by remember(nav.route) { mutableIntStateOf(nav.nextStepIndex ?: 0) }
    val routeIndex = routePage.coerceIn(0, routes.lastIndex.coerceAtLeast(0))
    val route = routes.getOrNull(routeIndex)
    val steps = nav.route?.steps.orEmpty()
    val stepIndex = stepPage.coerceIn(0, steps.lastIndex.coerceAtLeast(0))
    val step = steps.getOrNull(stepIndex)
    FixedNavigationDialog(if (stepsMode) "Étapes du trajet" else "Choisir votre trajet", onDismiss, footer = {
        val index = if (stepsMode) stepIndex else routeIndex
        val count = if (stepsMode) steps.size else routes.size
        IconButton(onClick = { if (stepsMode) stepPage-- else routePage-- }, enabled = index > 0) {
            Icon(Icons.Rounded.ChevronLeft, if (stepsMode) "Étape précédente" else "Trajet précédent")
        }
        Text("${if (count == 0) 0 else index + 1} / $count", color = Muted, fontSize = 12.sp)
        IconButton(onClick = { if (stepsMode) stepPage++ else routePage++ }, enabled = index < count - 1) {
            Icon(Icons.Rounded.ChevronRight, if (stepsMode) "Étape suivante" else "Trajet suivant")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { stepsMode = !stepsMode }, enabled = nav.route != null) { Text(if (stepsMode) "Trajets" else "Étapes", fontSize = 12.sp) }
        if (!stepsMode) FilledIconButton(onClick = { if (route?.let(onSelectRoute) == true) onDismiss() }, enabled = route != null && !nav.loading) {
            Icon(Icons.Rounded.Navigation, "Choisir cet itinéraire")
        }
    }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            if (stepsMode && step != null) {
                Icon(maneuverIcon(step), null, tint = NavigationBlue, modifier = Modifier.size(34.dp))
                Text(step.instruction, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(if (stepIndex == nav.nextStepIndex && nav.distanceToTurnMeters != null) "Dans ${formatRouteDistance(nav.distanceToTurnMeters)}"
                    else "Puis ${formatRouteDistance(step.distanceMeters)}", color = Muted, fontSize = 13.sp)
            } else if (route != null) {
                Text(nav.destination?.name ?: "Destination", fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatRouteDuration(route.totalSeconds.toLong()), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = NavigationBlue)
                    Text(formatRouteDistance(route.totalMeters), fontSize = 18.sp)
                }
                Text(route.summary.ifBlank { "Itinéraire automobile" }, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val extra = ((route.totalSeconds - (routes.firstOrNull()?.totalSeconds ?: route.totalSeconds)) / 60).toInt().coerceAtLeast(0)
                Text(if (routeIndex == 0) "Le plus rapide proposé · hors trafic" else "+$extra min · hors trafic",
                    color = Muted, fontSize = 12.sp)
                (nav.error ?: nav.routeWarning)?.let { Text(it, fontSize = 11.sp, color = Color(0xFFFFCF8B), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            } else {
                Text(nav.error ?: "Aucun itinéraire disponible", color = Muted, fontSize = 14.sp)
                if (nav.destination != null) Button(onClick = onReroute, enabled = !nav.loading) { Text("Recalculer") }
            }
        }
    }
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
