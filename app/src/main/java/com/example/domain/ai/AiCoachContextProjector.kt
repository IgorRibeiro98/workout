package com.example.domain.ai

import com.example.data.local.ExerciseEntity
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiExecutedExerciseContext
import com.example.domain.ai.model.AiPersonalRecordContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiSessionContext
import com.example.domain.ai.model.AiWorkoutContext

/**
 * Projeção pura: entidades canônicas do Spark viram [AiCoachContext].
 *
 * Aqui não há IO nem regra de negócio — só recorte e preservação de identidade. Duas garantias
 * são o motivo desta classe existir:
 *
 * - a identidade enviada ao modelo é sempre o id canônico do exercício, nunca o nome;
 * - o histórico é limitado por [AiModelConfig.RECENT_SESSIONS_LIMIT], então o prompt não cresce
 *   com o banco.
 */
object AiCoachContextProjector {

    /**
     * Prefixo do id de exercícios sem `canonicalId` (criados pelo usuário).
     *
     * Continua sendo identidade estável do app — e não o nome —, e a validação só aceita ids que
     * saíram daqui.
     */
    const val LOCAL_ID_PREFIX: String = "local:"

    fun project(
        exercisesById: Map<Long, ExerciseEntity>,
        templateName: String?,
        templateExercises: List<WorkoutTemplateExerciseEntity>,
        recentSessions: List<SessionCalendarSummary>,
        personalRecordsByExerciseId: Map<Long, PersonalRecordEntity>,
        weeklyGoal: Int?,
        bodyWeightKg: Float?
    ): AiCoachContext {
        val limitedSessions = recentSessions
            .sortedByDescending { it.session.finishedAt ?: it.session.startedAt }
            .take(AiModelConfig.RECENT_SESSIONS_LIMIT)

        val currentWorkout = projectCurrentWorkout(exercisesById, templateName, templateExercises)
        val sessions = limitedSessions.map { projectSession(exercisesById, it) }

        val idsInContext = buildSet {
            currentWorkout?.exercises?.forEach { add(it.exerciseId) }
            sessions.forEach { session -> session.exercises.forEach { add(it.exerciseId) } }
        }

        return AiCoachContext(
            athlete = AiAthleteContext(
                weeklyGoal = weeklyGoal,
                completedSessionsInWindow = sessions.size,
                bodyWeightKg = bodyWeightKg
            ),
            currentWorkout = currentWorkout,
            recentSessions = sessions,
            personalRecords = projectPersonalRecords(
                exercisesById = exercisesById,
                personalRecordsByExerciseId = personalRecordsByExerciseId,
                idsInContext = idsInContext
            )
        )
    }

    /** Id canônico do catálogo; sem ele, a identidade local determinística do app. */
    fun exerciseIdOf(exercise: ExerciseEntity): String =
        exercise.canonicalId?.trim()?.takeIf { it.isNotEmpty() } ?: "$LOCAL_ID_PREFIX${exercise.id}"

    private fun projectCurrentWorkout(
        exercisesById: Map<Long, ExerciseEntity>,
        templateName: String?,
        templateExercises: List<WorkoutTemplateExerciseEntity>
    ): AiWorkoutContext? {
        if (templateName == null && templateExercises.isEmpty()) return null

        val exercises = templateExercises
            .sortedBy { it.sortOrder }
            .mapNotNull { planned ->
                val exercise = exercisesById[planned.exerciseId] ?: return@mapNotNull null
                AiPlannedExerciseContext(
                    exerciseId = exerciseIdOf(exercise),
                    name = exercise.name,
                    targetSets = planned.targetSets,
                    minReps = planned.minReps,
                    maxReps = planned.maxReps,
                    plannedWeightKg = planned.plannedWeight,
                    restSeconds = planned.restDurationSeconds
                )
            }

        return AiWorkoutContext(templateName = templateName, exercises = exercises)
    }

    private fun projectSession(
        exercisesById: Map<Long, ExerciseEntity>,
        summary: SessionCalendarSummary
    ): AiSessionContext {
        val session = summary.session
        val finishedAt = session.finishedAt
        val durationMinutes = if (finishedAt != null && finishedAt > session.startedAt) {
            ((finishedAt - session.startedAt) / 60_000L).toInt()
        } else {
            null
        }

        val exercises = summary.sortedExercises.mapNotNull { executed ->
            // Sem id do exercício não há identidade canônica para preservar; enviar apenas o nome
            // convidaria o modelo a tratar o nome como identificador.
            val rowId = executed.exerciseSession.actualExerciseId
                ?: executed.exerciseSession.plannedExerciseId
                ?: return@mapNotNull null
            val exercise = exercisesById[rowId] ?: return@mapNotNull null

            val completedSets = executed.sets.filter { it.completed }
            if (completedSets.isEmpty()) return@mapNotNull null

            val weightedSets = completedSets.filter { it.weight > 0f && !it.isDurationMode }
            val repSets = completedSets.filter { !it.isDurationMode }

            AiExecutedExerciseContext(
                exerciseId = exerciseIdOf(exercise),
                name = exercise.name,
                completedSets = completedSets.size,
                maxWeightKg = weightedSets.maxOfOrNull { it.weight },
                totalReps = repSets.takeIf { it.isNotEmpty() }?.sumOf { it.repetitions }
            )
        }

        return AiSessionContext(
            finishedAtEpochMs = finishedAt,
            durationMinutes = durationMinutes,
            exercises = exercises
        )
    }

    private fun projectPersonalRecords(
        exercisesById: Map<Long, ExerciseEntity>,
        personalRecordsByExerciseId: Map<Long, PersonalRecordEntity>,
        idsInContext: Set<String>
    ): List<AiPersonalRecordContext> {
        return personalRecordsByExerciseId.entries
            .mapNotNull { (rowId, record) ->
                val exercise = exercisesById[rowId] ?: return@mapNotNull null
                val exerciseId = exerciseIdOf(exercise)
                // PR de exercício fora do contexto é ruído: nada na análise se apoia nele.
                if (exerciseId !in idsInContext) return@mapNotNull null
                AiPersonalRecordContext(
                    exerciseId = exerciseId,
                    name = exercise.name,
                    type = record.prType.name,
                    value = record.value,
                    achievedAtEpochMs = record.date
                )
            }
            .sortedByDescending { it.achievedAtEpochMs ?: 0L }
            .take(AiModelConfig.PERSONAL_RECORDS_LIMIT)
    }
}
