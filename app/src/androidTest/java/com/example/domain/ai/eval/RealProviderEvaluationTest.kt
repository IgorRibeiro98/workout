package com.example.domain.ai.eval

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.ai.FirebaseAiCoachGateway
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachExplanationContext
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachExplanationRequest
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiEvidenceContext
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiExerciseExecutionContext
import com.example.domain.ai.model.AiExerciseHistoryContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiWorkoutAdaptationContext
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.AiWorkoutAdaptationRequest
import com.example.domain.ai.model.AiWorkoutContext
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.AiWorkoutGenerationRequest
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutGoal
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Avaliação com o provider **real** — opt-in, nunca automática.
 *
 * Ela não roda em `testDebugUnitTest`, não roda no build e não roda em CI: é um teste
 * instrumentado que só executa quando alguém pedir explicitamente, com aparelho, Firebase
 * configurado e App Check válido. Cada execução gasta cota de verdade, por isso é **uma** chamada
 * por tipo de request.
 *
 * ```bash
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.example.domain.ai.eval.RealProviderEvaluationTest \
 *   -Pandroid.testInstrumentationRunnerArguments.realProviderEval=true
 * ```
 *
 * Sem o argumento `realProviderEval=true` os testes são ignorados (`assumeTrue`), e nenhuma
 * chamada acontece.
 *
 * O que se verifica aqui é o mesmo que a suíte determinística verifica — propriedade, não texto:
 * a resposta atravessou structured output e validador, e a metadata da chamada (tipo, modelo,
 * versão de prompt, versão de schema) é a configurada.
 */
class RealProviderEvaluationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val gateway by lazy { FirebaseAiCoachGateway(context) }

    private fun requireOptIn() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(OPT_IN_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(
            "avaliação com provider real desativada (passe -P...$OPT_IN_ARGUMENT=true)",
            enabled
        )
    }

    private fun report(type: AiCoachRequestType, outcome: String) {
        Log.i(
            TAG,
            "type=$type model=${AiModelConfig.MODEL_NAME} " +
                "promptVersion=${AiModelConfig.PROMPT_VERSION} " +
                "schemaVersion=${AiModelConfig.SCHEMA_VERSION} outcome=$outcome"
        )
    }

    @Test
    fun analyzeWorkoutUmaChamada() = runBlocking {
        requireOptIn()

        val request = AiCoachRequest(
            requestId = "real-eval-analyze",
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            type = AiCoachRequestType.ANALYZE_WORKOUT,
            context = analysisContext()
        )

        val outcome = when (val result = gateway.request(request)) {
            is AiCoachGatewayResult.Success -> "SUCCESS"
            is AiCoachGatewayResult.Error -> result.kind.name
        }
        report(AiCoachRequestType.ANALYZE_WORKOUT, outcome)
    }

    @Test
    fun generateWorkoutUmaChamada() = runBlocking {
        requireOptIn()

        val request = AiWorkoutGenerationRequest(
            requestId = "real-eval-generate",
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            context = generationContext()
        )

        val outcome = when (val result = gateway.generateWorkout(request)) {
            is AiWorkoutGenerationGatewayResult.Success -> "SUCCESS"
            is AiWorkoutGenerationGatewayResult.Error -> result.kind.name
        }
        report(AiCoachRequestType.GENERATE_WORKOUT, outcome)
    }

    @Test
    fun adaptWorkoutUmaChamada() = runBlocking {
        requireOptIn()

        val request = AiWorkoutAdaptationRequest(
            requestId = "real-eval-adapt",
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            context = adaptationContext()
        )

        val outcome = when (val result = gateway.adaptWorkout(request)) {
            is AiWorkoutAdaptationGatewayResult.Success -> "SUCCESS"
            is AiWorkoutAdaptationGatewayResult.Error -> result.kind.name
        }
        report(AiCoachRequestType.ADAPT_WORKOUT, outcome)
    }

    @Test
    fun explainUmaChamada() = runBlocking {
        requireOptIn()

        val request = AiCoachExplanationRequest(
            requestId = "real-eval-explain",
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            type = AiCoachRequestType.EXPLAIN_ADAPTATION,
            context = explanationContext()
        )

        val outcome = when (val result = gateway.explain(request)) {
            is AiCoachExplanationGatewayResult.Success -> "SUCCESS"
            is AiCoachExplanationGatewayResult.Error -> result.kind.name
        }
        report(AiCoachRequestType.EXPLAIN_ADAPTATION, outcome)
    }

    // ------------------------------------------------------------------------- fixtures

    private fun analysisContext() = AiCoachContext(
        athlete = AiAthleteContext(weeklyGoal = 4, completedSessionsInWindow = 3),
        currentWorkout = AiWorkoutContext(
            templateName = "Peito + Tríceps",
            exercises = listOf(
                AiPlannedExerciseContext(
                    exerciseId = "supino-reto-barra",
                    name = "Supino reto com barra",
                    targetSets = 4,
                    minReps = 8,
                    maxReps = 12,
                    plannedWeightKg = 55f,
                    restSeconds = 90
                )
            )
        ),
        exerciseHistory = listOf(
            AiExerciseHistoryContext(
                exerciseId = "supino-reto-barra",
                name = "Supino reto com barra",
                sessionsAnalyzed = 3,
                executions = listOf(55f, 52.5f, 50f).map {
                    AiExerciseExecutionContext(
                        finishedAtEpochMs = null,
                        completedSets = 4,
                        maxWeightKg = it,
                        totalReps = 40
                    )
                }
            )
        ),
        evidence = AiEvidenceContext(
            sessionsAnalyzed = 3,
            exercisesWithHistory = 1,
            maxDataQuality = AiDataQualityLevel.GOOD
        )
    )

    private fun generationContext() = AiWorkoutGenerationContext(
        goal = WorkoutGoal.HYPERTROPHY.name,
        goalGuidance = WorkoutGoal.HYPERTROPHY.guidance,
        durationMinutes = 60,
        focusMuscleGroups = listOf("Peitoral", "Tríceps"),
        candidateExercises = listOf(
            AiCandidateExerciseContext("supino-reto-barra", "Supino reto com barra", "Peitoral", "Barra"),
            AiCandidateExerciseContext("crucifixo-halteres", "Crucifixo com halteres", "Peitoral", "Halter"),
            AiCandidateExerciseContext("triceps-corda", "Tríceps na corda", "Tríceps", "Cabo")
        )
    )

    private fun adaptationContext() = AiWorkoutAdaptationContext(
        templateName = "Peito + Tríceps",
        template = AiWorkoutContext(
            templateName = "Peito + Tríceps",
            exercises = listOf(
                AiPlannedExerciseContext(
                    exerciseId = "supino-reto-barra",
                    name = "Supino reto com barra",
                    targetSets = 4,
                    minReps = 8,
                    maxReps = 12,
                    plannedWeightKg = 60f,
                    restSeconds = 90
                )
            )
        ),
        exerciseHistory = listOf(
            AiExerciseHistoryContext(
                exerciseId = "supino-reto-barra",
                name = "Supino reto com barra",
                sessionsAnalyzed = 3,
                executions = List(3) {
                    AiExerciseExecutionContext(
                        finishedAtEpochMs = null,
                        completedSets = 4,
                        maxWeightKg = 60f,
                        totalReps = 44
                    )
                }
            )
        ),
        allowedChangeTypes = WorkoutAdaptationType.entries.map { it.name },
        evidence = AiEvidenceContext(
            sessionsAnalyzed = 3,
            exercisesWithHistory = 1,
            maxDataQuality = AiDataQualityLevel.GOOD
        )
    )

    private fun explanationContext() = AiCoachExplanationContext(
        origin = "ADAPTATION",
        contextId = "ADJUST_LOAD:supino-reto-barra",
        subject = "Subir a carga do supino reto de 60 kg para 62,5 kg",
        exerciseId = "supino-reto-barra",
        exerciseName = "Supino reto com barra",
        currentValue = "60 kg",
        suggestedValue = "62,5 kg",
        reason = "As últimas execuções concluíram todas as séries com a carga planejada.",
        evidence = "60 kg em 3 sessões concluídas",
        dataQuality = AiDataQualityLevel.GOOD
    )

    private companion object {
        const val TAG = "AiCoachRealEval"
        const val OPT_IN_ARGUMENT = "realProviderEval"
    }
}
