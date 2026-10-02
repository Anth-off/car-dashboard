# Signature et mises à jour de Cockpit

Android conserve les données lors d'une mise à jour lorsque le nom de package reste `fr.cockpit.dashboard`, le numéro `versionCode` augmente et la nouvelle application utilise une signature compatible avec l'installation existante. Une clé de débogage recréée sur chaque runner GitHub ne convient pas.

## Blocage actuel de la version 0.2.0

L'APK publié sur GitHub sous `v0.2.0` a été signé par une clé de débogage créée pendant le run `36992390943`, le 2 octobre 2026 à 09:54:21 UTC. Cette clé privée n'a pas été sauvegardée. Les caches de ce workflow ne contiennent pas le dossier `.android` du runner. La clé de développement locale possède un autre certificat.

Le certificat public de cet APK est épinglé dans [`release-signing-certificate.sha256`](release-signing-certificate.sha256) :

```text
733ee611dcc27c55975d4b2f75e964646f41ee6f022f72be83a99ef83e2b94e8
```

**Aucune mise à jour compatible avec cette installation 0.2.0 ne peut être signée avec les clés actuellement disponibles.** Augmenter `versionCode`, recréer un certificat portant le même nom ou utiliser une nouvelle clé persistante ne répare pas cette situation. Le certificat public ne permet pas de retrouver la clé privée.

Les versions antérieures et leurs téléchargements restent inchangés. Le nouveau workflow bloque toute publication tant que la clé fournie ne correspond pas au certificat publié. Il ne génère pas de nouvelle clé et ne comporte pas de contournement de ce contrôle. La préparation de la version 0.3.0 dans les sources ne signifie pas qu'une mise à jour installable a été publiée. Ne pas désinstaller l'application actuelle pour essayer un APK local incompatible.

## Configuration de signature persistante

Une clé compatible retrouvée dans une sauvegarde pourrait être réutilisée. À défaut, la création d'une nouvelle lignée d'installation nécessiterait une décision explicite et un plan de migration ; elle ne doit pas être présentée comme une mise à jour directe de 0.2.0. Aucun tel changement n'est effectué ici.

Pour une clé de publication compatible, configurer ces secrets GitHub Actions :

| Secret | Contenu |
| --- | --- |
| `COCKPIT_SIGNING_KEYSTORE_BASE64` | Fichier keystore encodé en base64 |
| `COCKPIT_SIGNING_STORE_PASSWORD` | Mot de passe du keystore |
| `COCKPIT_SIGNING_KEY_ALIAS` | Alias de la clé |
| `COCKPIT_SIGNING_KEY_PASSWORD` | Mot de passe de la clé |

Conserver une sauvegarde chiffrée du keystore et les moyens de le déverrouiller hors du runner et du dépôt. Les caches Actions et un secret non récupérable ne remplacent pas une sauvegarde. Ne jamais commiter le keystore, son encodage base64 ou ses mots de passe.

Le workflow restaure la clé dans le dossier temporaire privé du runner, contrôle son certificat avant compilation, signe **l'APK et l'AAB en variante `release`**, vérifie chaque artefact, puis supprime la copie temporaire. Une configuration manquante ou une signature différente arrête le workflow avant publication. La clé n'est incluse ni dans les téléchargements, ni dans l'archive des sources, ni dans les caches Gradle configurés.

## Compilation locale

Les builds `debug` restent disponibles pour le développement. Leur clé locale n'est pas une clé de publication et ils ne doivent pas être proposés comme mise à jour de la version GitHub.

Pour compiler une variante `release`, fournir les variables d'environnement suivantes depuis un gestionnaire de secrets :

```text
COCKPIT_SIGNING_STORE_FILE=/chemin/prive/cockpit.keystore
COCKPIT_SIGNING_STORE_PASSWORD
COCKPIT_SIGNING_KEY_ALIAS
COCKPIT_SIGNING_KEY_PASSWORD
```

Puis lancer :

```bash
./gradlew :app:assembleRelease :app:bundleRelease
python3 scripts/verify-release-signature.py --apk app/build/outputs/apk/release/app-release.apk
python3 scripts/verify-release-signature.py --bundle app/build/outputs/bundle/release/app-release.aab
```

Gradle bloque lui aussi une clé manquante ou différente du certificat épinglé. Le script nécessite Python 3, le JDK et `apksigner` fourni par les Android Build Tools 35.0.0. Il utilise `JAVA_HOME`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, ou la variable `APKSIGNER` si l'outil est installé ailleurs.

Le contrôle d'un keystore avant compilation utilise seulement son certificat public :

```bash
python3 scripts/verify-release-signature.py --keystore "$COCKPIT_SIGNING_STORE_FILE"
```

Le workflow vérifie également que le tag correspond à `versionName` et que `versionCode` dépasse celui de la version 0.2.0. Les versions suivantes doivent continuer à augmenter `versionCode` et conserver la même clé. La configuration d'une éventuelle distribution Google Play doit préserver une identité de signature compatible avec les installations visées ; le simple passage par Google Play ne répare pas la perte de la clé 0.2.0.
