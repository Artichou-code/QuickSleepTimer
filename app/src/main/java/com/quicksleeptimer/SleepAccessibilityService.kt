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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrEmpty() && pkg != packageName && pkg != "android") {
                lastForegroundPackage = pkg
                Log.d(TAG, "Foreground window package changed to: $pkg")
            }
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
                        prefsManager.setAssignedKeyCode(keyCode)
                    }
                    keyAssignListener?.onKeyAssigned(keyCode)
                    return true // Consume event
                }
            }
            return true
        }

        // 2. Regular Native Trigger Interception (100% Zero Activity / Zero Pause Guarantee)
        val assignedKey = prefsManager.cachedAssignedKeyCode
        val isStarButton = (keyCode == KEYCODE_MACRO_1)
        val isTargetKey = (assignedKey != PreferencesManager.DEFAULT_KEYCODE && keyCode == assignedKey) || isStarButton

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
                    Log.d(TAG, "Native key intercepted in app ($activePkg) -> Triggering Sleep Timer with ZERO Activity (Crunchyroll/ExoPlayer safe)")
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

        fun getActivePackage(): String? {
            try {
                val root = instance?.rootInActiveWindow
                val pkg = root?.packageName?.toString()
                if (!pkg.isNullOrEmpty() && pkg != "com.quicksleeptimer" && pkg != "android") {
                    lastForegroundPackage = pkg
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
            fun onForbiddenKeyDetected(keyCode: Int)
            fun onKeyListeningCancelled()
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
