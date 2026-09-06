package com.example.domain.ai

import com.example.domain.ai.model.AiCoachAdvice
import com.example.domain.ai.model.AiCoachContextOrigin
import com.example.domain.ai.model.AiCoachExplanation
import com.example.domain.ai.model.AiCoachExplanationContext
import com.example.domain.ai.model.AiCoachExplanationSource
import com.example.domain.ai.model.AiCoachExplanationTarget
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiExerciseHistoryContext
import com.example.domain.ai.model.AiExplainableTarget
import com.example.domain.ai.model.AiExplanationFact
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiProgressSnapshot
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.WorkoutAdaptationChange
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue
import com.example.domain.ai.model.WorkoutGenerationPreferences

/**
 * O plano de uma explicação, antes de qualquer chamada ao provider.
 *
 * [local] existe sempre: é o piso da funcionalidade (offline, provider indisponível, cota
 * estourada) e é de onde sai a lista de evidências mesmo quando o modelo escreve o texto.
 * [useModel] é a decisão de custo — ela é tomada aqui, uma vez, e não na UI.
 */
data class AiCoachExplanationPlan(
    val target: AiCoachExplanationTarget,
    val context: AiCoachExplanationContext,
    val local: AiCoachExplanation,
    val useModel: Boolean
)

/**
 * Monta o **menor contexto suficiente** de cada explicação e decide se o modelo agrega valor.
 *
 * Projeção pura: nenhuma IO, nenhuma regra de domínio recalculada. Tudo o que entra aqui já foi
 * decidido pela autoridade correspondente — a análise da T14.1, a proposta da T14.2, a adaptação
 * da T14.3 ou os repositórios de progressão. Este arquivo apenas recorta e reescreve para
 * leitura.
 *
 * A política de custo em uma frase:
 *
 * ```
 * já existe reason/evidence que responde a pergunta?  -> explicação local, 0 chamadas
 * é preciso conectar dados que o app tem soltos?      -> Gemini sobre contexto mínimo
 * ```
 */
object AiCoachExplanationContextBuilder {

    /** Quantas execuções do exercício em foco entram em uma explicação de adaptação. */
    const val EXPLANATION_HISTORY_LIMIT: Int = 4

    // -------------------------------------------------------------------------------------
    // EXPLAIN_RECOMMENDATION — sempre local
    // -------------------------------------------------------------------------------------

    /**
     * "Por que você recomendou isso?"
     *
     * A resposta já existe: a recomendação carrega `reason` e `evidence`, e a análise carrega
     * `dataQuality` e `sessionsAnalyzed`. Chamar o modelo aqui seria pagar para reescrever o que
     * ele já escreveu — por isso [AiCoachExplanationPlan.useModel] é sempre `false`.
     */
    fun forAnalysisTarget(
        requestId: String,
        advice: AiCoachAdvice,
        targetId: String,
        target: AiExplainableTarget,
        exerciseName: String?
    ): AiCoachExplanationPlan {
        val facts = mutableListOf<AiExplanationFact>()
        val title: String
        val explanation: String
        val exerciseId: String?

        when (target) {
            is AiExplainableTarget.Recommendation -> {
                val recommendation = target.recommendation
                exerciseId = recommendation.exerciseId
                title = "Por que essa recomendação?"
                explanation = recommendation.reason
                exerciseName?.let { facts += AiExplanationFact("Exercício", it) }
                recommendation.evidence?.let { facts += AiExplanationFact("Base no seu histórico", it) }
                facts += AiExplanationFact(
                    "Confiança declarada",
                    "${(recommendation.confidence * 100).toInt().coerceIn(0, 100)}%"
                )
            }

            is AiExplainableTarget.Observation -> {
                val observation = target.observation
                exerciseId = observation.exerciseId
                title = if (target.isAttentionPoint) {
                    "Por que isso é um ponto de atenção?"
                } else {
                    "Por que isso é um ponto positivo?"
                }
                explanation = observation.description
                exerciseName?.let { facts += AiExplanationFact("Exercício", it) }
                facts += AiExplanationFact("Observação", observation.title)
            }
        }

        facts += AiExplanationFact("Sessões concluídas analisadas", advice.sessionsAnalyzed.toString())
        facts += AiExplanationFact("Base da análise", advice.dataQuality.level.readable())

        val limitations = dataQualityLimitations(advice.dataQuality.level, advice.sessionsAnalyzed)

        return AiCoachExplanationPlan(
            target = AiCoachExplanationTarget(
                origin = AiCoachContextOrigin.WORKOUT_ANALYSIS,
                contextId = targetId,
                sourceRevision = advice.requestId,
                requestType = AiCoachRequestType.EXPLAIN_RECOMMENDATION
            ),
            context = AiCoachExplanationContext(
                origin = AiCoachContextOrigin.WORKOUT_ANALYSIS.name,
                contextId = targetId,
                subject = title,
                facts = facts,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                dataQuality = advice.dataQuality.level,
                knownLimitations = limitations
            ),
            local = AiCoachExplanation(
                requestId = requestId,
                origin = AiCoachContextOrigin.WORKOUT_ANALYSIS,
                contextId = targetId,
                title = title,
                explanation = explanation,
                evidenceItems = facts.map { it.line },
                limitations = limitations,
                source = AiCoachExplanationSource.LOCAL
            ),
            // A recomendação já veio com razão e evidência: o app não paga uma chamada para
            // repetir o que já está escrito.
            useModel = false
        )
    }

