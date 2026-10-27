# Journal des Modifications (CHANGELOG)

Toutes les modifications notables apportées au projet **Quick Sleep Timer for Android TV** sont consignées dans ce document.

Le format est basé sur [Keep a Changelog](https://keepachangelog.com/fr/1.0.0/) et ce projet adhère au [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.3.9] - 2026-10-27

### 🌅 Réveil en Douceur & Démarrage Progressif Nocturne (Soft Wake-up)
* **Protection Contre l'Éblouissement Nocturne :**
  - Ajout d'une nouvelle fonctionnalité majeure conçue pour éviter « l'explosion des yeux » lorsqu'on allume la télévision au milieu de la nuit ou au petit matin.
  - Dès l'allumage ou la sortie de veille (`ACTION_SCREEN_ON` ou démarrage à froid `BOOT_COMPLETED`), l'écran est instantanément recouvert d'un filtre noir protecteur (à 85% d'opacité en &lt; 1 ms).
  - La luminosité augmente ensuite de manière douce, fluide et continue jusqu'à 100% de la luminosité normale de la TV.
* **Plage Horaire Nocturne Entièrement Personnalisable :**
  - L'utilisateur peut définir la période d'activation automatique (par défaut `23:00 → 08:00`), avec prise en charge intelligente du chevauchement de minuit.
  - Sélecteur TV simple et rapide avec pré-réglages ergonomiques adaptés à la télécommande (`23h-8h`, `23h-7h`, `22h-8h`, `00h-8h`, `00h-9h`) et mode personnalisation par tranche d'heures.
* **Durée de Montée Réglable de 1 à 30 Minutes :**
  - Choix de la vitesse de transition : `1 min`, `2 min`, `5 min` (par défaut), `10 min`, `15 min`, `20 min`, `25 min`, `30 min`.
* **Annulation Manuelle Immédiate :**
  - Si l'utilisateur est déjà bien réveillé et veut repasser instantanément à 100% de luminosité, un simple appui sur la touche physique configurée pour le minuteur (Étoile ★, Live TV, Netflix, etc.) coupe le filtre noir et rétablit la pleine luminosité avec un discret HUD de confirmation (`🌅 Réveil en douceur désactivé — Pleine luminosité rétablie`).
* **Bouton de Test / Simulation en Direct (15s) :**
  - Bouton interactif dans les réglages permettant de prévisualiser immédiatement l'effet de montée progressive de la luminosité en accéléré sur 15 secondes.

---

## [1.3.8] - 2026-10-26

### 🔘 Séparation & Support Dédié de la Touche Apps (9 points Xiaomi)
* **Différenciation Complète de la Touche Live TV et de la Touche Apps (9 points) :**
  - La touche ornée d'une grille de 9 points (`ALL_APPS`, Scancode 580 / Keycode 284) était auparavant assimilée par erreur à la touche Live TV, ce qui provoquait un conflit et bloquait son rôle d'origine.
  - Les codes `580` et `284` ont été retirés de la liste des déclencheurs Live TV. La touche Live TV et la touche Apps sont désormais 100% indépendantes.
