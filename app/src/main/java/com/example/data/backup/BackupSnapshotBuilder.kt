package com.example.data.backup

import com.example.data.datastore.SettingsManager
import com.example.data.local.BodyMeasurementDao
import com.example.data.local.WeeklyGoalDao
import com.example.data.local.WorkoutDao
import com.example.data.sync.SyncAggregateSnapshotBuilder
import com.example.data.sync.dto.ExerciseRefDto
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Monta o snapshot completo do estado pessoal (T16.4).
 *
 * ## Um snapshot completo, não um delta
 *
 * Cada backup é autocontido: ele não depende de backup anterior, de entrada de Outbox nem de
 * servidor antigo. É isso que permite ao restore da T16.5 pegar **um** snapshot e reconstruir o
 * estado — sem reproduzir uma fila histórica desde a instalação do app.
 *
 * ## Leitura, e só leitura
 *
 * Este montador tem DAOs, mas não chama nada que escreva. Ele não corrige PR, não recalcula XP,
 * não normaliza sessão, não reordena histórico e não salva template. Backup **observa** a
 * autoridade atual; aproveitar a serialização para "arrumar" dado seria criar uma segunda regra de
 * domínio no caminho menos visível do app.
 *
 * ## O que entra, e por quê
 *
 * A matriz (`docs/architecture/data-classification-matrix.md`) decide, não este arquivo. Os seis
 * agregados com identidade da T16.3 vêm do **mesmo** [SyncAggregateSnapshotBuilder] que a Outbox
 * usaria — não existe um segundo serializador de treino no Spark. Os três que só existem no
 * backup são montados aqui, com DTO próprio.
 *
 * Ficam **fora**: gamificação, XP, conquistas e recordes (derivados — o restore recalcula a partir
 * do histórico, com a política vigente, em vez de herdar um número que ninguém audita), catálogo
 * canônico e conteúdo premium (vêm do manifesto), preferências de aparelho, estado do timer,
 * `deviceId`, estado da nuvem, a chave da ExerciseDB (credencial), a Outbox (mecanismo interno) e
 * mídia local.
 */
