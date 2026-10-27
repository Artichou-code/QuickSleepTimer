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

    // Independent multi-button mapping cache
    @Volatile var cachedActionLive: String = if (isXiaomiDevice()) ACTION_TIMER else ACTION_DISABLED
        private set
    @Volatile var cachedAppPkgLive: String = ""
        private set
    @Volatile var cachedAppLabelLive: String = ""
        private set

    @Volatile var cachedActionNetflix: String = ACTION_DISABLED
        private set
    @Volatile var cachedAppPkgNetflix: String = ""
        private set
    @Volatile var cachedAppLabelNetflix: String = ""
        private set

    @Volatile var cachedActionStar: String = if (!isXiaomiDevice()) ACTION_TIMER else ACTION_DISABLED
        private set
    @Volatile var cachedAppPkgStar: String = ""
        private set
    @Volatile var cachedAppLabelStar: String = ""
        private set

    @Volatile var cachedActionApps: String = ACTION_DISABLED
        private set
    @Volatile var cachedAppPkgApps: String = ""
        private set
    @Volatile var cachedAppLabelApps: String = ""
        private set

    @Volatile var cachedActionCustom: String = ACTION_DISABLED
        private set
    @Volatile var cachedAppPkgCustom: String = ""
        private set
    @Volatile var cachedAppLabelCustom: String = ""
        private set

    @Volatile var cachedAssignedKeyCode: Int = DEFAULT_KEYCODE
        private set
    @Volatile var cachedAssignedScanCode: Int = 0
        private set
    @Volatile var cachedAssignedLabel: String = ""
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

    // Soft Wake-up (Progressive startup brightness)
    @Volatile var cachedSoftWakeEnabled: Boolean = DEFAULT_SOFT_WAKE_ENABLED
        private set
    @Volatile var cachedSoftWakeStartHour: Int = DEFAULT_SOFT_WAKE_START_HOUR
        private set
    @Volatile var cachedSoftWakeStartMinute: Int = DEFAULT_SOFT_WAKE_START_MINUTE
        private set
    @Volatile var cachedSoftWakeEndHour: Int = DEFAULT_SOFT_WAKE_END_HOUR
        private set
    @Volatile var cachedSoftWakeEndMinute: Int = DEFAULT_SOFT_WAKE_END_MINUTE
        private set
    @Volatile var cachedSoftWakeDurationMin: Int = DEFAULT_SOFT_WAKE_DURATION_MIN
        private set
    @Volatile var cachedSoftWakeIntensity: String = DEFAULT_SOFT_WAKE_INTENSITY
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

                    // Multi-button mappings with backward-compatibility fallbacks
                    val legacyTarget = prefs[KEY_TRIGGER_TARGET]
                    val legacyAction = prefs[KEY_TRIGGER_ACTION] ?: ACTION_TIMER
                    val legacyPkg = prefs[KEY_TRIGGER_APP_PKG] ?: ""
                    val legacyLabel = prefs[KEY_TRIGGER_APP_LABEL] ?: ""

                    cachedActionLive = prefs[KEY_ACTION_LIVE] ?: run {
                        if (prefs[KEY_TRIGGER_LIVE_ENABLED] == true || legacyTarget == TRIGGER_LIVE || isXiaomiDevice()) {
                            if (legacyTarget == TRIGGER_LIVE && legacyAction == ACTION_APP) ACTION_APP else ACTION_TIMER
                        } else {
                            ACTION_DISABLED
                        }
                    }
                    cachedAppPkgLive = prefs[KEY_APP_PKG_LIVE] ?: if (cachedActionLive == ACTION_APP) legacyPkg else ""
                    cachedAppLabelLive = prefs[KEY_APP_LABEL_LIVE] ?: if (cachedActionLive == ACTION_APP) legacyLabel else ""

                    cachedActionNetflix = prefs[KEY_ACTION_NETFLIX] ?: run {
                        if (prefs[KEY_TRIGGER_NETFLIX_ENABLED] == true || legacyTarget == TRIGGER_NETFLIX) {
                            if (legacyTarget == TRIGGER_NETFLIX && legacyAction == ACTION_APP) ACTION_APP else ACTION_TIMER
                        } else {
                            ACTION_DISABLED
                        }
                    }
                    cachedAppPkgNetflix = prefs[KEY_APP_PKG_NETFLIX] ?: if (cachedActionNetflix == ACTION_APP) legacyPkg else ""
                    cachedAppLabelNetflix = prefs[KEY_APP_LABEL_NETFLIX] ?: if (cachedActionNetflix == ACTION_APP) legacyLabel else ""

                    cachedActionStar = prefs[KEY_ACTION_STAR] ?: run {
                        if (prefs[KEY_TRIGGER_STAR_ENABLED] == true || legacyTarget == TRIGGER_STAR || (!isXiaomiDevice() && legacyTarget == null)) {
                            if (legacyTarget == TRIGGER_STAR && legacyAction == ACTION_APP) ACTION_APP else ACTION_TIMER
                        } else {
                            ACTION_DISABLED
                        }
                    }
                    cachedAppPkgStar = prefs[KEY_APP_PKG_STAR] ?: if (cachedActionStar == ACTION_APP) legacyPkg else ""
                    cachedAppLabelStar = prefs[KEY_APP_LABEL_STAR] ?: if (cachedActionStar == ACTION_APP) legacyLabel else ""

                    cachedActionApps = prefs[KEY_ACTION_APPS] ?: ACTION_DISABLED
                    cachedAppPkgApps = prefs[KEY_APP_PKG_APPS] ?: ""
                    cachedAppLabelApps = prefs[KEY_APP_LABEL_APPS] ?: ""

                    cachedActionCustom = prefs[KEY_ACTION_CUSTOM] ?: run {
                        if (legacyTarget == TRIGGER_CUSTOM) {
                            if (legacyAction == ACTION_APP) ACTION_APP else ACTION_TIMER
                        } else {
                            ACTION_DISABLED
                        }
                    }
                    cachedAppPkgCustom = prefs[KEY_APP_PKG_CUSTOM] ?: if (cachedActionCustom == ACTION_APP) legacyPkg else ""
                    cachedAppLabelCustom = prefs[KEY_APP_LABEL_CUSTOM] ?: if (cachedActionCustom == ACTION_APP) legacyLabel else ""

                    cachedAssignedKeyCode = prefs[KEY_ASSIGNED_KEYCODE] ?: DEFAULT_KEYCODE
                    cachedAssignedScanCode = prefs[KEY_ASSIGNED_SCANCODE] ?: 0
                    cachedAssignedLabel = prefs[KEY_ASSIGNED_LABEL] ?: ""

                    cachedHudDisplaySeconds = prefs[KEY_HUD_DISPLAY_SECONDS] ?: DEFAULT_HUD_SECONDS
                    cachedDimmerEnabled = prefs[KEY_DIMMER_ENABLED] ?: false
                    cachedDimmerIntensity = prefs[KEY_DIMMER_INTENSITY] ?: INTENSITY_MEDIUM
                    cachedDimmerMode = prefs[KEY_DIMMER_MODE] ?: DIMMER_MODE_IMMEDIATE
                    cachedVolumeFadeEnabled = prefs[KEY_VOLUME_FADE_ENABLED] ?: false
                    cachedVolumeFadeMode = prefs[KEY_VOLUME_FADE_MODE] ?: VOLUME_FADE_LAST_5MIN
                    cachedRestoreVolumeOnWake = prefs[KEY_RESTORE_VOLUME_ON_WAKE] ?: true

                    // Soft wake cache
                    cachedSoftWakeEnabled = prefs[KEY_SOFT_WAKE_ENABLED] ?: DEFAULT_SOFT_WAKE_ENABLED
                    cachedSoftWakeStartHour = prefs[KEY_SOFT_WAKE_START_HOUR] ?: DEFAULT_SOFT_WAKE_START_HOUR
                    cachedSoftWakeStartMinute = prefs[KEY_SOFT_WAKE_START_MINUTE] ?: DEFAULT_SOFT_WAKE_START_MINUTE
                    cachedSoftWakeEndHour = prefs[KEY_SOFT_WAKE_END_HOUR] ?: DEFAULT_SOFT_WAKE_END_HOUR
                    cachedSoftWakeEndMinute = prefs[KEY_SOFT_WAKE_END_MINUTE] ?: DEFAULT_SOFT_WAKE_END_MINUTE
                    cachedSoftWakeDurationMin = prefs[KEY_SOFT_WAKE_DURATION_MIN] ?: DEFAULT_SOFT_WAKE_DURATION_MIN
                    cachedSoftWakeIntensity = prefs[KEY_SOFT_WAKE_INTENSITY] ?: DEFAULT_SOFT_WAKE_INTENSITY
                }
        }
    }

    // Flows for reactive UI updates
    val initialDurationFlow: Flow<Int> = context.dataStore.data.map { it[KEY_INITIAL_DURATION] ?: DEFAULT_INITIAL_DURATION }
    val incrementStepFlow: Flow<Int> = context.dataStore.data.map { it[KEY_INCREMENT_STEP] ?: DEFAULT_INCREMENT_STEP }
    val maxDurationFlow: Flow<Int> = context.dataStore.data.map { it[KEY_MAX_DURATION] ?: DEFAULT_MAX_DURATION }
    val triggerModeFlow: Flow<String> = context.dataStore.data.map { it[KEY_TRIGGER_MODE] ?: MODE_ACCESSIBILITY }

    val actionLiveFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_ACTION_LIVE] ?: if (it[KEY_TRIGGER_LIVE_ENABLED] == true || isXiaomiDevice()) ACTION_TIMER else ACTION_DISABLED
    }
    val appLabelLiveFlow: Flow<String> = context.dataStore.data.map { it[KEY_APP_LABEL_LIVE] ?: "" }

    val actionNetflixFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_ACTION_NETFLIX] ?: if (it[KEY_TRIGGER_NETFLIX_ENABLED] == true) ACTION_TIMER else ACTION_DISABLED
    }
    val appLabelNetflixFlow: Flow<String> = context.dataStore.data.map { it[KEY_APP_LABEL_NETFLIX] ?: "" }

    val actionStarFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_ACTION_STAR] ?: if (it[KEY_TRIGGER_STAR_ENABLED] == true || !isXiaomiDevice()) ACTION_TIMER else ACTION_DISABLED
    }
    val appLabelStarFlow: Flow<String> = context.dataStore.data.map { it[KEY_APP_LABEL_STAR] ?: "" }

    val actionAppsFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_ACTION_APPS] ?: ACTION_DISABLED
    }
    val appLabelAppsFlow: Flow<String> = context.dataStore.data.map { it[KEY_APP_LABEL_APPS] ?: "" }

    val actionCustomFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_ACTION_CUSTOM] ?: ACTION_DISABLED
    }
    val appLabelCustomFlow: Flow<String> = context.dataStore.data.map { it[KEY_APP_LABEL_CUSTOM] ?: "" }
    val assignedLabelFlow: Flow<String> = context.dataStore.data.map { it[KEY_ASSIGNED_LABEL] ?: "" }

    val assignedKeyCodeFlow: Flow<Int> = context.dataStore.data.map { it[KEY_ASSIGNED_KEYCODE] ?: DEFAULT_KEYCODE }
    val assignedScanCodeFlow: Flow<Int> = context.dataStore.data.map { it[KEY_ASSIGNED_SCANCODE] ?: 0 }
    val hudDisplaySecondsFlow: Flow<Int> = context.dataStore.data.map { it[KEY_HUD_DISPLAY_SECONDS] ?: DEFAULT_HUD_SECONDS }
    val dimmerEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_DIMMER_ENABLED] ?: false }
    val dimmerIntensityFlow: Flow<String> = context.dataStore.data.map { it[KEY_DIMMER_INTENSITY] ?: INTENSITY_MEDIUM }
    val dimmerModeFlow: Flow<String> = context.dataStore.data.map { it[KEY_DIMMER_MODE] ?: DIMMER_MODE_IMMEDIATE }
    val volumeFadeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_VOLUME_FADE_ENABLED] ?: false }
    val volumeFadeModeFlow: Flow<String> = context.dataStore.data.map { it[KEY_VOLUME_FADE_MODE] ?: VOLUME_FADE_LAST_5MIN }
    val restoreVolumeOnWakeFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_RESTORE_VOLUME_ON_WAKE] ?: true }

    val softWakeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[KEY_SOFT_WAKE_ENABLED] ?: DEFAULT_SOFT_WAKE_ENABLED }
    val softWakeDurationFlow: Flow<Int> = context.dataStore.data.map { it[KEY_SOFT_WAKE_DURATION_MIN] ?: DEFAULT_SOFT_WAKE_DURATION_MIN }
    val softWakeIntensityFlow: Flow<String> = context.dataStore.data.map { it[KEY_SOFT_WAKE_INTENSITY] ?: DEFAULT_SOFT_WAKE_INTENSITY }

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

    suspend fun setLiveConfig(action: String, appPkg: String = "", appLabel: String = "") {
        cachedActionLive = action
        cachedAppPkgLive = appPkg
        cachedAppLabelLive = appLabel
        context.dataStore.edit {
            it[KEY_ACTION_LIVE] = action
            it[KEY_APP_PKG_LIVE] = appPkg
            it[KEY_APP_LABEL_LIVE] = appLabel
        }
    }

    suspend fun setNetflixConfig(action: String, appPkg: String = "", appLabel: String = "") {
        cachedActionNetflix = action
        cachedAppPkgNetflix = appPkg
        cachedAppLabelNetflix = appLabel
        context.dataStore.edit {
            it[KEY_ACTION_NETFLIX] = action
            it[KEY_APP_PKG_NETFLIX] = appPkg
            it[KEY_APP_LABEL_NETFLIX] = appLabel
        }
    }

    suspend fun setStarConfig(action: String, appPkg: String = "", appLabel: String = "") {
        cachedActionStar = action
        cachedAppPkgStar = appPkg
        cachedAppLabelStar = appLabel
        context.dataStore.edit {
            it[KEY_ACTION_STAR] = action
            it[KEY_APP_PKG_STAR] = appPkg
            it[KEY_APP_LABEL_STAR] = appLabel
        }
    }

    suspend fun setAppsConfig(action: String, appPkg: String = "", appLabel: String = "") {
        cachedActionApps = action
        cachedAppPkgApps = appPkg
        cachedAppLabelApps = appLabel
        context.dataStore.edit {
            it[KEY_ACTION_APPS] = action
            it[KEY_APP_PKG_APPS] = appPkg
            it[KEY_APP_LABEL_APPS] = appLabel
        }
    }

    suspend fun setCustomConfig(action: String, appPkg: String = "", appLabel: String = "") {
        cachedActionCustom = action
        cachedAppPkgCustom = appPkg
        cachedAppLabelCustom = appLabel
        context.dataStore.edit {
            it[KEY_ACTION_CUSTOM] = action
            it[KEY_APP_PKG_CUSTOM] = appPkg
            it[KEY_APP_LABEL_CUSTOM] = appLabel
        }
    }

    suspend fun setAssignedKey(keyCode: Int, scanCode: Int, label: String = "") {
        cachedAssignedKeyCode = keyCode
        cachedAssignedScanCode = scanCode
        cachedAssignedLabel = label
        context.dataStore.edit {
            it[KEY_ASSIGNED_KEYCODE] = keyCode
            it[KEY_ASSIGNED_SCANCODE] = scanCode
            it[KEY_ASSIGNED_LABEL] = label
        }
    }

    suspend fun clearCustomKey() {
        cachedActionCustom = ACTION_DISABLED
        cachedAppPkgCustom = ""
        cachedAppLabelCustom = ""
        cachedAssignedKeyCode = DEFAULT_KEYCODE
        cachedAssignedScanCode = 0
        cachedAssignedLabel = ""
        context.dataStore.edit {
            it[KEY_ACTION_CUSTOM] = ACTION_DISABLED
            it[KEY_APP_PKG_CUSTOM] = ""
            it[KEY_APP_LABEL_CUSTOM] = ""
            it[KEY_ASSIGNED_KEYCODE] = DEFAULT_KEYCODE
            it[KEY_ASSIGNED_SCANCODE] = 0
            it[KEY_ASSIGNED_LABEL] = ""
        }
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

    suspend fun setSoftWakeEnabled(enabled: Boolean) {
        cachedSoftWakeEnabled = enabled
        context.dataStore.edit { it[KEY_SOFT_WAKE_ENABLED] = enabled }
    }

    suspend fun setSoftWakeSchedule(startH: Int, startM: Int, endH: Int, endM: Int) {
        cachedSoftWakeStartHour = startH
        cachedSoftWakeStartMinute = startM
        cachedSoftWakeEndHour = endH
        cachedSoftWakeEndMinute = endM
        context.dataStore.edit {
            it[KEY_SOFT_WAKE_START_HOUR] = startH
            it[KEY_SOFT_WAKE_START_MINUTE] = startM
            it[KEY_SOFT_WAKE_END_HOUR] = endH
            it[KEY_SOFT_WAKE_END_MINUTE] = endM
        }
    }

    suspend fun setSoftWakeDuration(minutes: Int) {
        val clamped = minutes.coerceIn(1, 30)
        cachedSoftWakeDurationMin = clamped
        context.dataStore.edit { it[KEY_SOFT_WAKE_DURATION_MIN] = clamped }
    }

    suspend fun setSoftWakeIntensity(intensity: String) {
        cachedSoftWakeIntensity = intensity
        context.dataStore.edit { it[KEY_SOFT_WAKE_INTENSITY] = intensity }
    }

    fun isCurrentTimeInSoftWakeWindow(): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val currentMinutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        val startMinutes = cachedSoftWakeStartHour * 60 + cachedSoftWakeStartMinute
        val endMinutes = cachedSoftWakeEndHour * 60 + cachedSoftWakeEndMinute

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // Traverse minuit (ex: 23:00 -> 08:00)
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
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

        // Actions available per button
        const val ACTION_DISABLED = "disabled"
        const val ACTION_TIMER = "timer"
        const val ACTION_APP = "app"

        // Legacy constants kept for compatibility
        const val TRIGGER_STAR = "star"
        const val TRIGGER_LIVE = "live"
        const val TRIGGER_NETFLIX = "netflix"
        const val TRIGGER_CUSTOM = "custom"
        const val ACTION_LAUNCH_APP = "app"

        fun isXiaomiDevice(): Boolean {
            val manufacturer = android.os.Build.MANUFACTURER ?: ""
            val model = android.os.Build.MODEL ?: ""
            return manufacturer.contains("Xiaomi", ignoreCase = true) || model.contains("MIBOX", ignoreCase = true)
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

        // Soft Wake-up Defaults
        const val DEFAULT_SOFT_WAKE_ENABLED = false
        const val DEFAULT_SOFT_WAKE_START_HOUR = 23
        const val DEFAULT_SOFT_WAKE_START_MINUTE = 0
        const val DEFAULT_SOFT_WAKE_END_HOUR = 8
        const val DEFAULT_SOFT_WAKE_END_MINUTE = 0
        const val DEFAULT_SOFT_WAKE_DURATION_MIN = 5
        const val DEFAULT_SOFT_WAKE_INTENSITY = INTENSITY_VERY_STRONG

        private val KEY_INITIAL_DURATION = intPreferencesKey("pref_initial_duration")
        private val KEY_INCREMENT_STEP = intPreferencesKey("pref_increment_step")
        private val KEY_MAX_DURATION = intPreferencesKey("pref_max_duration")
        private val KEY_TRIGGER_MODE = stringPreferencesKey("pref_trigger_mode")

        // Multi-button mapping keys
        val KEY_ACTION_LIVE = stringPreferencesKey("pref_action_live")
        val KEY_APP_PKG_LIVE = stringPreferencesKey("pref_app_pkg_live")
        val KEY_APP_LABEL_LIVE = stringPreferencesKey("pref_app_label_live")

        val KEY_ACTION_NETFLIX = stringPreferencesKey("pref_action_netflix")
        val KEY_APP_PKG_NETFLIX = stringPreferencesKey("pref_app_pkg_netflix")
        val KEY_APP_LABEL_NETFLIX = stringPreferencesKey("pref_app_label_netflix")

        val KEY_ACTION_STAR = stringPreferencesKey("pref_action_star")
        val KEY_APP_PKG_STAR = stringPreferencesKey("pref_app_pkg_star")
        val KEY_APP_LABEL_STAR = stringPreferencesKey("pref_app_label_star")

        val KEY_ACTION_APPS = stringPreferencesKey("pref_action_apps")
        val KEY_APP_PKG_APPS = stringPreferencesKey("pref_app_pkg_apps")
        val KEY_APP_LABEL_APPS = stringPreferencesKey("pref_app_label_apps")

        val KEY_ACTION_CUSTOM = stringPreferencesKey("pref_action_custom")
        val KEY_APP_PKG_CUSTOM = stringPreferencesKey("pref_app_pkg_custom")
        val KEY_APP_LABEL_CUSTOM = stringPreferencesKey("pref_app_label_custom")
        val KEY_ASSIGNED_KEYCODE = intPreferencesKey("pref_assigned_keycode")
        val KEY_ASSIGNED_SCANCODE = intPreferencesKey("pref_assigned_scancode")
        val KEY_ASSIGNED_LABEL = stringPreferencesKey("pref_assigned_label")

        // Legacy keys kept for migration
        val KEY_TRIGGER_TARGET = stringPreferencesKey("pref_trigger_target")
        val KEY_TRIGGER_LIVE_ENABLED = booleanPreferencesKey("pref_trigger_live_enabled")
        val KEY_TRIGGER_NETFLIX_ENABLED = booleanPreferencesKey("pref_trigger_netflix_enabled")
        val KEY_TRIGGER_STAR_ENABLED = booleanPreferencesKey("pref_trigger_star_enabled")
        val KEY_TRIGGER_ACTION = stringPreferencesKey("pref_trigger_action")
        val KEY_TRIGGER_APP_PKG = stringPreferencesKey("pref_trigger_app_pkg")
        val KEY_TRIGGER_APP_LABEL = stringPreferencesKey("pref_trigger_app_label")

        private val KEY_HUD_DISPLAY_SECONDS = intPreferencesKey("pref_hud_display_seconds")
        private val KEY_DIMMER_ENABLED = booleanPreferencesKey("pref_dimmer_enabled")
        private val KEY_DIMMER_INTENSITY = stringPreferencesKey("pref_dimmer_intensity")
        private val KEY_DIMMER_MODE = stringPreferencesKey("pref_dimmer_mode")
        private val KEY_VOLUME_FADE_ENABLED = booleanPreferencesKey("pref_volume_fade_enabled")
        private val KEY_VOLUME_FADE_MODE = stringPreferencesKey("pref_volume_fade_mode")
        private val KEY_RESTORE_VOLUME_ON_WAKE = booleanPreferencesKey("pref_restore_volume_on_wake")

        private val KEY_SOFT_WAKE_ENABLED = booleanPreferencesKey("pref_soft_wake_enabled")
        private val KEY_SOFT_WAKE_START_HOUR = intPreferencesKey("pref_soft_wake_start_hour")
        private val KEY_SOFT_WAKE_START_MINUTE = intPreferencesKey("pref_soft_wake_start_minute")
        private val KEY_SOFT_WAKE_END_HOUR = intPreferencesKey("pref_soft_wake_end_hour")
        private val KEY_SOFT_WAKE_END_MINUTE = intPreferencesKey("pref_soft_wake_end_minute")
        private val KEY_SOFT_WAKE_DURATION_MIN = intPreferencesKey("pref_soft_wake_duration_min")
        private val KEY_SOFT_WAKE_INTENSITY = stringPreferencesKey("pref_soft_wake_intensity")

        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
