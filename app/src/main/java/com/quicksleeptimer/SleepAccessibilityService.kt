package com.quicksleeptimer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SleepAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private lateinit var prefsManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager.getInstance(this)
        instance = this
        Log.d(TAG, "SleepAccessibilityService created")

        serviceScope.launch {
            prefsManager.triggerTargetFlow.collect {
                updateKeyFilterFlags()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "SleepAccessibilityService connected and ready")

        updateKeyFilterFlags()

        lastForegroundPackage = getActivePackage()
        if (lastLegitimatePackage.isNullOrEmpty() && !isLiveTvPackage(lastForegroundPackage ?: "") && !isNetflixPackage(lastForegroundPackage ?: "")) {
            lastLegitimatePackage = lastForegroundPackage
        }
    }

    fun updateKeyFilterFlags() {
        val currentFlags = (serviceInfo?.flags ?: 0)
        val newFlags = currentFlags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = newFlags
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        Log.d(TAG, "updateKeyFilterFlags: flags=$newFlags")
    }

    private var lastInterceptTime: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val className = event.className?.toString()

            val isTargetOrAux = isLiveTvPackage(pkg) || isNetflixPackage(pkg) || isAuxiliaryDialog(pkg, className)

            if (pkg.isNotEmpty() && pkg != packageName && pkg != "android") {
                lastForegroundPackage = pkg
                if (!isTargetOrAux) {
                    lastLegitimatePackage = pkg
                    Log.d(TAG, "Foreground window package changed to legitimate app: $pkg")
                } else {
                    Log.d(TAG, "Target or auxiliary window detected: $pkg ($className)")
                }
            }

            // 1. Learning Mode : If user presses a hardcoded button (Live or Netflix) that forces app launch
            if (isListeningForKeyAssignation) {
                if (isLiveTvPackage(pkg)) {
                    Log.d(TAG, "Live TV app ($pkg) launched during key assignation -> Assigning TRIGGER_LIVE")
                    isListeningForKeyAssignation = false
                    dismissInterceptedApp(pkg, packageName)
                    serviceScope.launch {
                        prefsManager.setTriggerTarget(PreferencesManager.TRIGGER_LIVE)
                    }
                    keyAssignListener?.onAppTriggerAssigned("Touche Live TV", PreferencesManager.TRIGGER_LIVE)
                    return
                } else if (isNetflixPackage(pkg)) {
                    Log.d(TAG, "Netflix app ($pkg) launched during key assignation -> Assigning TRIGGER_NETFLIX")
                    isListeningForKeyAssignation = false
                    dismissInterceptedApp(pkg, packageName)
                    serviceScope.launch {
                        prefsManager.setTriggerTarget(PreferencesManager.TRIGGER_NETFLIX)
                    }
                    keyAssignListener?.onAppTriggerAssigned("Touche Netflix", PreferencesManager.TRIGGER_NETFLIX)
                    return
                }
            }

            // 2. Autonomous Runtime Interception of hardcoded buttons (Live / Netflix)
            val currentTarget = prefsManager.cachedTriggerTarget
            val shouldIntercept = (currentTarget == PreferencesManager.TRIGGER_LIVE && isLiveTvPackage(pkg)) ||
                    (currentTarget == PreferencesManager.TRIGGER_NETFLIX && isNetflixPackage(pkg))

            if (shouldIntercept) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastInterceptTime > 400L) {
                    lastInterceptTime = now
                    val targetAppToRestore = lastLegitimatePackage
                    Log.d(TAG, "Autonomous interception of $pkg ($currentTarget)! Restoring: $targetAppToRestore and triggering Sleep Timer")
                    
                    // Immediately trigger Sleep Timer
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val wasPlaying = audioManager?.isMusicActive ?: false
                    TimerService.startOrIncrement(
                        applicationContext,
                        wasMusicActive = wasPlaying || (!targetAppToRestore.isNullOrEmpty() && !isLauncherOrSettings(targetAppToRestore))
                    )

                    // Multi-stage dismissal and window restore
                    dismissInterceptedApp(pkg, targetAppToRestore)
                }
            }
        }
    }

    private fun dismissInterceptedApp(targetPkg: String, appToRestore: String?) {
        serviceScope.launch {
            Log.d(TAG, "dismissInterceptedApp: targetPkg=$targetPkg, restore=$appToRestore")

            // Phase 1 (0ms): Dismiss modal / back out
            performGlobalAction(GLOBAL_ACTION_BACK)

            // Phase 2 (80ms): Restore previous legitimate app or Projectivy
            kotlinx.coroutines.delay(80)
            if (!appToRestore.isNullOrEmpty() && appToRestore != "com.google.android.tvlauncher") {
                restoreAppToFront(appToRestore)
            } else {
                restoreProjectivyOrHome()
            }

            // Phase 3 (200ms): If target still active, dismiss again and restore
            kotlinx.coroutines.delay(120)
            val currentPkg = getActivePackage()
            if (isLiveTvPackage(currentPkg ?: "") || isNetflixPackage(currentPkg ?: "") || currentPkg == "com.google.android.gms") {
                Log.w(TAG, "Target still active ($currentPkg) at 200ms, forcing BACK + restore")
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (!appToRestore.isNullOrEmpty() && appToRestore != "com.google.android.tvlauncher") {
                    restoreAppToFront(appToRestore)
                } else {
                    restoreProjectivyOrHome()
                }
            }

            // Phase 4 (400ms): Target dismissal complete
            kotlinx.coroutines.delay(200)
            Log.d(TAG, "dismissInterceptedApp completed")
        }
    }

    private fun restoreProjectivyOrHome() {
        val projIntent = packageManager.getLaunchIntentForPackage("com.spocky.projengmenu")?.apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (projIntent != null) {
            startActivity(projIntent)
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun restoreAppToFront(pkg: String) {
        try {
            if (pkg == "com.google.android.tvlauncher") {
                restoreProjectivyOrHome()
                return
            }
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                restoreProjectivyOrHome()
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreAppToFront failed for $pkg: ${e.message}")
            restoreProjectivyOrHome()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "SleepAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        keyAssignListener = null
        isListeningForKeyAssignation = false
        Log.d(TAG, "SleepAccessibilityService destroyed")
    }

    // ==========================================
    // NATIVE KEY INTERCEPTION (ZERO ACTIVITY / ZERO PAUSE)
    // ==========================================

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scanCode
        val action = event.action

        Log.d(TAG, "onKeyEvent INCOMING: keyCode=$keyCode, scanCode=$scanCode, action=$action, target=${prefsManager.cachedTriggerTarget}")

        // 0. ABSOLUTE CRITICAL SHORT-CIRCUIT:
        // Never delay, touch, or consume HOME key (Scancode 172 on Xiaomi Mi Box)
        // Passes straight through to Projectivy Launcher in < 0.01ms
        if (keyCode in HOME_KEYCODES || scanCode in HOME_SCANCODES) {
            return false
        }

        // 1. Key Learning / Assignation Mode (from SetupActivity)
        if (isListeningForKeyAssignation) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    Log.d(TAG, "BACK key pressed during assignation -> Cancel listening")
                    isListeningForKeyAssignation = false
                    keyAssignListener?.onKeyListeningCancelled()
                    return true
                } else if (isForbiddenKey(keyCode)) {
                    Log.w(TAG, "Forbidden key ignored during assignation: keyCode=$keyCode, scanCode=$scanCode")
                    keyAssignListener?.onForbiddenKeyDetected(keyCode)
                    return true // Consume so it doesn't navigate away
                } else {
                    Log.d(TAG, "Valid key assigned: keyCode=$keyCode, scanCode=$scanCode (${getFriendlyKeyName(keyCode, scanCode)})")
                    isListeningForKeyAssignation = false
                    serviceScope.launch {
                        prefsManager.setTriggerTarget(PreferencesManager.TRIGGER_CUSTOM)
                        prefsManager.setAssignedKey(keyCode, scanCode)
                    }
                    keyAssignListener?.onKeyAssigned(keyCode, scanCode)
                    return true // Consume event
                }
            }
            return true
        }

        // 2. Hardware Native Trigger Interception (Zero Launch / Zero Pause / Zero Glitch)
        val triggerTarget = prefsManager.cachedTriggerTarget
        val assignedKey = prefsManager.cachedAssignedKeyCode
        val assignedScan = prefsManager.cachedAssignedScanCode

        val isTargetKey = when (triggerTarget) {
            PreferencesManager.TRIGGER_LIVE -> {
                (keyCode in LIVE_KEYCODES || scanCode in LIVE_SCANCODES)
            }
            PreferencesManager.TRIGGER_NETFLIX -> {
                (keyCode in NETFLIX_KEYCODES || scanCode in NETFLIX_SCANCODES)
            }
            PreferencesManager.TRIGGER_XIAOMI_ALL -> {
                (keyCode in XIAOMI_ALL_KEYCODES || scanCode in XIAOMI_ALL_SCANCODES)
            }
            PreferencesManager.TRIGGER_CUSTOM -> {
                (assignedKey > 0 && keyCode == assignedKey) || (assignedScan > 0 && scanCode == assignedScan)
            }
            else -> {
                // TRIGGER_STAR (Google TV Streamer Star button is keyCode 313)
                (keyCode == KEYCODE_MACRO_1 || keyCode == 313) ||
                (assignedKey > 0 && keyCode == assignedKey) ||
                (assignedScan > 0 && scanCode == assignedScan)
            }
        }

        if (isTargetKey) {
            if (action == KeyEvent.ACTION_DOWN) {
                val activePkg = getActivePackage()
                val isOnLauncher = isLauncherOrSettings(activePkg)
                Log.d(TAG, "Hardware key intercepted: keyCode=$keyCode, scanCode=$scanCode (${getFriendlyKeyName(keyCode, scanCode)}), isOnLauncher=$isOnLauncher (pkg=$activePkg)")

                if (isOnLauncher) {
                    // User is on Launcher (Projectivy, TV Launcher, etc.) or Settings -> Open QuickSleepTimer Settings!
                    Log.d(TAG, "Target key on launcher -> Opening SetupActivity")
                    val setupIntent = Intent(applicationContext, SetupActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("open_settings", true)
                    }
                    startActivity(setupIntent)
                } else {
                    // User is inside an app (Crunchyroll, VLC, SmartTube, etc.) -> Trigger or increment Sleep Timer!
                    Log.d(TAG, "Target key in app -> Triggering Sleep Timer HUD overlay")
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val wasPlaying = audioManager?.isMusicActive ?: false
                    TimerService.startOrIncrement(
                        applicationContext,
                        wasMusicActive = wasPlaying || !activePkg.isNullOrEmpty()
                    )
                }
            }
            // Consume BOTH ACTION_DOWN and ACTION_UP completely, identical to Button Mapper's pref_alt_buttonmethod!
            return true
        }

        return super.onKeyEvent(event)
    }

    companion object {
        private const val TAG = "QuickSleep_A11y"
        const val KEYCODE_MACRO_1 = 313 // Google TV Streamer 4K Star (★) button

        @Volatile
        var instance: SleepAccessibilityService? = null
            private set

        @Volatile
        var lastForegroundPackage: String? = null
            private set

        @Volatile
        var lastLegitimatePackage: String? = null
            private set

        fun getActivePackage(): String? {
            try {
                val root = instance?.rootInActiveWindow
                val pkg = root?.packageName?.toString()
                if (!pkg.isNullOrEmpty() && pkg != "com.quicksleeptimer" && pkg != "android") {
                    lastForegroundPackage = pkg
                    if (!isLiveTvPackage(pkg) && !isNetflixPackage(pkg) && pkg != "com.google.android.gms") {
                        lastLegitimatePackage = pkg
                    }
                    return pkg
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying rootInActiveWindow: ${e.message}")
            }
            return lastForegroundPackage
        }

        fun isLauncherOrSettings(pkg: String?): Boolean {
            if (pkg.isNullOrEmpty()) return false
            return pkg == "com.spocky.projengmenu" ||
                    pkg == "com.google.android.apps.tv.launcherx" ||
                    pkg == "com.google.android.tvlauncher" ||
                    pkg == "com.android.tv.launcher" ||
                    pkg == "com.android.tv.settings" ||
                    pkg == "com.google.tv.settings.chromecast" ||
                    pkg == "com.amazon.firehomelauncher" ||
                    pkg.contains("launcher", ignoreCase = true) ||
                    pkg.contains("settings", ignoreCase = true)
        }

        @Volatile
        var isListeningForKeyAssignation: Boolean = false
            set(value) {
                field = value
                instance?.updateKeyFilterFlags()
            }

        var keyAssignListener: KeyAssignListener? = null

        val HOME_KEYCODES = setOf(KeyEvent.KEYCODE_HOME, 3)
        val HOME_SCANCODES = setOf(172)

        interface KeyAssignListener {
            fun onKeyAssigned(keyCode: Int, scanCode: Int = 0)
            fun onAppTriggerAssigned(label: String, target: String)
            fun onForbiddenKeyDetected(keyCode: Int)
            fun onKeyListeningCancelled()
        }

        val LIVE_SCANCODES = setOf(580, 170, 171, 173, 183, 401)
        val LIVE_KEYCODES = setOf(
            KeyEvent.KEYCODE_TV,          // 170
            KeyEvent.KEYCODE_WINDOW,      // 171
            KeyEvent.KEYCODE_DVR,         // 173
            KeyEvent.KEYCODE_ALL_APPS,    // 284
            170, 171, 173, 183, 284
        )

        val NETFLIX_SCANCODES = setOf(393)
        val NETFLIX_KEYCODES = setOf(
            1001,                         // Xiaomi / Amlogic vendor Netflix key
            KeyEvent.KEYCODE_BUTTON_3,    // 98
            KeyEvent.KEYCODE_BUTTON_4,    // 99
            KeyEvent.KEYCODE_APP_SWITCH,  // 187
            98, 99, 187
        )

        // All Xiaomi remote dedicated shortcut buttons:
        // Live/Apps (284/580), Netflix (1001/393), Prime Video (97/399), YouTube (99/400), Mi/Apps (98/401)
        val XIAOMI_ALL_KEYCODES = LIVE_KEYCODES + NETFLIX_KEYCODES + setOf(97, 98, 99, 1001)
        val XIAOMI_ALL_SCANCODES = LIVE_SCANCODES + NETFLIX_SCANCODES + setOf(393, 399, 400, 401, 580)

        fun isLiveTvPackage(pkg: String): Boolean {
            if (pkg.isEmpty()) return false
            return pkg == "com.google.android.tv" ||
                    pkg == "com.mitv.tvhome.michannel" ||
                    pkg == "com.mitv.tvhome.atv" ||
                    pkg == "com.android.tv" ||
                    pkg.contains("livechannels", ignoreCase = true) ||
                    pkg.contains("tvhome", ignoreCase = true) ||
                    pkg.contains("michannel", ignoreCase = true)
        }

        fun isNetflixPackage(pkg: String): Boolean {
            if (pkg.isEmpty()) return false
            return pkg == "com.netflix.ninja" || pkg.contains("netflix", ignoreCase = true)
        }

        fun isAuxiliaryDialog(pkg: String, className: String?): Boolean {
            if (pkg == "com.google.android.gms" && className?.contains("assistedsignin", ignoreCase = true) == true) {
                return true
            }
            return false
        }

        // System-critical keys protected from assignment (Ajustement 1)
        val FORBIDDEN_KEYCODES = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE
        )

        fun isForbiddenKey(keyCode: Int): Boolean {
            return keyCode in FORBIDDEN_KEYCODES
        }

        fun getFriendlyKeyName(keyCode: Int, scanCode: Int = 0): String {
            if (scanCode == 393 || keyCode == 1001) return "Bouton Netflix (1001/393)"
            if (scanCode == 399 || keyCode == 97) return "Bouton Prime Video (97/399)"
            if (scanCode == 400 || keyCode == 99) return "Bouton YouTube (99/400)"
            if (scanCode == 401 || keyCode == 98) return "Bouton Apps Xiaomi (98/401)"
            if (scanCode == 580 || keyCode == 284) return "Bouton Live / Apps (284/580)"

            return when (keyCode) {
                KEYCODE_MACRO_1, 313 -> "Bouton Étoile (★)"
                KeyEvent.KEYCODE_TV, 170 -> "Touche Live / TV"
                KeyEvent.KEYCODE_GUIDE -> "Touche Guide / EPG"
                KeyEvent.KEYCODE_DVR, 173 -> "Touche DVR / Enregistrement"
                KeyEvent.KEYCODE_WINDOW, 171 -> "Touche Live / TV Window"
                KeyEvent.KEYCODE_ALL_APPS, 284 -> "Touche Apps / Live"
                KeyEvent.KEYCODE_BUTTON_1, 96 -> "Bouton Personnalisé 1"
                KeyEvent.KEYCODE_BUTTON_2, 97 -> "Bouton Prime Video"
                KeyEvent.KEYCODE_BUTTON_3, 98 -> "Bouton Netflix / Apps"
                KeyEvent.KEYCODE_BUTTON_4, 99 -> "Bouton YouTube / VOD"
                KeyEvent.KEYCODE_PROG_RED -> "Touche Rouge (Raccourci TV)"
                KeyEvent.KEYCODE_PROG_GREEN -> "Touche Verte (Raccourci TV)"
                KeyEvent.KEYCODE_PROG_YELLOW -> "Touche Jaune (Raccourci TV)"
                KeyEvent.KEYCODE_PROG_BLUE -> "Touche Bleue (Raccourci TV)"
                KeyEvent.KEYCODE_CAPTIONS -> "Touche Sous-titres"
                KeyEvent.KEYCODE_INFO -> "Touche Info"
                KeyEvent.KEYCODE_SEARCH -> "Touche Recherche"
                else -> {
                    if (scanCode > 0 && (keyCode <= 0 || keyCode == KeyEvent.KEYCODE_UNKNOWN)) {
                        "Touche Scancode $scanCode"
                    } else {
                        val raw = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                        if (raw.startsWith("Unknown", ignoreCase = true)) {
                            if (scanCode > 0) "Touche (Code $keyCode, Scan $scanCode)" else "Touche $keyCode"
                        } else {
                            raw
                        }
                    }
                }
            }
        }

        fun lockScreen(): Boolean {
            val service = instance
            if (service != null) {
                Log.d(TAG, "Calling performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)")
                return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                Log.e(TAG, "SleepAccessibilityService instance is null! Cannot lock screen.")
                return false
            }
        }

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, SleepAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }
}
