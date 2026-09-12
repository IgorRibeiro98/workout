package com.example.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * O arquivo da **identidade da instalação**, separado de `settings` (auditoria 2026-09-12).
 *
 * Existe por um motivo só: `settings` entra no Auto Backup do Android, e o `deviceId` não pode. Um
 * aparelho novo restaurado do backup de um antigo nascia com o **mesmo** `deviceId` — e, com ele, o
 * mesmo cursor de sync e o mesmo vínculo de nuvem. Dois aparelhos se passando por um só é
 * exatamente o que a T16.6 assume que não acontece.
 *
 * O nome do arquivo é contrato com `res/xml/backup_rules.xml` e `res/xml/data_extraction_rules.xml`,
 * que são uma **lista de inclusão**: só `datastore/settings.preferences_pb` viaja, e este arquivo
 * fica de fora por não estar lá. Renomeá-lo é seguro; movê-lo de volta para `settings` não é.
 */
private val Context.syncDeviceDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "sync_device")

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

        // ---- Identidade e nuvem (T16.3) --------------------------------------------------
        //
        // Estado da **instalação**, não do domínio. Por isso mora no DataStore e não no Room: não
        // é dado do usuário, não entra em backup e não sincroniza.
        //
        // A chave é a mesma; o **arquivo** mudou (ver [syncDeviceDataStore]). Ela continua
        // declarada aqui porque a adoção do valor legado precisa lê-la de `settings`.
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    /**
     * Identidade da **instalação** do Spark (T16.3).
     *
     * Nulo até alguém pedir — quem cria é o `DeviceIdProvider`, na primeira necessidade real, e
     * não a abertura do app.
     *
     * Lê **só** o arquivo `sync_device`. Uma instalação que ainda tem a identidade no arquivo
     * antigo vê `null` aqui e cai em [putDeviceIdIfAbsent], que é onde a adoção acontece — e é
     * assim, e não com um fallback de leitura, porque um fallback permanente manteria de pé
     * justamente o caminho que faz um `deviceId` restaurado de backup voltar a valer.
     */
    val deviceIdFlow: Flow<String?> =
        context.syncDeviceDataStore.data.map { it[DEVICE_ID] }.distinctUntilChanged()

    /**
     * Grava o `deviceId` apenas se ainda não existir. A identidade da instalação não é rotativa.
     *
     * Quando o arquivo novo está vazio mas o antigo tem um valor, o valor **antigo** vence: quem já
     * usava o Spark continua sendo o mesmo aparelho para o servidor, com o mesmo cursor e o mesmo
     * vínculo. Trocar a identidade numa atualização de app faria todo mundo parecer um aparelho
     * novo de uma vez.
     *
     * E a chave sai de `settings` no mesmo caminho. Não é limpeza estética: enquanto ela estiver
     * lá, ela viaja no Auto Backup, e a próxima restauração em outro aparelho adotaria o mesmo
     * `deviceId` de novo — o defeito continuaria existindo, só que uma restauração mais tarde.
     */
    suspend fun putDeviceIdIfAbsent(deviceId: String): String {
        // Fora do `edit`: a transformação de um `edit` pode ser reexecutada, e ler outro arquivo
        // dentro dela tornaria o número de leituras imprevisível.
        val legacy = context.dataStore.data.first()[DEVICE_ID]?.takeIf { it.isNotBlank() }

        var stored = legacy ?: deviceId
        context.syncDeviceDataStore.edit { prefs ->
            val existing = prefs[DEVICE_ID]
            if (existing.isNullOrBlank()) prefs[DEVICE_ID] = stored else stored = existing
        }

        // Depois de a identidade estar a salvo no arquivo novo — nunca antes: apagar primeiro e
        // morrer no meio perderia a identidade da instalação.
        if (legacy != null) {
            context.dataStore.edit { it.remove(DEVICE_ID) }
        }
        return stored
    }

    // O estado da nuvem **não** mora aqui desde a T16.4. Ele é sobre o conjunto de dados, não
    // sobre o aparelho, e vive no Room (`cloud_data_binding`) para que a adoção, a captura do
    // snapshot, o corte da Outbox e a tentativa de backup caibam na mesma transação.
    // Ver `com.example.data.backup.CloudDataBindingEntity`.

    val trackingStartedAtFlow: Flow<Long?> =
        context.dataStore.data.map { it[CONSISTENCY_TRACKING_STARTED_AT] }.distinctUntilChanged()

    suspend fun setTrackingStartedAt(epochDay: Long) {
        context.dataStore.edit { prefs ->
            prefs[CONSISTENCY_TRACKING_STARTED_AT] = epochDay
        }
    }

    val xpPolicyVersionFlow: Flow<Int> =
        context.dataStore.data.map { it[XP_POLICY_VERSION] ?: 0 }.distinctUntilChanged()

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
    }.distinctUntilChanged()

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

    /**
     * Uma preferência derivada do arquivo inteiro, **sem reemitir o que não mudou**.
     *
     * `dataStore.data` emite o `Preferences` completo a cada gravação, de qualquer chave: salvar o
     * estado do cronômetro de descanso fazia `showGifsFlow` emitir, e cada emissão dele
     * reconstruía o catálogo de exercícios inteiro (`ExerciseResolver.resolveAll`). Como o valor
     * lido é o mesmo, `distinctUntilChanged` corta a cascata na origem — e cada leitor volta a
     * reagir só à **sua** preferência.
     *
     * Vale para todas: uma que esquecesse o operador seria um caminho de reemissão sobrevivente,
     * e é por isso que o acesso a preferência derivada passa por aqui e não por `data.map { }`
     * solto.
     */
    private fun <T> preference(read: (Preferences) -> T): Flow<T> =
        context.dataStore.data.map { read(it) }.distinctUntilChanged()

    val weeklyGoalFlow: Flow<Int> = preference { it[WEEKLY_GOAL] ?: 5 }
    val useKgFlow: Flow<Boolean> = preference { it[USE_KG] ?: true }
    val hapticEnabledFlow: Flow<Boolean> = preference { it[HAPTIC_ENABLED] ?: true }
    val soundEnabledFlow: Flow<Boolean> = preference { it[SOUND_ENABLED] ?: true }
    val preAlertEnabledFlow: Flow<Boolean> = preference { it[PRE_ALERT_ENABLED] ?: true }
    val keepScreenOnFlow: Flow<Boolean> = preference { it[KEEP_SCREEN_ON] ?: true }
    val autoCheckInFlow: Flow<Boolean> = preference { it[AUTO_CHECK_IN] ?: true }
    val autoCheckOutFlow: Flow<Boolean> = preference { it[AUTO_CHECK_OUT] ?: true }
    val showGifsFlow: Flow<Boolean> = preference { it[SHOW_GIFS] ?: true }
    val showCoachTipFlow: Flow<Boolean> = preference { it[SHOW_COACH_TIP] ?: true }
    val defaultRestSecondsFlow: Flow<Int> = preference { it[DEFAULT_REST_SECONDS] ?: 90 }
    val defaultExerciseRestSecondsFlow: Flow<Int> = preference { it[DEFAULT_EXERCISE_REST_SECONDS] ?: 120 }
    val installedCatalogContentVersionFlow: Flow<Int> = preference { it[INSTALLED_CATALOG_CONTENT_VERSION] ?: 0 }
    val installedPremiumContentVersionFlow: Flow<Int> = preference { it[INSTALLED_PREMIUM_CONTENT_VERSION] ?: 0 }
    val lastMediaSyncAtFlow: Flow<Long?> = preference { it[LAST_MEDIA_SYNC_AT] }
    val mediaSyncContentVersionFlow: Flow<Int> = preference { it[MEDIA_SYNC_CONTENT_VERSION] ?: 0 }
    val restTimerDeadlineFlow: Flow<Long?> = preference { it[REST_TIMER_DEADLINE] }
    val restTimerSessionIdFlow: Flow<Long?> = preference { it[REST_TIMER_WORKOUT_SESSION_ID] }
    val restTimerExerciseSessionIdFlow: Flow<Long?> = preference { it[REST_TIMER_EXERCISE_SESSION_ID] }
    val restTimerTypeFlow: Flow<String?> = preference { it[REST_TIMER_TYPE] }
    val rirRpeEnabledFlow: Flow<Boolean> = preference { it[RIR_RPE_ENABLED] ?: true }
    val autoRestTimerOnSetFlow: Flow<Boolean> = preference { it[AUTO_REST_TIMER_ON_SET] ?: true }
    val overrideTemplateIdFlow: Flow<Long?> = preference { it[OVERRIDE_TEMPLATE_ID] }
    val timerNotificationEnabledFlow: Flow<Boolean> = preference { it[TIMER_NOTIFICATION_ENABLED] ?: true }
    val exerciseDbV2ApiKeyFlow: Flow<String> = preference { it[EXERCISE_DB_V2_API_KEY] ?: "" }

    suspend fun setTimerNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TIMER_NOTIFICATION_ENABLED] = enabled }
    }

    suspend fun setExerciseDbV2ApiKey(key: String) {
        context.dataStore.edit { it[EXERCISE_DB_V2_API_KEY] = key }
    }

    suspend fun setWeeklyGoal(goal: Int) {
        context.dataStore.edit { it[WEEKLY_GOAL] = goal }
    }

    /**
     * A unidade de carga do atleta.
     *
     * Só a leitura existia: a UI nunca ofereceu a troca, e o padrão (kg) servia. O restore (T16.5)
     * precisa **aplicar** a preferência que veio no backup — e ela é uma das seis que descrevem a
     * pessoa, não o aparelho (`UserPreferencesBackupDto`).
     */
    suspend fun setUseKg(useKg: Boolean) {
        context.dataStore.edit { it[USE_KG] = useKg }
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
