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
        val DEFAULT_REST_SECONDS = intPreferencesKey("default_rest_seconds")
        val DEFAULT_EXERCISE_REST_SECONDS = intPreferencesKey("default_exercise_rest_seconds")
        val REST_TIMER_DEADLINE = longPreferencesKey("rest_timer_deadline")
        val REST_TIMER_WORKOUT_SESSION_ID = longPreferencesKey("rest_timer_workout_session_id")
        val REST_TIMER_EXERCISE_SESSION_ID = longPreferencesKey("rest_timer_exercise_session_id")
        val REST_TIMER_TYPE = stringPreferencesKey("rest_timer_type")
        val RIR_RPE_ENABLED = booleanPreferencesKey("rir_rpe_enabled")
        val AUTO_REST_TIMER_ON_SET = booleanPreferencesKey("auto_rest_timer_on_set")
        val INSTALLED_CATALOG_CONTENT_VERSION = intPreferencesKey("installed_catalog_content_version")
        val LAST_MEDIA_SYNC_AT = longPreferencesKey("last_media_sync_at")
        val MEDIA_SYNC_CONTENT_VERSION = intPreferencesKey("media_sync_content_version")
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
    val defaultRestSecondsFlow: Flow<Int> = context.dataStore.data.map { it[DEFAULT_REST_SECONDS] ?: 90 }
    val defaultExerciseRestSecondsFlow: Flow<Int> = context.dataStore.data.map { it[DEFAULT_EXERCISE_REST_SECONDS] ?: 120 }
    val installedCatalogContentVersionFlow: Flow<Int> = context.dataStore.data.map { it[INSTALLED_CATALOG_CONTENT_VERSION] ?: 0 }
    val lastMediaSyncAtFlow: Flow<Long?> = context.dataStore.data.map { it[LAST_MEDIA_SYNC_AT] }
    val mediaSyncContentVersionFlow: Flow<Int> = context.dataStore.data.map { it[MEDIA_SYNC_CONTENT_VERSION] ?: 0 }
    val restTimerDeadlineFlow: Flow<Long?> = context.dataStore.data.map { it[REST_TIMER_DEADLINE] }
    val restTimerSessionIdFlow: Flow<Long?> = context.dataStore.data.map { it[REST_TIMER_WORKOUT_SESSION_ID] }
    val restTimerExerciseSessionIdFlow: Flow<Long?> = context.dataStore.data.map { it[REST_TIMER_EXERCISE_SESSION_ID] }
    val restTimerTypeFlow: Flow<String?> = context.dataStore.data.map { it[REST_TIMER_TYPE] }
    val rirRpeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[RIR_RPE_ENABLED] ?: true }
    val autoRestTimerOnSetFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_REST_TIMER_ON_SET] ?: true }
    val overrideTemplateIdFlow: Flow<Long?> = context.dataStore.data.map { it[OVERRIDE_TEMPLATE_ID] }

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