    // -------------------------------------------------------------------------------------
    // EXPLAIN_WORKOUT — Gemini, com fallback local
    // -------------------------------------------------------------------------------------

    /**
     * "Por que esse treino foi montado assim?"
     *
     * O app tem as peças soltas — o pedido, a lista escolhida, a ordem, as exclusões respeitadas
     * e a justificativa de cada exercício — mas não tem a leitura que liga ordem, foco e volume.
     * Essa síntese é o que o modelo acrescenta; sem ele, o fallback mostra as peças.
     */
    fun forGeneratedWorkout(
        requestId: String,
        draft: GeneratedWorkoutDraft,
        preferences: WorkoutGenerationPreferences
    ): AiCoachExplanationPlan {
        val facts = mutableListOf(
            AiExplanationFact("Objetivo pedido", preferences.goal.label),
            AiExplanationFact("Duração pedida", "${preferences.durationMinutes} min"),
            AiExplanationFact(
                "Foco pedido",
                preferences.focusMuscleGroups.joinToString(", ") { it.name }
                    .ifBlank { "não informado" }
            ),
            AiExplanationFact(
                "Equipamentos disponíveis",
                if (preferences.acceptsAnyEquipment) {
                    "sem restrição"
                } else {
                    preferences.availableEquipment.joinToString(", ") { it.label }
                }
            ),
            AiExplanationFact("Exercícios propostos", draft.exercises.size.toString())
        )
        if (preferences.excludedExerciseIds.isNotEmpty()) {
            facts += AiExplanationFact(
                "Exercícios excluídos por você",
                preferences.excludedExerciseIds.size.toString()
            )
        }
        draft.exercises.forEach { exercise ->
            facts += AiExplanationFact(
                "${exercise.sortOrder + 1}. ${exercise.name}",
                buildString {
                    append("${exercise.sets}x${exercise.minReps}-${exercise.maxReps}")
                    append(", descanso ${exercise.restSeconds}s")
                    exercise.weightKg?.let { append(", carga ${formatWeight(it)}") }
                    if (exercise.reason.isNotBlank()) append(" — ${exercise.reason}")
                }
            )
        }

        // O treino ainda não foi executado: não existe desempenho a citar, e dizer isso é
        // obrigação, não detalhe.
        val limitations = listOf(
            "Este treino ainda não foi executado, então esta explicação fala do plano, não de " +
                "resultado.",
            "Só entraram exercícios do seu catálogo que passaram pelo foco e pelos equipamentos " +
                "escolhidos."
        )

        val localExplanation = buildString {
            append(
                draft.explanation.ifBlank {
                    "O treino foi montado com ${draft.exercises.size} exercícios do seu catálogo, " +
                        "dentro do foco e dos equipamentos que você escolheu."
                }
            )
        }

        return AiCoachExplanationPlan(
            target = AiCoachExplanationTarget(
                origin = AiCoachContextOrigin.GENERATED_WORKOUT,
                contextId = draft.requestId,
                sourceRevision = draft.revision,
                requestType = AiCoachRequestType.EXPLAIN_WORKOUT
            ),
            context = AiCoachExplanationContext(
                origin = AiCoachContextOrigin.GENERATED_WORKOUT.name,
                contextId = draft.requestId,
                subject = "Por que o treino \"${draft.name}\" foi montado assim",
                facts = facts,
                reason = draft.explanation.takeIf { it.isNotBlank() },
                workoutExercises = draft.exercises.map { exercise ->
                    AiPlannedExerciseContext(
                        exerciseId = exercise.exerciseId,
                        name = exercise.name,
                        targetSets = exercise.sets,
                        minReps = exercise.minReps,
                        maxReps = exercise.maxReps,
                        plannedWeightKg = exercise.weightKg,
                        restSeconds = exercise.restSeconds
                    )
                },
                knownLimitations = limitations
            ),
            local = AiCoachExplanation(
                requestId = requestId,
                origin = AiCoachContextOrigin.GENERATED_WORKOUT,
                contextId = draft.requestId,
                title = "Por que esse treino foi montado assim?",
                explanation = localExplanation,
                evidenceItems = facts.map { it.line },
                limitations = limitations,
                source = AiCoachExplanationSource.LOCAL
            ),
            useModel = true
        )
    }

