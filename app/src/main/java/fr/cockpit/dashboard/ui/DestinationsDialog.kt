package fr.cockpit.dashboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.cockpit.dashboard.destinations.Destination
import fr.cockpit.dashboard.destinations.DestinationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun DestinationsDialog(repository: DestinationRepository, saved: List<Destination>, onNavigate: (Destination) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Destination>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Votre prochaine destination") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, onValueChange = { query = it }, label = { Text("Adresse ou lieu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    loading = true; error = null
                    scope.launch {
                        try {
                            results = withTimeout(20_000) { repository.search(query) }
                            if (results.isEmpty()) error = "Aucun résultat. Précisez la ville ou utilisez les coordonnées."
                        } catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) {
                            error = "La recherche prend trop de temps. Réessayez ou utilisez les coordonnées."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (problem: Exception) {
                            error = problem.message ?: "Recherche indisponible."
                        } finally { loading = false }
                    }
                }, enabled = query.isNotBlank() && !loading) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Rechercher")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                for (destination in results) {
                    DestinationRow(destination, onNavigate, {
                        repository.add(destination.name, destination.latitude, destination.longitude)
                        results = results.filterNot { it.id == destination.id }
                    }, favorite = false)
                }
                HorizontalDivider()
                Text("Lieux enregistrés", color = Lime)
                if (saved.isEmpty()) Text("Ajoutez vos adresses pour les retrouver aussi dans Android Auto.", color = Muted)
                for (destination in saved) DestinationRow(destination, onNavigate, { repository.remove(destination.id) }, favorite = true)
                TextButton(onClick = { manual = !manual }) { Text(if (manual) "Masquer les coordonnées" else "Ajouter avec des coordonnées GPS") }
                if (manual) {
                    OutlinedTextField(name, onValueChange = { name = it }, label = { Text("Nom du lieu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(latitude, onValueChange = { latitude = it }, label = { Text("Latitude, ex. 48.8566") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(longitude, onValueChange = { longitude = it }, label = { Text("Longitude, ex. 2.3522") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        runCatching {
                            repository.add(name, latitude.replace(',', '.').toDouble(), longitude.replace(',', '.').toDouble())
                        }.onSuccess { name = ""; latitude = ""; longitude = ""; manual = false; error = null }
                            .onFailure { error = "Vérifiez le nom, la latitude (−90 à 90) et la longitude (−180 à 180)." }
                    }, enabled = name.isNotBlank() && latitude.isNotBlank() && longitude.isNotBlank()) { Text("Enregistrer") }
                }
                Text("Recherche fournie par le service de géocodage Android. Préparez vos destinations à l’arrêt.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun DestinationRow(destination: Destination, onNavigate: (Destination) -> Unit, onAction: () -> Unit, favorite: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onNavigate(destination) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.Navigation, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(destination.name, modifier = Modifier.weight(1f))
        }
        IconButton(onClick = onAction) {
            Icon(if (favorite) Icons.Rounded.DeleteOutline else Icons.Rounded.StarOutline,
                if (favorite) "Supprimer le favori" else "Enregistrer le favori")
        }
    }
}
