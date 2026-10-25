package com.quicksleeptimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class TimerService : Service() {

    enum class TimerState {
        INACTIVE,
        RUNNING_HUD_OPEN,
        RUNNING_HUD_CLOSED
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var prefsManager: PreferencesManager
    private lateinit var overlayManager: OverlayManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var countdownJob: Job? = null
    private var hudWindowJob: Job? = null
    private var progressiveDimmerJob: Job? = null
    private var progressiveVolumeJob: Job? = null
    private var testVolumeFadeJob: Job? = null
    private var initialAudioVolume: Int? = null

    private var targetSleepRealtimeMs: Long = 0L
    private var currentState: TimerState = TimerState.INACTIVE
    private var hasShown5mWarning: Boolean = false
    private var hasShown60sWarning: Boolean = false

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager.getInstance(this)
        overlayManager = OverlayManager(this)
        createNotificationChannel()
        instance = this
        Log.d(TAG, "TimerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_TOGGLE_OR_INCREMENT
        Log.d(TAG, "onStartCommand action: $action")

        val wasPlaying = intent?.getBooleanExtra(EXTRA_WAS_PLAYING, false) ?: false
        if (wasPlaying) {
            autoResumePlayback()
        }

        when (action) {
            ACTION_TOGGLE_OR_INCREMENT -> handleToggleOrIncrement()
            ACTION_CANCEL -> cancelTimer(showHud = true)
            ACTION_TEST_SLEEP -> {
                if (currentState == TimerState.INACTIVE) {
                    startForegroundNotification(1)
                }
                executeSleepImmediate()
            }
            ACTION_TEST_VOLUME_FADE -> {
                if (currentState == TimerState.INACTIVE) {
                    startForegroundNotification(1)
                }
                testVolumeFade()
            }
            ACTION_STOP_TEST_VOLUME_FADE -> {
                stopVolumeFadeTestInternal(showInterruptedHud = true)
            }
        }

        return START_NOT_STICKY
    }

    private fun autoResumePlayback() {
        serviceScope.launch {
            // Pulse 1 at 100ms: ultra-fast immediate resumption
            delay(100L)
            sendMediaPlayKey()
            // Pulse 2 at +150ms (250ms total): ensures playback if transition took slightly longer
            delay(150L)
            sendMediaPlayKey()
            // Pulse 3 at +180ms (430ms total): safety backup pulse
            delay(180L)
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager?.isMusicActive == false) {
                sendMediaPlayKey()
            }
        }
    }

    private fun sendMediaPlayKey() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
            audioManager?.dispatchMediaKeyEvent(down)
            audioManager?.dispatchMediaKeyEvent(up)
            Log.d(TAG, "Dispatched KEYCODE_MEDIA_PLAY to restore video playback")
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching media play key", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cancelTimer(showHud = false)
        overlayManager.hideDimmer()
        overlayManager.hideHud(withAnimation = false)
        instance = null
        Log.d(TAG, "TimerService destroyed")
    }

    // ==========================================
    // TIMER LOGIC & KEY PRESS HANDLING
    // ==========================================

    private fun handleToggleOrIncrement() {
        val now = SystemClock.elapsedRealtime()

        when (currentState) {
            TimerState.INACTIVE -> {
                // 1st Press: Start new timer
                val initialMinutes = prefsManager.cachedInitialDuration
                val durationMs = initialMinutes * 60 * 1000L
                targetSleepRealtimeMs = now + durationMs
                hasShown5mWarning = (durationMs <= 300_000L)
                hasShown60sWarning = (durationMs <= 60_000L)

                acquireWakeLock(durationMs + 60_000L)
                startForegroundNotification(initialMinutes)

                // Night Dimmer handling
                if (prefsManager.cachedDimmerEnabled) {
                    val targetAlpha = prefsManager.getDimmerAlphaFloat()
                    when (prefsManager.cachedDimmerMode) {
                        PreferencesManager.DIMMER_MODE_IMMEDIATE -> {
                            overlayManager.showDimmer(targetAlpha)
                        }
                        PreferencesManager.DIMMER_MODE_FULL -> {
                            startProgressiveDimmer(targetAlpha, durationMs, startAlpha = 0f)
                        }
                        PreferencesManager.DIMMER_MODE_LAST_5MIN -> {
                            if (durationMs <= 300_000L) {
                                startProgressiveDimmer(targetAlpha, durationMs, startAlpha = 0f)
                            }
                        }
                    }
                }

                // Progressive Volume Reduction handling
                if (prefsManager.cachedVolumeFadeEnabled) {
                    when (prefsManager.cachedVolumeFadeMode) {
                        PreferencesManager.VOLUME_FADE_FULL -> {
                            startProgressiveVolumeFade(durationMs)
                        }
                        PreferencesManager.VOLUME_FADE_LAST_5MIN -> {
                            if (durationMs <= 300_000L) {
                                startProgressiveVolumeFade(durationMs)
                            }
                        }
                        PreferencesManager.VOLUME_FADE_LAST_10MIN -> {
                            if (durationMs <= 600_000L) {
                                startProgressiveVolumeFade(durationMs)
                            }
                        }
                    }
                }

                // Show HUD
                val hudDurationMs = prefsManager.cachedHudDisplaySeconds * 1000L
                overlayManager.showHud(
                    title = getString(R.string.hud_timer_started, initialMinutes),
                    subtitle = "Appuyez à nouveau pour +${prefsManager.cachedIncrementStep} min",
                    autoDismissMs = hudDurationMs
                )

                currentState = TimerState.RUNNING_HUD_OPEN
                resetHudWindow(5000L)
                startCountdownLoop()
            }

            TimerState.RUNNING_HUD_OPEN -> {
                // Subsequent presses within 5 seconds: Add step duration
                val stepMinutes = prefsManager.cachedIncrementStep
                val maxMinutes = prefsManager.cachedMaxDuration
                val currentRemainingMs = targetSleepRealtimeMs - now
                val currentRemainingMin = ((currentRemainingMs + 59_999L) / 60_000L).toInt()
                val newTotalMinutes = currentRemainingMin + stepMinutes

                if (newTotalMinutes > maxMinutes) {
                    // Exceeded max duration: Cancel timer
                    cancelTimer(showHud = true)
                } else {
                    // Increment duration
                    targetSleepRealtimeMs += (stepMinutes * 60 * 1000L)
                    val updatedRemainingMs = targetSleepRealtimeMs - now
                    val updatedRemainingMin = ((updatedRemainingMs + 59_999L) / 60_000L).toInt()

                    if (updatedRemainingMs > 300_000L) {
                        hasShown5mWarning = false
                    }
                    if (updatedRemainingMs > 60_000L) {
                        hasShown60sWarning = false
                    }

                    updateNotification(updatedRemainingMin)

                    val hudDurationMs = prefsManager.cachedHudDisplaySeconds * 1000L
                    overlayManager.showHud(
                        title = getString(R.string.hud_timer_started, updatedRemainingMin),
                        subtitle = "+$stepMinutes min (Plafond : $maxMinutes min)",
                        autoDismissMs = hudDurationMs
                    )

                    if (prefsManager.cachedDimmerEnabled) {
                        handleDimmerOnTimeAdded(stepMinutes, currentRemainingMin, updatedRemainingMs)
                    }

                    if (prefsManager.cachedVolumeFadeEnabled) {
                        handleVolumeFadeOnTimeAdded(stepMinutes, currentRemainingMin, updatedRemainingMs)
                    }

                    resetHudWindow(5000L)
                }
            }

            TimerState.RUNNING_HUD_CLOSED -> {
                // Timer already running > 5s with HUD closed: First press displays remaining time
                val currentRemainingMs = targetSleepRealtimeMs - now
                val currentRemainingMin = ((currentRemainingMs + 59_999L) / 60_000L).toInt()

                val hudDurationMs = prefsManager.cachedHudDisplaySeconds * 1000L
                overlayManager.showHud(
                    title = getString(R.string.hud_timer_remaining, currentRemainingMin),
                    subtitle = "Appuyez pour ajouter +${prefsManager.cachedIncrementStep} min",
                    autoDismissMs = hudDurationMs
                )

                // Open 5s increment window for subsequent presses
                currentState = TimerState.RUNNING_HUD_OPEN
                resetHudWindow(5000L)
            }
        }
    }

    private fun resetHudWindow(windowMs: Long) {
        hudWindowJob?.cancel()
        hudWindowJob = serviceScope.launch {
            delay(windowMs)
            if (currentState == TimerState.RUNNING_HUD_OPEN) {
                currentState = TimerState.RUNNING_HUD_CLOSED
            }
        }
    }

    private fun startCountdownLoop() {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val remainingMs = targetSleepRealtimeMs - now

                if (remainingMs <= 0L) {
                    onTimerFinished()
                    break
                }

                val remainingSeconds = (remainingMs / 1000L).toInt()
                val remainingMinutes = (remainingMs + 59_999L) / 60_000L

                // 5-minute warning (300 seconds)
                if (remainingSeconds in 290..300 && !hasShown5mWarning) {
                    hasShown5mWarning = true
                    overlayManager.showHud(
                        title = getString(R.string.hud_warning_5m),
                        subtitle = "Appuyez sur ★ pour ajouter +${prefsManager.cachedIncrementStep} min",
                        autoDismissMs = 5000L
                    )
                }

                // 60-second warning
                if (remainingSeconds in 1..60 && !hasShown60sWarning) {
                    hasShown60sWarning = true
                    overlayManager.showHud(
                        title = getString(R.string.hud_warning_60s),
                        subtitle = "Appuyez sur ★ pour ajouter +${prefsManager.cachedIncrementStep} min",
                        autoDismissMs = 6000L
                    )
                }

                // Progressive Dimmer check (last 5 minutes = 300_000 ms)
                if (prefsManager.cachedDimmerEnabled &&
                    prefsManager.cachedDimmerMode == PreferencesManager.DIMMER_MODE_LAST_5MIN &&
                    remainingMs <= 300_000L &&
                    progressiveDimmerJob == null
                ) {
                    startProgressiveDimmer(prefsManager.getDimmerAlphaFloat(), remainingMs, startAlpha = overlayManager.currentAlpha)
                }

                // Progressive Volume Fade check
                if (prefsManager.cachedVolumeFadeEnabled && progressiveVolumeJob == null) {
                    when (prefsManager.cachedVolumeFadeMode) {
                        PreferencesManager.VOLUME_FADE_LAST_5MIN -> {
                            if (remainingMs <= 300_000L) {
                                startProgressiveVolumeFade(remainingMs)
                            }
                        }
                        PreferencesManager.VOLUME_FADE_LAST_10MIN -> {
                            if (remainingMs <= 600_000L) {
                                startProgressiveVolumeFade(remainingMs)
                            }
                        }
                    }
                }

                // Periodically update foreground notification text
                if (remainingSeconds % 30 == 0) {
                    updateNotification(remainingMinutes.toInt())
                }

                delay(1000L)
            }
        }
    }

    private fun handleDimmerOnTimeAdded(stepMinutes: Int, currentRemainingMin: Int, updatedRemainingMs: Long) {
        val currentAlpha = overlayManager.currentAlpha
        val targetAlpha = prefsManager.getDimmerAlphaFloat()

        when (prefsManager.cachedDimmerMode) {
            PreferencesManager.DIMMER_MODE_FULL -> {
                progressiveDimmerJob?.cancel()
                if (currentAlpha > 0.03f) {
                    val reductionRatio = (stepMinutes.toFloat() / (stepMinutes + currentRemainingMin)).coerceIn(0.25f, 0.55f)
                    val newAlpha = (currentAlpha * (1f - reductionRatio)).coerceIn(0f, targetAlpha)
                    Log.d(TAG, "DIMMER_MODE_FULL: Time added (+${stepMinutes}m). Easing alpha from $currentAlpha to $newAlpha (ratio=$reductionRatio)")

                    progressiveDimmerJob = serviceScope.launch {
                        overlayManager.animateDimmerAlpha(newAlpha, 1500L)
                        delay(1500L)
                        startProgressiveDimmer(targetAlpha, (updatedRemainingMs - 1500L).coerceAtLeast(1000L), startAlpha = newAlpha)
                    }
                } else {
                    startProgressiveDimmer(targetAlpha, updatedRemainingMs, startAlpha = currentAlpha)
                }
            }

            PreferencesManager.DIMMER_MODE_LAST_5MIN -> {
                progressiveDimmerJob?.cancel()
                if (currentAlpha > 0.03f) {
                    val reductionRatio = (stepMinutes.toFloat() / (stepMinutes + currentRemainingMin)).coerceIn(0.30f, 0.65f)
                    val newAlpha = (currentAlpha * (1f - reductionRatio)).coerceIn(0f, targetAlpha)
                    Log.d(TAG, "DIMMER_MODE_LAST_5MIN: Time added (+${stepMinutes}m). Easing alpha from $currentAlpha to $newAlpha (ratio=$reductionRatio)")

                    if (updatedRemainingMs > 300_000L) {
                        progressiveDimmerJob = serviceScope.launch {
                            overlayManager.animateDimmerAlpha(newAlpha, 1500L)
                            delay(1500L)
                            val timeUntil5Min = updatedRemainingMs - 300_000L - 1500L
                            if (timeUntil5Min > 1000L) {
                                val steps = 50
                                val stepDelay = (timeUntil5Min / steps).coerceAtLeast(200L)
                                for (s in 1..steps) {
                                    if (!isActive) break
                                    val frac = s.toFloat() / steps
                                    val a = newAlpha * (1f - frac)
                                    overlayManager.setDimmerAlpha(a)
                                    delay(stepDelay)
                                }
                                overlayManager.setDimmerAlpha(0f)
                            } else {
                                overlayManager.setDimmerAlpha(0f)
                            }
                            progressiveDimmerJob = null
                        }
                    } else {
                        progressiveDimmerJob = serviceScope.launch {
                            overlayManager.animateDimmerAlpha(newAlpha, 1500L)
                            delay(1500L)
                            startProgressiveDimmer(targetAlpha, (updatedRemainingMs - 1500L).coerceAtLeast(1000L), startAlpha = newAlpha)
                        }
                    }
                } else {
                    if (updatedRemainingMs <= 300_000L) {
                        startProgressiveDimmer(targetAlpha, updatedRemainingMs, startAlpha = 0f)
                    } else {
                        progressiveDimmerJob = null
                        overlayManager.setDimmerAlpha(0f)
                    }
                }
            }

            PreferencesManager.DIMMER_MODE_IMMEDIATE -> {
                // Immediate mode keeps constant darkness
            }
        }
    }

    private fun handleVolumeFadeOnTimeAdded(stepMinutes: Int, currentRemainingMin: Int, updatedRemainingMs: Long) {
        when (prefsManager.cachedVolumeFadeMode) {
            PreferencesManager.VOLUME_FADE_FULL -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                val origVol = initialAudioVolume ?: currentVol
                if (origVol > currentVol) {
                    val reductionRatio = (stepMinutes.toFloat() / (stepMinutes + currentRemainingMin)).coerceIn(0.25f, 0.60f)
                    val restoredVol = (currentVol + (origVol - currentVol) * reductionRatio).toInt().coerceIn(currentVol, origVol)
                    try {
                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, restoredVol, 0)
                        Log.d(TAG, "VOLUME_FADE_FULL: Time added (+${stepMinutes}m). Bumped volume from $currentVol to $restoredVol (orig=$origVol)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error bumping volume on time added: ${e.message}")
                    }
                }
                startProgressiveVolumeFade(updatedRemainingMs)
            }
            PreferencesManager.VOLUME_FADE_LAST_5MIN -> {
                if (updatedRemainingMs > 300_000L) {
                    progressiveVolumeJob?.cancel()
                    progressiveVolumeJob = null
                    restoreVolumeIfNeeded()
                } else {
                    startProgressiveVolumeFade(updatedRemainingMs)
                }
            }
            PreferencesManager.VOLUME_FADE_LAST_10MIN -> {
                if (updatedRemainingMs > 600_000L) {
                    progressiveVolumeJob?.cancel()
                    progressiveVolumeJob = null
                    restoreVolumeIfNeeded()
                } else {
                    startProgressiveVolumeFade(updatedRemainingMs)
                }
            }
        }
    }

    private fun startProgressiveDimmer(targetAlpha: Float, durationMs: Long, startAlpha: Float = 0f) {
        progressiveDimmerJob?.cancel()
        progressiveDimmerJob = serviceScope.launch {
            val totalSteps = 150
            val stepDelayMs = (durationMs / totalSteps).coerceAtLeast(400L)

            overlayManager.showDimmer(startAlpha)
            for (step in 1..totalSteps) {
                if (!isActive) break
                val fraction = step.toFloat() / totalSteps
                val alpha = startAlpha + fraction * (targetAlpha - startAlpha)
                overlayManager.setDimmerAlpha(alpha)
                delay(stepDelayMs)
            }
            overlayManager.setDimmerAlpha(targetAlpha)
        }
    }

    private fun onTimerFinished() {
        Log.d(TAG, "Timer reached zero, initiating sleep sequence")
        executeSleepImmediate()
    }

    private fun executeSleepImmediate() {
        // 1. Remove Dimmer IMMEDIATELY before locking screen (Prevents black screen on wake)
        progressiveDimmerJob?.cancel()
        progressiveDimmerJob = null
        progressiveVolumeJob?.cancel()
        progressiveVolumeJob = null
        overlayManager.hideDimmer()
        overlayManager.hideHud(withAnimation = false)

        // 2. Pause media before locking
        sendMediaPauseKey()

        // 3. Perform global sleep action via Accessibility
        SleepAccessibilityService.lockScreen()

        // 4. Restore original volume while screen is locked if configured
        if (prefsManager.cachedRestoreVolumeOnWake) {
            val savedVol = initialAudioVolume
            if (savedVol != null) {
                serviceScope.launch {
                    delay(800L)
                    try {
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
                        Log.d(TAG, "Restored volume $savedVol while screen locked")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error restoring volume after lock: ${e.message}")
                    }
                }
            }
        }

        // 5. Clean up timer service without resetting volume immediately
        cancelTimer(showHud = false, restoreVolume = false)
    }

    private fun cancelTimer(showHud: Boolean, restoreVolume: Boolean = true) {
        countdownJob?.cancel()
        countdownJob = null

        hudWindowJob?.cancel()
        hudWindowJob = null

        progressiveDimmerJob?.cancel()
        progressiveDimmerJob = null

        progressiveVolumeJob?.cancel()
        progressiveVolumeJob = null

        if (restoreVolume) {
            restoreVolumeIfNeeded()
        }

        currentState = TimerState.INACTIVE
        hasShown60sWarning = false
        targetSleepRealtimeMs = 0L

        releaseWakeLock()
        overlayManager.hideDimmer(animated = true)

        if (showHud) {
            val hudDurationMs = prefsManager.cachedHudDisplaySeconds * 1000L
            overlayManager.showHud(
                title = getString(R.string.hud_timer_cancelled),
                subtitle = null,
                autoDismissMs = hudDurationMs
            )
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startProgressiveVolumeFade(durationMs: Long) {
        progressiveVolumeJob?.cancel()
        progressiveVolumeJob = serviceScope.launch {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@launch
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (initialAudioVolume == null) {
                initialAudioVolume = currentVol
            }
            Log.d(TAG, "Starting progressive volume fade from $currentVol over $durationMs ms")
            if (currentVol <= 0) return@launch

            val totalSteps = currentVol
            val stepDelayMs = (durationMs / totalSteps).coerceAtLeast(400L)

            for (step in 1..totalSteps) {
                if (!isActive) break
                delay(stepDelayMs)
                val targetVol = (currentVol - step).coerceAtLeast(0)
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                    Log.d(TAG, "Progressive volume fade step $step/$totalSteps: vol=$targetVol")
                } catch (e: Exception) {
                    Log.w(TAG, "Error adjusting volume: ${e.message}")
                }
            }
        }
    }

    private fun restoreVolumeIfNeeded() {
        val orig = initialAudioVolume
        if (orig != null) {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, orig, 0)
                Log.d(TAG, "Restored original audio volume: $orig")
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring volume: ${e.message}")
            } finally {
                initialAudioVolume = null
            }
        }
    }

    private fun sendMediaPauseKey() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
            audioManager?.dispatchMediaKeyEvent(down)
            audioManager?.dispatchMediaKeyEvent(up)
            Log.d(TAG, "Dispatched KEYCODE_MEDIA_PAUSE before sleep")
        } catch (e: Exception) {
            Log.w(TAG, "Error sending media pause key: ${e.message}")
        }
    }

    private var testOriginalVol: Int? = null

    private fun testVolumeFade() {
        testVolumeFadeJob?.cancel()
        _isTestingVolumeFadeFlow.value = true

        testVolumeFadeJob = serviceScope.launch {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: run {
                _isTestingVolumeFadeFlow.value = false
                return@launch
            }
            val originalVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            testOriginalVol = originalVol

            // Si le volume actuel est trop bas pour entendre le test, on démarre à un niveau sonore confortable
            val testStartVol = if (originalVol < 6) 12 else originalVol
            if (originalVol != testStartVol) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, testStartVol, 0)
            }

            var mediaPlayer: MediaPlayer? = null
            try {
                mediaPlayer = MediaPlayer.create(applicationContext, R.raw.test_melody)?.apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    isLooping = true
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error playing test melody: ${e.message}")
            }

            try {
                overlayManager.showHud(
                    title = "Test Baisse Volume (${testStartVol})",
                    subtitle = "🎵 Tchaïkovski — Casse-Noisette (Simulation 30s)",
                    autoDismissMs = 4000L
                )

                val totalDurationSec = 30
                val stepVol = testStartVol.toFloat() / totalDurationSec

                for (sec in 1..totalDurationSec) {
                    delay(1000L)
                    val v = (testStartVol - (sec * stepVol)).roundToInt().coerceAtLeast(0)
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        Log.d(TAG, "Test volume sec $sec/$totalDurationSec: vol=$v")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error adjusting test volume: ${e.message}")
                    }
                }

                delay(1000L)

                overlayManager.showHud(
                    title = "Test terminé ✓",
                    subtitle = "Volume restauré au niveau initial ($originalVol)",
                    autoDismissMs = 4000L
                )
            } finally {
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                } catch (e: Exception) {}

                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVol, 0)
                    Log.d(TAG, "testVolumeFade restored original volume: $originalVol")
                } catch (e: Exception) {
                    Log.w(TAG, "Error restoring original volume: ${e.message}")
                }

                _isTestingVolumeFadeFlow.value = false
                testOriginalVol = null

                if (currentState == TimerState.INACTIVE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
            }
        }
    }

    private fun stopVolumeFadeTestInternal(showInterruptedHud: Boolean = true) {
        val wasRunning = testVolumeFadeJob?.isActive == true
        val restoredVol = testOriginalVol
        testVolumeFadeJob?.cancel()
        testVolumeFadeJob = null
        _isTestingVolumeFadeFlow.value = false

        if (wasRunning && showInterruptedHud) {
            overlayManager.showHud(
                title = "Test interrompu ⏹",
                subtitle = if (restoredVol != null) "Volume restauré au niveau initial ($restoredVol)" else "Test arrêté",
                autoDismissMs = 3000L
            )
        }
    }

    // ==========================================
    // WAKELOCK MANAGEMENT
    // ==========================================

    private fun acquireWakeLock(timeoutMs: Long) {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "QuickSleepTimer::ServiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(timeoutMs)
            Log.d(TAG, "WakeLock acquired for $timeoutMs ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        } finally {
            wakeLock = null
        }
    }

    // ==========================================
    // FOREGROUND SERVICE & NOTIFICATION
    // ==========================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(remainingMinutes: Int): Notification {
        val launchIntent = Intent(this, SetupActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_content_running, "$remainingMinutes min"))
            .setSmallIcon(R.drawable.ic_moon)
            .setContentIntent(pendingIntent)
            .addAction(0, "Annuler", cancelPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun startForegroundNotification(remainingMinutes: Int) {
        val notification = buildNotification(remainingMinutes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(remainingMinutes: Int) {
        val notification = buildNotification(remainingMinutes)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "QuickSleep_TimerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "quicksleep_timer_channel"

        const val ACTION_TOGGLE_OR_INCREMENT = "com.quicksleeptimer.ACTION_TOGGLE"
        const val ACTION_CANCEL = "com.quicksleeptimer.ACTION_CANCEL"
        const val ACTION_TEST_SLEEP = "com.quicksleeptimer.ACTION_TEST_SLEEP"
        const val ACTION_TEST_VOLUME_FADE = "com.quicksleeptimer.ACTION_TEST_VOLUME_FADE"
        const val ACTION_STOP_TEST_VOLUME_FADE = "com.quicksleeptimer.ACTION_STOP_TEST_VOLUME_FADE"
        const val EXTRA_WAS_PLAYING = "extra_was_playing"

        private val _isTestingVolumeFadeFlow = MutableStateFlow(false)
        val isTestingVolumeFadeFlow: StateFlow<Boolean> = _isTestingVolumeFadeFlow.asStateFlow()
        val isTestingVolumeFade: Boolean
            get() = _isTestingVolumeFadeFlow.value

        @Volatile
        var instance: TimerService? = null
            private set

        val isRunning: Boolean
            get() = instance?.currentState != null && instance?.currentState != TimerState.INACTIVE

        fun getRemainingMinutes(): Int {
            val service = instance ?: return 0
            if (service.currentState == TimerState.INACTIVE) return 0
            val remainingMs = service.targetSleepRealtimeMs - SystemClock.elapsedRealtime()
            return ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(0L).toInt()
        }

        fun startOrIncrement(context: Context, wasMusicActive: Boolean = false) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_TOGGLE_OR_INCREMENT
                putExtra(EXTRA_WAS_PLAYING, wasMusicActive)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

        fun testSleep(context: Context) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_TEST_SLEEP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun testVolumeFade(context: Context) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_TEST_VOLUME_FADE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopVolumeFadeTest(context: Context) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_STOP_TEST_VOLUME_FADE
            }
            context.startService(intent)
        }
    }
}