    // -------------------------------------------------------------------------------------
    // EXPLAIN_ADAPTATION — Gemini, com fallback local
    // -------------------------------------------------------------------------------------

    /**
     * "Entender sugestão" sobre uma mudança proposta.
     *
     * A mudança já traz `reason` e `evidence` em uma linha; o que o app acrescenta aqui é o
     * **histórico real daquele exercício**, relido da autoridade no instante da pergunta. Ligar N
     * execuções concretas ao antes/depois é a síntese que justifica a chamada.
     */
    fun forAdaptationChange(
        requestId: String,
        draft: WorkoutAdaptationDraft,
        change: WorkoutAdaptationChange,
        history: AiExerciseHistoryContext?
    ): AiCoachExplanationPlan {
        val trimmedHistory = history?.let {
            it.copy(executions = it.executions.take(EXPLANATION_HISTORY_LIMIT))
        }

        val facts = mutableListOf(
            AiExplanationFact("Treino", draft.templateName),
            AiExplanationFact("Exercício", change.exerciseName),
            AiExplanationFact("Tipo de ajuste", change.type.label),
            AiExplanationFact("Valor atual no treino", change.currentValue.readable()),
            AiExplanationFact("Valor sugerido", change.suggestedValue.readable()),
            AiExplanationFact("Base da sugestão", change.evidence),
            AiExplanationFact(
                "Confiança declarada",
                "${(change.confidence * 100).toInt().coerceIn(0, 100)}%"
            )
        )

        val executions = trimmedHistory?.executions.orEmpty()
        facts += AiExplanationFact("Execuções concluídas consideradas", executions.size.toString())
        executions.forEachIndexed { index, execution ->
            facts += AiExplanationFact(
                "Execução ${index + 1}" + if (index == 0) " (mais recente)" else "",
                buildString {
                    append("${execution.completedSets} série(s) concluída(s)")
                    execution.maxWeightKg?.let { append(", maior carga ${formatWeight(it)}") }
                    execution.totalReps?.let { append(", $it repetições no total") }
                }
            )
        }

        val limitations = buildList {
            addAll(dataQualityLimitations(draft.dataQuality.level, executions.size))
            if (executions.isEmpty()) {
                add(
                    "Não há execução concluída registrada deste exercício: a sugestão se apoia " +
                        "apenas na configuração atual do treino."
                )
            }
            if (change.type == WorkoutAdaptationType.ADJUST_LOAD &&
                executions.all { it.maxWeightKg == null }
            ) {
                add("Não há carga registrada nas execuções consideradas.")
            }
            add("Nada foi alterado no seu treino: esta é uma sugestão que você ainda pode recusar.")
        }

        val localExplanation = buildString {
            append(change.reason)
            if (executions.isNotEmpty()) {
                append(" Foram consideradas ")
                append(
                    if (executions.size == 1) {
                        "1 execução concluída"
                    } else {
                        "as últimas ${executions.size} execuções concluídas"
                    }
                )
                append(" deste exercício.")
            }
        }

        return AiCoachExplanationPlan(
            target = AiCoachExplanationTarget(
                origin = AiCoachContextOrigin.WORKOUT_ADAPTATION,
                contextId = change.id,
                sourceRevision = draft.sourceRevision,
                requestType = AiCoachRequestType.EXPLAIN_ADAPTATION
            ),
            context = AiCoachExplanationContext(
                origin = AiCoachContextOrigin.WORKOUT_ADAPTATION.name,
                contextId = change.id,
                subject = "Por que ${change.type.label.lowercase()} em ${change.exerciseName}",
                facts = facts,
                exerciseId = change.exerciseId,
                exerciseName = change.exerciseName,
                currentValue = change.currentValue.readable(),
                suggestedValue = change.suggestedValue.readable(),
                reason = change.reason,
                evidence = change.evidence,
                exerciseHistory = trimmedHistory,
                dataQuality = draft.dataQuality.level,
                knownLimitations = limitations
            ),
            local = AiCoachExplanation(
                requestId = requestId,
                origin = AiCoachContextOrigin.WORKOUT_ADAPTATION,
                contextId = change.id,
                title = "Por que essa sugestão?",
                explanation = localExplanation,
                evidenceItems = facts.map { it.line },
                limitations = limitations,
                source = AiCoachExplanationSource.LOCAL
            ),
            useModel = true
        )
    }

