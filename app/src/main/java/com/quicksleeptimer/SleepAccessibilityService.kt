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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "SleepAccessibilityService connected and ready")

        // Ensure key event filtering and window inspection are enabled
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        } ?: AccessibilityServiceInfo().apply {
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        lastForegroundPackage = getActivePackage()
        if (lastLegitimatePackage.isNullOrEmpty() && !isLiveTvPackage(lastForegroundPackage ?: "") && !isNetflixPackage(lastForegroundPackage ?: "")) {
            lastLegitimatePackage = lastForegroundPackage
        }
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
            val isTargetLauncher = isLauncherOrSettings(appToRestore)
            Log.d(TAG, "dismissInterceptedApp: targetPkg=$targetPkg, restore=$appToRestore (isLauncher=$isTargetLauncher)")

            // Phase 1 (0ms): Always force HOME immediately to banish full-screen TV apps (Live TV / Netflix)
            performGlobalAction(GLOBAL_ACTION_HOME)

            // If on launcher or dialog, BACK helps dismiss any prompt
            if (isTargetLauncher || appToRestore.isNullOrEmpty()) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            // Phase 2 (120ms): If user was in a legitimate app (e.g. VLC, YouTube, Blaze), restore it to the front
            kotlinx.coroutines.delay(120)
            if (!isTargetLauncher && !appToRestore.isNullOrEmpty()) {
                restoreAppToFront(appToRestore)
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            // Phase 3 (260ms): Catch any lingering modal dialogs (e.g. Google assisted sign-in) or check active window
            kotlinx.coroutines.delay(140)
            val currentPkg = getActivePackage()
            if (isLiveTvPackage(currentPkg ?: "") || isNetflixPackage(currentPkg ?: "") || currentPkg == "com.google.android.gms") {
                Log.w(TAG, "Target still active ($currentPkg) at 260ms, forcing HOME + BACK")
                performGlobalAction(GLOBAL_ACTION_HOME)
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (!isTargetLauncher && !appToRestore.isNullOrEmpty()) {
                    kotlinx.coroutines.delay(100)
                    restoreAppToFront(appToRestore)
                }
            }

            // Phase 4 (450ms): Clean up background processes so it doesn't consume TV RAM / audio
            kotlinx.coroutines.delay(190)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                am?.killBackgroundProcesses(targetPkg)
            } catch (e: Exception) {
                Log.w(TAG, "killBackgroundProcesses failed: ${e.message}")
            }
        }
    }

    private fun restoreAppToFront(pkg: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreAppToFront failed for $pkg: ${e.message}")
            performGlobalAction(GLOBAL_ACTION_HOME)
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
        val action = event.action

        Log.d(TAG, "onKeyEvent: keyCode=$keyCode (${getFriendlyKeyName(keyCode)}), action=${if (action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}")

        // 1. Key Learning / Assignation Mode (from SetupActivity)
        if (isListeningForKeyAssignation) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    Log.d(TAG, "BACK key pressed during assignation -> Cancel listening")
                    isListeningForKeyAssignation = false
                    keyAssignListener?.onKeyListeningCancelled()
                    return true
                } else if (isForbiddenKey(keyCode)) {
                    Log.w(TAG, "Forbidden key ignored during assignation: $keyCode")
                    keyAssignListener?.onForbiddenKeyDetected(keyCode)
                    return true // Consume so it doesn't navigate away
                } else {
                    Log.d(TAG, "Valid key assigned: $keyCode (${getFriendlyKeyName(keyCode)})")
                    isListeningForKeyAssignation = false
                    serviceScope.launch {
                        prefsManager.setTriggerTarget(PreferencesManager.TRIGGER_CUSTOM)
                        prefsManager.setAssignedKeyCode(keyCode)
                    }
                    keyAssignListener?.onKeyAssigned(keyCode)
                    return true // Consume event
                }
            }
            return true
        }

        // 2. Regular Native Trigger Interception (100% Zero Activity / Zero Pause Guarantee)
        val triggerTarget = prefsManager.cachedTriggerTarget
        val assignedKey = prefsManager.cachedAssignedKeyCode

        val isTargetKey = when (triggerTarget) {
            PreferencesManager.TRIGGER_LIVE -> (keyCode in LIVE_KEYCODES)
            PreferencesManager.TRIGGER_NETFLIX -> (keyCode in NETFLIX_KEYCODES)
            PreferencesManager.TRIGGER_CUSTOM -> (assignedKey != PreferencesManager.DEFAULT_KEYCODE && keyCode == assignedKey)
            else -> (keyCode == KEYCODE_MACRO_1 || (assignedKey != PreferencesManager.DEFAULT_KEYCODE && keyCode == assignedKey))
        }

        if (isTargetKey) {
            if (action == KeyEvent.ACTION_DOWN) {
                val activePkg = getActivePackage()
                val isOnLauncher = isLauncherOrSettings(activePkg)

                if (isOnLauncher) {
                    Log.d(TAG, "Native key intercepted on launcher ($activePkg) -> Opening Settings Activity")
                    val setupIntent = Intent(applicationContext, SetupActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("open_settings", true)
                    }
                    startActivity(setupIntent)
                } else {
                    Log.d(TAG, "Native key intercepted in app ($activePkg) -> Triggering Sleep Timer with ZERO Activity")
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val wasPlaying = audioManager?.isMusicActive ?: false
                    TimerService.startOrIncrement(applicationContext, wasMusicActive = wasPlaying || !activePkg.isNullOrEmpty())
                }
            }
            return true // Consume event
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

        var keyAssignListener: KeyAssignListener? = null

        interface KeyAssignListener {
            fun onKeyAssigned(keyCode: Int)
            fun onAppTriggerAssigned(label: String, target: String)
            fun onForbiddenKeyDetected(keyCode: Int)
            fun onKeyListeningCancelled()
        }

        val LIVE_KEYCODES = setOf(
            KeyEvent.KEYCODE_TV,          // 170
            KeyEvent.KEYCODE_GUIDE,       // 172
            KeyEvent.KEYCODE_WINDOW,      // 171
            KeyEvent.KEYCODE_DVR,         // 173
            KeyEvent.KEYCODE_ALL_APPS,    // 284
            KeyEvent.KEYCODE_BUTTON_2,    // 97
            KeyEvent.KEYCODE_BUTTON_3,    // 98
            KeyEvent.KEYCODE_BUTTON_4,    // 99
            170, 171, 172, 173, 183, 284, 96, 97, 98, 99
        )

        val NETFLIX_KEYCODES = setOf(
            KeyEvent.KEYCODE_BUTTON_3,    // 98
            KeyEvent.KEYCODE_BUTTON_4,    // 99
            KeyEvent.KEYCODE_APP_SWITCH,  // 187
            KeyEvent.KEYCODE_BUTTON_2,    // 97
            98, 99, 187, 284
        )

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

        fun getFriendlyKeyName(keyCode: Int): String {
            return when (keyCode) {
                KEYCODE_MACRO_1 -> "Bouton Étoile (★)"
                KeyEvent.KEYCODE_TV -> "Touche Live / TV"
                KeyEvent.KEYCODE_GUIDE -> "Touche Guide / EPG"
                KeyEvent.KEYCODE_DVR -> "Touche DVR / Enregistrement"
                KeyEvent.KEYCODE_WINDOW -> "Touche Live / TV Window"
                KeyEvent.KEYCODE_BUTTON_1 -> "Bouton Personnalisé 1"
                KeyEvent.KEYCODE_BUTTON_2 -> "Bouton Personnalisé 2"
                KeyEvent.KEYCODE_BUTTON_3 -> "Bouton App (Netflix/Live)"
                KeyEvent.KEYCODE_BUTTON_4 -> "Bouton App (Prime/VOD)"
                KeyEvent.KEYCODE_PROG_RED -> "Touche Rouge (Raccourci TV)"
                KeyEvent.KEYCODE_PROG_GREEN -> "Touche Verte (Raccourci TV)"
                KeyEvent.KEYCODE_PROG_YELLOW -> "Touche Jaune (Raccourci TV)"
                KeyEvent.KEYCODE_PROG_BLUE -> "Touche Bleue (Raccourci TV)"
                KeyEvent.KEYCODE_CAPTIONS -> "Touche Sous-titres"
                KeyEvent.KEYCODE_INFO -> "Touche Info"
                KeyEvent.KEYCODE_SEARCH -> "Touche Recherche"
                else -> {
                    val raw = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                    if (raw.startsWith("Unknown", ignoreCase = true)) "Touche $keyCode" else raw
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
