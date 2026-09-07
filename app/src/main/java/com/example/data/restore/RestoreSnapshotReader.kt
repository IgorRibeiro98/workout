package com.example.data.restore

import com.example.data.backup.BackupContract
import com.example.data.backup.BackupEntityType
import com.example.data.backup.ExerciseOverrideBackupDto
import com.example.data.backup.UserPreferencesBackupDto
import com.example.data.backup.WeeklyGoalBackupDto
import com.example.data.sync.dto.BodyMeasurementSyncDto
import com.example.data.sync.dto.CheckInSyncDto
import com.example.data.sync.dto.CustomExerciseSyncDto
import com.example.data.sync.dto.ExerciseRefDto
import com.example.data.sync.dto.WorkoutProgramSyncDto
import com.example.data.sync.dto.WorkoutSessionSyncDto
import com.example.data.sync.dto.WorkoutTemplateSyncDto
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * O SHA-256 de um arquivo baixado (T16.5).
 *
 * ## Por que hash, se o transporte é HTTPS
 *
 * HTTPS protege o **caminho**. Ele não diz nada sobre o arquivo depois de escrito no disco, sobre
 * um proxy corporativo que reempacota resposta, sobre um bug do servidor que devolveu o snapshot
 * errado ou sobre uma escrita truncada. O hash responde a uma pergunta diferente: *este documento é
 * exatamente aquele que a metadata descreve?*
 *
 * Ele é calculado em blocos, sobre os bytes do arquivo — o mesmo texto canônico que o servidor
 * guardou e que o Android produziu no backup. Nenhuma reserialização acontece no meio: bastaria
 * reordenar uma chave para o hash deixar de fechar, e é por isso que o download é aplicado verbatim.
 */
object BackupIntegrityVerifier {

    private const val BUFFER_BYTES = 16 * 1024

    /** SHA-256 hexadecimal minúsculo do conteúdo de [file]. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Confere o hash e **não** tem caminho de "quase igual".
     *
     * Divergência é sempre [RestoreError.BACKUP_INTEGRITY_ERROR], e sempre antes de qualquer
     * escrita local. A causa (rede, disco, servidor) não muda a conduta.
     */
    fun verify(file: File, expectedHash: String) {
        val actual = sha256(file)
        if (!actual.equals(expectedHash, ignoreCase = true)) {
            // A mensagem não carrega os hashes inteiros: ela vai para banco, tela e suporte.
            throw RestoreException(RestoreError.BACKUP_INTEGRITY_ERROR, "hash do conteúdo divergente")
        }
    }
}

/**
 * A fronteira de migração entre versões do **formato de backup** (T16.5).
 *
 * ```text
 * v1 ──▶ formato atual        (identidade: v1 É o formato atual)
 * v2 ──▶ formato atual        (não existe — e não é inventada aqui)
 * ```
 *
 * Ele existe agora, com uma versão só, porque o lugar de decidir "como ler um backup antigo" tem
 * que ser óbvio antes de o problema aparecer. O que ele **não** faz é inventar migração de versões
 * que não existem: um `v2 → v1` especulativo seria código não exercitado no caminho mais perigoso
 * do app, e a primeira coisa a divergir da v2 real quando ela existir.
 *
 * Uma versão acima da maior conhecida não é migrada nem interpretada:
 * [RestoreError.UNSUPPORTED_BACKUP_VERSION].
 */
object BackupMigrator {

    /**
     * Prepara o documento para leitura pelo formato atual.
     *
     * Hoje: v1 é o formato atual, então a migração é a identidade. A função existe para que a
     * chamada esteja no lugar certo quando houver uma v2 — e para que o teste que fixa
     * `supported = {1}` tenha o que exercitar.
     */
    fun migrate(backupSchemaVersion: Int, document: JsonObject): JsonObject {
        if (!RestoreContract.supports(backupSchemaVersion)) {
            throw RestoreException(
                RestoreError.UNSUPPORTED_BACKUP_VERSION,
                "backupSchemaVersion $backupSchemaVersion"
            )
        }
        return when (backupSchemaVersion) {
            BackupContract.SCHEMA_VERSION -> document
            // Inalcançável enquanto só existir a v1; o `else` é o lugar onde uma migração real
            // entra, e não um caminho tolerante que aceita o desconhecido.
            else -> throw RestoreException(
                RestoreError.UNSUPPORTED_BACKUP_VERSION,
                "sem migração para $backupSchemaVersion"
            )
        }
    }
}

