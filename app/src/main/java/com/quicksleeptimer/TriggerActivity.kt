package com.quicksleeptimer

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

class TriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // CRITICAL ANTI-INTERRUPTION REQUIREMENT:
        // Apply NOT_FOCUSABLE and NOT_TOUCHABLE before super.onCreate to prevent
        // stealing window focus and avoid triggering onPause() in active video players.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        super.onCreate(savedInstanceState)
        Log.d(TAG, "TriggerActivity launched: dispatching toggle to TimerService")

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val wasPlaying = audioManager?.isMusicActive ?: false
        val activePkg = SleepAccessibilityService.getActivePackage()
        val isOnLauncher = SleepAccessibilityService.isLauncherOrSettings(activePkg)
        val shouldResume = wasPlaying || (!activePkg.isNullOrEmpty() && !isOnLauncher)

        // Trigger Sleep Timer increment or start
        TimerService.startOrIncrement(applicationContext, wasMusicActive = shouldResume)

        // Immediately finish without any window animation or flash
        finish()

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "QuickSleep_Trigger"
    }
}
