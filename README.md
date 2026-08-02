# StreamBox X — V17 réparée

Projet Android complet avec lecteur Media3 et Google Cast natif.

## Corrections V17

- AndroidX et Jetifier activés dans `gradle.properties` et dans le workflow GitHub.
- Chargement immédiat du média lorsqu’un téléviseur est déjà connecté.
- Contrôle du résultat de la commande Cast avant de mettre le lecteur local en pause.
- Relais HTTP local pour que le Chromecast puisse lire les flux accessibles via le réseau/VPN du téléphone.
- Réécriture des playlists HLS et prise en charge des requêtes `Range`.
- Identifiants enregistrés pendant la saisie dans localStorage et dans les préférences Android.
- Correction du diagnostic vidéo WebView qui utilisait une variable inexistante.

## Compilation

Le workflow `.github/workflows/build-apk.yml` installe Java 17, Gradle 8.9 et Android SDK 35, puis compile `:app:assembleDebug`.

L’APK est publié dans la release GitHub `generated-apk`.

## Conditions pour le Cast

Le téléphone et le téléviseur doivent être sur le même réseau Wi-Fi. Si un VPN est actif, il doit autoriser l’accès au réseau local afin que le téléviseur puisse joindre le relais du téléphone.