/**
 * Um snapshot lido, estruturalmente e semanticamente válido (T16.5).
 *
 * Chegar aqui significa: versão suportada, todo item de tipo conhecido, todo payload no formato do
 * seu agregado, identidades consistentes com o conteúdo, sem duplicidade e com as referências
 * internas fechadas. Ainda **não** significa que ele possa ser aplicado neste aparelho — isso é o
 * [RestorePlanBuilder], que resolve o catálogo local.
 */
data class ValidatedRestoreSnapshot(
    val clientBackupId: String,
    val backupSchemaVersion: Int,
    val deviceId: String,
    val capturedAt: Long?,
    val programs: List<WorkoutProgramSyncDto>,
    val templates: List<WorkoutTemplateSyncDto>,
    val sessions: List<WorkoutSessionSyncDto>,
    val customExercises: List<CustomExerciseSyncDto>,
    val measurements: List<BodyMeasurementSyncDto>,
    val checkIns: List<CheckInSyncDto>,
    val overrides: List<ExerciseOverrideBackupDto>,
    val weeklyGoals: List<WeeklyGoalBackupDto>,
    val preferences: UserPreferencesBackupDto?
) {
    /** Quantos agregados o snapshot descreve. É o número que a metadata do servidor também conta. */
    val itemCount: Int
        get() = programs.size + templates.size + sessions.size + customExercises.size +
            measurements.size + checkIns.size + overrides.size + weeklyGoals.size +
            (if (preferences != null) 1 else 0)
}

/**
 * Lê e valida integralmente um snapshot baixado — **antes** de qualquer escrita (T16.5).
 *
 * ```text
 * arquivo → JSON → versão → envelope → item a item → identidade → duplicidade → relações
 * ```
 *
 * ## Binário, nunca parcial
 *
 * Ou o snapshot inteiro é válido, ou nada é aplicado. Não existe caminho que restaure 99 agregados
 * e ignore 1: um dataset com 99/100 parece íntegro e não é, e o usuário não teria como saber o que
 * faltou. Um item desconhecido também não é ignorado "por compatibilidade" — ele é a evidência de
 * que este app não entende aquele backup.
 *
 * ## Sem *fuzzy matching*
 *
 * Uma identidade que não bate com o payload, uma referência que não fecha ou um `entityType` fora
 * do registry são recusas. O Spark não escolhe "o mais parecido": um dado gravado no lugar errado
 * é pior do que um restore recusado, porque não tem sintoma.
 *
 * ## JSON válido não basta
 *
 * As duas validações são diferentes e as duas são obrigatórias — a mesma regra que o Coach IA
 * segue desde a T14 (PROJECT_RULES §13): o schema garante a **forma**, e é a validação semântica
 * que garante que aquilo descreve um dataset possível.
 */
