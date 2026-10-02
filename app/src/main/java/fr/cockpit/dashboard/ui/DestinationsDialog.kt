package fr.cockpit.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.cockpit.dashboard.destinations.Destination
import fr.cockpit.dashboard.destinations.DestinationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.UUID

private enum class DestinationTab { SEARCH, FAVORITES, RECENTS }

@Composable
fun DestinationsDialog(repository: DestinationRepository, saved: List<Destination>, onNavigate: (Destination) -> Unit, onDismiss: () -> Unit) {
    val recents by repository.recents.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Destination>>(emptyList()) }
    var tab by remember { mutableStateOf(if (saved.isNotEmpty()) DestinationTab.FAVORITES else DestinationTab.SEARCH) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingQuery by remember { mutableStateOf(saved.isEmpty()) }
    var manual by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun updateQuery(value: String) {
        searchGeneration++
        searchJob?.cancel()
        query = value
        results = emptyList()
        loading = false
        searched = false
        error = null
        tab = DestinationTab.SEARCH
    }
    fun search() {
        if (query.isBlank() || loading) return
        keyboard?.hide()
        editingQuery = false
        searchJob?.cancel()
        val generation = ++searchGeneration
        val submittedQuery = query
        loading = true
        searched = true
        error = null
        results = emptyList()
        tab = DestinationTab.SEARCH
        searchJob = scope.launch {
            try {
                val found = withTimeout(20_000) { repository.search(submittedQuery) }
                if (generation == searchGeneration) results = found
            } catch (timeout: TimeoutCancellationException) {
                if (generation == searchGeneration) error = "Réessayez ou saisissez des coordonnées GPS."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                if (generation == searchGeneration) error = problem.message ?: "Réessayez la recherche."
            } finally {
                if (generation == searchGeneration) loading = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            val compactWindow = maxHeight < 260.dp
            Surface(Modifier.padding(if (compactWindow) 0.dp else 8.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(),
                shape = RoundedCornerShape(24.dp), color = Ink, tonalElevation = 0.dp) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(if (compactWindow) 4.dp else 12.dp)) {
                    val compact = maxHeight < 220.dp
                    when {
                        manual -> ManualDestinationForm(Modifier.fillMaxSize(), repository,
                            onSaved = { manual = false; tab = DestinationTab.FAVORITES }, onNavigate = onNavigate,
                            onBack = { keyboard?.hide(); manual = false })
                        clearHistory -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Text("Effacer les trajets récents ?", color = Mist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                            Text("Vos favoris seront conservés.", color = Muted)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { clearHistory = false }) { Text("Annuler") }
                                Button(onClick = { repository.clearRecents(); clearHistory = false }) { Text("Effacer") }
                            }
                        }
                        editingQuery -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                            if (!compact) Text("Où allons-nous ?", color = Mist, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(query, onValueChange = ::updateQuery, label = { Text("Adresse, ville ou lieu") },
                                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                                FilledIconButton(onClick = ::search, enabled = query.isNotBlank() && !loading, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Rounded.Search, "Rechercher")
                                }
                                IconButton(onClick = { keyboard?.hide(); editingQuery = false }) {
                                    Icon(Icons.Rounded.Close, "Fermer la saisie", tint = Muted)
                                }
                            }
                            if (!compact) Text("Recherchez une adresse ou saisissez latitude, longitude.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Destinations", color = Mist, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                                IconButton(onClick = { editingQuery = true }) { Icon(Icons.Rounded.Search, "Rechercher une adresse", tint = Lime) }
                                IconButton(onClick = { manual = true }) { Icon(Icons.Rounded.MyLocation, "Saisir des coordonnées GPS", tint = Lime) }
                                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer les destinations", tint = Muted) }
                            }
                            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                DestinationTab.entries.forEach { choice ->
                                    FilterChip(selected = tab == choice, onClick = { tab = choice }, modifier = Modifier.weight(1f),
                                        label = { Text(when (choice) {
                                            DestinationTab.SEARCH -> "Recherche"
                                            DestinationTab.FAVORITES -> "Favoris"
                                            DestinationTab.RECENTS -> "Récents"
                                        }, fontSize = 12.sp, maxLines = 1) })
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when {
                                    tab == DestinationTab.SEARCH && loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CircularProgressIndicator(color = Lime, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                                        Text("Recherche en cours…", color = Muted)
                                    }
                                    tab == DestinationTab.SEARCH && error != null -> DestinationEmptyState(Icons.Rounded.WifiOff,
                                        "Recherche indisponible", error.orEmpty(), "Réessayer", ::search)
                                    tab == DestinationTab.SEARCH && results.isEmpty() -> DestinationEmptyState(Icons.Rounded.TravelExplore,
                                        if (searched) "Aucun lieu trouvé" else "Votre prochaine destination",
                                        if (searched) "Précisez la ville ou le code postal." else "Recherchez un lieu ou ajoutez ses coordonnées GPS.",
                                        "Rechercher", { editingQuery = true })
                                    tab == DestinationTab.FAVORITES && saved.isEmpty() -> DestinationEmptyState(Icons.Rounded.StarOutline,
                                        "Aucun favori", "Touchez l’étoile d’un résultat pour le conserver.", "Rechercher", { editingQuery = true })
                                    tab == DestinationTab.RECENTS && recents.isEmpty() -> DestinationEmptyState(Icons.Rounded.History,
                                        "Aucun trajet récent", "Vos 12 dernières destinations apparaîtront ici.")
                                    else -> {
                                        val destinations = when (tab) {
                                            DestinationTab.SEARCH -> results
                                            DestinationTab.FAVORITES -> saved
                                            DestinationTab.RECENTS -> recents
                                        }
                                        key(tab) {
                                            DestinationPages(destinations, saved, onNavigate,
                                                onFavorite = { repository.toggleFavorite(it) }, isFavorite = repository::isFavorite,
                                                onClear = if (tab == DestinationTab.RECENTS) {{ clearHistory = true }} else null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Fixed pages: the number of rows follows the available viewport, never a scrolling container. */
@Composable
private fun DestinationPages(destinations: List<Destination>, favorites: List<Destination>, onNavigate: (Destination) -> Unit,
    onFavorite: (Destination) -> Unit, isFavorite: (Destination) -> Boolean, onClear: (() -> Unit)?) {
    var page by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rows = ((maxHeight.value - 54f) / 74f).toInt().coerceIn(1, 6)
        val pages = ((destinations.size + rows - 1) / rows).coerceAtLeast(1)
        val visiblePage = page.coerceIn(0, pages - 1)
        LaunchedEffect(visiblePage) { page = visiblePage }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                destinations.drop(visiblePage * rows).take(rows).forEach { destination ->
                    val favorite = remember(destination, favorites) { isFavorite(destination) }
                    DestinationRow(destination, onNavigate, { onFavorite(destination) }, favorite)
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = visiblePage - 1 }, enabled = visiblePage > 0) { Icon(Icons.Rounded.ChevronLeft, "Page précédente") }
                Text("${visiblePage + 1} / $pages", color = Muted, modifier = Modifier.weight(1f), fontSize = 13.sp)
                if (onClear != null) TextButton(onClick = onClear) { Text("Effacer") }
                IconButton(onClick = { page = visiblePage + 1 }, enabled = visiblePage < pages - 1) { Icon(Icons.Rounded.ChevronRight, "Page suivante") }
            }
        }
    }
}

@Composable
private fun DestinationRow(destination: Destination, onNavigate: (Destination) -> Unit, onFavorite: () -> Unit, favorite: Boolean) {
    Surface(Modifier.fillMaxWidth().height(68.dp), shape = RoundedCornerShape(16.dp), color = Surface) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().clickable { onNavigate(destination) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NearMe, null, tint = Lime, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(destination.name, fontWeight = FontWeight.SemiBold, color = Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(destination.address.ifBlank { String.format(Locale.FRANCE, "%.4f°, %.4f°", destination.latitude, destination.longitude) },
                        color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    if (favorite) "Retirer ${destination.name} des favoris" else "Enregistrer ${destination.name} en favori", tint = if (favorite) Lime else Muted)
            }
        }
    }
}

@Composable
private fun BoxScope.DestinationEmptyState(icon: ImageVector, title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentHeight = maxHeight
        Column(Modifier.align(Alignment.Center).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (contentHeight >= 230.dp) Box(Modifier.size(56.dp).background(Surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(26.dp), tint = Lime)
            }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(message, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = if (contentHeight < 180.dp) 2 else 4,
                overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ManualDestinationForm(modifier: Modifier, repository: DestinationRepository, onSaved: () -> Unit,
    onNavigate: (Destination) -> Unit, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(0) }
    var invalid by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun coordinate(text: String, limit: Double) = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in -limit..limit }
    fun next() {
        if (step >= 3) return
        invalid = when (step) {
            1 -> coordinate(latitude, 90.0) == null
            2 -> coordinate(longitude, 180.0) == null
            else -> false
        }
        if (!invalid) {
            if (step == 2) keyboard?.hide()
            step++
        }
    }
    fun destination() = Destination(UUID.randomUUID().toString(), name.trim().ifEmpty { "Point GPS" },
        requireNotNull(coordinate(latitude, 90.0)), requireNotNull(coordinate(longitude, 180.0)))

    BoxWithConstraints(modifier) {
        val compact = maxHeight < 220.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compact) Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Coordonnées GPS", color = Mist, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, "Fermer les coordonnées", tint = Muted) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (step < 3) {
                    OutlinedTextField(value = when (step) { 0 -> name; 1 -> latitude; else -> longitude },
                        onValueChange = { value -> invalid = false; when (step) { 0 -> name = value; 1 -> latitude = value; else -> longitude = value } },
                        label = { Text(when (step) { 0 -> "Nom du lieu (facultatif)"; 1 -> "Latitude : −90 à 90"; else -> "Longitude : −180 à 180" }) },
                        placeholder = { Text(when (step) { 0 -> "Point GPS"; 1 -> "48.8566"; else -> "2.3522" }) },
                        isError = invalid, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = if (step == 2) ImeAction.Done else ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { next() }, onDone = { next() }))
                } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name.ifBlank { "Point GPS" }, color = Mist, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(String.format(Locale.FRANCE, "%.5f°, %.5f°", coordinate(latitude, 90.0), coordinate(longitude, 180.0)), color = Muted)
                    Text("Lancer le guidage ou conserver ce lieu.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { invalid = false; if (step > 0) step-- else onBack() }) { Text("Retour") }
                Text("${step + 1} / 4", color = Muted, modifier = Modifier.weight(1f), fontSize = 12.sp)
                if (step < 3) Button(onClick = ::next) { Text(if (step == 2) "Vérifier" else "Suivant") }
                else {
                    IconButton(onClick = { repository.addFavorite(destination()); onSaved() }) { Icon(Icons.Rounded.StarOutline, "Enregistrer en favori", tint = Lime) }
                    Button(onClick = { onNavigate(destination()) }) { Text("Y aller") }
                }
            }
        }
    }
}
