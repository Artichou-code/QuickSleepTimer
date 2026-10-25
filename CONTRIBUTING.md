# Guide de Contribution / Contributing Guide

[Français](#français) | [English](#english)

---

<a name="français"></a>
## 🇫🇷 Français

Merci de votre intérêt pour **Quick Sleep Timer for Android TV** ! Les contributions (corrections de bugs, nouvelles fonctionnalités, compatibilité avec de nouvelles télécommandes ou traductions) sont les bienvenues.

### Comment Contribuer

1. **Signaler un bug ou proposer une idée :**
   - Ouvrez une *Issue* sur GitHub pour discuter du changement avant d'engager un gros développement.
2. **Développement local :**
   - Forkez le dépôt et clonez-le en local.
   - Ouvrez le projet dans **Android Studio** (Koala / Ladybug ou version plus récente).
   - Compilez avec Gradle : `./gradlew assembleDebug`.
   - Testez vos modifications sur un boîtier Android TV / Google TV réel via ADB : `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. **Bonnes pratiques :**
   - Respectez les standards Kotlin et l'architecture du projet.
   - Ne modifiez jamais le cycle de vie sans vérifier l'impact sur la lecture vidéo (l'application doit conserver la règle d'or : **zéro pause multimédia sur Netflix, YouTube, Crunchyroll, etc.**).
   - N'ajoutez aucune dépendance de tracking ou de réseau (l'application ne doit requérir **aucune permission Internet**).

---

### Contributor License Agreement (CLA)

> [!IMPORTANT]
> En soumettant une Pull Request, un patch ou toute autre forme de contribution à ce dépôt, vous acceptez expressément les termes du Contributor License Agreement (CLA) ci-dessous.

**Accord de Licence Contributeur (CLA) :**
> *"En soumettant une Pull Request ou une contribution à ce dépôt, vous acceptez de concéder à l'auteur principal (**Artem Tchaikovski / ART-créa**) une licence mondiale, perpétuelle, irrévocable, gratuite et libre de droits lui permettant d'utiliser, modifier, adapter, sous-licencier et distribuer votre contribution à la fois sous licence open source (GNU GPL v3) et sous toute licence propriétaire ou commerciale (notamment pour sa distribution sur le Google Play Store et autres stores d'applications officiels)."*

Ce modèle de double licence (*Dual Licensing*) garantit :
1. Que le code source sur ce dépôt public reste éternellement libre et open-source sous licence **GNU GPL v3**.
2. Que l'auteur principal conserve la pleine capacité légale de distribuer, maintenir et publier l'application sur les canaux officiels comme le Google Play Store sans blocage de droits d'auteur tiers.

Si vous n'acceptez pas ces conditions, veuillez ne pas soumettre de code à ce dépôt.

---

<a name="english"></a>
## 🇬🇧 English

Thank you for your interest in **Quick Sleep Timer for Android TV**! Contributions such as bug fixes, new features, device/remote compatibility improvements, and translations are very welcome.

### How to Contribute

1. **Reporting Issues & Proposing Features:**
   - Please open an *Issue* on GitHub before starting major work to ensure alignment with project goals.
2. **Local Development:**
   - Fork the repository and clone your fork locally.
   - Open in **Android Studio** (Koala / Ladybug or newer).
   - Build with Gradle: `./gradlew assembleDebug`.
   - Test on an actual Android TV or Google TV device via ADB: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. **Guidelines:**
   - Adhere to idiomatic Kotlin practices.
   - Preserve the core UX promise: **Zero playback interruption** (video playback on Crunchyroll, YouTube, Netflix, Spotify must never pause when the sleep timer is triggered).
   - **Zero network dependencies**: The app must remain 100% offline with no `INTERNET` permission and zero telemetry.

---

### Contributor License Agreement (CLA)

> [!IMPORTANT]
> By submitting a Pull Request, patch, or any other contribution to this repository, you explicitly agree to the Contributor License Agreement (CLA) terms below.

**Contributor License Agreement (CLA):**
> *"By submitting a Pull Request or any contribution to this repository, you grant the primary author (**Artem Tchaikovski / ART-créa**) a worldwide, perpetual, irrevocable, royalty-free, and fully sublicensable license to use, modify, adapt, sublicense, and distribute your contribution both under the project's open source license (GNU GPL v3) and under any proprietary or commercial license (including but not limited to distribution on the Google Play Store and other official app marketplaces)."*

This dual-licensing framework guarantees:
1. That the open-source community edition on GitHub remains permanently free and accessible under the **GNU General Public License v3.0**.
2. That the original project creator retains the legal rights necessary to publish, package, and maintain the app on official marketplaces such as Google Play without licensing impediments.

If you do not agree to these terms, please do not submit contributions to this repository.
