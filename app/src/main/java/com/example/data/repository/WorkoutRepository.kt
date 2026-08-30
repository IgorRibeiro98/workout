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
    private val remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource()
) {
    val activeExercises: Flow<List<ExerciseEntity>> = dao.getActiveExercises()
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

    suspend fun addTemplate(programId: Long, name: String, shortId: String, order: Int, dayOfWeek: String? = null) {
        dao.insertTemplate(WorkoutTemplateEntity(
            programId = programId,
            name = name,
            shortIdentifier = shortId,
            orderInProgram = order,
            dayOfWeek = dayOfWeek
        ))
    }

    suspend fun deleteTemplate(template: WorkoutTemplateEntity) {
        dao.deleteTemplate(template)
    }

    suspend fun deleteProgram(program: WorkoutProgramEntity) {
        dao.deleteProgram(program)
    }

    suspend fun getLastCompletedSession() = dao.getLastCompletedSession()

    fun getWeeklyCompletedSessionsCount(startOfWeek: Long) = dao.getWeeklyCompletedSessionsCount(startOfWeek)

    fun getTemplateExercises(templateId: Long) = dao.getTemplateExercisesWithDetailsFlow(templateId)

    suspend fun getTemplate(templateId: Long) = dao.getTemplateById(templateId)

    suspend fun addExerciseToTemplate(templateId: Long, exerciseId: Long, sortOrder: Int) {
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = sortOrder))
    }

    suspend fun updateTemplateExerciseFull(templateExercise: WorkoutTemplateExerciseEntity) {
        dao.updateTemplateExerciseFull(templateExercise)
    }

    suspend fun removeExerciseFromTemplate(templateExercise: WorkoutTemplateExerciseEntity) {
        dao.deleteTemplateExercise(templateExercise)
    }
}
