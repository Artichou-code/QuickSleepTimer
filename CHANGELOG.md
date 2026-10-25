# Journal des Modifications (CHANGELOG)

Toutes les modifications notables apportées au projet **Quick Sleep Timer for Android TV** sont consignées dans ce document.

Le format est basé sur [Keep a Changelog](https://keepachangelog.com/fr/1.0.0/) et ce projet adhère au [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.2.0] - 2026-10-25

### ✨ Nouvelles Fonctionnalités & Améliorations Majeures
* **Zéro interruption vidéo (Crunchyroll, Netflix, YouTube, ExoPlayer) :**
  - Application du style de fenêtre translucide (`Theme.QuickSleepTimer.Setup` avec `android:windowIsTranslucent="true"`). Android WindowManager n'émet plus de transition plein écran opaque lors de l'activation du timer, empêchant la mise en pause du lecteur vidéo sous-jacent.
  - Implémentation d'une séquence de reprise audio/vidéo multi-pulse ultra-rapide (`autoResumePlayback` à 100ms, 250ms et 430ms avec `KEYCODE_MEDIA_PLAY`) pour relancer instantanément la lecture en cas de micro-hésitation du lecteur système.
* **Recul adaptatif et intelligent de l'assombrissement d'écran (Night Dimmer) :**
  - Lorsque du temps est ajouté (`+10 min`, `+15 min`) alors que l'écran est déjà fortement assombri (ex: 80% d'obscurité), l'écran s'éclaircit avec une transition fluide de 1,5s proportionnellement au temps ajouté.
  - L'écran ne reste plus bloqué dans l'obscurité et ne saute pas non plus à 100% de luminosité blanche pour préserver les yeux de l'utilisateur dans le noir.
  - La progression lente vers l'obscurité totale reprend ensuite naturellement sur la nouvelle durée restante.
* **Recul adaptatif du fondu sonore :**
  - La baisse progressive du volume bénéficie de la même formule : si du temps est ajouté pendant que le son baisse, le volume remonte proportionnellement avant de reprendre sa descente progressive.
* **Double comportement intelligent Accueil vs Application :**
  - **Sur l'écran d'accueil (Google TV / Projectivy Launcher) :** Le bouton Étoile (★) et la tuile de l'application ouvrent directement la page complète des paramètres et réglages.
  - **Dans une application multimédia (Crunchyroll, Deezer, YouTube...) :** Le bouton Étoile déclenche ou incrémente le minuteur en tâche de fond avec la pastille HUD, sans jamais ouvrir les réglages ni couper la vidéo.

---

## [1.1.0] - 2026-10-24

### ✨ Nouvelles Fonctionnalités
* **Baisse progressive du volume sonore (Audio Fade Out) :**
  - Option configurable pour baisser le son pas à pas vers zéro : sur toute la durée du minuteur ou sur les 5 / 10 dernières minutes.
  - Restauration automatique du volume initial en cas d'annulation manuelle ou après la mise en veille.
* **Pastille HUD flottante discrète (Overlay) :**
  - Affichage animé en haut à droite indiquant le temps restant et le palier ajouté (`+10 min`), avec disparition automatique après 5 secondes.
  - Fenêtre d'incrémentation glissante de 5s : chaque appui consécutif cumule du temps sans fermer la pastille.

---

## [1.0.0] - 2026-10-23

### 🚀 Lancement Initial
* **Minuteur de veille natif pour Android TV & Google TV :**
  - Conçu spécialement pour la télécommande du **Google TV Streamer 4K** (bouton personnalisable Étoile ★ / `KEYCODE_MACRO_1`).
  - Compatible avec toutes les télécommandes Android TV via l'interception native d'accessibilité ou l'application tiers *Button Mapper*.
* **Mode Filtre de Nuit (Night Dimmer) :**
  - Calque logiciel d'assombrissement (`SYSTEM_ALERT_WINDOW`) permettant d'abaisser la luminosité de l'écran en dessous du seuil matériel de la dalle TV.
  - Modes : assombrissement immédiat ou progressif sur les 5 dernières minutes.
* **Extinction matérielle :**
  - Déclenchement de la mise en veille globale via le service d'accessibilité (`GLOBAL_ACTION_LOCK_SCREEN`), avec extinction de la TV par signal HDMI-CEC.
* **Vie privée absolue :**
  - Zéro publicité, zéro tracking, zéro permission réseau (`INTERNET` non requis).
