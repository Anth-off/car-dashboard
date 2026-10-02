# Cockpit GPS

Application Android native en français : tableau de bord sombre, navigation propre à l’application, commandes Apple Music et mesures GPS. Android 10 ou plus récent.

Depuis la version **0.4.0**, Cockpit GPS reprend le dashboard du téléphone sur Android Auto : même carte, mêmes compteurs et commandes musicales dans la zone d’affichage fournie par la voiture. Les menus de destination et de réglage restent des écrans Android Auto sans défilement. Le détail du fonctionnement et des limites figure dans [ANDROID_AUTO.md](docs/ANDROID_AUTO.md).

**Installation par-dessus Cockpit GPS 0.3.0 ou 0.4.0, sans désinstaller.** L’identifiant `fr.cockpit.gps` et la clé de signature sont conservés ; la mise à jour conserve les compteurs et favoris et ne réinitialise pas les autorisations accordées par Android. La version 0.2 nommée « Cockpit », sous `fr.cockpit.dashboard`, reste une application distincte : ses anciennes données ne sont pas transférées automatiquement.

[Page de distribution 0.4.1](https://github.com/Anth-off/car-dashboard/releases/tag/v0.4.1) · [Version 0.4.0](https://github.com/Anth-off/car-dashboard/releases/tag/v0.4.0) · [Historique des versions](https://github.com/Anth-off/car-dashboard/releases)

Version de test **0.4.1** : [télécharger l’APK](https://github.com/Anth-off/car-dashboard/releases/download/v0.4.1/cockpit-0.4.1.apk). La page de distribution regroupe également le bundle signé `cockpit-0.4.1.aab`, les sources et les sommes de contrôle SHA-256. Ce correctif réduit les annonces GPS et leurs interruptions, améliore la reconnexion musicale avec les accès déjà accordés et distingue le suivi actif de l’attente d’une position ; son comportement après mise à jour sur le S23 reste à confirmer.

![Dashboard sans défilement sur téléphone, au repos](artifacts/cockpit-landscape.png)

Le téléphone et Android Auto réutilisent les mêmes composants Compose `Dashboard` et `CockpitTheme`. La disposition s’adapte à la taille et à la zone disponible ; les barres, marges et commandes propres à Android Auto restent gérées par l’hôte. Le dashboard projeté demande la **Car App API 5 ou plus**, distincte de la version Android du téléphone. Les hôtes plus anciens conservent le rendu de navigation classique ; le bouton **Carte seule** permet aussi d’y revenir. Les déplacements et zooms à l’intérieur de la carte restent possibles, sans défilement de la page.

## Fonctions

- Vitesse GPS ; affichage « — » si le signal est absent ou périmé.
- Distance totale enregistrée par l’application, conservée entre sessions.
- Compteur trajet distinct, réinitialisable quand le suivi est arrêté.
- Dashboard fixe sans défilement en portrait et paysage : carte, vitesse, météo, compteurs et commandes musicales restent dans le même écran. Mode carte plein écran sur téléphone.
- Carte orientée dans le sens de la marche, véhicule plus bas pour anticiper, zoom automatique selon la vitesse et les virages. Panoramique, zoom manuel, recentrage et vue d’ensemble.
- Styles jour/nuit, route bleue avec flèches, marqueur de destination, précision GPS et portion parcourue atténuée. Position grisée si le GPS est périmé ; aucune position extrapolée.
- Jusqu’à trois itinéraires automobiles OSRM réellement proposés, classés par durée estimée puis distance. Noms des routes, comparaison des durées et choix explicite sur téléphone ou Android Auto.
- Instructions françaises, prochaine manœuvre, étapes par pages, progression et arrivée estimée **hors trafic**. Direction GPS utilisée en mouvement pour mieux choisir le sens de circulation.
- Départ raccordé à moins de 100 m et arrivée à moins de 200 m d’une route ; message si le point demandé est éloigné de l’accès routier. Une alternative devenue ancienne est recalculée depuis la position courante.
- Guidage vocal limité aux manœuvres utiles : préparation puis action, sans couper la phrase en cours ni accumuler les anciennes annonces. La voix reste activable depuis le bandeau ; écran maintenu allumé pendant un guidage actif.
- Recherche d’adresse, favoris locaux et 12 destinations récentes par pages courtes ; coordonnées manuelles en étapes avec clavier visible. Réglages présentés une section par page.
- Recalcul automatique après trois positions confirmant la sortie d’itinéraire ; ancien trajet conservé si le réseau échoue et délais progressifs entre tentatives. Recalcul manuel disponible.
- Lecture/pause, précédent/suivant et métadonnées de la session Apple Music active.
- Température météo extérieure Open-Meteo, optionnelle, avec date de mise à jour.
- Android Auto : dashboard partagé avec le téléphone sur Car App API 5+, commandes tactiles transmises aux composants, panoramique et zoom de carte. Favoris, alternatives et options sur écrans fixes à pagination ; mode **Carte seule** disponible.

Les compteurs mesurent **uniquement les déplacements enregistrés pendant le suivi GPS**. Ils ne lisent pas le compteur kilométrique du véhicule. Les tunnels, mauvaises positions et arrêts de l’application peuvent laisser des distances non enregistrées. La température n’est pas une mesure d’un capteur de la voiture.

## Essayer sur le téléphone

1. Télécharger **`cockpit-0.4.1.apk`** depuis la [page de distribution 0.4.1](https://github.com/Anth-off/car-dashboard/releases/tag/v0.4.1), puis l’installer par-dessus Cockpit GPS 0.3.0 ou 0.4.0. Accepter la **mise à jour** proposée par Android ; ne pas désinstaller l’application. Ouvrir **Cockpit GPS**, distinct de l’ancien Cockpit, puis démarrer le suivi avec son bouton. Si Android demande la localisation, choisir **Lorsque l’application est utilisée** avec la position **précise** ; **Seulement cette fois** expire à la fin de la session. Le suivi se lance depuis l’application visible et reste signalé par une notification.
2. Dans les réglages Cockpit GPS, activer la météo si souhaité. Autoriser les notifications Android pour voir le suivi et les indications hors de l’application.
3. Pour la musique, accorder à Cockpit GPS l’accès spécial aux notifications, puis ouvrir Apple Music et lancer un morceau. Le contrôle nécessite une session Apple Music active ; l’application ne fournit ni abonnement ni catalogue musical.
4. Utiliser la loupe de la carte pour rechercher une destination, sélectionner un favori ou un trajet récent. L’étoile ajoute/retire un favori ; les résultats de recherche ne sont pas enregistrés automatiquement. Un trajet calculé avec succès rejoint les récents.
5. Accepter l’utilisation du calcul d’itinéraire en ligne, puis attendre une position GPS. Les boutons de carte permettent d’agrandir, zoomer, recentrer et afficher tout le trajet ; le haut-parleur coupe les annonces et le bouton d’itinéraires ouvre les alternatives et les étapes paginées.
6. Arrêter le suivi pour mettre fin à l’enregistrement. Le compteur trajet reste conservé jusqu’à sa remise à zéro ; le total reste conservé.

Pour une mise à jour depuis Cockpit GPS 0.3 ou 0.4, les données et les autorisations déjà accordées sont conservées par une installation compatible. Les étapes d’autorisation ci-dessus concernent une première installation ou un accès absent, expiré ou révoqué. Conserver l’ancien Cockpit 0.2 si ses compteurs ou favoris sont utiles : ses données restent séparées de celles de Cockpit GPS. Les [contrôles de signature](docs/SIGNING.md) protègent la continuité des prochaines mises à jour.

Les versions Android récentes peuvent limiter l’accès aux notifications pour une application installée manuellement. Le téléphone indique alors les réglages supplémentaires disponibles dans sa fiche d’application. Cockpit n’active aucune permission automatiquement.

### Après une mise à jour : accès et attente GPS

La version 0.4.1 réutilise les autorisations présentes. Si Android déconnecte le service musical alors que l’accès spécial aux notifications est toujours accordé, Cockpit tente de le reconnecter ; il n’est pas nécessaire de retirer cet accès pour le redonner. Une session Apple Music active reste nécessaire pour afficher et commander un morceau.

Le bouton de suivi GPS lance ou arrête l’enregistrement : **démarrer le suivi n’est pas redonner une autorisation**. Si le suivi est actif mais qu’aucune position n’est encore reçue, le dashboard indique une recherche du signal. Attendre une position avec la localisation du téléphone activée ; la carte et la météo attendent aussi cette position. Réactiver les autorisations ne crée pas de signal GPS.

Un refus des notifications de suivi n’entraîne plus une nouvelle demande à chaque démarrage du suivi. Les notifications peuvent être réactivées dans les paramètres Android. Cet accès est distinct de l’accès spécial utilisé pour Apple Music. Le correctif n’accorde jamais un accès refusé, expiré ou révoqué : une autorisation Android manquante nécessite toujours une action explicite dans la demande système ou les paramètres. La correction du problème observé sur le S23 n’est pas encore confirmée sur l’appareil.

## Essayer sur Android Auto

Le propriétaire a confirmé que **Cockpit GPS 0.3 fonctionne sur son Samsung S23 avec KingInstaller**. C’est la méthode à réutiliser pour essayer la mise à jour 0.4.1 sur cet appareil ; cela ne garantit pas sa compatibilité avec toutes les versions d’Android Auto, tous les téléphones ou tous les autoradios. KingInstaller est un outil externe, distinct de Cockpit GPS.

1. Télécharger **`cockpit-0.4.1.apk`**, puis reprendre la même installation via KingInstaller que pour les versions précédentes. Android doit proposer une **mise à jour de Cockpit GPS**, sans désinstallation.
2. À l’arrêt, ouvrir Cockpit GPS sur le téléphone et démarrer le suivi GPS. Les accès déjà accordés ne demandent pas d’être désactivés puis réactivés ; le bouton du suivi démarre l’enregistrement. Les favoris restent disponibles.
3. Connecter Android Auto puis ouvrir **Cockpit GPS**. Sur un hôte Car App API 5+, le dashboard partagé est l’écran principal ; sa surface conserve les commandes et les dimensions disponibles de l’autoradio.
4. Utiliser le dashboard pour la carte, les compteurs et la musique. Les destinations, favoris et options s’ouvrent dans les menus Android Auto sans défilement. Si la projection n’est pas disponible ou si le rendu classique est préféré, utiliser **Carte seule**.

L’installation de la 0.3 a été confirmée sur le S23 ; la projection du nouveau dashboard 0.4 doit encore être vérifiée sur l’autoradio réel. Les contrôles de compilation et les tests logiciels ne constituent pas cet essai.

La voie officielle de distribution Android Auto reste un canal compatible **Google Play Console**, après satisfaction de ses exigences. **Cette version privée n’est pas qualifiée pour Google Play** : elle affiche le dashboard complet, avec musique et compteurs, sur la surface réservée à la cartographie par le critère de navigation **NF-2**. Une distribution officielle nécessiterait un rendu conforme, les validations Android Auto et la mise à niveau des exigences de plateforme applicables, notamment le niveau cible **API 36** au lieu du 35 actuel. Aucun compte Play Console ni déploiement Google Play n’est configuré ici ; le fichier AAB signé ne constitue pas une approbation de distribution.

Google Play peut utiliser une autre signature, notamment pour le partage interne. Un changement de canal doit préserver la compatibilité des certificats pour conserver une mise à jour directe de l’APK GitHub. Voir [ANDROID_AUTO.md](docs/ANDROID_AUTO.md) et [SIGNING.md](docs/SIGNING.md).

Le [Desktop Head Unit officiel](https://developer.android.com/training/cars/testing/dhu) permet de poursuivre les tests de projection. Le rappel de simulation Android Auto suit un itinéraire réellement calculé et marque ses instructions « Simulation » ; il ne modifie pas les compteurs GPS. La carte et le guidage sont propres à Cockpit GPS ; Waze n’est pas intégré.

## Compiler

Ouvrir ce dossier dans Android Studio, installer le SDK Android 35 et utiliser un JDK complet 17 ou 21. Le projet utilise Gradle 8.13, AGP 8.9.2 et Kotlin 2.1.20.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:bundleDebug
```

Fichiers produits :

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/bundle/debug/app-debug.aab`
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`

La signature `debug` est réservée au développement. Un APK de développement ne constitue pas une mise à jour de l’APK signé distribué. Les téléchargements destinés aux utilisateurs sont les variantes `release`, signées avec la clé persistante de Cockpit GPS. Le dépôt ne contient aucune clé privée, aucun jeton et aucune clé API de fournisseur.

Pour produire les versions signées, fournir les variables de signature décrites dans [SIGNING.md](docs/SIGNING.md), puis lancer :

```sh
./gradlew :app:assembleRelease :app:bundleRelease
```

Les fichiers sont `app/build/outputs/apk/release/app-release.apk` et `app/build/outputs/bundle/release/app-release.aab`.

Le workflow **Android checks** compile et vérifie chaque changement sans publier d’APK. Le workflow de publication d’artefacts préparés contrôle les fichiers signés localement, leur certificat, leurs sommes de contrôle, leur version, leurs sources et les vérifications CI avant l’envoi sur GitHub Releases ; il n’a pas besoin de la clé privée. Le workflow **Android signed release** permet une compilation future dans GitHub Actions avec des secrets de signature persistants. Ces secrets ne sont pas configurés par défaut ; ce second workflow refuse de publier si la clé est absente ou différente du certificat épinglé.

## Données, fournisseurs et limites

- Les compteurs, les favoris et les 12 destinations récentes sont stockés uniquement dans les préférences locales. Aucune trace GPS n’est conservée ; la sauvegarde cloud Android est désactivée.
- La position actuelle et la destination sont transmises à `router.project-osrm.org` pour calculer l’itinéraire. C’est un serveur public de démonstration : **pas de garantie de disponibilité, pas de trafic en direct**. Prévoir un fournisseur ou une instance OSRM dédiée pour un usage distribué.
- Les tuiles affichées sont demandées à `tile.openstreetmap.org`. Cache HTTP respecté, seules les tuiles visibles sont chargées, aucun téléchargement massif. Une connexion reste nécessaire pour les zones absentes du cache. Attribution permanente : [© OpenStreetMap contributors](https://www.openstreetmap.org/copyright), données sous ODbL. Respecter la [politique des tuiles](https://operations.osmfoundation.org/policies/tiles/) et prévoir un fournisseur adapté pour une application diffusée largement.
- La météo désactivée par défaut transmet une position arrondie à deux décimales à [Open-Meteo](https://open-meteo.com/) au maximum toutes les 15 minutes pendant le suivi. Les relevés et coordonnées météo ne sont pas persistés. Vérifier les conditions du fournisseur pour une diffusion commerciale.
- La recherche d’adresse utilise le fournisseur `Geocoder` configuré sur le téléphone ; ses conditions et sa disponibilité dépendent de l’appareil.
- L’accès spécial aux notifications est étendu au niveau Android. Le code n’utilise que les sessions média du package Apple Music et ne lit ni ne conserve le contenu des notifications.
- La synthèse vocale dépend du moteur et des voix françaises installées sur le téléphone.
- Aucun SDK publicitaire, outil d’analyse ni compte utilisateur n’est ajouté.

## Vérification du calcul de trajet

Le 2 octobre 2026, une demande entre des points proches des centres de Sainville et de Dourdan a été comparée sur deux serveurs automobiles OSRM. Ils ont retourné les mêmes propositions : **D5/D17, 17,54 km et 23 min** ; **D17/D838, 18,46 km et 25,3 min**. Il s’agit d’un contrôle entre communes approximatives, sans les adresses exactes ni le trafic ; il ne valide pas un trajet domicile–travail précis. Le centre géométrique d’une commune peut différer de son centre-ville et de l’entrée souhaitée.

Le serveur public ne fournit pas de trafic, fermetures en temps réel ou filtre configurable « éviter les chemins ». Le classement choisit le plus rapide **parmi les propositions reçues** et ne peut garantir le meilleur trajet sur tous les réseaux. Un service configurable et tenant compte du trafic serait nécessaire pour ces garanties supplémentaires.

## Organisation

`ui/` : dashboard et réglages Compose. `telemetry/` : suivi GPS et compteurs. `navigation/` : itinéraires, progression et voix. `map/` : rendu cartographique partagé téléphone/voiture. `integrations/` : musique et météo. `destinations/` : favoris et géocodage. `car/` : service et écrans Android Auto.

Les tests JVM couvrent le filtrage GPS, la progression, le recalcul, le classement des alternatives, la sélection d’un ancien trajet, la projection, le cadrage automobile, le zoom et les angles de rotation. Les tests de destinations vérifient aussi la compatibilité du stockage précédent, la persistance et la déduplication des récents.

Les tests Robolectric vérifient les modèles Android Auto API 1 et 7, la pagination de 25 favoris, les choix d’itinéraire, l’absence de panneaux défilants, les bounds des commandes sur petits écrans et avec police agrandie, la saisie avec clavier, ainsi que le rendu de l’Activity en portrait et paysage. Pour produire les aperçus de l’interface avec le moteur graphique Android natif de Robolectric :

```sh
./gradlew -Dcockpit.screenshot.dir="$PWD/artifacts" :app:testDebugUnitTest
```

Les aperçus téléphone et les captures `cockpit-auto-dashboard*.png` montrent respectivement la véritable Activity et le contenu de la véritable `Presentation` de projection, rendus sous Robolectric au repos, sans mesures fictives ; les captures de projection couvrent 1280 × 720, 1024 × 600, 800 × 480 et un texte à 130 %. Ces rendus logiciels ne constituent pas des captures de l’autoradio physique ni du chrome Android Auto. L’installation Android Auto de la 0.3 via KingInstaller a été confirmée sur le S23 ; les essais du dashboard projeté 0.4 et de ses commandes avec une session Apple Music réelle restent à effectuer sur véhicule.

Références : [Car App Library](https://developer.android.com/training/cars/apps/library), [applications de navigation](https://developer.android.com/training/cars/apps/navigation), [installation et tests Android Auto](https://developer.android.com/training/cars/testing), [critères qualité](https://developer.android.com/docs/quality-guidelines/car-app-quality).
