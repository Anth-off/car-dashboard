package fr.cockpit.dashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.cockpit.dashboard.integrations.WeatherState

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
    var showMusicConsent by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        icon = { Icon(Icons.Rounded.Tune, contentDescription = null, tint = Lime) },
        title = { Text("Votre Cockpit", color = Mist, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsSection(Icons.Rounded.MusicNote, "Apple Music") {
                    Text(
                        if (musicAccess) "Commandes musicales autorisées."
                        else "Pilotez le morceau en cours depuis le dashboard.",
                        color = if (musicAccess) Lime else Mist,
                        fontSize = 13.sp,
                    )
                    SettingsDetail(
                        "Android demande un accès étendu aux notifications. Cockpit utilise uniquement " +
                            "les sessions multimédias ; aucun contenu de notification n’est lu ni conservé.",
                    )
                    Button(
                        onClick = {
                            if (musicAccess) onMusicAccess() else showMusicConsent = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceRaised, contentColor = Lime),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (musicAccess) "Gérer l’accès musical" else "Autoriser les commandes musicales", fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = Muted.copy(alpha = .15f))

                SettingsSection(Icons.Rounded.WbCloudy, "Météo extérieure") {
                    SettingsToggle(
                        title = "Activer la météo",
                        checked = weather.enabled,
                        onCheckedChange = onWeatherEnabled,
                    )
                    SettingsDetail(
                        "En activant la météo, vous autorisez l’envoi de votre position arrondie à " +
                            "Open-Meteo, au plus toutes les 15 min pendant le suivi GPS. " +
                            "La température est une estimation météo locale, pas celle d’un capteur de la voiture.",
                    )
                    TextButton(onClick = { uriHandler.openUri("https://open-meteo.com/") }) {
                        Text("Données météo : Open-Meteo", fontSize = 12.sp)
                    }
                }

                SettingsSection(Icons.Rounded.GraphicEq, "Guidage vocal") {
                    SettingsToggle(
                        title = "Annoncer les directions",
                        checked = voiceEnabled,
                        onCheckedChange = onVoiceEnabled,
                    )
                    SettingsDetail("Active les annonces de navigation de Cockpit avec la voix française du téléphone.")
                }

                HorizontalDivider(color = Muted.copy(alpha = .15f))

                SettingsSection(Icons.Rounded.Map, "Carte et itinéraires") {
                    SettingsDetail(
                        "Les tuiles OpenStreetMap révèlent la zone affichée au fournisseur de carte. " +
                            "Votre position et votre destination sont envoyées au service public OSRM " +
                            "pour calculer l’itinéraire. Ce prototype nécessite Internet et ne prend pas " +
                            "en charge le trafic en direct ni le guidage hors ligne.",
                    )
                    TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }) {
                        Text("© OpenStreetMap contributors", fontSize = 12.sp)
                    }
                }

                SettingsSection(Icons.Rounded.SatelliteAlt, "Vos compteurs") {
                    SettingsDetail(
                        "La vitesse et les distances sont estimées par GPS pendant le suivi. " +
                            "Le total enregistré est conservé sur ce téléphone ; ce n’est pas " +
                            "le kilométrage du véhicule. Seul le compteur trajet est réinitialisable.",
                    )
                }

                SettingsSection(Icons.Rounded.DirectionsCar, "Sur Android Auto") {
                    SettingsDetail(
                        "L’écran de la voiture utilise les modèles Android Auto, avec une présentation " +
                            "différente du téléphone. Le test sur un écran réel nécessite une installation " +
                            "via le canal de test interne Google Play ; l’APK installé seul ne suffit pas.",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Terminé", color = Lime) }
        },
    )

    if (showMusicConsent) {
        AlertDialog(
            onDismissRequest = { showMusicConsent = false },
            containerColor = Surface,
            icon = { Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Lime) },
            title = { Text("Autoriser Apple Music", color = Mist) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "L’écran suivant permet d’accorder à Cockpit l’accès Android aux notifications. " +
                            "Cette autorisation est étendue : Android autorise techniquement l’accès " +
                            "aux notifications des autres applications.",
                        color = Mist,
                    )
                    SettingsDetail(
                        "Cockpit s’en sert uniquement pour détecter et contrôler Apple Music, " +
                            "sans lire ni stocker les notifications. Vous pouvez retirer cet accès " +
                            "à tout moment dans les réglages Android.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMusicConsent = false
                    onMusicAccess()
                }) { Text("Ouvrir les réglages", color = Lime) }
            },
            dismissButton = {
                TextButton(onClick = { showMusicConsent = false }) { Text("Annuler", color = Muted) }
            },
        )
    }
}

@Composable
private fun SettingsSection(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(30.dp).background(SurfaceRaised, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Lime, modifier = Modifier.size(17.dp))
            }
            Text(title, color = Mist, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun SettingsDetail(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable
private fun SettingsToggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceRaised, RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Mist, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Lime),
        )
    }
}
