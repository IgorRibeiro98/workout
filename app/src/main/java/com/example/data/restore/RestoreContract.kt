package com.example.data.restore

import com.example.data.backup.BackupContract

/**
 * O contrato do restore no Android (T16.5).
 *
 * ## Restore reusa o contrato do backup — não cria outro
 *
 * ```text
 * BackupSnapshotBuilder → backupSchemaVersion → Spark Backend → o MESMO snapshot → RestoreReader
 * ```
 *
 * Não existe `RestoreFormatForDownload` ao lado de `BackupFormatForUpload`: o documento validado
 * aqui é exatamente o que `BackupSnapshotDto` produziu, com os mesmos DTOs de agregado da T16.3 e
 * a mesma forma canônica de `BackupCanonicalJson`. Dois formatos para o mesmo snapshot
 * divergiriam, e a divergência apareceria como "backup corrompido" no aparelho de um usuário.
 *
 * A definição legível continua sendo [`contracts/backup/v1/README.md`](contracts/backup/v1), agora
 * com a seção de leitura (lista, metadata e conteúdo).
 *
 * ## Restore não é sincronização
 *
 * ```text
 * T16.4   Android ──snapshot completo──▶ Spark Backend        (backup)
 * T16.5   Android ◀──snapshot completo── Spark Backend        (restore, por ação explícita)
 * T16.6   Android ⇄ Spark Backend, incremental                (não existe)
 * ```
 *
 * O restore **substitui** o dataset local por um snapshot escolhido. Ele não mescla, não resolve
 * conflito, não baixa mudanças e não roda sozinho.
 */
object RestoreContract {

    /** Lista de backups retidos da conta autenticada. Só metadata. */
    const val BACKUPS_PATH: String = BackupContract.BACKUPS_PATH

    /** O conteúdo de um backup: o snapshot canônico, verbatim. */
    fun contentPath(backupId: String): String = "${BackupContract.BACKUPS_PATH}/$backupId/content"

    /**
     * As versões de **formato de backup** que este app sabe interpretar.
     *
     * Hoje é só a 1, e o conjunto é escrito explicitamente em vez de derivado de
     * `BackupContract.SCHEMA_VERSION`: quando existir uma v2, um app novo precisará continuar
     * lendo backups v1 — e a lista é o lugar onde essa decisão fica visível.
     *
     * Uma versão **maior** que qualquer uma daqui é recusada sem tentativa de interpretação
     * ([RestoreError.UNSUPPORTED_BACKUP_VERSION]). Adivinhar o significado de um formato futuro é
     * a forma mais direta de gravar dado errado com aparência de dado certo.
     */
    val SUPPORTED_BACKUP_SCHEMA_VERSIONS: Set<Int> = setOf(1)

    fun supports(backupSchemaVersion: Int): Boolean =
        backupSchemaVersion in SUPPORTED_BACKUP_SCHEMA_VERSIONS
}

/**
 * Os tetos do restore no cliente (T16.5).
 *
 * Eles espelham `backend/src/modules/backup/backup.limits.ts`. Existir dos dois lados não é
 * duplicação inútil: o servidor protege o servidor, e estes protegem **este aparelho** de gastar
 * memória e CPU com um documento absurdo antes de descobrir que ele é absurdo.
 *
 * O teto de bytes é conferido **durante** o download, e não depois: o objetivo é parar de escrever,
 * não descobrir no fim que se escreveu 400 MB no disco do usuário.
 */
object RestoreLimits {

    /** Igual ao teto de corpo do servidor. Um snapshot real fica na casa de centenas de KB. */
    const val MAX_SNAPSHOT_BYTES: Long = 4L * 1024 * 1024

    /** Agregados em um snapshot. */
    const val MAX_ITEMS: Int = 5_000

    /** Coleção aninhada: exercícios de um treino, séries de um exercício. */
    const val MAX_COLLECTION_SIZE: Int = 500
}

