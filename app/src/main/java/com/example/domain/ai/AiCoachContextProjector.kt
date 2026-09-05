package com.example.domain.ai

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiEvidenceContext
import com.example.domain.ai.model.AiExerciseLoadEvidenceContext
import com.example.domain.ai.model.AiExerciseExecutionContext
import com.example.domain.ai.model.AiExerciseHistoryContext
import com.example.domain.ai.model.AiPersonalRecordContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiWorkoutContext

/**
 * Projeção pura: entidades canônicas do Spark viram [AiCoachContext].
 *
 * Aqui não há IO nem regra de negócio — só recorte e preservação de identidade. Três garantias
 * são o motivo desta classe existir:
 *
 * - a identidade enviada ao modelo é sempre o id canônico do exercício, nunca o nome;
 * - o histórico é **relevante**: só entram os exercícios do treino em foco;
 * - o histórico é **limitado**: até [AiModelConfig.HISTORY_PER_EXERCISE_LIMIT] execuções por
 *   exercício, buscadas em no máximo [AiModelConfig.HISTORY_SCAN_SESSIONS] sessões concluídas.
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
        completedSessions: List<SessionCalendarSummary>,
        personalRecordsByExerciseId: Map<Long, PersonalRecordEntity>,
        weeklyGoal: Int?
    ): AiCoachContext {
        val plannedExercises = templateExercises.sortedBy { it.sortOrder }
        val currentWorkout = projectCurrentWorkout(exercisesById, templateName, plannedExercises)

        val scannedSessions = completedSessions
            .sortedByDescending { it.session.finishedAt ?: it.session.startedAt }
            .take(AiModelConfig.HISTORY_SCAN_SESSIONS)

        val relevantRowIds = resolveRelevantExercises(plannedExercises, scannedSessions)
        val contributingSessionIds = mutableSetOf<Long>()
        val history = projectHistory(exercisesById, relevantRowIds, scannedSessions, contributingSessionIds)

        // Só conta o que realmente foi enviado: nunca mais sessões do que o modelo enxergou.
        val sessionsAnalyzed = contributingSessionIds.size
        val idsInContext = buildSet {
            currentWorkout?.exercises?.forEach { add(it.exerciseId) }
            history.forEach { add(it.exerciseId) }
        }

        return AiCoachContext(
            athlete = AiAthleteContext(
                weeklyGoal = weeklyGoal,
                completedSessionsInWindow = sessionsAnalyzed
            ),
            currentWorkout = currentWorkout,
            exerciseHistory = history,
            personalRecords = projectPersonalRecords(
                exercisesById = exercisesById,
                personalRecordsByExerciseId = personalRecordsByExerciseId,
                idsInContext = idsInContext
            ),
            evidence = AiEvidenceContext(
                sessionsAnalyzed = sessionsAnalyzed,
                exercisesWithHistory = history.count { it.executions.isNotEmpty() },
                maxDataQuality = AiDataQualityPolicy.ceilingFor(sessionsAnalyzed)
            )
        )
    }

    /** Id canônico do catálogo; sem ele, a identidade local determinística do app. */
    fun exerciseIdOf(exercise: ExerciseEntity): String =
        exercise.canonicalId?.trim()?.takeIf { it.isNotEmpty() } ?: "$LOCAL_ID_PREFIX${exercise.id}"

    /**
     * A linha do catálogo por trás de um id local, ou `null` quando o id não é local.
     *
     * O caminho de volta de [exerciseIdOf]: quem precisa resolver um id do Coach para a linha do
     * Room usa isto e, quando dá `null`, procura pelo `canonicalId`. Nunca pelo nome.
     */
    fun localRowIdOf(exerciseId: String): Long? {
        if (!exerciseId.startsWith(LOCAL_ID_PREFIX)) return null
        return exerciseId.removePrefix(LOCAL_ID_PREFIX).toLongOrNull()
    }

    /**
     * Carga registrada dos exercícios candidatos a uma geração.
     *
     * Só olha o que está persistido em séries concluídas de sessões `COMPLETED`, e só para os
     * exercícios que já foram oferecidos como candidatos. Serve para o modelo **não** inventar
     * carga; não autoriza progressão — isso é assunto da T14.3.
     */
    fun projectLoadEvidence(
        exercisesById: Map<Long, ExerciseEntity>,
        candidateExerciseIds: Set<String>,
        completedSessions: List<SessionCalendarSummary>
    ): List<AiExerciseLoadEvidenceContext> {
        if (candidateExerciseIds.isEmpty()) return emptyList()

        val scanned = completedSessions
            .sortedByDescending { it.session.finishedAt ?: it.session.startedAt }
            .take(AiModelConfig.HISTORY_SCAN_SESSIONS)

        // Ordem de inserção = da execução mais recente para a mais antiga.
        val evidence = LinkedHashMap<String, AiExerciseLoadEvidenceContext>()
        for (summary in scanned) {
            for (executed in summary.sortedExercises) {
                val rowId = executed.exerciseRowId() ?: continue
                val exercise = exercisesById[rowId] ?: continue
                val exerciseId = exerciseIdOf(exercise)
                if (exerciseId !in candidateExerciseIds) continue

                val completedSets = executed.sets.filter { it.completed }
                if (completedSets.isEmpty()) continue

                val existing = evidence[exerciseId]
                if (existing != null) {
                    evidence[exerciseId] = existing.copy(
                        sessionsWithHistory = existing.sessionsWithHistory + 1
                    )
                    continue
                }

                val heaviest = completedSets
                    .filter { it.weight > 0f && !it.isDurationMode }
                    .maxByOrNull { it.weight }
                evidence[exerciseId] = AiExerciseLoadEvidenceContext(
                    exerciseId = exerciseId,
                    lastWeightKg = heaviest?.weight,
                    lastReps = heaviest?.repetitions,
                    sessionsWithHistory = 1
                )
            }
        }
        return evidence.values.take(AiModelConfig.MAX_LOAD_EVIDENCE_EXERCISES)
    }

    /**
     * Quais exercícios a análise pode olhar.
     *
     * O treino em foco manda. Sem treino em foco, o recorte é o último treino realmente
     * concluído — analisar "o que você acabou de treinar" é a leitura honesta desse caso, e
     * evita transformar o prompt no histórico inteiro do usuário.
     */
    private fun resolveRelevantExercises(
        plannedExercises: List<WorkoutTemplateExerciseEntity>,
        scannedSessions: List<SessionCalendarSummary>
    ): List<Long> {
        val planned = plannedExercises.map { it.exerciseId }.distinct()
        if (planned.isNotEmpty()) return planned.take(AiModelConfig.MAX_EXERCISES_IN_CONTEXT)

        val lastSession = scannedSessions.firstOrNull() ?: return emptyList()
        return lastSession.sortedExercises
            .mapNotNull { it.exerciseRowId() }
            .distinct()
            .take(AiModelConfig.MAX_EXERCISES_IN_CONTEXT)
    }

    private fun projectCurrentWorkout(
        exercisesById: Map<Long, ExerciseEntity>,
        templateName: String?,
        plannedExercises: List<WorkoutTemplateExerciseEntity>
    ): AiWorkoutContext? {
        if (templateName == null && plannedExercises.isEmpty()) return null

        val exercises = plannedExercises.mapNotNull { planned ->
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

    /**
     * A série histórica de cada exercício relevante, da execução mais recente para a mais antiga.
     *
     * Só entra série concluída: `completed = 1` dentro de sessão `COMPLETED`. O que ficou
     * planejado, em andamento ou cancelado não é desempenho realizado.
     */
    private fun projectHistory(
        exercisesById: Map<Long, ExerciseEntity>,
        relevantRowIds: List<Long>,
        scannedSessions: List<SessionCalendarSummary>,
        contributingSessionIds: MutableSet<Long>
    ): List<AiExerciseHistoryContext> = relevantRowIds.mapNotNull { rowId ->
        val exercise = exercisesById[rowId] ?: return@mapNotNull null

        val executions = mutableListOf<AiExerciseExecutionContext>()
        for (summary in scannedSessions) {
            if (executions.size >= AiModelConfig.HISTORY_PER_EXERCISE_LIMIT) break
            val execution = summary.sortedExercises
                .firstOrNull { it.exerciseRowId() == rowId }
                ?.let { projectExecution(summary, it) }
            if (execution != null) {
                executions += execution
                contributingSessionIds += summary.session.id
            }
        }

        AiExerciseHistoryContext(
            exerciseId = exerciseIdOf(exercise),
            name = exercise.name,
            sessionsAnalyzed = executions.size,
            executions = executions
        )
    }

    private fun projectExecution(
        summary: SessionCalendarSummary,
        executed: ExerciseSessionWithSets
    ): AiExerciseExecutionContext? {
        val completedSets = executed.sets.filter { it.completed }
        if (completedSets.isEmpty()) return null

        val weightedSets = completedSets.filter { it.weight > 0f && !it.isDurationMode }
        val repSets = completedSets.filter { !it.isDurationMode }

        return AiExerciseExecutionContext(
            finishedAtEpochMs = summary.session.finishedAt,
            completedSets = completedSets.size,
            maxWeightKg = weightedSets.maxOfOrNull { it.weight },
            totalReps = repSets.takeIf { it.isNotEmpty() }?.sumOf { it.repetitions }
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

    /**
     * Identidade do exercício executado.
     *
     * O substituído durante a execução conta como o exercício que foi realmente feito; sem id
     * não há identidade canônica para preservar, e enviar só o nome convidaria o modelo a tratar
     * nome como identificador.
     */
    private fun ExerciseSessionWithSets.exerciseRowId(): Long? =
        exerciseSession.actualExerciseId ?: exerciseSession.plannedExerciseId
}
