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
        val REST_TIMER_DEADLINE = longPreferencesKey("rest_timer_deadline")
        val RIR_RPE_ENABLED = booleanPreferencesKey("rir_rpe_enabled")
        val AUTO_REST_TIMER_ON_SET = booleanPreferencesKey("auto_rest_timer_on_set")
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
    val restTimerDeadlineFlow: Flow<Long?> = context.dataStore.data.map { 
        val deadline = it[REST_TIMER_DEADLINE] ?: 0L
        if (deadline > System.currentTimeMillis()) deadline else null
    }
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

    suspend fun setRestTimerDeadline(deadlineMs: Long?) {
        context.dataStore.edit {
            if (deadlineMs == null) it.remove(REST_TIMER_DEADLINE)
            else it[REST_TIMER_DEADLINE] = deadlineMs
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
}
