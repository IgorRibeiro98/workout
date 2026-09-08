package com.example.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.pushDataStore: DataStore<Preferences> by preferencesDataStore(name = "social_push_scope")

/**
 * Cache local do escopo de notificações push no aparelho (T17.5 §14, §15, §87).
 *
 * O push no Spark é efêmero e server-authoritative. Este escopo guarda no DataStore
 * apenas o estado da instalação em relação ao FCM e à conta ativa:
 * - Último token FCM recebido pelo FirebaseMessagingService;
 * - socialId da conta que registrou este aparelho no backend;
 * - Timestamp do último registro confirmado.
 *
 * ## Isolamento de Conta (§87)
 * Se uma notificação push chegar quando o aparelho estiver logado em outra conta
 * (ou deslogado), o recipientSocialId do payload é confrontado com o
 * registeredSocialId. Se não baterem, o push é descartado sem exibição.
 */
class PushAccountScope(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.pushDataStore
) {

    companion object {
        val KEY_FCM_TOKEN = stringPreferencesKey("fcm_token")
        val KEY_REGISTERED_SOCIAL_ID = stringPreferencesKey("registered_social_id")
        val KEY_LAST_REGISTERED_AT = longPreferencesKey("last_registered_at")
    }

    val fcmTokenFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_FCM_TOKEN]
    }

    val registeredSocialIdFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_REGISTERED_SOCIAL_ID]
    }

    val lastRegisteredAtFlow: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_REGISTERED_AT]
    }

    suspend fun getFcmToken(): String? = fcmTokenFlow.first()

    suspend fun getRegisteredSocialId(): String? = registeredSocialIdFlow.first()

    suspend fun setFcmToken(token: String) {
        dataStore.edit { prefs ->
            prefs[KEY_FCM_TOKEN] = token
        }
    }

    suspend fun setRegisteredAccount(socialId: String, timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_REGISTERED_SOCIAL_ID] = socialId
            prefs[KEY_LAST_REGISTERED_AT] = timestamp
        }
    }

    suspend fun clearRegisteredAccount() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_REGISTERED_SOCIAL_ID)
            prefs.remove(KEY_LAST_REGISTERED_AT)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    suspend fun isAccountActiveAndRegistered(currentSocialId: String): Boolean {
        val registered = getRegisteredSocialId()
        return !registered.isNullOrBlank() && registered == currentSocialId
    }
}