class BackupSnapshotBuilder(
    private val workoutDao: WorkoutDao,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val weeklyGoalDao: WeeklyGoalDao,
    private val aggregates: SyncAggregateSnapshotBuilder,
    private val json: Json = Json { encodeDefaults = true }
) {

    /**
     * O que existe no dataset, para a tela de confirmação da adoção.
     *
     * Contagem, nunca conteúdo: a confirmação precisa dizer "12 treinos, 84 sessões", e não listar
     * o que a pessoa treina. Não estabelece vínculo nem cria tentativa — é leitura pura.
     */
    suspend fun summary(): BackupSummary = BackupSummary(
        programs = workoutDao.getAllProgramSyncIds().size,
        templates = workoutDao.getAllTemplateSyncIds().size,
        completedSessions = workoutDao.getCompletedSessionSyncIds().size,
        customExercises = workoutDao.getCustomExerciseSyncIds().size,
        bodyMeasurements = bodyMeasurementDao.getAllMeasurementSyncIds().size,
        checkIns = workoutDao.getAllCheckInSyncIds().size,
        exerciseOverrides = workoutDao.getAllOverrides().size,
        weeklyGoals = weeklyGoalDao.getAllGoals().size
    )

    /**
     * Os itens do snapshot, na ordem determinística do contrato.
     *
     * **Precisa ser chamado dentro de uma transação Room.** Ler templates, o usuário alterar um
     * treino, e só então ler as sessões produziria um snapshot internamente contraditório — um
     * histórico apontando para uma estrutura que o backup não contém.
     *
     * As preferências **não** vêm daqui: elas moram no DataStore, e não existe transação
     * atravessando os dois armazenamentos. Ver [preferencesItem].
     */
    suspend fun captureRoomItems(): List<BackupItemDto> {
        val items = mutableListOf<BackupItemDto>()

        items += syncAggregateItems(
            BackupEntityType.WORKOUT_PROGRAM,
            workoutDao.getAllProgramSyncIds()
        )
        items += syncAggregateItems(
            BackupEntityType.WORKOUT_TEMPLATE,
            workoutDao.getAllTemplateSyncIds()
        )
        items += syncAggregateItems(
            BackupEntityType.WORKOUT_SESSION,
            workoutDao.getCompletedSessionSyncIds()
        )
        items += syncAggregateItems(
            BackupEntityType.CUSTOM_EXERCISE,
            workoutDao.getCustomExerciseSyncIds()
        )
        items += syncAggregateItems(
            BackupEntityType.BODY_MEASUREMENT,
            bodyMeasurementDao.getAllMeasurementSyncIds()
        )
        items += syncAggregateItems(
            BackupEntityType.CHECK_IN,
            workoutDao.getAllCheckInSyncIds()
        )
        items += overrideItems()
        items += weeklyGoalItems()

        return items
    }

    /**
     * As preferências sincronizáveis, lidas do DataStore.
     *
     * Separado de [captureRoomItems] porque **não existe atomicidade entre Room e DataStore**, e
     * fingir que existe seria pior do que assumir a limitação. Na prática ela é aceitável: uma
     * preferência trocada no milissegundo entre as duas leituras significa um backup com a
     * preferência antiga, e o próximo backup a corrige. Nenhum histórico é afetado.
     */
    suspend fun preferencesItem(settings: SettingsManager): BackupItemDto {
        val preferences = UserPreferencesBackupDto(
            weeklyGoal = settings.weeklyGoalFlow.first(),
            useKg = settings.useKgFlow.first(),
            defaultRestSeconds = settings.defaultRestSecondsFlow.first(),
            defaultExerciseRestSeconds = settings.defaultExerciseRestSecondsFlow.first(),
            rirRpeEnabled = settings.rirRpeEnabledFlow.first(),
            autoRestTimerOnSet = settings.autoRestTimerOnSetFlow.first()
        )
        return BackupItemDto(
            entityType = BackupEntityType.USER_PREFERENCES.name,
            entitySchemaVersion = BackupEntityType.USER_PREFERENCES.schemaVersion,
            syncId = BackupContract.PREFERENCES_IDENTITY,
            payload = json.encodeToJsonElement(preferences)
        )
    }

    /**
     * Os agregados que a T16.3 já sabe serializar.
     *
     * `null` do montador significa "identidade global impossível de resolver" — um exercício sem
     * `canonicalId` e sem `syncId`, por exemplo. O montador da T16.3 recusa em vez de adivinhar, e
     * aqui a recusa vira [BackupSnapshotIncompleteException]: enviar o agregado incompleto criaria
     * um dado errado permanente no servidor, e omiti-lo em silêncio faria o backup mentir sobre o
     * que protege.
     */
    private suspend fun syncAggregateItems(
        type: BackupEntityType,
        syncIds: List<String>
    ): List<BackupItemDto> {
        val syncType = requireNotNull(type.syncEntityType) { "tipo sem agregado de sync: $type" }
        return syncIds.mapNotNull { syncId ->
            val envelope = aggregates.snapshot(syncType, syncId)
            if (envelope == null) {
                // A linha pode ter sumido entre a enumeração e a montagem dentro da mesma
                // transação? Não: a transação impede. Então isto é dado inconsistente de verdade.
                throw BackupSnapshotIncompleteException(type)
            }
            BackupItemDto(
                entityType = type.name,
                entitySchemaVersion = envelope.schemaVersion,
                syncId = envelope.entitySyncId,
                payload = envelope.payload
            )
        }
    }

    /**
     * As customizações de exercício, com identidade derivada do exercício alvo.
     *
     * `customPhotoUri` não é serializado: é um `content://` deste aparelho. Uma customização que
     * seja **só** foto não vira item — não haveria nada portátil dentro dela.
     */
    private suspend fun overrideItems(): List<BackupItemDto> =
        workoutDao.getAllOverrides().mapNotNull { override ->
            val exercise = workoutDao.getExerciseById(override.exerciseId) ?: return@mapNotNull null
            val ref = exerciseRefOf(exercise) ?: return@mapNotNull null

            val hasPortableContent = override.displayName != null ||
                override.notes != null ||
                override.defaultRestSeconds != null
            if (!hasPortableContent) return@mapNotNull null

            val payload = ExerciseOverrideBackupDto(
                exercise = ref,
                displayName = override.displayName,
                notes = override.notes,
                defaultRestSeconds = override.defaultRestSeconds,
                updatedAt = override.updatedAt
            )
            BackupItemDto(
                entityType = BackupEntityType.EXERCISE_OVERRIDE.name,
                entitySchemaVersion = BackupEntityType.EXERCISE_OVERRIDE.schemaVersion,
                syncId = identityOf(ref),
                payload = json.encodeToJsonElement(payload)
            )
        }

    private suspend fun weeklyGoalItems(): List<BackupItemDto> =
        weeklyGoalDao.getAllGoals().map { goal ->
            BackupItemDto(
                entityType = BackupEntityType.WEEKLY_GOAL.name,
                entitySchemaVersion = BackupEntityType.WEEKLY_GOAL.schemaVersion,
                syncId = "${BackupContract.WEEK_IDENTITY_PREFIX}${goal.effectiveFromWeekStartEpochDay}",
                payload = json.encodeToJsonElement(
                    WeeklyGoalBackupDto(
                        effectiveFromWeekStartEpochDay = goal.effectiveFromWeekStartEpochDay,
                        goal = goal.goal,
                        createdAt = goal.createdAt
                    )
                )
            )
        }

    /**
     * A identidade global de um exercício: `canonicalId` para catálogo, `syncId` para o pessoal.
     *
     * Mesma regra do montador da T16.3, e pelo mesmo motivo: `localId` não significa nada no outro
     * aparelho.
     */
    private fun exerciseRefOf(exercise: com.example.data.local.ExerciseEntity): ExerciseRefDto? {
        val canonicalId = exercise.canonicalId?.takeIf { it.isNotBlank() }
        if (canonicalId != null && !exercise.isUserCreated) {
            return ExerciseRefDto(ExerciseRefDto.CANONICAL, canonicalId)
        }
        val syncId = exercise.syncId?.takeIf { it.isNotBlank() }
        if (syncId != null) return ExerciseRefDto(ExerciseRefDto.CUSTOM, syncId)
        return canonicalId?.let { ExerciseRefDto(ExerciseRefDto.CANONICAL, it) }
    }

    private fun identityOf(ref: ExerciseRefDto): String = when (ref.kind) {
        ExerciseRefDto.CANONICAL -> "${BackupContract.CANONICAL_IDENTITY_PREFIX}${ref.id}"
        else -> "${BackupContract.CUSTOM_IDENTITY_PREFIX}${ref.id}"
    }
}

/**
 * O que o backup vai associar à conta — para a confirmação explícita da adoção.
 *
 * Só contagem. Nomes de treino, notas e medidas não aparecem na tela de confirmação: o usuário
 * precisa saber **quanto** será associado, não reler o próprio histórico.
 */
data class BackupSummary(
    val programs: Int,
    val templates: Int,
    val completedSessions: Int,
    val customExercises: Int,
    val bodyMeasurements: Int,
    val checkIns: Int,
    val exerciseOverrides: Int,
    val weeklyGoals: Int
) {
    val total: Int
        get() = programs + templates + completedSessions + customExercises +
            bodyMeasurements + checkIns + exerciseOverrides + weeklyGoals
}

/**
 * Um agregado não pôde ser descrito de forma portátil.
 *
 * O backup falha inteiro, e de forma recuperável. As duas alternativas são piores: enviar o
 * agregado incompleto grava dado errado no servidor de forma permanente, e omiti-lo em silêncio
 * faz o backup dizer que protege algo que ele não protege.
 */
class BackupSnapshotIncompleteException(
    val entityType: BackupEntityType
) : IllegalStateException("agregado sem identidade global portátil: $entityType")
