package com.example.data.sync

import com.example.data.datastore.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Identidade da **instalação** do Spark (T16.3).
 *
 * ```text
 * Firebase UID       → quem é o usuário
 * deviceId           → qual instalação do app
 * syncId             → qual entidade
 * clientMutationId   → qual alteração
 * ```
 *
 * Nenhum desses substitui outro. O `deviceId` existe para que o sync multi-device (T16.6) saiba de
 * onde veio uma mudança e para que o cursor do servidor seja por dispositivo — não para
 * identificar pessoa e não para autorizar nada.
 *
 * ## O que ele deliberadamente não é
 *
 * UUID aleatório gerado pelo próprio Spark, guardado no DataStore. **Não** é Android ID, IMEI,
 * número de série, MAC nem qualquer *fingerprint* de hardware: esses são identificadores
 * persistentes do aparelho, transformariam um detalhe de sincronização em rastreamento, e vários
 * são inacessíveis ou instáveis no Android atual.
 *
 * ## Reinstalar gera outro, e está certo
 *
 * Não existe tentativa de sobreviver à desinstalação. Uma reinstalação é outra cópia local, e
 * fabricar uma identidade de aparelho que atravesse isso seria justamente o rastreamento que a
 * regra acima proíbe. A identidade que importa para os dados é o `syncId`, que viaja com a
 * entidade — não com a instalação.
 */
class DeviceIdProvider(
    private val settingsManager: SettingsManager,
    private val idGenerator: IdGenerator = RandomUuidIdGenerator
) {

    private val mutex = Mutex()

    /**
     * O `deviceId` desta instalação, criando-o na primeira vez.
     *
     * Estável entre reaberturas do app: uma vez gravado, as chamadas seguintes devolvem o mesmo
     * valor. O `Mutex` mais a gravação condicional no DataStore evitam que duas chamadas
     * concorrentes na primeira execução produzam dois ids.
     */
    suspend fun deviceId(): String = mutex.withLock {
        val existing = settingsManager.deviceIdFlow.first()
        if (!existing.isNullOrBlank()) return@withLock existing
        settingsManager.putDeviceIdIfAbsent(idGenerator.newId())
    }
}
