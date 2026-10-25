package com.quicksleeptimer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.quicksleeptimer.databinding.ActivitySetupBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity(), SleepAccessibilityService.Companion.KeyAssignListener {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var overlayManager: OverlayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isListeningForKey = false
    private var cancelListeningRunnable: Runnable? = null

    private val durationOptions = listOf(5, 10, 15, 20, 30, 45, 60)
    private val stepOptions = listOf(5, 10, 15, 20, 30)
    private val maxOptions = listOf(60, 90, 120, 180, 240)

    private val dimmerIntensityOptions = listOf(
        PreferencesManager.INTENSITY_LIGHT,
        PreferencesManager.INTENSITY_MEDIUM,
        PreferencesManager.INTENSITY_STRONG,
        PreferencesManager.INTENSITY_VERY_STRONG,
        PreferencesManager.INTENSITY_EXTREME
    )

    private val dimmerModeOptions = listOf(
        PreferencesManager.DIMMER_MODE_IMMEDIATE,
        PreferencesManager.DIMMER_MODE_FULL,
        PreferencesManager.DIMMER_MODE_LAST_5MIN
    )

    private val volumeFadeModeOptions = listOf(
        PreferencesManager.VOLUME_FADE_LAST_5MIN,
        PreferencesManager.VOLUME_FADE_LAST_10MIN,
        PreferencesManager.VOLUME_FADE_FULL
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isMusicPlaying = audioManager?.isMusicActive ?: false
        val activePkg = SleepAccessibilityService.getActivePackage()
        val isExplicitTrigger = intent?.action == "com.quicksleeptimer.ACTION_TRIGGER" ||
                intent?.getBooleanExtra("trigger_now", false) == true
        val isExplicitSettings = intent?.getBooleanExtra("open_settings", false) == true

        // User is on the home launcher or settings
        val isOnLauncher = SleepAccessibilityService.isLauncherOrSettings(activePkg)
        // Trigger timer ONLY if inside an app (Crunchyroll/Deezer/etc.) or explicit trigger.
        // On home launcher or explicit settings -> ALWAYS open Settings!
        val shouldTriggerTimer = !isExplicitSettings && (isExplicitTrigger || (!isOnLauncher && activePkg != null))

        if (shouldTriggerTimer) {
            // Fast transparent trigger: do not steal window focus from Crunchyroll/media
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
            window.setBackgroundDrawableResource(android.R.color.transparent)
            super.onCreate(savedInstanceState)
            Log.d(TAG, "Fast trigger invoked: activePkg=$activePkg, music=$isMusicPlaying")

            val shouldResume = isMusicPlaying || (!activePkg.isNullOrEmpty() && !isOnLauncher)
            TimerService.startOrIncrement(applicationContext, wasMusicActive = shouldResume)

            finish()
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            return
        }

        super.onCreate(savedInstanceState)
        Log.d(TAG, "SetupActivity: displaying settings screen (activePkg=$activePkg)")
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager.getInstance(this)
        overlayManager = OverlayManager(this)

        setupDurationControls()
        setupPermissionControls()
        setupTriggerModeControls()
        setupNightDimmerControls()
        setupVolumeFadeControls()
        setupActionButtons()
        observePreferences()

        // Default focus for TV remote
        binding.btnInitialDuration.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isMusicPlaying = audioManager?.isMusicActive ?: false
        val activePkg = SleepAccessibilityService.getActivePackage()
        val isExplicitTrigger = intent.action == "com.quicksleeptimer.ACTION_TRIGGER" ||
                intent.getBooleanExtra("trigger_now", false) == true
        val isExplicitSettings = intent.getBooleanExtra("open_settings", false) == true

        val isOnLauncher = SleepAccessibilityService.isLauncherOrSettings(activePkg)
        val shouldTriggerTimer = !isExplicitSettings && (isExplicitTrigger || (!isOnLauncher && activePkg != null))

        if (shouldTriggerTimer) {
            Log.d(TAG, "SetupActivity onNewIntent: fast trigger invoked (activePkg=$activePkg, music=$isMusicPlaying)")
            val shouldResume = isMusicPlaying || (!activePkg.isNullOrEmpty() && !isOnLauncher)
            TimerService.startOrIncrement(applicationContext, wasMusicActive = shouldResume)
            finish()
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            return
        }
        if (::binding.isInitialized) {
            refreshTimerStatusBadge()
        }
    }

    override fun onStop() {
        super.onStop()
        if (TimerService.isTestingVolumeFade) {
            TimerService.stopVolumeFadeTest(this)
        }
        // Ensure settings UI doesn't linger in background task stack when leaving to other apps
        if (!isFinishing && ::binding.isInitialized) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            refreshPermissionStates()
            refreshTimerStatusBadge()
            checkAndDisplayVolumeRoutingStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isListeningForKey) {
            cancelKeyListening()
        }
        if (::overlayManager.isInitialized) {
            overlayManager.hideDimmer()
        }
    }

    // ==========================================
    // PREFERENCES OBSERVATION
    // ==========================================

    private fun observePreferences() {
        lifecycleScope.launch {
            prefsManager.initialDurationFlow.collectLatest { minutes ->
                binding.btnInitialDuration.text = "$minutes min"
            }
        }
        lifecycleScope.launch {
            prefsManager.incrementStepFlow.collectLatest { minutes ->
                binding.btnStepDuration.text = "+$minutes min"
            }
        }
        lifecycleScope.launch {
            prefsManager.maxDurationFlow.collectLatest { minutes ->
                binding.btnMaxDuration.text = "$minutes min"
            }
        }
        lifecycleScope.launch {
            prefsManager.triggerModeFlow.collectLatest { mode ->
                updateTriggerModeUi(mode)
            }
        }
        lifecycleScope.launch {
            prefsManager.assignedKeyCodeFlow.collectLatest { keyCode ->
                updateAssignedKeyUi(keyCode)
            }
        }
        lifecycleScope.launch {
            prefsManager.dimmerEnabledFlow.collectLatest { enabled ->
                updateDimmerEnabledUi(enabled)
            }
        }
        lifecycleScope.launch {
            prefsManager.dimmerIntensityFlow.collectLatest { intensity ->
                binding.btnDimmerIntensity.text = when (intensity) {
                    PreferencesManager.INTENSITY_LIGHT -> getString(R.string.dimmer_intensity_light)
                    PreferencesManager.INTENSITY_STRONG -> getString(R.string.dimmer_intensity_strong)
                    PreferencesManager.INTENSITY_VERY_STRONG -> getString(R.string.dimmer_intensity_very_strong)
                    PreferencesManager.INTENSITY_EXTREME -> getString(R.string.dimmer_intensity_extreme)
                    else -> getString(R.string.dimmer_intensity_medium)
                }
            }
        }
        lifecycleScope.launch {
            prefsManager.dimmerModeFlow.collectLatest { mode ->
                binding.btnDimmerMode.text = when (mode) {
                    PreferencesManager.DIMMER_MODE_FULL -> getString(R.string.dimmer_mode_full)
                    PreferencesManager.DIMMER_MODE_LAST_5MIN -> getString(R.string.dimmer_mode_last_5min)
                    else -> getString(R.string.dimmer_mode_immediate)
                }
            }
        }
        lifecycleScope.launch {
            prefsManager.volumeFadeEnabledFlow.collectLatest { enabled ->
                updateVolumeFadeEnabledUi(enabled)
            }
        }
        lifecycleScope.launch {
            prefsManager.volumeFadeModeFlow.collectLatest { mode ->
                binding.btnVolumeFadeMode.text = when (mode) {
                    PreferencesManager.VOLUME_FADE_LAST_10MIN -> getString(R.string.volume_fade_mode_last_10min)
                    PreferencesManager.VOLUME_FADE_FULL -> getString(R.string.volume_fade_mode_full)
                    else -> getString(R.string.volume_fade_mode_last_5min)
                }
            }
        }
        lifecycleScope.launch {
            prefsManager.restoreVolumeOnWakeFlow.collectLatest { restore ->
                binding.btnRestoreVolume.text = if (restore) {
                    getString(R.string.volume_restore_yes)
                } else {
                    getString(R.string.volume_restore_no)
                }
            }
        }
        lifecycleScope.launch {
            TimerService.isTestingVolumeFadeFlow.collectLatest { isTesting ->
                if (isTesting) {
                    binding.btnTestVolumeFade.text = getString(R.string.btn_stop_volume_fade)
                    binding.btnTestVolumeFade.setTextColor(getColor(R.color.tv_status_warning))
                    binding.btnTestVolumeFade.requestFocus()
                } else {
                    binding.btnTestVolumeFade.text = getString(R.string.btn_test_volume_fade)
                    binding.btnTestVolumeFade.setTextColor(getColor(R.color.tv_text_primary))
                }
            }
        }
    }

    // ==========================================
    // SECTION 1 : CONFIGURATION DES DURÉES
    // ==========================================

    private fun setupDurationControls() {
        binding.btnInitialDuration.setOnClickListener {
            val current = prefsManager.cachedInitialDuration
            val nextIndex = (durationOptions.indexOf(current) + 1) % durationOptions.size
            val nextValue = durationOptions[nextIndex]
            lifecycleScope.launch { prefsManager.setInitialDuration(nextValue) }
        }

        binding.btnStepDuration.setOnClickListener {
            val current = prefsManager.cachedIncrementStep
            val nextIndex = (stepOptions.indexOf(current) + 1) % stepOptions.size
            val nextValue = stepOptions[nextIndex]
            lifecycleScope.launch { prefsManager.setIncrementStep(nextValue) }
        }

        binding.btnMaxDuration.setOnClickListener {
            val current = prefsManager.cachedMaxDuration
            val nextIndex = (maxOptions.indexOf(current) + 1) % maxOptions.size
            val nextValue = maxOptions[nextIndex]
            lifecycleScope.launch { prefsManager.setMaxDuration(nextValue) }
        }
    }

    // ==========================================
    // SECTION 2 : PERMISSIONS
    // ==========================================

    private fun setupPermissionControls() {
        binding.btnPermOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        binding.btnPermAccessibility.setOnClickListener {
            Toast.makeText(
                this,
                "⚠️ Ne pas activer TalkBack ! Descendez jusqu'à 'Services' > 'QuickSleepTimer'",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun refreshPermissionStates() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            binding.tvOverlayStatus.text = getString(R.string.status_granted)
            binding.tvOverlayStatus.setTextColor(getColor(R.color.tv_status_ok))
            binding.btnPermOverlay.text = "Gérer dans Paramètres ✓"
        } else {
            binding.tvOverlayStatus.text = getString(R.string.status_missing)
            binding.tvOverlayStatus.setTextColor(getColor(R.color.tv_status_warning))
            binding.btnPermOverlay.text = "Autoriser la superposition"
        }
        // Always keep button clickable for D-Pad navigation and reconfiguration
        binding.btnPermOverlay.isEnabled = true
        binding.btnPermOverlay.isFocusable = true

        val hasAccessibility = SleepAccessibilityService.isAccessibilityServiceEnabled(this)
        if (hasAccessibility) {
            binding.tvAccessibilityStatus.text = getString(R.string.status_granted)
            binding.tvAccessibilityStatus.setTextColor(getColor(R.color.tv_status_ok))
            binding.btnPermAccessibility.text = "Gérer / Désactiver ✓"
        } else {
            binding.tvAccessibilityStatus.text = getString(R.string.status_missing)
            binding.tvAccessibilityStatus.setTextColor(getColor(R.color.tv_status_warning))
            binding.btnPermAccessibility.text = "Activer dans Paramètres"
        }
        // Always keep button clickable for D-Pad navigation and reconfiguration
        binding.btnPermAccessibility.isEnabled = true
        binding.btnPermAccessibility.isFocusable = true
    }

    // ==========================================
    // SECTION 3 : MODE DE DÉCLENCHEMENT
    // ==========================================

    private fun setupTriggerModeControls() {
        binding.btnModeAccessibility.setOnClickListener {
            lifecycleScope.launch {
                prefsManager.setTriggerMode(PreferencesManager.MODE_ACCESSIBILITY)
            }
        }

        binding.btnModeButtonMapper.setOnClickListener {
            lifecycleScope.launch {
                prefsManager.setTriggerMode(PreferencesManager.MODE_BUTTON_MAPPER)
            }
        }

        binding.btnAssignKey.setOnClickListener {
            if (isListeningForKey) {
                cancelKeyListening()
            } else {
                startKeyListening()
            }
        }
    }

    private fun updateTriggerModeUi(mode: String) {
        if (mode == PreferencesManager.MODE_ACCESSIBILITY) {
            binding.btnModeAccessibility.text = "● Mode Accessibilité (100% Zéro Pause)"
            binding.btnModeAccessibility.setTextColor(getColor(R.color.tv_text_primary))
            binding.btnModeButtonMapper.text = "○ Mode Button Mapper"
            binding.btnModeButtonMapper.setTextColor(getColor(R.color.tv_text_secondary))
            binding.layoutKeyAssignation.visibility = View.VISIBLE
        } else {
            binding.btnModeAccessibility.text = "○ Mode Accessibilité"
            binding.btnModeAccessibility.setTextColor(getColor(R.color.tv_text_secondary))
            binding.btnModeButtonMapper.text = "● Mode Button Mapper (TriggerActivity)"
            binding.btnModeButtonMapper.setTextColor(getColor(R.color.tv_text_primary))
            binding.layoutKeyAssignation.visibility = View.GONE
        }
    }

    private fun updateAssignedKeyUi(keyCode: Int) {
        if (keyCode == PreferencesManager.DEFAULT_KEYCODE || keyCode == SleepAccessibilityService.KEYCODE_MACRO_1) {
            binding.tvAssignedKeyLabel.text = "Touche : Bouton Étoile (★) activé par défaut"
            binding.tvAssignedKeyLabel.setTextColor(getColor(R.color.tv_status_ok))
        } else {
            val keyLabel = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            binding.tvAssignedKeyLabel.text = getString(R.string.assigned_key_format, keyLabel, keyCode)
            binding.tvAssignedKeyLabel.setTextColor(getColor(R.color.tv_text_primary))
        }
    }

    private fun startKeyListening() {
        if (!SleepAccessibilityService.isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, "Activez d'abord le Service d'Accessibilité", Toast.LENGTH_SHORT).show()
            binding.btnPermAccessibility.requestFocus()
            return
        }

        isListeningForKey = true
        SleepAccessibilityService.isListeningForKeyAssignation = true
        SleepAccessibilityService.keyAssignListener = this

        binding.btnAssignKey.text = "Annuler (ou touche Retour)"
        binding.tvAssignKeyFeedback.text = "Appuyez sur un bouton libre de la télécommande… (ou RETOUR pour annuler)"
        binding.tvAssignKeyFeedback.setTextColor(getColor(R.color.tv_accent))
        binding.tvAssignKeyFeedback.visibility = View.VISIBLE

        // Auto-cancel after 15 seconds if no key is pressed
        cancelListeningRunnable?.let { mainHandler.removeCallbacks(it) }
        cancelListeningRunnable = Runnable {
            if (isListeningForKey) {
                cancelKeyListening()
                Toast.makeText(this, "Délai expiré : assignation annulée", Toast.LENGTH_SHORT).show()
            }
        }
        mainHandler.postDelayed(cancelListeningRunnable!!, 15000L)
    }

    private fun cancelKeyListening() {
        cancelListeningRunnable?.let { mainHandler.removeCallbacks(it) }
        cancelListeningRunnable = null

        isListeningForKey = false
        SleepAccessibilityService.isListeningForKeyAssignation = false
        SleepAccessibilityService.keyAssignListener = null
        binding.btnAssignKey.text = getString(R.string.btn_assign_key)
        binding.tvAssignKeyFeedback.text = "Assignation annulée"
        binding.tvAssignKeyFeedback.setTextColor(getColor(R.color.tv_text_secondary))
        mainHandler.postDelayed({
            if (!isListeningForKey) {
                binding.tvAssignKeyFeedback.visibility = View.GONE
            }
        }, 2000L)
    }

    // KeyAssignListener Callbacks
    override fun onKeyAssigned(keyCode: Int) {
        runOnUiThread {
            cancelListeningRunnable?.let { mainHandler.removeCallbacks(it) }
            cancelListeningRunnable = null

            isListeningForKey = false
            SleepAccessibilityService.keyAssignListener = null
            binding.btnAssignKey.text = getString(R.string.btn_assign_key)
            val keyLabel = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            binding.tvAssignKeyFeedback.text = "✓ Touche assignée avec succès : $keyLabel"
            binding.tvAssignKeyFeedback.setTextColor(getColor(R.color.tv_status_ok))
            binding.tvAssignKeyFeedback.visibility = View.VISIBLE
            updateAssignedKeyUi(keyCode)
        }
    }

    override fun onForbiddenKeyDetected(keyCode: Int) {
        runOnUiThread {
            binding.tvAssignKeyFeedback.text = getString(R.string.key_forbidden_warning)
            binding.tvAssignKeyFeedback.setTextColor(getColor(R.color.tv_status_warning))
            binding.tvAssignKeyFeedback.visibility = View.VISIBLE
        }
    }

    override fun onKeyListeningCancelled() {
        runOnUiThread {
            cancelKeyListening()
        }
    }

    // In case SetupActivity has focus when key is pressed
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && TimerService.isTestingVolumeFade) {
            TimerService.stopVolumeFadeTest(this)
            return true
        }

        if (isListeningForKey && event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                cancelKeyListening()
                return true
            } else if (SleepAccessibilityService.isForbiddenKey(keyCode)) {
                onForbiddenKeyDetected(keyCode)
                return true
            } else {
                lifecycleScope.launch {
                    prefsManager.setAssignedKeyCode(keyCode)
                }
                onKeyAssigned(keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ==========================================
    // SECTION 4 : NIGHT DIMMER
    // ==========================================

    private fun setupNightDimmerControls() {
        binding.btnDimmerToggle.setOnClickListener {
            val newState = !prefsManager.cachedDimmerEnabled
            lifecycleScope.launch { prefsManager.setDimmerEnabled(newState) }
        }

        binding.btnDimmerIntensity.setOnClickListener {
            val current = prefsManager.cachedDimmerIntensity
            val nextIndex = (dimmerIntensityOptions.indexOf(current) + 1) % dimmerIntensityOptions.size
            val nextIntensity = dimmerIntensityOptions[nextIndex]
            lifecycleScope.launch { prefsManager.setDimmerIntensity(nextIntensity) }
        }

        binding.btnDimmerMode.setOnClickListener {
            val current = prefsManager.cachedDimmerMode
            val nextIndex = (dimmerModeOptions.indexOf(current) + 1) % dimmerModeOptions.size
            val nextMode = dimmerModeOptions[nextIndex]
            lifecycleScope.launch { prefsManager.setDimmerMode(nextMode) }
        }

        binding.btnTestDimmer.setOnClickListener {
            testDimmerPreview()
        }
    }

    private fun updateDimmerEnabledUi(enabled: Boolean) {
        if (enabled) {
            binding.btnDimmerToggle.text = "Assombrissement : ACTIVÉ ✓"
            binding.btnDimmerToggle.setTextColor(getColor(R.color.tv_status_ok))
            binding.layoutDimmerOptions.visibility = View.VISIBLE
        } else {
            binding.btnDimmerToggle.text = "Assombrissement : DÉSACTIVÉ"
            binding.btnDimmerToggle.setTextColor(getColor(R.color.tv_text_secondary))
            binding.layoutDimmerOptions.visibility = View.GONE
        }
    }

    private fun testDimmerPreview() {
        val alpha = prefsManager.getDimmerAlphaFloat()
        overlayManager.showDimmer(alpha)
        overlayManager.showHud(
            title = "Test Assombrissement (${(alpha * 100).toInt()}%)",
            subtitle = "L'écran reviendra à la normale dans 4s",
            autoDismissMs = 4000L
        )
        mainHandler.postDelayed({
            overlayManager.hideDimmer()
        }, 4000L)
    }

    // ==========================================
    // SECTION 5 : BAISSE DE VOLUME PROGRESSIVE
    // ==========================================

    private fun setupVolumeFadeControls() {
        binding.btnVolumeFadeToggle.setOnClickListener {
            val newState = !prefsManager.cachedVolumeFadeEnabled
            lifecycleScope.launch { prefsManager.setVolumeFadeEnabled(newState) }
        }

        binding.btnVolumeFadeMode.setOnClickListener {
            val current = prefsManager.cachedVolumeFadeMode
            val nextIndex = (volumeFadeModeOptions.indexOf(current) + 1) % volumeFadeModeOptions.size
            val nextMode = volumeFadeModeOptions[nextIndex]
            lifecycleScope.launch { prefsManager.setVolumeFadeMode(nextMode) }
        }

        binding.btnRestoreVolume.setOnClickListener {
            val newState = !prefsManager.cachedRestoreVolumeOnWake
            lifecycleScope.launch { prefsManager.setRestoreVolumeOnWake(newState) }
        }

        binding.btnTestVolumeFade.setOnClickListener {
            if (TimerService.isTestingVolumeFade) {
                TimerService.stopVolumeFadeTest(this)
            } else {
                TimerService.testVolumeFade(this)
            }
        }
    }

    private fun updateVolumeFadeEnabledUi(enabled: Boolean) {
        if (enabled) {
            binding.btnVolumeFadeToggle.text = "Baisse de volume : ACTIVÉE ✓"
            binding.btnVolumeFadeToggle.setTextColor(getColor(R.color.tv_status_ok))
            binding.layoutVolumeFadeOptions.visibility = View.VISIBLE
            checkAndDisplayVolumeRoutingStatus()
        } else {
            binding.btnVolumeFadeToggle.text = "Baisse de volume : DÉSACTIVÉE"
            binding.btnVolumeFadeToggle.setTextColor(getColor(R.color.tv_text_secondary))
            binding.layoutVolumeFadeOptions.visibility = View.GONE
        }
    }

    private fun checkAndDisplayVolumeRoutingStatus() {
        val behavior = try {
            Settings.Global.getInt(contentResolver, "AudioService_DeviceVolumeBehavior_hdmi", 0)
        } catch (e: Exception) {
            0
        }

        if (behavior == 1) {
            binding.tvVolumeRoutingHint.text = "⚠️ Volume TV en mode Infrarouge (IR) :\nSur Google TV, le son HDMI est verrouillé à 100% quand la télécommande contrôle la TV en direct. Pour que la baisse progressive fonctionne, allez dans :\nParamètres TV > Télécommandes et accessoires > Configurer les boutons > Contrôle du volume > Choisissez 'Google TV Streamer'."
            binding.tvVolumeRoutingHint.setTextColor(getColor(R.color.tv_status_warning))
        } else {
            binding.tvVolumeRoutingHint.text = "✓ Volume Google TV actif :\nLa sortie HDMI permet l'atténuation sonore numérique. La baisse progressive du volume est 100% opérationnelle."
            binding.tvVolumeRoutingHint.setTextColor(getColor(R.color.tv_status_ok))
        }
    }

    // ==========================================
    // SECTION 6 : TESTS & ACTIONS
    // ==========================================

    private fun setupActionButtons() {
        binding.btnSimulatePress.setOnClickListener {
            TimerService.startOrIncrement(this)
            refreshTimerStatusBadge()
        }

        binding.btnTestSleep.setOnClickListener {
            if (!SleepAccessibilityService.isAccessibilityServiceEnabled(this)) {
                Toast.makeText(
                    this,
                    "Activez le Service d'Accessibilité pour permettre la mise en veille",
                    Toast.LENGTH_LONG
                ).show()
                binding.btnPermAccessibility.requestFocus()
            } else {
                TimerService.testSleep(this)
            }
        }
    }

    private fun refreshTimerStatusBadge() {
        if (TimerService.isRunning) {
            val remaining = TimerService.getRemainingMinutes()
            binding.tvTimerStatusBadge.text = "● Actif : $remaining min restantes"
            binding.tvTimerStatusBadge.setTextColor(getColor(R.color.tv_status_ok))
        } else {
            binding.tvTimerStatusBadge.text = "○ Timer inactif"
            binding.tvTimerStatusBadge.setTextColor(getColor(R.color.tv_text_secondary))
        }
    }

    companion object {
        private const val TAG = "QuickSleep_Setup"
    }
}
