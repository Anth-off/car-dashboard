# Cockpit

Application Android native en français : tableau de bord sombre, navigation propre à l’application, commandes Apple Music et mesures GPS. Android 10 ou plus récent.

**Sources 0.3.0 en préparation — aucune nouvelle mise à jour installable publiée.** Le certificat de signature de l’APK GitHub 0.2.0 est connu, mais sa clé privée n’a pas été conservée sur le runner de compilation. Android interdit donc sa mise à jour directe avec les clés disponibles. La publication est maintenant bloquée si la clé est absente ou différente : [détails et contrôles de signature](docs/SIGNING.md). Il ne faut pas désinstaller la version actuellement installée pour essayer un APK local.

[Version de test 0.2 déjà publiée](https://github.com/Anth-off/car-dashboard/releases/tag/v0.2.0) · [Historique des versions](https://github.com/Anth-off/car-dashboard/releases)

![Dashboard sans défilement sur téléphone, au repos](artifacts/cockpit-landscape.png)

Le téléphone utilise une interface Jetpack Compose personnalisée. L’écran Android Auto utilise les modèles de navigation Google : son apparence diffère du téléphone. Les nouveaux écrans évitent les listes défilantes et présentent les éléments longs par pages courtes avec boutons. Les déplacements au doigt à l’intérieur de la carte restent possibles.

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
- Guidage vocal activable directement dans le bandeau de navigation ; écran maintenu allumé pendant un guidage actif.
- Recherche d’adresse, favoris locaux et 12 destinations récentes par pages courtes ; coordonnées manuelles en étapes avec clavier visible. Réglages présentés une section par page.
- Recalcul automatique après trois positions confirmant la sortie d’itinéraire ; ancien trajet conservé si le réseau échoue et délais progressifs entre tentatives. Recalcul manuel disponible.
- Lecture/pause, précédent/suivant et métadonnées de la session Apple Music active.
- Température météo extérieure Open-Meteo, optionnelle, avec date de mise à jour.
- Android Auto : carte de guidage avec cadrage adaptatif et panneaux fixes de deux lignes ; favoris et alternatives par boutons précédent/suivant, musique, compteurs et météo.

Les compteurs mesurent **uniquement les déplacements enregistrés pendant le suivi GPS**. Ils ne lisent pas le compteur kilométrique du véhicule. Les tunnels, mauvaises positions et arrêts de l’application peuvent laisser des distances non enregistrées. La température n’est pas une mesure d’un capteur de la voiture.

## Essayer sur le téléphone

1. Pour les développeurs, installer un build local sur un appareil de test distinct. Un APK local ne constitue pas une mise à jour de la 0.2 GitHub. Ouvrir Cockpit et autoriser la localisation **précise** lors du démarrage du suivi. Le suivi se lance depuis l’application visible et reste signalé par une notification.
2. Dans les réglages Cockpit, activer la météo si souhaité. Autoriser les notifications Android pour voir le suivi et les indications hors de l’application.
3. Pour la musique, accorder à Cockpit l’accès spécial aux notifications, puis ouvrir Apple Music et lancer un morceau. Le contrôle nécessite une session Apple Music active ; l’application ne fournit ni abonnement ni catalogue musical.
4. Utiliser la loupe de la carte pour rechercher une destination, sélectionner un favori ou un trajet récent. L’étoile ajoute/retire un favori ; les résultats de recherche ne sont pas enregistrés automatiquement. Un trajet calculé avec succès rejoint les récents.
5. Accepter l’utilisation du calcul d’itinéraire en ligne, puis attendre une position GPS. Les boutons de carte permettent d’agrandir, zoomer, recentrer et afficher tout le trajet ; le haut-parleur coupe les annonces et le bouton d’itinéraires ouvre les alternatives et les étapes paginées.
6. Arrêter le suivi pour mettre fin à l’enregistrement. Le compteur trajet reste conservé jusqu’à sa remise à zéro ; le total reste conservé.

Les versions Android récentes peuvent limiter l’accès aux notifications pour une application installée manuellement. Le téléphone indique alors les réglages supplémentaires disponibles dans sa fiche d’application. Cockpit n’active aucune permission automatiquement.

## Essayer sur Android Auto

L’installation directe de l’APK permet de tester **le téléphone**. Pour une application basée sur la Car App Library, l’option Android Auto « Sources inconnues » ne suffit pas à la rendre disponible sur l’autoradio.

La voie officielle pour un véhicule réel est l’**Internal App Sharing** ou une piste de test interne dans **Google Play Console**, puis l’installation par le lien Google Play avec le compte testeur. Le partage interne d’applications accepte des versions de débogage ; une piste de test classique nécessite une version release et votre signature d’envoi. Le compte Play Console et les éventuelles exigences de configuration doivent être fournis par le propriétaire du projet. Aucun envoi ou déploiement sur Google Play n’est effectué par ce dépôt.

1. Charger un APK/AAB accepté par le canal choisi dans votre Play Console.
2. Installer Cockpit depuis son lien de test, préparer les autorisations et les favoris sur le téléphone, à l’arrêt.
3. Démarrer le suivi GPS depuis le téléphone, connecter Android Auto, puis ouvrir Cockpit dans le lanceur d’applications.
4. Choisir une destination enregistrée. La navigation et la carte sont propres à Cockpit ; Waze n’est pas intégré.

Le service est déclaré comme application **NAVIGATION** et implémente le calcul d’itinéraire et les instructions virage par virage. La conformité complète doit encore être vérifiée dans le Desktop Head Unit et sur véhicule avant distribution. Le rendu personnalisé Compose du téléphone ne remplace pas le lanceur Android Auto.

Le [Desktop Head Unit officiel](https://developer.android.com/training/cars/testing/dhu) permet de poursuivre les tests de projection. Le rappel de simulation Android Auto suit un itinéraire réellement calculé et marque ses instructions « Simulation » ; il ne modifie pas les compteurs GPS.

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

La signature debug est réservée aux essais. Une distribution publique nécessite votre configuration de signature, votre identifiant d’application et la validation de toutes les exigences Android Auto. Le projet ne contient aucune clé privée, aucun jeton et aucune clé API de fournisseur.

Le workflow **Android checks** compile et vérifie chaque changement sans publier d’APK. Le workflow **Android signed release** exige une clé persistante compatible avec le certificat publié, vérifie la signature de l’APK et de l’AAB et l’incrément de version avant publication. Il reste bloqué dans l’état actuel : voir [SIGNING.md](docs/SIGNING.md).

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

Ces aperçus montrent l’interface du téléphone au repos, sans trajet ni mesures fictives. Ils ne constituent pas une capture de l’autoradio. Les essais sur Android Auto réel et avec une session Apple Music réelle restent à effectuer.

Références : [Car App Library](https://developer.android.com/training/cars/apps/library), [applications de navigation](https://developer.android.com/training/cars/apps/navigation), [installation et tests Android Auto](https://developer.android.com/training/cars/testing), [critères qualité](https://developer.android.com/docs/quality-guidelines/car-app-quality).
