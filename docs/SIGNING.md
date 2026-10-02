# Signature et mises à jour de Cockpit GPS

Depuis la version 0.3.0, l’application utilise le nom **Cockpit GPS** et l’identifiant **`fr.cockpit.gps`**. Elle s’installe à côté de Cockpit 0.2, qui conserve l’identifiant `fr.cockpit.dashboard`. L’ancienne application et ses données restent présentes ; il n’est pas nécessaire de la désinstaller.

Il s’agit d’une nouvelle installation, pas d’une mise à jour de la 0.2. Les compteurs démarrent à zéro, les favoris doivent être recréés et les autorisations doivent être accordées à Cockpit GPS. Aucune migration automatique des données n’est effectuée.

La [page de distribution 0.3.0](https://github.com/Anth-off/car-dashboard/releases/tag/v0.3.0) est la destination prévue pour **`cockpit-0.3.0.apk`** et **`cockpit-0.3.0.aab`**, tous deux signés en variante `release`. Leur disponibilité dépend de la réussite du workflow de publication.

## Pourquoi la version 0.2 ne peut pas être remplacée

L’APK GitHub `v0.2.0` a été signé par une clé de débogage créée pendant le run `36992390943`, le 2 octobre 2026 à 09:54:21 UTC. Cette clé privée n’a pas été sauvegardée et les caches du workflow ne contiennent pas le dossier `.android` du runner.

Son certificat public SHA-256 est :

```text
733ee611dcc27c55975d4b2f75e964646f41ee6f022f72be83a99ef83e2b94e8
```

Une autre clé ne peut pas signer une mise à jour compatible avec cette installation, même en conservant le nom de l’application ou en augmentant `versionCode`. Le certificat public ne permet pas de retrouver la clé privée. La nouvelle identité `fr.cockpit.gps` permet donc de conserver l’ancienne application sans prétendre la mettre à jour.

## Signature des prochaines versions

Cockpit GPS utilise une nouvelle clé persistante. Son certificat public est épinglé dans [`release-signing-certificate.sha256`](release-signing-certificate.sha256). Ce fichier concerne **la nouvelle application** ; il ne rétablit pas la compatibilité avec Cockpit 0.2.

Pour mettre à jour Cockpit GPS sans réinstallation et conserver ses données, garder l’identifiant `fr.cockpit.gps`, augmenter `versionCode` et signer avec cette même clé. Gradle et les scripts de publication refusent une signature différente. Ne pas recréer une clé à chaque compilation.

La clé et son archive de sauvegarde privée sont conservées hors du dépôt et hors des téléchargements publics. **Une copie présente uniquement dans l’environnement de travail ne constitue pas encore une sauvegarde durable.** Le propriétaire doit télécharger l’archive privée qui lui est fournie, la conserver dans un emplacement protégé et garder les moyens de déverrouiller la clé. L’archive privée ne doit jamais être jointe à une release GitHub ni commitée.

## Publication d’artefacts signés localement

La première publication de Cockpit GPS peut utiliser des artefacts préparés :

1. Compiler localement l’APK et l’AAB `release` avec la clé persistante, en dehors du dépôt.
2. Vérifier leurs signatures, identifiant, version et sommes de contrôle, puis préparer l’archive des sources correspondant au commit.
3. Fournir au workflow de publication ces artefacts et leurs métadonnées sur la branche temporaire prévue à cet effet. Elle ne contient jamais le keystore ni ses mots de passe.
4. Le workflow vérifie les artefacts, le certificat épinglé, la version, la correspondance des sources avec le commit et la réussite des contrôles CI avant de publier sur GitHub Releases.

Cette voie transmet uniquement des fichiers déjà signés ; la clé privée reste hors de GitHub Actions. Elle ne remplace pas la sauvegarde privée de la clé pour les versions suivantes.

## Compilation dans GitHub Actions avec des secrets

Le workflow **Android signed release** reste disponible pour les prochaines versions, après configuration de ces secrets GitHub Actions :

| Secret | Contenu |
| --- | --- |
| `COCKPIT_SIGNING_KEYSTORE_BASE64` | Fichier keystore persistant encodé en base64 |
| `COCKPIT_SIGNING_STORE_PASSWORD` | Mot de passe du keystore |
| `COCKPIT_SIGNING_KEY_ALIAS` | Alias de la clé |
| `COCKPIT_SIGNING_KEY_PASSWORD` | Mot de passe de la clé |

Ces secrets ne sont pas configurés par la voie de publication d’artefacts préparés. Le workflow refuse de publier s’ils sont absents. Un secret GitHub non récupérable et les caches Actions ne remplacent pas une sauvegarde du keystore.

Le workflow restaure la clé dans le dossier temporaire privé du runner, contrôle son certificat, signe **l’APK et l’AAB en variante `release`**, vérifie les artefacts et leur version, puis supprime la copie temporaire. Une configuration manquante ou une signature différente arrête la publication. La clé n’est incluse ni dans les téléchargements, ni dans l’archive des sources, ni dans les caches Gradle configurés.

## Compilation locale

Les builds `debug` restent disponibles pour le développement. Leur clé locale diffère de la clé de publication : ils ne doivent pas être proposés comme mises à jour de l’APK distribué.

Pour compiler une variante `release`, fournir les variables d’environnement suivantes depuis un gestionnaire de secrets :

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

Gradle bloque une clé manquante ou différente du certificat épinglé. Le script nécessite Python 3, le JDK et `apksigner` fourni par les Android Build Tools 35.0.0. Il utilise `JAVA_HOME`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, ou la variable `APKSIGNER` si l’outil est installé ailleurs.

Le contrôle d’un keystore avant compilation utilise seulement son certificat public :

```bash
python3 scripts/verify-release-signature.py --keystore "$COCKPIT_SIGNING_STORE_FILE"
```

Les tags doivent correspondre à `versionName`. Chaque version suivante doit augmenter `versionCode` et conserver la même clé et le même identifiant d’application.

## Android Auto et Google Play

La publication GitHub fournit un APK installable sur le téléphone et un AAB signé. Elle ne rend pas automatiquement Cockpit GPS disponible sur un autoradio Android Auto. Une application basée sur la Car App Library doit passer par un canal de test Google Play compatible, par exemple le partage interne d’applications ou une piste de test interne, avec un compte Play Console configuré.

Aucun accès à un compte Play Console ni déploiement Google Play n’est configuré ici. Le fichier AAB sert à préparer cette étape ; il ne s’installe pas directement sur le téléphone.

Google Play peut utiliser une autre clé de signature, en particulier pour le partage interne. Avant de changer de canal de distribution, vérifier la compatibilité avec les installations existantes. Le passage par Google Play ne répare pas la perte de la clé 0.2 et ne garantit pas à lui seul une mise à jour compatible de l’APK GitHub.
