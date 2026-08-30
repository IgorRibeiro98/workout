package com.example

import com.example.data.local.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeWorkoutDao : WorkoutDao {
    val exercises = mutableListOf<ExerciseEntity>()
    val overrides = mutableListOf<ExerciseUserOverrideEntity>()
    val programs = mutableListOf<WorkoutProgramEntity>()
    val templates = mutableListOf<WorkoutTemplateEntity>()
    val templateExercises = mutableListOf<WorkoutTemplateExerciseEntity>()
    val alternatives = mutableListOf<ExerciseAlternativeEntity>()
    val sessions = mutableListOf<WorkoutSessionEntity>()
    val exerciseSessions = mutableListOf<ExerciseSessionEntity>()
    val setLogs = mutableListOf<SetLogEntity>()
    val prs = mutableListOf<PersonalRecordEntity>()

    override fun getActiveExercises(): Flow<List<ExerciseEntity>> = flowOf(exercises.filter { it.active })
    override suspend fun getAllExercisesList(): List<ExerciseEntity> = exercises.filter { it.active }
    override suspend fun insertExercise(exercise: ExerciseEntity): Long {
        val id = (exercises.maxOfOrNull { it.id } ?: 0L) + 1L
        val newEx = exercise.copy(id = id)
        exercises.add(newEx)
        return id
    }
    override suspend fun updateExercise(exercise: ExerciseEntity) {
        val idx = exercises.indexOfFirst { it.id == exercise.id }
        if (idx >= 0) exercises[idx] = exercise
    }
    override suspend fun deleteExercise(exercise: ExerciseEntity) {
        exercises.removeAll { it.id == exercise.id }
    }
    override suspend fun getExerciseByCanonicalId(canonicalId: String): ExerciseEntity? =
        exercises.firstOrNull { it.canonicalId == canonicalId }
    override suspend fun getExerciseByName(name: String): ExerciseEntity? =
        exercises.firstOrNull { it.name.equals(name, ignoreCase = true) }
    override suspend fun getAllExercisesSync(): List<ExerciseEntity> = exercises.toList()

    override fun getAllPrograms(): Flow<List<WorkoutProgramEntity>> = flowOf(programs)
    override suspend fun getAllProgramsSync(): List<WorkoutProgramEntity> = programs.toList()
    override fun getCurrentProgram(): Flow<WorkoutProgramEntity?> = flowOf(programs.firstOrNull { it.isCurrent })
    override suspend fun insertProgram(program: WorkoutProgramEntity): Long {
        val id = (programs.maxOfOrNull { it.id } ?: 0L) + 1L
        programs.add(program.copy(id = id))
        return id
    }
    override suspend fun updateProgram(program: WorkoutProgramEntity) {
        val idx = programs.indexOfFirst { it.id == program.id }
        if (idx >= 0) programs[idx] = program
    }
    override suspend fun clearCurrentProgram() {
        programs.indices.forEach { programs[it] = programs[it].copy(isCurrent = false) }
    }
    override suspend fun setCurrentProgram(id: Long) {
        clearCurrentProgram()
        val idx = programs.indexOfFirst { it.id == id }
        if (idx >= 0) programs[idx] = programs[idx].copy(isCurrent = true)
    }

    override fun getTemplatesForProgram(programId: Long): Flow<List<WorkoutTemplateEntity>> =
        flowOf(templates.filter { it.programId == programId })
    override suspend fun getTemplatesForProgramSync(programId: Long): List<WorkoutTemplateEntity> =
        templates.filter { it.programId == programId }
    override suspend fun insertTemplate(template: WorkoutTemplateEntity): Long {
        val id = (templates.maxOfOrNull { it.id } ?: 0L) + 1L
        templates.add(template.copy(id = id))
        return id
    }
    override suspend fun updateTemplate(template: WorkoutTemplateEntity) {
        val idx = templates.indexOfFirst { it.id == template.id }
        if (idx >= 0) templates[idx] = template
    }
    override suspend fun deleteTemplateExercisesForTemplate(templateId: Long) {
        templateExercises.removeAll { it.templateId == templateId }
    }

    override suspend fun getLastCompletedSession(): WorkoutSessionEntity? =
        sessions.filter { it.status == "COMPLETED" }.maxByOrNull { it.finishedAt ?: 0L }
    override fun getWeeklyCompletedSessionsCount(startOfWeek: Long): Flow<Int> =
        flowOf(sessions.count { it.status == "COMPLETED" && (it.finishedAt ?: 0L) >= startOfWeek })
    override fun getActiveSessionFlow(): Flow<WorkoutSessionEntity?> =
        flowOf(sessions.firstOrNull { it.status == "IN_PROGRESS" })
    override suspend fun getActiveSession(): WorkoutSessionEntity? =
        sessions.firstOrNull { it.status == "IN_PROGRESS" }
    override suspend fun insertSession(session: WorkoutSessionEntity): Long {
        val id = (sessions.maxOfOrNull { it.id } ?: 0L) + 1L
        sessions.add(session.copy(id = id))
        return id
    }
    override suspend fun updateSession(session: WorkoutSessionEntity) {
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx >= 0) sessions[idx] = session
    }
    override suspend fun insertExerciseSession(exerciseSession: ExerciseSessionEntity): Long {
        val id = (exerciseSessions.maxOfOrNull { it.id } ?: 0L) + 1L
        exerciseSessions.add(exerciseSession.copy(id = id))
        return id
    }
    override suspend fun insertSetLogs(setLogs: List<SetLogEntity>) {
        setLogs.forEach { log ->
            val id = (this.setLogs.maxOfOrNull { it.id } ?: 0L) + 1L
            this.setLogs.add(log.copy(id = id))
        }
    }
    override fun getActiveSessionWithDetailsFlow(): Flow<SessionWithDetails?> = flowOf(null)
    override suspend fun getLastExecutionSetsForExercise(exerciseId: Long): List<SetLogEntity> = emptyList()
    override suspend fun updateSetLog(setLog: SetLogEntity) {
        val idx = setLogs.indexOfFirst { it.id == setLog.id }
        if (idx >= 0) setLogs[idx] = setLog
    }
    override suspend fun deleteSetLog(setLog: SetLogEntity) {
        setLogs.removeAll { it.id == setLog.id }
    }
    override suspend fun deleteProgram(program: WorkoutProgramEntity) {
        programs.removeAll { it.id == program.id }
    }
    override suspend fun deleteTemplate(template: WorkoutTemplateEntity) {
        templates.removeAll { it.id == template.id }
    }
    override suspend fun getTemplateById(templateId: Long): WorkoutTemplateEntity? =
        templates.firstOrNull { it.id == templateId }

    override suspend fun insertPersonalRecord(pr: PersonalRecordEntity) {
        prs.add(pr)
    }
    override suspend fun getHighestPR(exerciseId: Long, prType: String): PersonalRecordEntity? =
        prs.filter { it.exerciseId == exerciseId && it.prType.name == prType }.maxByOrNull { it.value }
    override fun getPRsForExerciseFlow(exerciseId: Long): Flow<List<PersonalRecordEntity>> =
        flowOf(prs.filter { it.exerciseId == exerciseId })
    override fun getRecentPRsFlow(): Flow<List<PersonalRecordEntity>> = flowOf(prs)

    override suspend fun insertCheckIn(checkIn: CheckInEntity): Long = 1L
    override suspend fun updateCheckIn(checkIn: CheckInEntity) {}
    override suspend fun deleteCheckIn(checkIn: CheckInEntity) {}
    override suspend fun getActiveCheckIn(): CheckInEntity? = null
    override fun getActiveCheckInFlow(): Flow<CheckInEntity?> = flowOf(null)
    override suspend fun getCheckInForSession(sessionId: Long): CheckInEntity? = null
    override fun getAllCompletedSessionsWithDetailsFlow(): Flow<List<SessionCalendarSummary>> = flowOf(emptyList())
    override suspend fun getAllCompletedSessionsWithDetails(): List<SessionCalendarSummary> = emptyList()
    override suspend fun deleteWorkoutSession(session: WorkoutSessionEntity) {
        sessions.removeAll { it.id == session.id }
    }

    override suspend fun getAlternativesForExercise(exerciseId: Long): List<ExerciseAlternativeEntity> =
        alternatives.filter { it.exerciseId == exerciseId }
    override suspend fun insertAlternative(alt: ExerciseAlternativeEntity): Long {
        if (alternatives.any { it.exerciseId == alt.exerciseId && it.alternativeExerciseId == alt.alternativeExerciseId }) {
            return -1L
        }
        val id = (alternatives.maxOfOrNull { it.id } ?: 0L) + 1L
        alternatives.add(alt.copy(id = id))
        return id
    }
    override suspend fun getExerciseById(id: Long): ExerciseEntity? = exercises.firstOrNull { it.id == id }

    override fun getOverrideForExerciseFlow(exerciseId: Long): Flow<ExerciseUserOverrideEntity?> =
        flowOf(overrides.firstOrNull { it.exerciseId == exerciseId })
    override suspend fun getOverrideForExercise(exerciseId: Long): ExerciseUserOverrideEntity? =
        overrides.firstOrNull { it.exerciseId == exerciseId }
    override suspend fun getAllOverrides(): List<ExerciseUserOverrideEntity> = overrides.toList()
    override fun getAllOverridesFlow(): Flow<List<ExerciseUserOverrideEntity>> = flowOf(overrides)
    override suspend fun insertOrUpdateOverride(override: ExerciseUserOverrideEntity) {
        val idx = overrides.indexOfFirst { it.exerciseId == override.exerciseId }
        if (idx >= 0) overrides[idx] = override else overrides.add(override)
    }
    override suspend fun deleteOverride(override: ExerciseUserOverrideEntity) {
        overrides.removeAll { it.exerciseId == override.exerciseId }
    }
    override suspend fun getMaxContentVersion(): Int? = exercises.maxOfOrNull { it.contentVersion }
    override suspend fun getCanonicalExercisesCount(): Int = exercises.count { !it.canonicalId.isNullOrBlank() }
    override suspend fun getExerciseSessionById(id: Long): ExerciseSessionEntity? =
        exerciseSessions.firstOrNull { it.id == id }

    override suspend fun getExplicitAlternatives(exerciseId: Long): List<ExerciseEntity> = emptyList()
    override suspend fun getAlternativesBySubstitutionGroup(exerciseId: Long): List<ExerciseEntity> = emptyList()
    override suspend fun getAlternativesByMovementPattern(exerciseId: Long): List<ExerciseEntity> = emptyList()
    override suspend fun getAlternativesByMuscle(exerciseId: Long): List<ExerciseEntity> = emptyList()
    override suspend fun updateExerciseSessionActualExercise(exerciseSessionId: Long, newExerciseId: Long, newName: String, reason: String) {}
    override suspend fun updateTemplateExercise(templateId: Long, oldExerciseId: Long, newExerciseId: Long) {}
    override suspend fun getTemplateExercisesWithDetails(templateId: Long): List<TemplateExerciseWithDetails> = emptyList()
    override fun getTemplateExercisesWithDetailsFlow(templateId: Long): Flow<List<TemplateExerciseWithDetails>> = flowOf(emptyList())
    override suspend fun insertTemplateExercise(templateExercise: WorkoutTemplateExerciseEntity) {
        templateExercises.add(templateExercise)
    }
    override suspend fun updateTemplateExerciseFull(templateExercise: WorkoutTemplateExerciseEntity) {}
    override suspend fun deleteTemplateExercise(templateExercise: WorkoutTemplateExerciseEntity) {}
}
