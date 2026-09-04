package com.example.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val OVERRIDE_TEMPLATE_ID = longPreferencesKey("override_template_id")
        val WEEKLY_GOAL = intPreferencesKey("weekly_goal")
        val USE_KG = booleanPreferencesKey("use_kg")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val PRE_ALERT_ENABLED = booleanPreferencesKey("pre_alert_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AUTO_CHECK_IN = booleanPreferencesKey("auto_check_in")
        val AUTO_CHECK_OUT = booleanPreferencesKey("auto_check_out")
        val SHOW_GIFS = booleanPreferencesKey("show_gifs")
        val SHOW_COACH_TIP = booleanPreferencesKey("show_coach_tip")
        val DEFAULT_REST_SECONDS = intPreferencesKey("default_rest_seconds")
        val DEFAULT_EXERCISE_REST_SECONDS = intPreferencesKey("default_exercise_rest_seconds")
        val REST_TIMER_DEADLINE = longPreferencesKey("rest_timer_deadline")
        val REST_TIMER_WORKOUT_SESSION_ID = longPreferencesKey("rest_timer_workout_session_id")
        val REST_TIMER_EXERCISE_SESSION_ID = longPreferencesKey("rest_timer_exercise_session_id")
        val REST_TIMER_TYPE = stringPreferencesKey("rest_timer_type")
        val RIR_RPE_ENABLED = booleanPreferencesKey("rir_rpe_enabled")
        val AUTO_REST_TIMER_ON_SET = booleanPreferencesKey("auto_rest_timer_on_set")
        // Cada manifesto tem a sua própria chave: compartilhá-las fazia a gravação de um
        // importador bloquear a importação do outro.
        val INSTALLED_CATALOG_CONTENT_VERSION = intPreferencesKey("installed_catalog_content_version")
        val INSTALLED_PREMIUM_CONTENT_VERSION = intPreferencesKey("installed_premium_content_version")
        val LAST_MEDIA_SYNC_AT = longPreferencesKey("last_media_sync_at")
        val MEDIA_SYNC_CONTENT_VERSION = intPreferencesKey("media_sync_content_version")
        val EXERCISE_DB_ENABLED = booleanPreferencesKey("exercise_db_enabled")
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        val MEDIA_SYNC_ENABLED = booleanPreferencesKey("media_sync_enabled")
        val LAST_SYNC_STATUS = stringPreferencesKey("last_sync_status")
        val TIMER_NOTIFICATION_ENABLED = booleanPreferencesKey("timer_notification_enabled")
        val EXERCISE_DB_V2_API_KEY = stringPreferencesKey("exercise_db_v2_api_key")
        val XP_POLICY_VERSION = intPreferencesKey("xp_policy_version")
        val CONSISTENCY_TRACKING_STARTED_AT = longPreferencesKey("consistency_tracking_started_at")
    }

    val trackingStartedAtFlow: Flow<Long?> = context.dataStore.data.map { it[CONSISTENCY_TRACKING_STARTED_AT] }

    suspend fun setTrackingStartedAt(epochDay: Long) {
        context.dataStore.edit { prefs ->
            prefs[CONSISTENCY_TRACKING_STARTED_AT] = epochDay
        }
    }

    val xpPolicyVersionFlow: Flow<Int> = context.dataStore.data.map { it[XP_POLICY_VERSION] ?: 0 }

    suspend fun setXpPolicyVersion(version: Int) {
        context.dataStore.edit { prefs ->
            prefs[XP_POLICY_VERSION] = version
        }
    }

    val mediaProviderSettingsFlow: Flow<MediaProviderSettings> = context.dataStore.data.map { prefs ->
        val enabled = prefs[EXERCISE_DB_ENABLED] ?: false
        val autoSync = prefs[AUTO_SYNC_ENABLED] ?: false
        val syncEnabled = prefs[MEDIA_SYNC_ENABLED] ?: false
        val lastSyncAt = prefs[LAST_MEDIA_SYNC_AT]
        val statusStr = prefs[LAST_SYNC_STATUS]
        val status = try {
            if (statusStr != null) SyncStatus.valueOf(statusStr) else if (enabled) SyncStatus.READY else SyncStatus.DISABLED
        } catch (e: Exception) {
            if (enabled) SyncStatus.READY else SyncStatus.DISABLED
        }

        MediaProviderSettings(
            exerciseDbEnabled = enabled,
            autoSyncEnabled = autoSync,
            mediaSyncEnabled = syncEnabled,
            lastSyncTimestamp = lastSyncAt,
            lastSyncStatus = status
        )
    }

    val integrationSettingsFlow: Flow<IntegrationSettings> = mediaProviderSettingsFlow

    suspend fun setExerciseDbEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[EXERCISE_DB_ENABLED] = enabled
            if (!enabled) {
                prefs[LAST_SYNC_STATUS] = SyncStatus.DISABLED.name
            } else if (prefs[LAST_SYNC_STATUS] == SyncStatus.DISABLED.name || prefs[LAST_SYNC_STATUS] == null) {
                prefs[LAST_SYNC_STATUS] = SyncStatus.READY.name
            }
        }
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setMediaSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[MEDIA_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setLastSyncStatus(status: SyncStatus) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SYNC_STATUS] = status.name
        }
    }

    val weeklyGoalFlow: Flow<Int> = context.dataStore.data.map { it[WEEKLY_GOAL] ?: 5 }
    val useKgFlow: Flow<Boolean> = context.dataStore.data.map { it[USE_KG] ?: true }
    val hapticEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[HAPTIC_ENABLED] ?: true }
    val soundEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[SOUND_ENABLED] ?: true }
    val preAlertEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[PRE_ALERT_ENABLED] ?: true }
    val keepScreenOnFlow: Flow<Boolean> = context.dataStore.data.map { it[KEEP_SCREEN_ON] ?: true }
    val autoCheckInFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CHECK_IN] ?: true }
    val autoCheckOutFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CHECK_OUT] ?: true }
    val showGifsFlow: Flow<Boolean> = context.dataStore.data.map { it[SHOW_GIFS] ?: true }
    val showCoachTipFlow: Flow<Boolean> = context.dataStore.data.map { it[SHOW_COACH_TIP] ?: true }
    val defaultRestSecondsFlow: Flow<Int> = context.dataStore.data.map { it[DEFAULT_REST_SECONDS] ?: 90 }
    val defaultExerciseRestSecondsFlow: Flow<Int> = context.dataStore.data.map { it[DEFAULT_EXERCISE_REST_SECONDS] ?: 120 }
    val installedCatalogContentVersionFlow: Flow<Int> = context.dataStore.data.map { it[INSTALLED_CATALOG_CONTENT_VERSION] ?: 0 }
    val installedPremiumContentVersionFlow: Flow<Int> = context.dataStore.data.map { it[INSTALLED_PREMIUM_CONTENT_VERSION] ?: 0 }
    val lastMediaSyncAtFlow: Flow<Long?> = context.dataStore.data.map { it[LAST_MEDIA_SYNC_AT] }
    val mediaSyncContentVersionFlow: Flow<Int> = context.dataStore.data.map { it[MEDIA_SYNC_CONTENT_VERSION] ?: 0 }
    val restTimerDeadlineFlow: Flow<Long?> = context.dataStore.data.map { it[REST_TIMER_DEADLINE] }
    val restTimerSessionIdFlow: Flow<Long?> = context.dataStore.data.map { it[REST_TIMER_WORKOUT_SESSION_ID] }
    val restTimerExerciseSessionIdFlow: Flow<Long?> = context.dataStore.data.map { it[REST_TIMER_EXERCISE_SESSION_ID] }
    val restTimerTypeFlow: Flow<String?> = context.dataStore.data.map { it[REST_TIMER_TYPE] }
    val rirRpeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[RIR_RPE_ENABLED] ?: true }
    val autoRestTimerOnSetFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_REST_TIMER_ON_SET] ?: true }
    val overrideTemplateIdFlow: Flow<Long?> = context.dataStore.data.map { it[OVERRIDE_TEMPLATE_ID] }
    val timerNotificationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[TIMER_NOTIFICATION_ENABLED] ?: true }
    val exerciseDbV2ApiKeyFlow: Flow<String> = context.dataStore.data.map { it[EXERCISE_DB_V2_API_KEY] ?: "" }

    suspend fun setTimerNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TIMER_NOTIFICATION_ENABLED] = enabled }
    }

    suspend fun setExerciseDbV2ApiKey(key: String) {
        context.dataStore.edit { it[EXERCISE_DB_V2_API_KEY] = key }
    }

    suspend fun setWeeklyGoal(goal: Int) {
        context.dataStore.edit { it[WEEKLY_GOAL] = goal }
    }
    
    suspend fun setAutoCheckIn(auto: Boolean) {
        context.dataStore.edit { it[AUTO_CHECK_IN] = auto }
    }
    
    suspend fun setAutoCheckOut(auto: Boolean) {
        context.dataStore.edit { it[AUTO_CHECK_OUT] = auto }
    }
    
    suspend fun setShowGifs(show: Boolean) {
        context.dataStore.edit { it[SHOW_GIFS] = show }
    }

    suspend fun setShowCoachTip(show: Boolean) {
        context.dataStore.edit { it[SHOW_COACH_TIP] = show }
    }

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.dataStore.edit { it[HAPTIC_ENABLED] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SOUND_ENABLED] = enabled }
    }

    suspend fun setPreAlertEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PRE_ALERT_ENABLED] = enabled }
    }

    suspend fun setKeepScreenOn(keep: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = keep }
    }

    suspend fun setDefaultRestSeconds(seconds: Int) {
        context.dataStore.edit { it[DEFAULT_REST_SECONDS] = seconds }
    }

    suspend fun setDefaultExerciseRestSeconds(seconds: Int) {
        context.dataStore.edit { it[DEFAULT_EXERCISE_REST_SECONDS] = seconds }
    }

    suspend fun setInstalledPremiumContentVersion(version: Int) {
        context.dataStore.edit { it[INSTALLED_PREMIUM_CONTENT_VERSION] = version }
    }

    suspend fun setInstalledCatalogContentVersion(version: Int) {
        context.dataStore.edit { it[INSTALLED_CATALOG_CONTENT_VERSION] = version }
    }

    suspend fun setLastMediaSyncAt(timestamp: Long) {
        context.dataStore.edit { it[LAST_MEDIA_SYNC_AT] = timestamp }
    }

    suspend fun setMediaSyncContentVersion(version: Int) {
        context.dataStore.edit { it[MEDIA_SYNC_CONTENT_VERSION] = version }
    }

    suspend fun setRestTimerState(
        deadlineMs: Long?,
        workoutSessionId: Long? = null,
        exerciseSessionId: Long? = null,
        timerType: String? = null
    ) {
        context.dataStore.edit { prefs ->
            if (deadlineMs == null) {
                prefs.remove(REST_TIMER_DEADLINE)
                prefs.remove(REST_TIMER_WORKOUT_SESSION_ID)
                prefs.remove(REST_TIMER_EXERCISE_SESSION_ID)
                prefs.remove(REST_TIMER_TYPE)
            } else {
                prefs[REST_TIMER_DEADLINE] = deadlineMs
                if (workoutSessionId != null) prefs[REST_TIMER_WORKOUT_SESSION_ID] = workoutSessionId else prefs.remove(REST_TIMER_WORKOUT_SESSION_ID)
                if (exerciseSessionId != null) prefs[REST_TIMER_EXERCISE_SESSION_ID] = exerciseSessionId else prefs.remove(REST_TIMER_EXERCISE_SESSION_ID)
                if (timerType != null) prefs[REST_TIMER_TYPE] = timerType else prefs.remove(REST_TIMER_TYPE)
            }
        }
    }

    suspend fun setRestTimerDeadline(deadlineMs: Long?) {
        context.dataStore.edit { prefs ->
            if (deadlineMs == null) {
                prefs.remove(REST_TIMER_DEADLINE)
                prefs.remove(REST_TIMER_WORKOUT_SESSION_ID)
                prefs.remove(REST_TIMER_EXERCISE_SESSION_ID)
                prefs.remove(REST_TIMER_TYPE)
            } else {
                prefs[REST_TIMER_DEADLINE] = deadlineMs
            }
        }
    }

    suspend fun setRirRpeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[RIR_RPE_ENABLED] = enabled }
    }

    suspend fun setAutoRestTimerOnSet(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_REST_TIMER_ON_SET] = enabled }
    }

    suspend fun setOverrideTemplateId(id: Long?) {
        context.dataStore.edit {
            if (id == null) it.remove(OVERRIDE_TEMPLATE_ID)
            else it[OVERRIDE_TEMPLATE_ID] = id
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
