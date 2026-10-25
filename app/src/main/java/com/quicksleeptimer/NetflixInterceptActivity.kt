package com.quicksleeptimer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline activity to cleanly intercept the hardware Netflix button on Xiaomi / Android TV.
 * When com.netflix.ninja is disabled, the system's PhoneWindowManager dispatches:
 *   Intent { act=android.intent.action.VIEW dat=nflx://www.netflix.com/ }
 *
 * This activity handles that intent in < 1 millisecond with zero visual flicker:
 * - If user is on the Launcher or Settings: redirects to SetupActivity (Settings screen).
 * - If user is inside an app (Crunchyroll, VLC, SmartTube, etc.): triggers Sleep Timer HUD without leaving or pausing the app.
 * - If the active trigger is NOT Netflix: ignores and finishes immediately so other buttons are never blocked.
 */
class NetflixInterceptActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PreferencesManager.getInstance(this)
        if (prefs.cachedTriggerTarget != PreferencesManager.TRIGGER_NETFLIX) {
            Log.d(TAG, "Netflix button pressed but active triggerTarget is ${prefs.cachedTriggerTarget} -> Ignoring")
            finish()
            return
        }

        val activePkg = SleepAccessibilityService.lastLegitimatePackage ?: SleepAccessibilityService.getActivePackage()
        val isOnLauncher = SleepAccessibilityService.isLauncherOrSettings(activePkg)

        Log.d(TAG, "Hardware Netflix button intercepted via system intent: activePkg=$activePkg, isOnLauncher=$isOnLauncher")

        if (isOnLauncher) {
            Log.d(TAG, "Launcher detected -> Redirecting to Settings screen")
            val setupIntent = Intent(this, SetupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_settings", true)
            }
            startActivity(setupIntent)
        } else {
            Log.d(TAG, "App detected ($activePkg) -> Triggering Sleep Timer HUD")
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val wasPlaying = audioManager?.isMusicActive ?: false
            TimerService.startOrIncrement(
                applicationContext,
                wasMusicActive = wasPlaying || !activePkg.isNullOrEmpty()
            )
        }

        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "QuickSleep_Nflx"
    }
}