* **Nouvelle Ligne de Configuration Dédiée (Section 4) :**
  - Ajout de la ligne **㗊 Touche Apps (9 points - Xiaomi)** dans les paramètres de télécommande.
  - Par défaut, l'action est configurée sur `❌ Désactivée (Tiroir d'apps par défaut)` : elle n'est plus bloquée ni interceptée et conserve son comportement système normal vers le tiroir d'applications ou le launcher (Projectivy).
  - L'utilisateur peut librement l'assigner à `🌙 Minuteur de Veille` ou à `🚀 Lancer une application TV` au choix.

---

## [1.3.7] - 2026-10-26

### 🎮 Personnalisation & Remapping Indépendant des Touches (Style Button Mapper)
* **Configuration Individuelle par Bouton :**
  - Chaque touche matérielle de la télécommande dispose désormais de sa propre affectation totalement autonome :
    - **📺 Touche Live TV (Xiaomi)**
    - **🍿 Touche Netflix**
    - **★ Touche Étoile (Google TV Streamer)**
    - **⚡ Touche Personnalisée (Apprentissage direct avec n'importe quelle touche de télécommande)**
  - Actions disponibles pour chaque touche :
    - `🌙 Minuteur de Veille` (QuickSleepTimer)
    - `🚀 Lancer une application TV` (avec sélecteur complet incluant les icônes de toutes les applications TV installées : Crunchyroll, YouTube, Deezer, VLC, etc.)
    - `❌ Désactivé` (ne rien faire / comportement système)
* **Support Simultané Multi-Boutons :**
  - Possibilité d'assigner par exemple la touche **Netflix** au lancement direct de **Crunchyroll**, tout en conservant la touche **Live TV** ou **Étoile** pour piloter le minuteur de veille.
* **Note Technique sur la Touche Live TV (Xiaomi Mi Box / Mi TV) :**
  - Sur les appareils Xiaomi, le firmware intercepte la touche physique Live TV (`KEYCODE_TV` / 170) au niveau matériel très bas (`PhoneWindowManager.interceptKeyBeforeQueueing`) et force le démarrage direct de l'application *Live Channels* (`com.google.android.tv`).
  - Bien que QuickSleepTimer injecte un bouclier furtif et un signal retour arrière automatique pour étouffer cette fenêtre, un bref flash bleu de l'écran Live peut parfois rester perceptible lors de l'appel du minuteur.
  - **Recommandation :** L'assignation de la touche Live TV au **lancement direct d'une autre application** (Crunchyroll, etc.) fonctionne beaucoup plus proprement et harmonieusement, car la nouvelle application prend immédiatement et naturellement le relais sur Live Channels. Pour le déclenchement du minuteur sans aucun artefact visuel, l'utilisation de la touche Netflix, de la touche Étoile (★) ou d'une touche personnalisée est recommandée.

### 📺 Défilement Centré Intelligent Spécial TV (`TvScrollView`)
* **Anticipation & Centrage Automatique du Focus :**
  - Remplacement du défilement standard Android (qui attendait que les boutons touchent le bord de l'écran pour défiler) par un composant sur-mesure `TvScrollView`.
  - Calcul dynamique en temps réel (`computeScrollDeltaToGetChildRectOnScreen`) qui aligne systématiquement le centre de l'élément sélectionné avec le centre vertical de l'écran de télévision.
  - Les boutons actifs ne se retrouvent plus jamais collés ou coupés contre le cadre supérieur ou inférieur de la télévision.
* **Marge de Respiration Basse Étendue :**
  - Augmentation du rembourrage inférieur à `160dp` pour permettre aux derniers boutons d'action d'être confortablement centrés sans heurter le bas de l'écran.

---

## [1.3.1] - 2026-10-25

### 🔧 Ajustements Visuels & Finitions Haute Précision
* **Éradication du rognage des boutons au focus :**
  - Ajout de `clipChildren="false"` et `clipToPadding="false"` sur tous les conteneurs parents (ScrollView, cartes, colonnes et lignes).
  - Déclippage dynamique récursif des ancêtres dans `applyTvFocusScale` pour garantir que les arrondis gauche et droite ne soient jamais coupés net.
  - Recalibrage de l'échelle à `1.02x` combinée à une élévation 3D (`translationZ = 12dp`) et une surbrillance à bordure blanche lumineuse de 2.5dp.
* **Noir Profond OLED Absolu (`#000000`) & Verre Fumé Translucide :**
  - Fond d'écran passé en noir pur OLED (`#000000`) avec halo d'aurore nocturne subtil au sommet.
  - Cartes habillées d'un dégradé de verre fumé (`#1A1E2B` vers `#0B0C12`) avec biseau translucide à 15% d'opacité.
* **Harmonisation Intégrale des Rayons de Courbure :**
  - Remplacement du style pilule `999dp` inadapté sur le texte multi-lignes du statut Google TV par la nouvelle sous-carte biseautée à coins `14dp` (`bg_apple_subcard`).
  - Standardisation absolue : `18dp` pour les grandes cartes, `14dp` pour les sous-conteneurs et messages, `12dp` pour les boutons/chips, et `999dp` exclusivement pour les micro-badges mono-ligne.
* **Boutons Toggle Émeraude Lumineux :**
  - Boutons `ACTIVÉ ✓` habillés d'un fond émeraude profond avec contour vert fluo et texte contrasté.

---

## [1.3.0] - 2026-10-25

### 🎨 Refonte Complète UI/UX : Style Apple tvOS Dark & Store-Ready
* **Ambiance Sombre & Reposante OLED (`#08090C`) :**
  - Éradication des couleurs criardes au profit d'une palette nocturne feutrée et apaisante pour les yeux dans la chambre.
  - Cartes modulaires Inset Grouped en verre fumé (`#151821`) avec bordures biseautées ultra-fines (`1px rgba(255,255,255,0.10)`).
