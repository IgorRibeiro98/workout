package com.example.data.repository

import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import kotlinx.coroutines.flow.Flow
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.NetworkExerciseRemoteDataSource

class WorkoutRepository(
    val dao: WorkoutDao,
    private val remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource(),
    val settingsManager: com.example.data.datastore.SettingsManager? = null
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

    suspend fun addExercise(name: String, muscle: String, equipment: String? = null) {
        dao.insertExercise(
            ExerciseEntity(
                name = name,
                primaryMuscle = muscle,
                equipment = equipment,
                isUserCreated = true
            )
        )
    }

    suspend fun deleteExercise(exercise: ExerciseEntity) {
        if (exercise.isUserCreated) {
            dao.deleteExercise(exercise)
        }
    }

    suspend fun addProgram(name: String) {
        val id = dao.insertProgram(WorkoutProgramEntity(name = name))
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
        return dao.insertTemplate(WorkoutTemplateEntity(
            programId = programId,
            name = name,
            shortIdentifier = shortId,
            orderInProgram = order,
            dayOfWeek = dayOfWeek
        ))
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
        dao.deleteTemplate(template)
    }

    suspend fun deleteProgram(program: WorkoutProgramEntity) {
        dao.deleteProgram(program)
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
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = sortOrder))
    }

    /**
     * Insere um exercício de template já com séries, repetições, descanso e carga definidos.
     *
     * É o mesmo insert de [addExerciseToTemplate] seguido de [updateTemplateExerciseFull] que o
     * editor faz em dois passos, em uma escrita só — útil para quem já chega com os valores
     * prontos.
     */
    suspend fun addTemplateExercise(templateExercise: WorkoutTemplateExerciseEntity) {
        dao.insertTemplateExercise(templateExercise)
    }

    /** Exercício do catálogo pelo id canônico. A identidade nunca é o nome. */
    suspend fun getExerciseByCanonicalId(canonicalId: String): ExerciseEntity? =
        dao.getExerciseByCanonicalId(canonicalId)

    /** Exercício do catálogo pela linha do Room. */
    suspend fun getExerciseByRowId(rowId: Long): ExerciseEntity? = dao.getExerciseById(rowId)

    suspend fun updateTemplateExerciseFull(templateExercise: WorkoutTemplateExerciseEntity) {
        dao.updateTemplateExerciseFull(templateExercise)
    }

    suspend fun updateTemplateExercises(items: List<WorkoutTemplateExerciseEntity>) {
        items.forEach { dao.updateTemplateExerciseFull(it) }
    }

    suspend fun removeExerciseFromTemplate(templateExercise: WorkoutTemplateExerciseEntity) {
        dao.deleteTemplateExercise(templateExercise)
    }
}
