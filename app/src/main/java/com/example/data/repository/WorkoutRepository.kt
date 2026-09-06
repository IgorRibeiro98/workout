package com.example.data.repository

import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import kotlinx.coroutines.flow.Flow
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.NetworkExerciseRemoteDataSource
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncIds
import com.example.data.sync.SyncMutationCoordinator

/**
 * @param syncMutations a fronteira transacional entre a escrita de domínio e a Outbox (T16.3).
 *
 * O padrão é [SyncMutationCoordinator.disabled]: a alteração acontece e nada é registrado para a
 * nuvem. É o comportamento do Spark hoje — criar e editar treino continua sendo operação local,
 * sem conta, sem backend e sem rede.
 */
class WorkoutRepository(
    val dao: WorkoutDao,
    private val remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource(),
    val settingsManager: com.example.data.datastore.SettingsManager? = null,
    private val syncMutations: SyncMutationCoordinator = SyncMutationCoordinator.disabled()
) {
    val activeExercises: Flow<List<ExerciseEntity>> = dao.getActiveExercises()
    
    val activeResolvedExercises: Flow<List<com.example.domain.model.ResolvedExercise>> = 
        if (settingsManager != null) {
            kotlinx.coroutines.flow.combine(
                dao.getActiveExercises(), 
                dao.getAllOverridesFlow(),
                settingsManager.showGifsFlow
            ) { exercises, overrides, showGifs ->
                com.example.domain.engine.ExerciseResolver.resolveAll(exercises, overrides.associateBy { it.exerciseId }, showGifs)
            }
        } else {
            kotlinx.coroutines.flow.combine(dao.getActiveExercises(), dao.getAllOverridesFlow()) { exercises, overrides ->
                com.example.domain.engine.ExerciseResolver.resolveAll(exercises, overrides.associateBy { it.exerciseId })
            }
        }
    val allPrograms: Flow<List<WorkoutProgramEntity>> = dao.getAllPrograms()
    val currentProgram: Flow<WorkoutProgramEntity?> = dao.getCurrentProgram()

    /**
     * Exercício criado pelo usuário — dado pessoal, e por isso nasce com identidade global.
     *
     * O `syncId` é explícito aqui porque a coluna é anulável: o catálogo canônico continua sem
     * `syncId`, com `canonicalId` como identidade.
     */
    suspend fun addExercise(name: String, muscle: String, equipment: String? = null) {
        val exercise = ExerciseEntity(
            name = name,
            primaryMuscle = muscle,
            equipment = equipment,
            isUserCreated = true,
            syncId = SyncIds.random()
        )
        syncMutations.mutate {
            dao.insertExercise(exercise)
            exercise.syncId?.let { upsert(SyncEntityType.CUSTOM_EXERCISE, it) }
        }
    }

    suspend fun deleteExercise(exercise: ExerciseEntity) {
        // Exercício canônico não é apagado por aqui, e continua não sendo: a regra de domínio vem
        // antes da mutação, então uma tentativa recusada não registra intenção de sync nenhuma.
        if (!exercise.isUserCreated) return
        syncMutations.mutate {
            dao.deleteExercise(exercise)
            exercise.syncId?.let { delete(SyncEntityType.CUSTOM_EXERCISE, it) }
        }
    }

    suspend fun addProgram(name: String) {
        val program = WorkoutProgramEntity(name = name)
        val id = syncMutations.mutate {
            val id = dao.insertProgram(program)
            upsert(SyncEntityType.WORKOUT_PROGRAM, program.syncId)
            id
        }
        if (dao.getCurrentProgram() == null) {
            dao.setCurrentProgram(id)
        }
    }

    suspend fun setCurrentProgram(id: Long) {
        dao.clearCurrentProgram()
        dao.setCurrentProgram(id)
    }

    fun getTemplatesForProgram(programId: Long): Flow<List<WorkoutTemplateEntity>> {
        return dao.getTemplatesForProgram(programId)
    }

    /** Devolve o id gerado pelo Room, para quem precisa continuar montando o treino recém-criado. */
    suspend fun addTemplate(programId: Long, name: String, shortId: String, order: Int, dayOfWeek: String? = null): Long {
        val template = WorkoutTemplateEntity(
            programId = programId,
            name = name,
            shortIdentifier = shortId,
            orderInProgram = order,
            dayOfWeek = dayOfWeek
        )
        return syncMutations.mutate {
            val id = dao.insertTemplate(template)
            upsert(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)
            id
        }
    }

    /**
     * O programa que deve receber um treino novo: o atual, ou o primeiro existente.
     *
     * Mesma escolha que a tela de treinos faz ao criar um template manualmente; `null` significa
     * que ainda não existe programa nenhum.
     */
    suspend fun getProgramForNewTemplate(): WorkoutProgramEntity? {
        val programs = dao.getAllProgramsSync()
        return programs.firstOrNull { it.isCurrent } ?: programs.firstOrNull()
    }

    suspend fun deleteTemplate(template: WorkoutTemplateEntity) {
        syncMutations.mutate {
            dao.deleteTemplate(template)
            delete(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)
        }
    }

    suspend fun deleteProgram(program: WorkoutProgramEntity) {
        syncMutations.mutate {
            dao.deleteProgram(program)
            delete(SyncEntityType.WORKOUT_PROGRAM, program.syncId)
        }
    }

    suspend fun getLastCompletedSession() = dao.getLastCompletedSession()

    fun getWeeklyCompletedSessionsCount(startOfWeek: Long) = dao.getWeeklyCompletedSessionsCount(startOfWeek)

    /** Total de treinos concluídos. Apenas sessões `COMPLETED` entram na contagem. */
    fun getCompletedSessionsCountFlow() = dao.getCompletedSessionsCountFlow()

    /** Total de recordes pessoais persistidos. */
    fun getPersonalRecordsCountFlow() = dao.getPersonalRecordsCountFlow()

    fun getTemplateExercises(templateId: Long) = dao.getTemplateExercisesWithDetailsFlow(templateId)
    val allOverridesFlow = dao.getAllOverridesFlow()

    suspend fun getTemplate(templateId: Long) = dao.getTemplateById(templateId)

    suspend fun addExerciseToTemplate(templateId: Long, exerciseId: Long, sortOrder: Int) {
        syncMutations.mutate {
            dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = sortOrder))
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(templateId) }
        }
    }

    /**
     * Insere um exercício de template já com séries, repetições, descanso e carga definidos.
     *
     * É o mesmo insert de [addExerciseToTemplate] seguido de [updateTemplateExerciseFull] que o
     * editor faz em dois passos, em uma escrita só — útil para quem já chega com os valores
     * prontos.
     */
    suspend fun addTemplateExercise(templateExercise: WorkoutTemplateExerciseEntity) {
        syncMutations.mutate {
            dao.insertTemplateExercise(templateExercise)
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(templateExercise.templateId) }
        }
    }

    /** Exercício do catálogo pelo id canônico. A identidade nunca é o nome. */
    suspend fun getExerciseByCanonicalId(canonicalId: String): ExerciseEntity? =
        dao.getExerciseByCanonicalId(canonicalId)

    /** Exercício do catálogo pela linha do Room. */
    suspend fun getExerciseByRowId(rowId: Long): ExerciseEntity? = dao.getExerciseById(rowId)

    suspend fun updateTemplateExerciseFull(templateExercise: WorkoutTemplateExerciseEntity) {
        syncMutations.mutate {
            dao.updateTemplateExerciseFull(templateExercise)
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(templateExercise.templateId) }
        }
    }

    /**
     * Lote de atualizações do treino: todas entram juntas ou nenhuma entra.
     *
     * É o caminho do reordenar (arrastar e soltar) e de qualquer edição em massa. Ordem é dado de
     * domínio persistido, então mudar a ordem **é** mudar o agregado — e dezenas de linhas
     * alteradas produzem **uma** mutação do treino, não uma por linha.
     */
    suspend fun updateTemplateExercises(items: List<WorkoutTemplateExerciseEntity>) {
        if (items.isEmpty()) return
        syncMutations.mutate {
            dao.updateTemplateExercisesTransactionally(items)
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(items.first().templateId) }
        }
    }

    /** Os exercícios do treino como estão persistidos agora, fora de qualquer Flow. */
    suspend fun getTemplateExercisesSync(templateId: Long) =
        dao.getTemplateExercisesWithDetails(templateId)

    suspend fun removeExerciseFromTemplate(templateExercise: WorkoutTemplateExerciseEntity) {
        syncMutations.mutate {
            dao.deleteTemplateExercise(templateExercise)
            // O exercício some do treino, mas quem mudou foi o treino. Um `DELETE` de agregado
            // aqui apagaria o template inteiro do outro lado.
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(templateExercise.templateId) }
        }
    }
}
