package com.quicksleeptimer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "quick_sleep_preferences")

class PreferencesManager(private val context: Context) {

    private val applicationScope = CoroutineScope(Dispatchers.IO)

    // Cached values for zero-latency access in onKeyEvent and WindowManager overlays
    @Volatile var cachedInitialDuration: Int = DEFAULT_INITIAL_DURATION
        private set
    @Volatile var cachedIncrementStep: Int = DEFAULT_INCREMENT_STEP
        private set
    @Volatile var cachedMaxDuration: Int = DEFAULT_MAX_DURATION
        private set
    @Volatile var cachedTriggerMode: String = MODE_ACCESSIBILITY
        private set
    @Volatile var cachedTriggerTarget: String = DEFAULT_TRIGGER_TARGET
        private set
    @Volatile var isLiveTriggerEnabled: Boolean = isXiaomiDevice()
        private set
    @Volatile var isNetflixTriggerEnabled: Boolean = isXiaomiDevice()
        private set
    @Volatile var isStarTriggerEnabled: Boolean = !isXiaomiDevice()
        private set
    @Volatile var cachedAssignedKeyCode: Int = DEFAULT_KEYCODE
        private set
    @Volatile var cachedAssignedScanCode: Int = 0
        private set
    @Volatile var cachedHudDisplaySeconds: Int = DEFAULT_HUD_SECONDS
        private set
    @Volatile var cachedDimmerEnabled: Boolean = false
        private set
    @Volatile var cachedDimmerIntensity: String = INTENSITY_MEDIUM
        private set
    @Volatile var cachedDimmerMode: String = DIMMER_MODE_IMMEDIATE
        private set
    @Volatile var cachedVolumeFadeEnabled: Boolean = false
        private set
    @Volatile var cachedVolumeFadeMode: String = VOLUME_FADE_LAST_5MIN
        private set
    @Volatile var cachedRestoreVolumeOnWake: Boolean = true
        private set

    init {
        // Pre-warm cache on creation
        applicationScope.launch {
            context.dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .collect { prefs ->
                    cachedInitialDuration = prefs[KEY_INITIAL_DURATION] ?: DEFAULT_INITIAL_DURATION
                    cachedIncrementStep = prefs[KEY_INCREMENT_STEP] ?: DEFAULT_INCREMENT_STEP
                    cachedMaxDuration = prefs[KEY_MAX_DURATION] ?: DEFAULT_MAX_DURATION
                    cachedTriggerMode = prefs[KEY_TRIGGER_MODE] ?: MODE_ACCESSIBILITY
                    cachedTriggerTarget = prefs[KEY_TRIGGER_TARGET] ?: DEFAULT_TRIGGER_TARGET
                    isLiveTriggerEnabled = prefs[KEY_TRIGGER_LIVE_ENABLED] ?: (cachedTriggerTarget == TRIGGER_LIVE || isXiaomiDevice())
                    isNetflixTriggerEnabled = prefs[KEY_TRIGGER_NETFLIX_ENABLED] ?: (cachedTriggerTarget == TRIGGER_NETFLIX || isXiaomiDevice())
                    isStarTriggerEnabled = prefs[KEY_TRIGGER_STAR_ENABLED] ?: (cachedTriggerTarget == TRIGGER_STAR || !isXiaomiDevice())
                    cachedAssignedKeyCode = prefs[KEY_ASSIGNED_KEYCODE] ?: DEFAULT_KEYCODE
                    cachedAssignedScanCode = prefs[KEY_ASSIGNED_SCANCODE] ?: 0
                    cachedHudDisplaySeconds = prefs[KEY_HUD_DISPLAY_SECONDS] ?: DEFAULT_HUD_SECONDS
                    cachedDimmerEnabled = prefs[KEY_DIMMER_ENABLED] ?: false
                    cachedDimmerIntensity = prefs[KEY_DIMMER_INTENSITY] ?: INTENSITY_MEDIUM
                    cachedDimmerMode = prefs[KEY_DIMMER_MODE] ?: DIMMER_MODE_IMMEDIATE
                    cachedVolumeFadeEnabled = prefs[KEY_VOLUME_FADE_ENABLED] ?: false
                    cachedVolumeFadeMode = prefs[KEY_VOLUME_FADE_MODE] ?: VOLUME_FADE_LAST_5MIN
                    cachedRestoreVolumeOnWake = prefs[KEY_RESTORE_VOLUME_ON_WAKE] ?: true
                }
        }
    }

