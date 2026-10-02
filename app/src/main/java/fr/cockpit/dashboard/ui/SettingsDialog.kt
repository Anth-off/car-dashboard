package fr.cockpit.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.cockpit.dashboard.integrations.WeatherState

/** Each section is a fixed page so that every action stays visible in a short landscape viewport. */
@Composable
fun SettingsDialog(
    weather: WeatherState,
    musicAccess: Boolean,
    voiceEnabled: Boolean,
    onWeatherEnabled: (Boolean) -> Unit,
    onMusicAccess: () -> Unit,
    onVoiceEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    var consentStep by remember { mutableIntStateOf(-1) }
    val uriHandler = LocalUriHandler.current
    val sections = listOf(
        "Apple Music" to Icons.Rounded.MusicNote,
        "Météo extérieure" to Icons.Rounded.WbCloudy,
        "Guidage vocal" to Icons.Rounded.GraphicEq,
        "La carte" to Icons.Rounded.Map,
        "Les itinéraires" to Icons.Rounded.Route,
        "Vos compteurs" to Icons.Rounded.SatelliteAlt,
        "Sur Android Auto" to Icons.Rounded.DirectionsCar,
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 560.dp).fillMaxHeight(),
                shape = RoundedCornerShape(24.dp), color = Surface) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsHeading(if (consentStep >= 0) Icons.Rounded.MusicNote else sections[page].second,
                            if (consentStep >= 0) "Autoriser Apple Music" else sections[page].first, Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer les réglages", tint = Muted) }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        when {
                            consentStep == 0 -> {
                                Text("Une autorisation Android étendue", color = Mist, fontWeight = FontWeight.SemiBold)
                                SettingsDetail("Le réglage Android donne techniquement accès aux notifications des autres applications. Vérifiez cette autorisation avant de l’activer.")
                            }
                            consentStep == 1 -> {
                                Text("Utilisée pour vos commandes musicales", color = Mist, fontWeight = FontWeight.SemiBold)
                                SettingsDetail("Cockpit détecte et contrôle uniquement les sessions Apple Music. Aucun contenu de notification n’est lu ni conservé. Vous pouvez retirer l’accès à tout moment dans les réglages Android.")
                            }
                            page == 0 -> {
                                Text(if (musicAccess) "Commandes musicales autorisées." else "Pilotez le morceau en cours depuis le dashboard.",
                                    color = if (musicAccess) Lime else Mist, fontSize = 14.sp)
                                SettingsDetail("Les commandes utilisent l’accès Android aux notifications pour détecter Apple Music. Aucun contenu de notification n’est lu ni conservé.")
                                Button(onClick = { if (musicAccess) onMusicAccess() else consentStep = 0 }, modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceRaised, contentColor = Lime)) {
                                    Text(if (musicAccess) "Gérer l’accès musical" else "Autoriser les commandes musicales", fontSize = 13.sp)
                                }
                            }
                            page == 1 -> {
                                SettingsToggle("Activer la météo", weather.enabled, onWeatherEnabled)
                                SettingsDetail("En l’activant, vous autorisez l’envoi de votre position arrondie à Open-Meteo, au plus toutes les 15 min pendant le suivi GPS. La température est une estimation météo locale.")
                                TextButton(onClick = { uriHandler.openUri("https://open-meteo.com/") }) { Text("Données : Open-Meteo", fontSize = 12.sp) }
                            }
                            page == 2 -> {
                                SettingsToggle("Annoncer les directions", voiceEnabled, onVoiceEnabled)
                                SettingsDetail("Cockpit annonce les directions avec la voix française du téléphone. Vous pouvez aussi couper la voix depuis la carte.")
                            }
                            page == 3 -> {
                                SettingsDetail("La carte provient d’OpenStreetMap. Le téléchargement des tuiles révèle la zone affichée au fournisseur. Une connexion Internet est nécessaire.")
                                TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap contributors", fontSize = 12.sp) }
                            }
                            page == 4 -> SettingsDetail("Votre position et votre destination sont envoyées au service public OSRM pour calculer le trajet. Le guidage ne comprend ni le trafic en direct ni un mode hors ligne.")
                            page == 5 -> SettingsDetail("La vitesse et les distances sont estimées par GPS pendant le suivi. Le total reste sur ce téléphone et correspond à la distance enregistrée par Cockpit. Seul le compteur trajet peut être remis à zéro.")
                            page == 6 -> SettingsDetail("La voiture utilise les modèles Android Auto. Pour apparaître sur l’autoradio, cette application nécessite une distribution Google Play par un canal de test ou le partage interne. L’APK installé seul ne suffit pas.")
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (consentStep >= 0) {
                            TextButton(onClick = { consentStep = -1 }) { Text("Annuler", color = Muted) }
                            Spacer(Modifier.weight(1f))
                            if (consentStep == 0) Button(onClick = { consentStep = 1 }) { Text("Continuer") }
                            else Button(onClick = { consentStep = -1; onMusicAccess() }) { Text("Ouvrir les réglages") }
                        } else {
                            IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.Rounded.ChevronLeft, "Réglage précédent") }
                            Text("${page + 1} / ${sections.size}", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = onDismiss) { Text("Terminé", color = Lime) }
                            IconButton(onClick = { page++ }, enabled = page < sections.lastIndex) { Icon(Icons.Rounded.ChevronRight, "Réglage suivant") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeading(icon: ImageVector, title: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(36.dp).background(SurfaceRaised, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Lime, modifier = Modifier.size(20.dp))
        }
        Text(title, color = Mist, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SettingsDetail(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
private fun SettingsToggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(SurfaceRaised, RoundedCornerShape(14.dp))
        .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Mist, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Lime))
    }
}
