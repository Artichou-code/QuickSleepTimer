# Quick Sleep Timer for Android TV & Google TV 🌙

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-00875A.svg)](https://android.com/tv/)
[![API](https://img.shields.io/badge/API-28%2B%20%28Android%209%20to%2014%2B%29-green.svg)](#)
[![Version](https://img.shields.io/badge/Version-1.3.0-blueviolet.svg)](https://github.com/Artichou-code/QuickSleepTimer/releases)
[![No Internet Permission](https://img.shields.io/badge/Privacy-100%25%20Offline%20%28No%20Internet%29-brightgreen.svg)](#transparence--vie-privée)

[Français](#-français) | [English](#-english)

---

<a name="-français"></a>
## 🇫🇷 Français

### 🎯 Le Pitch
Vous vous souvenez du fameux bouton **"Sleep"** des télécommandes cathodiques d'antan ? Un appui = 30 min, deux appuis = 60 min, et la télévision s'éteignait toute seule sans réveiller personne.

Sur les téléviseurs modernes **Android TV** et le **Google TV Streamer 4K**, cette fonction a disparu ou se retrouve enfouie au fin fond des menus paramètres. Pire encore, les applications de mise en veille tierces sur le Play Store sont souvent payantes ou polluées de bannières publicitaires et de pop-ups intrusifs.

**Quick Sleep Timer** réinvente le bouton Sleep avec une contrainte d'ingénierie absolue : **Zéro interruption multimédia**.
> Lorsque vous appuyez sur votre télécommande pour ajouter du temps, **Netflix, YouTube, Crunchyroll, Deezer ou Spotify ne se mettent JAMAIS en pause**. Pas d'écran noir, pas de flash, pas de coupure audio.

---

### ⚡ Fonctionnalités Clés

* **Zéro Interruption Vidéo (Anti-Pause) :**
  - Fenêtre 100% translucide (`android:windowIsTranslucent="true"`) et relance multi-pulse instantanée (`KEYCODE_MEDIA_PLAY`). Le lecteur vidéo continue de tourner en toute fluidité pendant que le timer s'active.
* **Double Comportement Intelligent :**
  - **Sur l'accueil TV (Launcher / Projectivy) :** Un appui sur le bouton Étoile (★) ouvre la page complète de configuration.
  - **Dans une application multimédia :** Un appui lance ou incrémente le minuteur avec la pastille HUD sans quitter la vidéo.
* **Paliers Configurables à la Volée :**
  - 1er appui = durée initiale (ex: 20 min).
  - Appuis suivants = incrément (ex: +10 min par appui).
  - Fenêtre glissante de 5 secondes : appuyez plusieurs fois d'affilée pour cumuler le temps souhaité.
* **Pastille HUD Flottante & Discrète :**
  - Indicateur visuel élégant en haut à droite indiquant le temps restant et l'ajout effectué.
* **Filtre de Nuit (Night Dimmer) Adaptatif :**
  - Permet d'assombrir l'écran au-delà du minimum matériel de la dalle TV pour protéger vos yeux dans le noir complet.
  - **Recul intelligent :** Si vous rajoutez du temps alors que l'écran est déjà sombre, la luminosité remonte doucement sur 1,5 seconde proportionnellement au temps ajouté, évitant de rester bloqué dans le noir ou de subir un flash blanc éblouissant.
* **Baisse de Volume Progressive (Audio Fade Out) :**
  - Diminue le volume sonore pas à pas vers zéro sur toute la durée ou sur les 5 / 10 dernières minutes. Le son remonte doucement si vous rajoutez du temps.
* **Extinction Automatique Matérielle :**
  - Verrouillage Android à l'échéance et extinction automatique de l'écran et des périphériques audio via le protocole HDMI-CEC.

---

### 📦 Guide d'Installation Facile (Sideloading)

#### 1. Télécharger l'APK
Téléchargez directement le fichier `QuickSleepTimer-v1.2.0.apk` depuis la [section Releases](https://github.com/Artichou-code/QuickSleepTimer/releases) :
* Soit via l'application **Downloader** sur votre TV.
* Soit en envoyant le fichier depuis votre smartphone / PC avec l'application **Send Files to TV**.

#### 2. Activer les Autorisations Requises
Au premier lancement, l'écran de configuration vous guide pas-à-pas pour activer 2 autorisations :
1. **Superposition d'écran (`SYSTEM_ALERT_WINDOW`) :** Indispensable pour afficher la pastille HUD discrète et le filtre de nuit.
2. **Service d'accessibilité (`SleepAccessibilityService`) :** Nécessaire pour éteindre l'écran à la fin du compte à rebours et intercepter la touche de raccourci.

> [!TIP]
> **Configuration du bouton personnalisable sur Google TV Streamer 4K :**
> Rendez-vous dans *Paramètres TV > Télécommandes et accessoires > Personnaliser le bouton (★)* > sélectionnez **Quick Sleep Timer**.

#### 3. Pour les Utilisateurs Avancés (ADB)
Si vous configurez votre TV via ADB :
```bash
# Installation de l'application
adb install -r QuickSleepTimer-v1.2.0.apk

# Accorder la permission de superposition d'écran
adb shell appops set com.quicksleeptimer SYSTEM_ALERT_WINDOW allow
```
> [!WARNING]
> Pour le service d'accessibilité, il est fortement recommandé de l'activer directement via l'interface TV (*Paramètres > Système > Accessibilité*). L'utilisation de `settings put secure enabled_accessibility_services` via ADB peut écraser vos autres services actifs (comme *Button Mapper* ou *Projectivy Launcher*).

---

### 🔒 Transparence & Vie Privée

* **AUCUNE PERMISSION INTERNET (`INTERNET`) :** L'application ne possède même pas le droit de se connecter au réseau dans son `AndroidManifest.xml`. Aucune donnée ne peut quitter votre téléviseur.
* **Zéro Télémétrie, Zéro Publicité :** Pas d'analyses Google Firebase, pas de trackers, pas d'achats intégrés.
* **Utilisation Justifiée des Permissions :**
  - `SYSTEM_ALERT_WINDOW` sert exclusivement à dessiner le calque HUD et le filtre noir.
  - `BIND_ACCESSIBILITY_SERVICE` sert uniquement à déclencher l'action native `GLOBAL_ACTION_LOCK_SCREEN` lors du sommeil et à écouter la touche matérielle configurée.

---

### ⚖️ Licence & Contributions

Ce projet est distribué sous licence libre **GNU General Public License v3.0 (GPLv3)**. Toute modification ou redistribution du code source par un tiers doit obligatoirement demeurer open-source sous licence GPLv3.

Les contributions sont les bienvenues ! En soumettant une Pull Request, vous acceptez les termes du **Contributor License Agreement (CLA)** décrit dans [CONTRIBUTING.md](CONTRIBUTING.md), concédant à l'auteur principal (**Artem Tchaikovski / ART-créa**) le droit d'exploiter commercialement et de distribuer l'application sur les stores officiels (notamment le Google Play Store).

---

<a name="-english"></a>
## 🇬🇧 English

### 🎯 The Pitch
Remember the dedicated **"Sleep"** button on classic TV remotes? One click = 30 min, two clicks = 60 min, and your TV turned off automatically without waking anyone up.

On modern **Android TV** and **Google TV Streamer 4K** devices, this simple feature is gone or buried deep within complex system sub-menus. Existing Play Store alternatives are often subscription-based or plagued with annoying fullscreen ads and pop-ups.

**Quick Sleep Timer** brings back the ultimate sleep timer with a strict engineering requirement: **Zero playback interruption**.
> When you press your remote button to set or extend the timer, **Netflix, YouTube, Crunchyroll, Deezer, or Spotify will NEVER pause**. No black screen, no UI freeze, no audio dropouts.

---

### ⚡ Key Features

* **Zero Media Interruption (Anti-Pause):**
  - Fully translucent window architecture (`android:windowIsTranslucent="true"`) coupled with instant multi-pulse playback resume (`KEYCODE_MEDIA_PLAY`). Your movies and audio keep streaming seamlessly.
* **Smart Dual-Context Triggering:**
  - **On Home Launcher (Google TV / Projectivy):** Pressing the Star (★) button opens the complete settings screen.
  - **Inside an App (Crunchyroll, Netflix, YouTube):** Pressing Star immediately starts or adds time to the timer with the sleek HUD pill, without opening settings or leaving your video.
* **Configurable Increments on the Fly:**
  - 1st press = initial duration (e.g., 20 min).
  - Subsequent presses = configurable step (e.g., +10 min per click).
  - 5-second sliding window for rapid consecutive clicks.
* **Minimalist Floating HUD Pill:**
  - Elegant top-right animation showing remaining time and the newly added interval.
* **Adaptive Night Dimmer:**
  - Software overlay allowing you to dim the screen below the TV hardware brightness minimum.
  - **Smart Rollback:** Adding time when the screen is dark smoothly eases brightness back up over 1.5s proportionally to the added time, preventing both pitch-black lockups and blinding white flashes at night.
* **Progressive Volume Fade Out:**
  - Gently decreases audio volume down to zero across the timer duration or over the final 5 / 10 minutes.
* **Hardware Sleep via HDMI-CEC:**
  - Native global lock triggers TV and connected soundbar standby via HDMI-CEC.

---

### 📦 Installation Guide (Sideloading)

#### 1. Download the APK
Download `QuickSleepTimer-v1.2.0.apk` from the [Releases section](https://github.com/Artichou-code/QuickSleepTimer/releases):
* Directly on your TV via the **Downloader** app.
* Or transferred from your phone/PC using **Send Files to TV**.

#### 2. Grant Permissions
On first launch, the setup UI guides you through granting 2 TV permissions:
1. **Display over other apps (`SYSTEM_ALERT_WINDOW`):** Required for the HUD pill and night dimmer.
2. **Accessibility Service (`SleepAccessibilityService`):** Required for global sleep action and remote shortcut interception.

#### 3. Advanced Users (ADB)
```bash
adb install -r QuickSleepTimer-v1.2.0.apk
adb shell appops set com.quicksleeptimer SYSTEM_ALERT_WINDOW allow
```
*(Enable Accessibility via TV Settings to avoid overwriting existing accessibility services like Button Mapper).*

---

### 🔒 Privacy & Transparency

* **NO INTERNET PERMISSION (`INTERNET`):** The app does not declare network permissions. No data can ever leave your TV.
* **100% Offline & Ad-Free:** No analytics, no telemetry, no tracking.

---

### ⚖️ License & CLA

Distributed under the **GNU General Public License v3.0 (GPLv3)**. Any public derivative work must remain open source under GPLv3.

Pull Requests are welcomed under the **Contributor License Agreement (CLA)** detailed in [CONTRIBUTING.md](CONTRIBUTING.md), allowing the author (**Artem Tchaikovski / ART-créa**) to distribute official builds on commercial app marketplaces (Google Play Store).
