package fr.cockpit.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
                if (generation == searchGeneration) error = "La recherche prend trop de temps. Réessayez ou utilisez les coordonnées GPS."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                if (generation == searchGeneration) error = problem.message ?: "La recherche est indisponible. Réessayez."
            } finally {
                if (generation == searchGeneration) loading = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.94f),
            shape = RoundedCornerShape(28.dp), color = Ink, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(Lime, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Explore, null, tint = Ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (manual) "Un point sur la carte" else "Où allons-nous ?", fontWeight = FontWeight.Bold,
                            fontSize = 21.sp, color = Mist)
                        Text(if (manual) "Ajouter des coordonnées GPS" else "Trouvez votre prochaine destination", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer les destinations", tint = Muted) }
                }
                if (manual) {
                    ManualDestinationForm(Modifier.weight(1f), repository,
                        onSaved = { manual = false; tab = DestinationTab.FAVORITES }, onNavigate = onNavigate)
                    TextButton(onClick = { manual = false }) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("Retour aux destinations") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, onValueChange = ::updateQuery, placeholder = { Text("Adresse, ville ou lieu…") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = if (query.isNotEmpty()) {{ IconButton(onClick = { updateQuery("") }) { Icon(Icons.Rounded.Close, "Effacer la recherche") } }} else null,
                            singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                        FilledIconButton(onClick = ::search, enabled = query.isNotBlank() && !loading,
                            modifier = Modifier.size(52.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.ArrowForward, "Rechercher")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DestinationTab.entries.forEach { choice ->
                            FilterChip(selected = tab == choice, onClick = { tab = choice },
                                label = { Text(when (choice) {
                                    DestinationTab.SEARCH -> "Recherche"
                                    DestinationTab.FAVORITES -> "Favoris"
                                    DestinationTab.RECENTS -> "Récents"
                                }, fontSize = 12.sp) },
                                leadingIcon = { Icon(when (choice) {
                                    DestinationTab.SEARCH -> Icons.Rounded.Search
                                    DestinationTab.FAVORITES -> Icons.Rounded.StarOutline
                                    DestinationTab.RECENTS -> Icons.Rounded.History
                                }, null, Modifier.size(16.dp)) })
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            tab == DestinationTab.SEARCH && loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator(color = Lime, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                                Text("Recherche de votre destination…", color = Muted, style = MaterialTheme.typography.bodyMedium)
                            }
                            tab == DestinationTab.SEARCH && error != null -> DestinationEmptyState(Icons.Rounded.WifiOff,
                                "Recherche indisponible", error.orEmpty(), "Réessayer", ::search)
                            tab == DestinationTab.SEARCH && results.isEmpty() -> DestinationEmptyState(Icons.Rounded.TravelExplore,
                                if (searched) "Aucun lieu trouvé" else "La route commence ici",
                                if (searched) "Ajoutez la ville ou le code postal à votre recherche. Vous pouvez aussi utiliser des coordonnées GPS."
                                else "Recherchez une adresse, un restaurant ou une ville. Touchez un lieu pour lancer le guidage, ou son étoile pour le garder.")
                            tab == DestinationTab.FAVORITES && saved.isEmpty() -> DestinationEmptyState(Icons.Rounded.StarOutline,
                                "Vos adresses à portée de main", "Enregistrez un résultat avec son étoile. Vos favoris sont aussi disponibles dans Android Auto.",
                                "Rechercher un lieu", { tab = DestinationTab.SEARCH })
                            tab == DestinationTab.RECENTS && recents.isEmpty() -> DestinationEmptyState(Icons.Rounded.History,
                                "Votre prochain trajet vous attend", "Les 12 dernières destinations de vos guidages seront conservées ici, sur ce téléphone.")
                            else -> {
                                val destinations = when (tab) {
                                    DestinationTab.SEARCH -> results
                                    DestinationTab.FAVORITES -> saved
                                    DestinationTab.RECENTS -> recents
                                }
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(when (tab) {
                                                DestinationTab.SEARCH -> "${destinations.size} lieu${if (destinations.size > 1) "x" else ""} trouvé${if (destinations.size > 1) "s" else ""}"
                                                DestinationTab.FAVORITES -> "Vos lieux enregistrés · ${destinations.size}"
                                                DestinationTab.RECENTS -> "Derniers guidages"
                                            }, color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                            if (tab == DestinationTab.RECENTS) TextButton(onClick = { clearHistory = true }) { Text("Effacer") }
                                        }
                                    }
                                    items(destinations, key = { it.id }) { destination ->
                                        DestinationRow(destination, onNavigate,
                                            onFavorite = { repository.toggleFavorite(destination) }, favorite = repository.isFavorite(destination))
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = SurfaceRaised)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { manual = true }) {
                            Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Coordonnées GPS")
                        }
                        Text("À préparer\nà l’arrêt", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("Effacer les trajets récents ?") },
        text = { Text("Vos favoris seront conservés.") },
        confirmButton = { TextButton(onClick = { repository.clearRecents(); clearHistory = false }) { Text("Effacer") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("Annuler") } })
}

@Composable
private fun DestinationRow(destination: Destination, onNavigate: (Destination) -> Unit, onFavorite: () -> Unit, favorite: Boolean) {
    Surface(shape = RoundedCornerShape(18.dp), color = Surface) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { onNavigate(destination) }.padding(start = 14.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(SurfaceRaised, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.NearMe, null, tint = Lime, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(destination.name, fontWeight = FontWeight.SemiBold, color = Mist, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(destination.address.ifBlank { String.format(Locale.FRANCE, "%.4f°, %.4f°", destination.latitude, destination.longitude) },
                        color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onFavorite, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    if (favorite) "Retirer ${destination.name} des favoris" else "Enregistrer ${destination.name} en favori", tint = if (favorite) Lime else Muted)
            }
        }
    }
}

@Composable
private fun BoxScope.DestinationEmptyState(icon: ImageVector, title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.align(Alignment.Center).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(70.dp).background(Surface, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(30.dp), tint = Lime) }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(message, color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ManualDestinationForm(modifier: Modifier, repository: DestinationRepository, onSaved: () -> Unit, onNavigate: (Destination) -> Unit) {
    var name by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = latitude.isNotBlank() && longitude.isNotBlank()
    fun destination(): Destination? = runCatching {
        Destination(UUID.randomUUID().toString(), name.trim().ifEmpty { "Point GPS" },
            latitude.trim().replace(',', '.').toDouble(), longitude.trim().replace(',', '.').toDouble())
    }.onFailure { error = "Vérifiez la latitude (−90 à 90) et la longitude (−180 à 180)." }.getOrNull()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Un point précis, même sans recherche d’adresse. Les virgules et les points décimaux sont acceptés.", color = Muted, style = MaterialTheme.typography.bodyMedium) }
        item { OutlinedTextField(name, onValueChange = { name = it }, label = { Text("Nom du lieu (facultatif)") }, singleLine = true,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(latitude, onValueChange = { latitude = it; error = null }, label = { Text("Latitude") }, placeholder = { Text("48.8566") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(longitude, onValueChange = { longitude = it; error = null }, label = { Text("Longitude") }, placeholder = { Text("2.3522") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item { Button(onClick = { destination()?.let(onNavigate) }, enabled = ready, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.Navigation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Y aller")
        } }
        item { OutlinedButton(onClick = { destination()?.let { repository.addFavorite(it); onSaved() } }, enabled = ready,
            modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.StarOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Enregistrer en favori")
        } }
    }
}