/**
 * O que pode dar errado em um restore — em vocabulário do Spark, nunca detalhe interno.
 *
 * Nenhum destes valores carrega conteúdo do snapshot. Eles acabam em coluna de banco, em tela e em
 * relato de suporte: um nome de treino, uma nota de série ou uma medida não podem vazar por aqui.
 *
 * A UI traduz cada um em uma frase que diz o que aconteceu e o que fazer — `SQLiteConstraintException`,
 * `JsonDecodingException` e "HTTP 422" não são texto de usuário.
 */
enum class RestoreError {
    /** Não há endereço de Spark Backend neste build. Nenhuma requisição foi feita. */
    NOT_CONFIGURED,

    /** É preciso estar na Conta Spark. Não é falha: é o convite para entrar. */
    AUTH_REQUIRED,

    /** Sem rede ou servidor inalcançável. Recuperável — e nada local mudou. */
    NETWORK,

    /** O servidor respondeu indisponível. Recuperável — e nada local mudou. */
    UNAVAILABLE,

    /** O backup escolhido não existe (ou não é desta conta, que é a mesma resposta). */
    BACKUP_NOT_FOUND,

    /** O servidor não tem o documento daquele backup — ele foi criado antes da T16.5. */
    BACKUP_CONTENT_UNAVAILABLE,

    /** O download não completou. Nada local foi alterado; tentar de novo é seguro. */
    BACKUP_DOWNLOAD_FAILED,

    /** O snapshot recebido não é maior que o teto — ele é. Recusado antes de ser interpretado. */
    BACKUP_TOO_LARGE,

    /**
     * O SHA-256 do que chegou não é o que a metadata declara.
     *
     * Pode ser corrupção no caminho, no disco ou no servidor. A causa não muda a conduta: **zero**
     * alteração local.
     */
    BACKUP_INTEGRITY_ERROR,

    /** `backupSchemaVersion` que este app não sabe ler — tipicamente uma versão mais nova. */
    UNSUPPORTED_BACKUP_VERSION,

    /** `entitySchemaVersion` desconhecida para aquele agregado. */
    UNSUPPORTED_ENTITY_VERSION,

    /** Estrutura, identidade, duplicidade ou referência fora do contrato. */
    INVALID_BACKUP,

    /**
     * O backup referencia exercício de catálogo que este aparelho não tem.
     *
     * Não há *fuzzy matching*: o Spark não escolhe "o exercício mais parecido". O restore para
     * antes de qualquer escrita, e a saída é atualizar o catálogo do app.
     */
    MISSING_CATALOG_EXERCISE,

    /** Os dados deste aparelho pertencem a outra Conta Spark. */
    ACCOUNT_MISMATCH,

    /** A conta mudou entre a confirmação e a aplicação. Nada foi alterado. */
    ACCOUNT_CHANGED,

    /** Um treino está em andamento neste aparelho. Substituir o dataset agora o destruiria. */
    WORKOUT_IN_PROGRESS,

    /** A aplicação foi pedida sem a confirmação explícita do usuário. */
    RESTORE_CONFIRMATION_REQUIRED,

    /** Já existe um restore (ou um backup) em andamento. Não vira uma operação nova. */
    RESTORE_IN_PROGRESS,

    /** A transação de aplicação falhou. O dataset anterior foi preservado pelo rollback. */
    RESTORE_APPLY_FAILED,

    /**
     * Existe uma tentativa interrompida que precisa ser resolvida antes de qualquer coisa.
     *
     * É o estado que impede tratar um dataset possivelmente pela metade como se estivesse íntegro.
     */
    RESTORE_RECOVERY_REQUIRED
}

/**
 * Uma falha de restore com a classe de erro que a UI e o diagnóstico entendem.
 *
 * [detail] descreve a **forma** do defeito ("item duplicado em [3]"), nunca o valor: o mesmo
 * cuidado que o servidor tem com as mensagens dele (`backup.errors.ts`).
 */
class RestoreException(
    val error: RestoreError,
    val detail: String? = null
) : Exception("${error.name}${detail?.let { ": $it" } ?: ""}")