    // -------------------------------------------------------------------------------------
    // EXPLAIN_PROGRESS — Gemini, com fallback local
    // -------------------------------------------------------------------------------------

    /**
     * "Por que meu Coach diz que estou evoluindo?"
     *
     * Todos os números vêm prontos das autoridades canônicas e **não** são recalculados aqui nem
     * pelo modelo: nível e XP de `XpTransactionRepository`, sequência e meta de
     * `ConsistencyRepository`, conquistas de `AchievementRepository`, treinos e recordes de
     * `WorkoutRepository`. O que falta é a leitura que liga um número ao outro.
     */
    fun forProgress(
        requestId: String,
        snapshot: AiProgressSnapshot
    ): AiCoachExplanationPlan {
        val facts = listOf(
            AiExplanationFact("Nível atual", snapshot.level.toString()),
            AiExplanationFact("XP total", snapshot.totalXp.toString()),
            AiExplanationFact(
                "XP no nível atual",
                "${snapshot.currentLevelXp} de ${snapshot.xpForNextLevel}"
            ),
            AiExplanationFact("Sequência semanal", "${snapshot.streakWeeks} semana(s)"),
            AiExplanationFact(
                "Semana atual",
                "${snapshot.weeklyCompleted} de ${snapshot.weeklyGoal} treinos da meta"
            ),
            AiExplanationFact("Treinos concluídos no total", snapshot.completedWorkouts.toString()),
            AiExplanationFact(
                "Conquistas",
                "${snapshot.unlockedAchievements} de ${snapshot.totalAchievements} desbloqueadas"
            ),
            AiExplanationFact("Recordes pessoais", snapshot.personalRecordsCount.toString())
        )

        val limitations = buildList {
            add(
                "Estes números são calculados pelo Spark; o Coach apenas os lê e explica, sem " +
                    "recalcular nível, XP, sequência ou conquistas."
            )
            if (snapshot.completedWorkouts == 0) {
                add("Ainda não há treino concluído: não há evolução de desempenho para descrever.")
            }
            if (snapshot.weeklyGoal <= 0) {
                add("Não há meta semanal configurada, então a semana atual não tem referência.")
            }
        }

        val localExplanation = buildString {
            append("Você está no nível ${snapshot.level}, com ${snapshot.totalXp} XP acumulado ")
            append("e ${snapshot.completedWorkouts} treino(s) concluído(s). ")
            append(
                if (snapshot.streakWeeks > 0) {
                    "Sua sequência está em ${snapshot.streakWeeks} semana(s) porque a meta " +
                        "semanal foi cumprida nesse período. "
                } else {
                    "Sua sequência semanal está zerada. "
                }
            )
            append(
                "Nesta semana você concluiu ${snapshot.weeklyCompleted} de " +
                    "${snapshot.weeklyGoal} treinos da meta."
            )
        }

        return AiCoachExplanationPlan(
            target = AiCoachExplanationTarget(
                origin = AiCoachContextOrigin.PROFILE_PROGRESS,
                contextId = PROGRESS_CONTEXT_ID,
                sourceRevision = snapshot.revision,
                requestType = AiCoachRequestType.EXPLAIN_PROGRESS
            ),
            context = AiCoachExplanationContext(
                origin = AiCoachContextOrigin.PROFILE_PROGRESS.name,
                contextId = PROGRESS_CONTEXT_ID,
                subject = "O que os números de progressão do Perfil querem dizer",
                facts = facts,
                knownLimitations = limitations
            ),
            local = AiCoachExplanation(
                requestId = requestId,
                origin = AiCoachContextOrigin.PROFILE_PROGRESS,
                contextId = PROGRESS_CONTEXT_ID,
                title = "Como sua evolução foi medida",
                explanation = localExplanation,
                evidenceItems = facts.map { it.line },
                limitations = limitations,
                source = AiCoachExplanationSource.LOCAL
            ),
            useModel = true
        )
    }

