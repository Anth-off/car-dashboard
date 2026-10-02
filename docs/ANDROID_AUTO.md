# Dashboard Cockpit GPS sur Android Auto

La version 0.4 reprend les composants **`Dashboard` et `CockpitTheme` utilisés sur le téléphone** dans la surface fournie par Android Auto. La carte, les compteurs, la météo et les commandes Apple Music partagent ainsi le même rendu et les mêmes données. La disposition s’adapte aux dimensions et à la densité de l’écran ; elle n’impose pas les dimensions physiques du téléphone à l’autoradio.

Les barres et commandes propres à Android Auto restent gérées par l’hôte. Les menus de destination, les favoris, les choix d’itinéraire et les options restent des modèles Android Auto avec pagination par boutons, sans listes défilantes. Le démarrage du suivi GPS et l’octroi des autorisations restent sur le téléphone, à l’arrêt.

## Mise à jour sur le S23

Le propriétaire a confirmé que **Cockpit GPS 0.3 fonctionne dans Android Auto après installation avec KingInstaller sur son Samsung S23**. Pour essayer la 0.4 publiée, télécharger `cockpit-0.4.0.apk` depuis la [page de distribution](https://github.com/Anth-off/car-dashboard/releases/tag/v0.4.0) et reprendre la même méthode.

Android doit proposer une **mise à jour**, pas demander de désinstaller Cockpit GPS. Les versions 0.3 et 0.4 utilisent toutes deux `fr.cockpit.gps` et la même signature. Les compteurs, favoris et autorisations existants sont conservés. Si Android indique une signature incompatible, conserver l’installation actuelle et vérifier le fichier utilisé ; un APK `debug` local n’est pas l’APK signé distribué.

KingInstaller est un outil externe. Le retour positif sur la version 0.3 concerne ce S23 ; il ne garantit pas la prise en charge d’autres téléphones, versions d’Android Auto ou autoradios. Le nouveau dashboard projeté de la 0.4 doit encore être essayé sur l’autoradio réel.

## Surface et interactions

Le dashboard partagé est activé lorsque l’hôte fournit la **Car App API 5 ou plus**, nécessaire pour transmettre les clics de surface. Ce niveau d’API appartient à l’hôte Android Auto ; ce n’est pas le numéro de version Android du téléphone.

Une `Presentation` contenant un `ComposeView` est rendue sur un `VirtualDisplay` connecté à la `Surface` de l’hôte. Les zones visibles et stables annoncées par Android Auto limitent le dashboard à l’espace réellement disponible. La composition est libérée lorsque la surface est remplacée ou que l’écran quitte le premier plan.

Les clics reçus de l’hôte sont transmis aux composants du dashboard. Les gestes de déplacement et de zoom agissent sur la carte ; ils ne font pas défiler la page. Les commandes qui ouvrent une destination, un favori ou un réglage passent par les écrans Android Auto dédiés. Les gestes effectivement transmis dépendent de l’écran et de l’hôte.

Le bouton **Carte seule** conserve l’accès au rendu de navigation classique. Les hôtes antérieurs à la Car App API 5 utilisent ce parcours classique ; lorsqu’une projection ne peut pas être créée ou que l’espace disponible est insuffisant, le message affiché invite à utiliser **Carte seule**.

## Distribution privée et critères Google Play

Le rendu partagé répond à la demande d’une interface privée identique à celle du téléphone. Il ne constitue pas une qualification Google Play. Le critère de navigation **NF-2** réserve la surface au contenu cartographique, avec certaines informations pertinentes pour la conduite dans la zone sûre. Les instructions textuelles, le guidage par voie et l’arrivée estimée doivent utiliser les composants du modèle de navigation prévus à cet effet. Le dashboard complet place notamment ses commandes musicales et son bandeau de guidage directement sur la surface. Cette version ne doit donc pas être présentée comme conforme pour une publication officielle telle quelle.

La technique Compose avec écran virtuel est documentée par Google pour le rendu sur une surface. Son utilisation ne dispense pas des règles portant sur le contenu affiché. Pour une distribution officielle Android Auto, il faudrait adapter l’interface aux critères, réaliser les validations requises et préparer un canal compatible dans Google Play Console. La cible Android actuelle est API 35 ; une future soumission doit intégrer les exigences de niveau cible applicables, notamment API 36.

Aucun compte Play Console ni déploiement Google Play n’est configuré ici. Le bundle signé `cockpit-0.4.0.aab` ne s’installe pas directement et ne prouve aucune approbation Google Play. Une distribution Play peut employer un certificat différent, notamment via le partage interne ; préserver la compatibilité de signature est nécessaire pour les mises à jour des installations existantes. Voir [SIGNING.md](SIGNING.md).

## Vérification

Les tests logiciels couvrent les composants et les modèles d’écran selon leur portée documentée dans [VERIFICATION.txt](../artifacts/VERIFICATION.txt). Une compilation, un test de surface ou une capture de téléphone ne remplace pas l’essai du rendu, des gestes et des commandes sur le S23 connecté à l’autoradio. Le [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu) est un outil complémentaire pour tester la projection.

Références Google : [dessiner avec Compose et un écran virtuel](https://developer.android.com/training/cars/apps/library/draw-maps#compose-virtual-display), [critères qualité des applications pour voiture](https://developer.android.com/docs/quality-guidelines/car-app-quality), [tests Android Auto](https://developer.android.com/training/cars/testing), [niveau d’API cible Google Play](https://support.google.com/googleplay/android-developer/answer/11926878).
