package com.quicksleeptimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class LockScreenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        Log.d(TAG, "Received broadcast action: $action")
        if (action == ACTION_LOCK_NOW) {
            val locked = SleepAccessibilityService.lockScreen()
            Log.d(TAG, "SleepAccessibilityService.lockScreen() result: $locked")
        } else if (action == ACTION_CANCEL_NOW) {
            context?.let { TimerService.cancel(it) }
            Log.d(TAG, "Timer cancelled via broadcast")
        } else if (action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "BOOT_COMPLETED received -> checking soft wake startup")
            SleepAccessibilityService.instance?.handleScreenOn()
        }
    }

    companion object {
        private const val TAG = "LockScreenReceiver"
        const val ACTION_LOCK_NOW = "com.quicksleeptimer.ACTION_LOCK_NOW"
        const val ACTION_CANCEL_NOW = "com.quicksleeptimer.ACTION_CANCEL_NOW"
    }
}
