package com.example.data.sync

import com.example.data.datastore.SettingsManager
import kotlinx.coroutines.flow.first

/**
 * Lê o estado da nuvem do DataStore.
 *
 * Nada aqui observa `AuthState`: **entrar na conta não liga a nuvem**. Um usuário pode estar
 * autenticado o dia inteiro — para usar o Coach IA, por exemplo — com o backup desligado, e é
 * exatamente esse o comportamento ao final da T16.3.
 *
 * A leitura é tolerante a lixo por decisão: um valor desconhecido gravado por uma versão futura,
 * ou um estado sem `uid`, resolve para [CloudSyncScope.Disabled]. O modo seguro de errar aqui é
 * não registrar mutação com dono ambíguo — nunca adotar dados por engano.
 */
class SettingsCloudSyncScopeProvider(
    private val settingsManager: SettingsManager
) : CloudSyncScopeProvider {

    override suspend fun current(): CloudSyncScope {
        val state = settingsManager.cloudSyncStateFlow.first()
        val ownerUid = settingsManager.cloudSyncOwnerUidFlow.first()
        if (state.isNullOrBlank() || ownerUid.isNullOrBlank()) return CloudSyncScope.Disabled
        return when (state) {
            CloudSyncState.PREPARING.name -> CloudSyncScope.Preparing(ownerUid)
            CloudSyncState.ENABLED.name -> CloudSyncScope.Enabled(ownerUid)
            else -> CloudSyncScope.Disabled
        }
    }
}

/**
 * A forma persistida de [CloudSyncScope].
 *
 * `DISABLED` é o padrão e não precisa estar gravado: ausência de valor já significa nuvem
 * desligada. Quem escreve `PREPARING`/`ENABLED` é a ativação explícita de backup, na T16.4.
 */
enum class CloudSyncState {
    DISABLED,
    PREPARING,
    ENABLED
}