    /** O Perfil tem um único alvo explicável; a revisão é que distingue um estado do outro. */
    const val PROGRESS_CONTEXT_ID: String = "profile-progress"

    // -------------------------------------------------------------------------------------

    /**
     * As limitações que o próprio app reconhece a partir da evidência que reuniu.
     *
     * Elas vão para a UI e para o prompt: o modelo não pode contradizer o que o app já admitiu
     * não saber.
     */
    private fun dataQualityLimitations(
        level: AiDataQualityLevel,
        sessionsAnalyzed: Int
    ): List<String> = buildList {
        when (level) {
            AiDataQualityLevel.INSUFFICIENT -> add(
                "Não há sessão concluída suficiente para afirmar tendência: esta explicação " +
                    "descreve o plano, não a evolução."
            )

            AiDataQualityLevel.LIMITED -> add(
                if (sessionsAnalyzed == 1) {
                    "Esta explicação se baseia em apenas 1 sessão registrada."
                } else {
                    "Esta explicação se baseia em apenas $sessionsAnalyzed sessões registradas."
                }
            )

            AiDataQualityLevel.GOOD -> Unit
        }
    }

    private fun AiDataQualityLevel.readable(): String = when (this) {
        AiDataQualityLevel.INSUFFICIENT -> "dados insuficientes"
        AiDataQualityLevel.LIMITED -> "dados limitados"
        AiDataQualityLevel.GOOD -> "dados suficientes"
    }

    /** A mesma leitura que a tela de adaptação mostra, para o texto não divergir da UI. */
    private fun WorkoutAdaptationValue.readable(): String = when (this) {
        is WorkoutAdaptationValue.Load -> weightKg?.let { formatWeight(it) } ?: "sem carga planejada"
        is WorkoutAdaptationValue.Reps -> "$minReps-$maxReps reps"
        is WorkoutAdaptationValue.Sets -> "$sets séries"
        is WorkoutAdaptationValue.Rest -> "${restSeconds}s de descanso"
        is WorkoutAdaptationValue.Exercise -> name
    }

    private fun formatWeight(weightKg: Float): String =
        if (weightKg % 1f == 0f) "${weightKg.toInt()} kg" else "$weightKg kg"
}
