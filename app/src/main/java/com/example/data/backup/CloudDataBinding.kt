package com.example.data.backup

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.data.sync.CloudSyncScope
import com.example.data.sync.CloudSyncScopeProvider
import kotlinx.coroutines.flow.Flow

/**
 * A quem este **conjunto de dados** pertence na nuvem (T16.4).
 *
 * ## O problema que esta tabela resolve
 *
 * ```text
 * Google login  →  todos os dados locais passam a ser da conta      ← PROIBIDO
 *
 * Google login  →  dados continuam locais e sem dono
 *      ↓ usuário toca "Ativar backup" e confirma
 * dataset passa a pertencer àquela Conta Spark                      ← o que acontece
 * ```
 *
 * Login, sozinho, não adota nada. A T16.1 deliberadamente deixou entrar, sair e trocar de conta
 * sem tocar em dado local, e a T16.3 deu identidade global (`syncId`) sem dar dono. A adoção é um
 * ato explícito, com confirmação, e é isto aqui que a registra.
 *
 * ## Por que uma linha em Room, e não uma preferência
 *
 * O que esta linha diz é sobre o **banco**, não sobre o aparelho. Guardá-la no Room é o que permite
 * estabelecer o vínculo, capturar o snapshot, ler a posição da Outbox e criar a tentativa de
 * backup **na mesma transação** — um commit, um rollback. Com o vínculo no DataStore não haveria
 * transação cobrindo os dois, e existiria uma janela em que o dataset tem dono mas ninguém
 * registrou a tentativa (ou o contrário, que é pior).
 *
 * Na T16.3 esse estado morava no DataStore (`CLOUD_SYNC_STATE`/`CLOUD_SYNC_OWNER_UID`), porque
 * nada o escrevia ainda. Ele nunca foi gravado em produção: a adoção só passa a existir agora.
 *
 * ## Dono é do dataset, não da linha
 *
 * Nenhum `WorkoutTemplate`, `WorkoutSession` ou `BodyMeasurement` ganha coluna `ownerUid`. O dono
 * é um só, é deste banco, e vive aqui.
 */
@Entity(tableName = "cloud_data_binding")
data class CloudDataBindingEntity(

    /**
     * Sempre [SINGLETON_ID].
     *
     * Um banco tem um dono, não uma lista deles. A PK fixa é o que impede duas linhas dizendo
     * coisas diferentes sobre a mesma coisa.
     */
    @PrimaryKey val id: Int = SINGLETON_ID,

    /** O Firebase UID que adotou este conjunto de dados. */
    val ownerUid: String,

    /** Nome de [com.example.data.sync.CloudSyncState]: `PREPARING` até o primeiro sucesso. */
    val state: String,

    /** Quando a adoção foi confirmada, em epoch millis UTC. */
    val boundAt: Long,

    /** A instalação que fez a adoção. Metadado — não autoriza nada. */
    val deviceId: String,

    /** O `backupId` do último backup confirmado **pelo servidor**. */
    val lastSuccessfulBackupId: String? = null,

    /**
     * Quando o último backup foi criado, segundo o **servidor**.
     *
     * Não é "quando eu toquei no botão": o relógio do aparelho não decide nada aqui, e mostrar a
     * hora do toque como se fosse a hora do backup mentiria justamente quando importa — quando o
     * upload demorou ou falhou depois.
     */
    val lastSuccessfulBackupAt: Long? = null
) {
    companion object {
        const val SINGLETON_ID: Int = 1
    }
}

@Dao
interface CloudDataBindingDao {

    @Query("SELECT * FROM cloud_data_binding WHERE id = :id LIMIT 1")
    suspend fun get(id: Int = CloudDataBindingEntity.SINGLETON_ID): CloudDataBindingEntity?

    @Query("SELECT * FROM cloud_data_binding WHERE id = :id LIMIT 1")
    fun observe(id: Int = CloudDataBindingEntity.SINGLETON_ID): Flow<CloudDataBindingEntity?>

    /**
     * Cria o vínculo **apenas se ainda não existir**.
     *
     * `IGNORE` e não `REPLACE`: um vínculo existente nunca é sobrescrito por esta chamada. Trocar
     * de dono precisa ser um caminho próprio, explícito e visível — não um efeito colateral de
     * alguém tocar em "Ativar backup" com outra conta conectada.
     */
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(binding: CloudDataBindingEntity): Long

    @Query(
        """
        UPDATE cloud_data_binding
        SET state = :state,
            lastSuccessfulBackupId = :backupId,
            lastSuccessfulBackupAt = :backupAt
        WHERE id = :id AND ownerUid = :ownerUid
        """
    )
    suspend fun markBackupSucceeded(
        ownerUid: String,
        state: String,
        backupId: String,
        backupAt: Long,
        id: Int = CloudDataBindingEntity.SINGLETON_ID
    )
}

/**
 * O estado da nuvem lido do vínculo do dataset.
 *
 * Substitui o `SettingsCloudSyncScopeProvider` da T16.3, que lia a mesma informação do DataStore —
 * onde nada nunca a escreveu, porque a adoção não existia. Uma autoridade por coisa: quem responde
 * "de quem é este banco" é a tabela que registra a adoção.
 *
 * **Nada aqui observa `AuthState`.** Entrar na conta não liga a nuvem: um usuário pode estar
 * autenticado o dia inteiro — para usar o Coach IA, por exemplo — com o backup desligado. E, depois
 * da adoção, sair da conta **não** desliga: o dono do dataset continua sendo quem adotou, e é no
 * escopo dele que as mutações locais continuam sendo registradas.
 */
class CloudDataBindingScopeProvider(
    private val dao: CloudDataBindingDao
) : CloudSyncScopeProvider {

    override suspend fun current(): CloudSyncScope {
        val binding = dao.get() ?: return CloudSyncScope.Disabled
        if (binding.ownerUid.isBlank()) return CloudSyncScope.Disabled
        return when (binding.state) {
            com.example.data.sync.CloudSyncState.PREPARING.name ->
                CloudSyncScope.Preparing(binding.ownerUid)

            com.example.data.sync.CloudSyncState.ENABLED.name ->
                CloudSyncScope.Enabled(binding.ownerUid)

            // Valor desconhecido, gravado por uma versão futura: o modo seguro de errar é não
            // registrar mutação com dono ambíguo — nunca adotar dados por engano.
            else -> CloudSyncScope.Disabled
        }
    }
}