    // Flows for reactive UI updates
    val initialDurationFlow: Flow<Int> = context.dataStore.data.map { it[KEY_INITIAL_DURATION] ?: DEFAULT_INITIAL_DURATION }
    val incrementStepFlow: Flow<Int> = context.dataStore.data.map { it[KEY_INCREMENT_STEP] ?: DEFAULT_INCREMENT_STEP }
    val maxDurationFlow: Flow<Int> = context.dataStore.data.map { it[KEY_MAX_DURATION] ?: DEFAULT_MAX_DURATION }
    val triggerModeFlow: Flow<String> = context.dataStore.data.map { it[KEY_TRIGGER_MODE] ?: MODE_ACCESSIBILITY }
    val triggerTargetFlow: Flow<String> = context.dataStore.data.map { it[KEY_TRIGGER_TARGET] ?: DEFAULT_TRIGGER_TARGET }
    val isLiveTriggerEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_TRIGGER_LIVE_ENABLED] ?: (it[KEY_TRIGGER_TARGET] == TRIGGER_LIVE || isXiaomiDevice())
    }
    val isNetflixTriggerEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_TRIGGER_NETFLIX_ENABLED] ?: (it[KEY_TRIGGER_TARGET] == TRIGGER_NETFLIX || isXiaomiDevice())
    }
    val isStarTriggerEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_TRIGGER_STAR_ENABLED] ?: (it[KEY_TRIGGER_TARGET] == TRIGGER_STAR || !isXiaomiDevice())
    }
    val assignedKeyCodeFlow: Flow<Int> = context.dataStore.data.map { it[KEY_ASSIGNED_KEYCODE] ?: DEFAULT_KEYCODE }
    val assignedScanCodeFlow: Flow<Int> = context.dataStore.data.map { it[KEY_ASSIGNED_SCANCODE] ?: 0 }
    val hudDisplaySecondsFlow: Flow<Int> = context.dataStore.data.map { it[KEY_HUD_DISPLAY_SECONDS] ?: DEFAULT_HUD_SECONDS }
    val dimmerEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_DIMMER_ENABLED] ?: false }
    val dimmerIntensityFlow: Flow<String> = context.dataStore.data.map { it[KEY_DIMMER_INTENSITY] ?: INTENSITY_MEDIUM }
    val dimmerModeFlow: Flow<String> = context.dataStore.data.map { it[KEY_DIMMER_MODE] ?: DIMMER_MODE_IMMEDIATE }
    val volumeFadeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_VOLUME_FADE_ENABLED] ?: false }
    val volumeFadeModeFlow: Flow<String> = context.dataStore.data.map { it[KEY_VOLUME_FADE_MODE] ?: VOLUME_FADE_LAST_5MIN }
    val restoreVolumeOnWakeFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_RESTORE_VOLUME_ON_WAKE] ?: true }

    suspend fun setInitialDuration(minutes: Int) {
        cachedInitialDuration = minutes
        context.dataStore.edit { it[KEY_INITIAL_DURATION] = minutes }
    }

    suspend fun setIncrementStep(minutes: Int) {
        cachedIncrementStep = minutes
        context.dataStore.edit { it[KEY_INCREMENT_STEP] = minutes }
    }

    suspend fun setMaxDuration(minutes: Int) {
        cachedMaxDuration = minutes
        context.dataStore.edit { it[KEY_MAX_DURATION] = minutes }
    }

    suspend fun setTriggerMode(mode: String) {
        cachedTriggerMode = mode
        context.dataStore.edit { it[KEY_TRIGGER_MODE] = mode }
    }

    suspend fun setTriggerTarget(target: String) {
        cachedTriggerTarget = target
        context.dataStore.edit { it[KEY_TRIGGER_TARGET] = target }
    }

    suspend fun setLiveTriggerEnabled(enabled: Boolean) {
        isLiveTriggerEnabled = enabled
        context.dataStore.edit { it[KEY_TRIGGER_LIVE_ENABLED] = enabled }
    }

    suspend fun setNetflixTriggerEnabled(enabled: Boolean) {
        isNetflixTriggerEnabled = enabled
        context.dataStore.edit { it[KEY_TRIGGER_NETFLIX_ENABLED] = enabled }
    }

    suspend fun setStarTriggerEnabled(enabled: Boolean) {
        isStarTriggerEnabled = enabled
        context.dataStore.edit { it[KEY_TRIGGER_STAR_ENABLED] = enabled }
    }

    suspend fun setAssignedKeyCode(keyCode: Int) {
        cachedAssignedKeyCode = keyCode
        context.dataStore.edit { it[KEY_ASSIGNED_KEYCODE] = keyCode }
    }

    suspend fun setAssignedKey(keyCode: Int, scanCode: Int) {
        cachedAssignedKeyCode = keyCode
        cachedAssignedScanCode = scanCode
        context.dataStore.edit {
            it[KEY_ASSIGNED_KEYCODE] = keyCode
            it[KEY_ASSIGNED_SCANCODE] = scanCode
        }
    }

    suspend fun setAssignedScanCode(scanCode: Int) {
        cachedAssignedScanCode = scanCode
        context.dataStore.edit { it[KEY_ASSIGNED_SCANCODE] = scanCode }
    }

    suspend fun setHudDisplaySeconds(seconds: Int) {
        cachedHudDisplaySeconds = seconds
        context.dataStore.edit { it[KEY_HUD_DISPLAY_SECONDS] = seconds }
    }

    suspend fun setDimmerEnabled(enabled: Boolean) {
        cachedDimmerEnabled = enabled
        context.dataStore.edit { it[KEY_DIMMER_ENABLED] = enabled }
    }

    suspend fun setDimmerIntensity(intensity: String) {
        cachedDimmerIntensity = intensity
        context.dataStore.edit { it[KEY_DIMMER_INTENSITY] = intensity }
    }

    suspend fun setDimmerMode(mode: String) {
        cachedDimmerMode = mode
        context.dataStore.edit { it[KEY_DIMMER_MODE] = mode }
    }

    suspend fun setVolumeFadeEnabled(enabled: Boolean) {
        cachedVolumeFadeEnabled = enabled
        context.dataStore.edit { it[KEY_VOLUME_FADE_ENABLED] = enabled }
    }

    suspend fun setVolumeFadeMode(mode: String) {
        cachedVolumeFadeMode = mode
        context.dataStore.edit { it[KEY_VOLUME_FADE_MODE] = mode }
    }

    suspend fun setRestoreVolumeOnWake(restore: Boolean) {
        cachedRestoreVolumeOnWake = restore
        context.dataStore.edit { it[KEY_RESTORE_VOLUME_ON_WAKE] = restore }
    }

    fun getDimmerAlphaFloat(intensity: String = cachedDimmerIntensity): Float {
        return when (intensity) {
            INTENSITY_LIGHT -> 0.25f
            INTENSITY_MEDIUM -> 0.50f
            INTENSITY_STRONG -> 0.75f
            INTENSITY_VERY_STRONG -> 0.85f
            INTENSITY_EXTREME -> 0.95f
            else -> 0.50f
        }
    }

    companion object {
        const val DEFAULT_INITIAL_DURATION = 20
        const val DEFAULT_INCREMENT_STEP = 10
        const val DEFAULT_MAX_DURATION = 120
        const val DEFAULT_HUD_SECONDS = 4
        const val DEFAULT_KEYCODE = -1

        const val MODE_ACCESSIBILITY = "accessibility"
        const val MODE_BUTTON_MAPPER = "button_mapper"

        const val TRIGGER_STAR = "star"
        const val TRIGGER_LIVE = "live"
        const val TRIGGER_NETFLIX = "netflix"
        const val TRIGGER_CUSTOM = "custom"

        fun isXiaomiDevice(): Boolean {
            val manufacturer = android.os.Build.MANUFACTURER ?: ""
            val model = android.os.Build.MODEL ?: ""
            return manufacturer.contains("Xiaomi", ignoreCase = true) || model.contains("MIBOX", ignoreCase = true)
        }

        val DEFAULT_TRIGGER_TARGET: String
            get() {
                return if (isXiaomiDevice()) {
                    TRIGGER_LIVE
                } else {
                    TRIGGER_STAR
                }
            }

        const val INTENSITY_LIGHT = "light"
        const val INTENSITY_MEDIUM = "medium"
        const val INTENSITY_STRONG = "strong"
        const val INTENSITY_VERY_STRONG = "very_strong"
        const val INTENSITY_EXTREME = "extreme"

        const val DIMMER_MODE_IMMEDIATE = "immediate"
        const val DIMMER_MODE_FULL = "progressive_full"
        const val DIMMER_MODE_LAST_5MIN = "progressive_5min"

        const val VOLUME_FADE_LAST_5MIN = "last_5min"
        const val VOLUME_FADE_LAST_10MIN = "last_10min"
        const val VOLUME_FADE_FULL = "full"

        private val KEY_INITIAL_DURATION = intPreferencesKey("pref_initial_duration")
        private val KEY_INCREMENT_STEP = intPreferencesKey("pref_increment_step")
        private val KEY_MAX_DURATION = intPreferencesKey("pref_max_duration")
        private val KEY_TRIGGER_MODE = stringPreferencesKey("pref_trigger_mode")
        private val KEY_TRIGGER_TARGET = stringPreferencesKey("pref_trigger_target")
        val KEY_TRIGGER_LIVE_ENABLED = booleanPreferencesKey("pref_trigger_live_enabled")
        val KEY_TRIGGER_NETFLIX_ENABLED = booleanPreferencesKey("pref_trigger_netflix_enabled")
        val KEY_TRIGGER_STAR_ENABLED = booleanPreferencesKey("pref_trigger_star_enabled")
        private val KEY_ASSIGNED_KEYCODE = intPreferencesKey("pref_assigned_keycode")
        private val KEY_ASSIGNED_SCANCODE = intPreferencesKey("pref_assigned_scancode")
        private val KEY_HUD_DISPLAY_SECONDS = intPreferencesKey("pref_hud_display_seconds")
        private val KEY_DIMMER_ENABLED = booleanPreferencesKey("pref_dimmer_enabled")
        private val KEY_DIMMER_INTENSITY = stringPreferencesKey("pref_dimmer_intensity")
        private val KEY_DIMMER_MODE = stringPreferencesKey("pref_dimmer_mode")
        private val KEY_VOLUME_FADE_ENABLED = booleanPreferencesKey("pref_volume_fade_enabled")
        private val KEY_VOLUME_FADE_MODE = stringPreferencesKey("pref_volume_fade_mode")
        private val KEY_RESTORE_VOLUME_ON_WAKE = booleanPreferencesKey("pref_restore_volume_on_wake")

        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