class RestoreSnapshotReader(
    private val json: Json = Json {
        // Campo desconhecido é erro, não é ignorado: ele indica contrato divergente entre este app
        // e quem produziu o backup, e guardá-lo em silêncio significaria restaurar um dado que
        // ninguém sabe ler. É o espelho do `.strict()` do registry do servidor.
        ignoreUnknownKeys = false
    }
) {

    /** Lê e valida o arquivo baixado. */
    fun read(file: File): ValidatedRestoreSnapshot = read(file.readText(Charsets.UTF_8))

    fun read(text: String): ValidatedRestoreSnapshot {
        val document = parse(text)

        // A versão é a **primeira** pergunta. Interpretar um envelope de formato desconhecido para
        // depois descobrir a versão seria ler dado com o leitor errado.
        val version = document["backupSchemaVersion"]?.jsonPrimitive?.intOrNull
            ?: throw RestoreException(RestoreError.INVALID_BACKUP, "backupSchemaVersion ausente")
        val migrated = BackupMigrator.migrate(version, document)

        val envelope = decodeEnvelope(migrated)
        if (envelope.items.size > RestoreLimits.MAX_ITEMS) {
            throw RestoreException(RestoreError.INVALID_BACKUP, "itens acima do teto do cliente")
        }

        val programs = mutableListOf<WorkoutProgramSyncDto>()
        val templates = mutableListOf<WorkoutTemplateSyncDto>()
        val sessions = mutableListOf<WorkoutSessionSyncDto>()
        val customExercises = mutableListOf<CustomExerciseSyncDto>()
        val measurements = mutableListOf<BodyMeasurementSyncDto>()
        val checkIns = mutableListOf<CheckInSyncDto>()
        val overrides = mutableListOf<ExerciseOverrideBackupDto>()
        val weeklyGoals = mutableListOf<WeeklyGoalBackupDto>()
        var preferences: UserPreferencesBackupDto? = null

        val seen = mutableSetOf<String>()

        envelope.items.forEachIndexed { index, item ->
            val type = BackupEntityType.entries.firstOrNull { it.name == item.entityType }
                ?: throw RestoreException(
                    RestoreError.INVALID_BACKUP,
                    "entityType desconhecido em [$index]"
                )

            if (item.entitySchemaVersion != type.schemaVersion) {
                throw RestoreException(
                    RestoreError.UNSUPPORTED_ENTITY_VERSION,
                    "${type.name} v${item.entitySchemaVersion}"
                )
            }

            val key = "${type.name} ${item.syncId}"
            if (!seen.add(key)) {
                throw RestoreException(RestoreError.INVALID_BACKUP, "item duplicado em [$index]")
            }

            when (type) {
                BackupEntityType.WORKOUT_PROGRAM ->
                    programs += decode(WorkoutProgramSyncDto.serializer(), item, index).also {
                        requireUuidIdentity(item.syncId, it.syncId, index)
                    }

                BackupEntityType.WORKOUT_TEMPLATE ->
                    templates += decode(WorkoutTemplateSyncDto.serializer(), item, index).also {
                        requireUuidIdentity(item.syncId, it.syncId, index)
                        validateTemplate(it, index)
                    }

                BackupEntityType.WORKOUT_SESSION ->
                    sessions += decode(WorkoutSessionSyncDto.serializer(), item, index).also {
                        requireUuidIdentity(item.syncId, it.syncId, index)
                        validateSession(it, index)
                    }

                BackupEntityType.CUSTOM_EXERCISE ->
                    customExercises += decode(CustomExerciseSyncDto.serializer(), item, index).also {
                        requireUuidIdentity(item.syncId, it.syncId, index)
                    }

                BackupEntityType.BODY_MEASUREMENT ->
                    measurements += decode(BodyMeasurementSyncDto.serializer(), item, index).also {
                        requireUuidIdentity(item.syncId, it.syncId, index)
                    }

                BackupEntityType.CHECK_IN ->
                    checkIns += decode(CheckInSyncDto.serializer(), item, index).also {
                        requireUuidIdentity(item.syncId, it.syncId, index)
                    }

                BackupEntityType.EXERCISE_OVERRIDE ->
                    overrides += decode(ExerciseOverrideBackupDto.serializer(), item, index).also {
                        val expected = identityOf(it.exercise)
                        if (item.syncId != expected) {
                            throw RestoreException(
                                RestoreError.INVALID_BACKUP,
                                "identidade derivada inconsistente em [$index]"
                            )
                        }
                        if (it.exercise.kind == ExerciseRefDto.CUSTOM && !isUuid(it.exercise.id)) {
                            throw RestoreException(
                                RestoreError.INVALID_BACKUP,
                                "referência de exercício personalizado inválida em [$index]"
                            )
                        }
                    }

                BackupEntityType.WEEKLY_GOAL ->
                    weeklyGoals += decode(WeeklyGoalBackupDto.serializer(), item, index).also {
                        val expected =
                            "${BackupContract.WEEK_IDENTITY_PREFIX}${it.effectiveFromWeekStartEpochDay}"
                        if (item.syncId != expected) {
                            throw RestoreException(
                                RestoreError.INVALID_BACKUP,
                                "syncId não corresponde à semana em [$index]"
                            )
                        }
                    }

                BackupEntityType.USER_PREFERENCES -> {
                    if (item.syncId != BackupContract.PREFERENCES_IDENTITY) {
                        throw RestoreException(
                            RestoreError.INVALID_BACKUP,
                            "identidade singleton inválida em [$index]"
                        )
                    }
                    preferences = decode(UserPreferencesBackupDto.serializer(), item, index)
                }
            }
        }

        val snapshot = ValidatedRestoreSnapshot(
            clientBackupId = envelope.clientBackupId,
            backupSchemaVersion = envelope.backupSchemaVersion,
            deviceId = envelope.deviceId,
            capturedAt = envelope.capturedAt,
            programs = programs,
            templates = templates,
            sessions = sessions,
            customExercises = customExercises,
            measurements = measurements,
            checkIns = checkIns,
            overrides = overrides,
            weeklyGoals = weeklyGoals,
            preferences = preferences
        )
        validateRelations(snapshot)
        return snapshot
    }

    private fun parse(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject
            ?: throw RestoreException(RestoreError.INVALID_BACKUP, "documento não é um objeto JSON")
    } catch (e: SerializationException) {
        throw RestoreException(RestoreError.INVALID_BACKUP, "documento JSON inválido")
    } catch (e: IllegalArgumentException) {
        throw RestoreException(RestoreError.INVALID_BACKUP, "documento JSON inválido")
    }

    private fun decodeEnvelope(document: JsonObject): RestoreEnvelopeDto = try {
        json.decodeFromJsonElement(RestoreEnvelopeDto.serializer(), document)
    } catch (e: SerializationException) {
        throw RestoreException(RestoreError.INVALID_BACKUP, "envelope fora do contrato")
    } catch (e: IllegalArgumentException) {
        throw RestoreException(RestoreError.INVALID_BACKUP, "envelope fora do contrato")
    }

    private fun <T> decode(
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        item: RestoreItemDto,
        index: Int
    ): T = try {
        json.decodeFromJsonElement(serializer, item.payload)
    } catch (e: SerializationException) {
        // A razão descreve a **forma** do defeito, nunca o valor: o payload é treino, medida e nota
        // do usuário, e mensagem de erro acaba em tela e em log.
        throw RestoreException(RestoreError.INVALID_BACKUP, "payload inválido em [$index]")
    } catch (e: IllegalArgumentException) {
        throw RestoreException(RestoreError.INVALID_BACKUP, "payload inválido em [$index]")
    }

    private fun requireUuidIdentity(declared: String, payloadSyncId: String, index: Int) {
        if (!isUuid(declared)) {
            throw RestoreException(
                RestoreError.INVALID_BACKUP,
                "syncId não é identidade global em [$index]"
            )
        }
        if (declared != payloadSyncId) {
            throw RestoreException(
                RestoreError.INVALID_BACKUP,
                "syncId do item não corresponde ao do payload em [$index]"
            )
        }
    }

    /**
     * O que um treino precisa ser para caber no Room deste aparelho.
     *
     * `programSyncId` é exigido aqui e **não** é exigido pelo servidor, de propósito: o servidor
     * guarda um snapshot opaco, e o Room tem `workout_templates.programId NOT NULL` com chave
     * estrangeira. Um treino sem programa não tem onde ser inserido — e adivinhar um programa para
     * ele seria inventar dado.
     */
    private fun validateTemplate(template: WorkoutTemplateSyncDto, index: Int) {
        if (template.programSyncId.isNullOrBlank()) {
            throw RestoreException(
                RestoreError.INVALID_BACKUP,
                "treino sem programa em [$index]"
            )
        }
        if (template.exercises.size > RestoreLimits.MAX_COLLECTION_SIZE) {
            throw RestoreException(RestoreError.INVALID_BACKUP, "treino acima do teto em [$index]")
        }
        val positions = template.exercises.map { it.position }
        if (positions.any { it < 0 } || positions.toSet().size != positions.size) {
            // Ordem é dado de domínio (T16.3). Duas linhas na mesma posição descrevem uma ordem
            // impossível, e escolher uma delas seria decidir pelo usuário.
            throw RestoreException(
                RestoreError.INVALID_BACKUP,
                "ordem inválida de exercícios em [$index]"
            )
        }
    }

    /**
     * O que uma sessão precisa ser.
     *
     * Só `COMPLETED` entra no backup, e continua sendo só `COMPLETED` que sai dele: execução viva
     * é deste aparelho, e restaurar uma sessão em andamento de outro celular colocaria dois
     * aparelhos disputando o mesmo cursor de treino.
     */
    private fun validateSession(session: WorkoutSessionSyncDto, index: Int) {
        if (session.status != COMPLETED_STATUS) {
            throw RestoreException(
                RestoreError.INVALID_BACKUP,
                "sessão com status não restaurável em [$index]"
            )
        }
        if (session.startedAt <= 0) {
            throw RestoreException(RestoreError.INVALID_BACKUP, "sessão sem início em [$index]")
        }
        if (session.exercises.size > RestoreLimits.MAX_COLLECTION_SIZE) {
            throw RestoreException(RestoreError.INVALID_BACKUP, "sessão acima do teto em [$index]")
        }
        session.exercises.forEach { exercise ->
            if (exercise.plannedOrder < 0 || exercise.executionOrder < 0) {
                throw RestoreException(
                    RestoreError.INVALID_BACKUP,
                    "ordem inválida de exercício executado em [$index]"
                )
            }
            if (exercise.sets.size > RestoreLimits.MAX_COLLECTION_SIZE) {
                throw RestoreException(
                    RestoreError.INVALID_BACKUP,
                    "séries acima do teto em [$index]"
                )
            }
            exercise.sets.forEach { set ->
                if (set.weight < 0f || set.repetitions < 0) {
                    throw RestoreException(
                        RestoreError.INVALID_BACKUP,
                        "série com valor impossível em [$index]"
                    )
                }
            }
        }
    }

    /**
     * As relações internas exigidas — e só elas.
     *
     * A política é a mesma do servidor (`contracts/backup/v1/README.md` §6), porque um snapshot
     * aceito lá tem que ser restaurável aqui. O que **não** é exigido está registrado com o motivo:
     *
     * - `WORKOUT_SESSION.templateSyncId` — histórico sobrevive ao treino que o originou;
     * - referência de exercício **dentro** de uma sessão — a sessão carrega `exerciseNameSnapshot`,
     *   que é justamente o que preserva o passado quando o exercício é renomeado ou apagado;
     * - `CHECK_IN.sessionSyncId` — pode apontar para uma sessão não concluída, que o backup não
     *   inclui.
     */
    private fun validateRelations(snapshot: ValidatedRestoreSnapshot) {
        val programIds = snapshot.programs.map { it.syncId }.toSet()
        val customIds = snapshot.customExercises.map { it.syncId }.toSet()

        snapshot.templates.forEachIndexed { index, template ->
            val programSyncId = template.programSyncId
            if (programSyncId != null && programSyncId !in programIds) {
                throw RestoreException(
                    RestoreError.INVALID_BACKUP,
                    "programa referenciado ausente do snapshot em [$index]"
                )
            }
            template.exercises.forEach { entry ->
                if (entry.exercise.kind == ExerciseRefDto.CUSTOM && entry.exercise.id !in customIds) {
                    throw RestoreException(
                        RestoreError.INVALID_BACKUP,
                        "exercício personalizado referenciado ausente do snapshot em [$index]"
                    )
                }
            }
        }

        snapshot.overrides.forEachIndexed { index, override ->
            if (override.exercise.kind == ExerciseRefDto.CUSTOM &&
                override.exercise.id !in customIds
            ) {
                throw RestoreException(
                    RestoreError.INVALID_BACKUP,
                    "exercício personalizado referenciado ausente do snapshot em [$index]"
                )
            }
        }
    }

    private fun identityOf(ref: ExerciseRefDto): String = when (ref.kind) {
        ExerciseRefDto.CANONICAL -> "${BackupContract.CANONICAL_IDENTITY_PREFIX}${ref.id}"
        else -> "${BackupContract.CUSTOM_IDENTITY_PREFIX}${ref.id}"
    }

    private fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

    private companion object {
        const val COMPLETED_STATUS = "COMPLETED"

        /** UUID em qualquer caixa — a mesma tolerância do servidor, e pelo mesmo motivo. */
        val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

/**
 * O envelope, do ponto de vista de quem **lê**.
 *
 * Existe ao lado do `BackupSnapshotDto` (que é o de quem escreve) por uma razão só: aqui
 * `capturedAt` e `source` são opcionais. O contrato os descreve como informativos, e um restore não
 * pode falhar porque um diagnóstico não veio — enquanto o backup, que os produz, sempre os escreve.
 */
@Serializable
private data class RestoreEnvelopeDto(
    val clientBackupId: String,
    val backupSchemaVersion: Int,
    val deviceId: String,
    val capturedAt: Long? = null,
    val source: JsonElement? = null,
    val items: List<RestoreItemDto> = emptyList()
)

@Serializable
private data class RestoreItemDto(
    val entityType: String,
    val entitySchemaVersion: Int,
    val syncId: String,
    val payload: JsonElement
)