* **Micro-Animations D-Pad Signature tvOS (Focus Scale `1.04x`) :**
  - Chaque bouton, capsule ou sélecteur grossit dynamiquement de 4% avec une élévation et un contour argenté doux lors de la navigation avec la télécommande.
  - Rendu haptique fluide et réactif via un interpolateur de décélération doux.
* **Pictogrammes Vectoriels Modernes Épurés (Style SF Symbols) :**
  - Nouvelles icônes vectorielles harmonisées à trait fin 2dp : lune stylisée, chronomètre, atténuateur d'écran, haut-parleur nocturne, télécommande Apple-like et écusson de sécurité Play Protect.
* **Sélecteurs Segmentés & Badges d'État Flottants :**
  - Remplacement des boutons bruts par des sélecteurs segmentés pour les paliers (`10 min`, `+10 min`, `60 min`).
  - Badges d'état dynamiques en forme de pilule (`Actif (OK)` vert émeraude doux, `Requis` ambre feutré).
  - Pastille HUD flottante modernisée en verre fumé avec police compacte.

---

## [1.2.2] - 2026-10-25

### ⚡ Performances & Zéro Pause Multimédia (Crunchyroll / ExoPlayer)
* **Pulse 0 Immédiat à 0 ms (`SetupActivity.onCreate`) :**
  - Dès la première milliseconde d'entrée dans le cycle de vie de `SetupActivity`, un événement direct `KEYCODE_MEDIA_PLAY` est injecté via `AudioManager.dispatchMediaKeyEvent`.
  - Annule instantanément toute intention de mise en pause du lecteur vidéo sous-jacent (Crunchyroll / ExoPlayer) avant même l'initialisation des vues.
* **Fenêtre Fantôme 1x1 Pixel (`FLAG_NOT_FOCUSABLE` & `FLAG_NOT_TOUCHABLE`) :**
  - Lors du déclenchement rapide par raccourci télécommande, la fenêtre système est restreinte à une taille microscopique de 1x1 px positionnée à l'origine `(0, 0)`.
  - Android WindowManager ne retire jamais le focus graphique de la vidéo en cours de lecture.
* **Séquence Haute Fréquence de Réactivation Sonore/Vidéo :**
  - Remplacement de l'ancien délai artificiel de 100 ms par une séquence d'impulsions à cadence ultra-rapide : Pulse 1 à 0 ms, Pulse 2 à +35 ms, Pulse 3 à +110 ms, Pulse 4 à +230 ms.
  - La micro-coupure audio/vidéo perçue passe d'environ 200–250 ms à un niveau quasi imperceptible (15–30 ms).
* **Interface Réglages Plein Écran Garantie :**
  - Préservation du plein écran immersif 1920x1080 sans encadrement de dialogue quand les paramètres sont ouverts depuis l'écran d'accueil.

---

## [1.2.1] - 2026-10-25

### ✨ Améliorations & Corrections Audio
* **Détection du statut HDMI de contrôle du volume (IR vs Numérique) :**
  - Ajout d'un indicateur temps réel dans les paramètres vérifiant si la sortie HDMI est en mode fixe (Infrarouge direct vers la TV) ou variable (`Google TV Streamer`).
  - Alerte explicative guidant l'utilisateur pour activer le contrôle logiciel du volume dans les réglages système de Google TV.
* **Piste musicale de test intégrée — Casse-Noisette (Tchaïkovski) :**
  - Intégration du thème de *La Danse de la Fée Dragée* de Piotr Ilitch Tchaïkovski (1892), dans le domaine public mondial.
  - Synthèse acoustique numérique haute précision du timbre de la célesta et des cordes en pizzicato, sans aucun droit d'interprétation tiers et avec un poids plume dans l'APK.
* **Simulation de baisse de volume sur 30 secondes avec arrêt interactif :**
  - La simulation passe à 30 secondes complètes pour apprécier pleinement le fondu progressif vers zéro.
  - Bascule dynamique du bouton en `ARRÊTER LE TEST DU VOLUME ⏹` (avec conservation automatique du focus D-Pad).
  - Possibilité d'interrompre le test à tout moment via un second appui ou via la touche `Retour (Back)` de la télécommande, restaurant instantanément le volume initial.

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
