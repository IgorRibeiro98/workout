package com.example.domain.ai.eval

import com.example.domain.ai.AiCoachTelemetry
import com.example.domain.ai.AiCoachTestData
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.FakeAiCoachGateway
import com.example.domain.ai.WorkoutGenerationTestData
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiCoachResult
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiEvidenceContext
import com.example.domain.ai.model.AiExerciseExecutionContext
import com.example.domain.ai.model.AiExerciseHistoryContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiWorkoutContext
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.usecase.AnalyzeWorkoutUseCase
import com.example.domain.ai.usecase.GenerateWorkoutUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O que o Coach registra — e o que ele nunca registra.
 *
 * A observabilidade precisa responder "qual chamada falhou, quanto demorou, com qual
 * modelo/prompt/schema e que erro deu". Ela não pode responder "o que o usuário treinou".
 */
class AiCoachObservabilityTest {

    /** Um registro de telemetria com tudo que a implementação recebeu, concatenado. */
    private class RecordingTelemetry : AiCoachTelemetry {
        val records = mutableListOf<String>()
        val requestIds = mutableListOf<String>()
        val durations = mutableListOf<Long>()

        override fun onRequestFinished(
            requestId: String,
            type: AiCoachRequestType,
            model: String,
            promptVersion: Int,
            schemaVersion: Int,
            durationMs: Long,
            result: String
        ) {
            requestIds += requestId
            durations += durationMs
            records += listOf(
                requestId, type.name, model, promptVersion.toString(),
                schemaVersion.toString(), durationMs.toString(), result
            ).joinToString("|")
        }
    }

    private val marker = "MARCADOR-DE-CONTEXTO-SENSIVEL"

    private fun contextWithMarker() = AiCoachContext(
        athlete = AiAthleteContext(weeklyGoal = 4, completedSessionsInWindow = 3),
        currentWorkout = AiWorkoutContext(
            templateName = marker,
            exercises = listOf(
                AiPlannedExerciseContext(
                    exerciseId = "supino-reto-barra",
                    name = marker,
                    plannedWeightKg = 137.5f
                )
            )
        ),
        exerciseHistory = listOf(
            AiExerciseHistoryContext(
                exerciseId = "supino-reto-barra",
                name = marker,
                sessionsAnalyzed = 1,
                executions = listOf(
                    AiExerciseExecutionContext(
                        finishedAtEpochMs = 1_700_000_000_000,
                        completedSets = 4,
                        maxWeightKg = 137.5f,
                        totalReps = 40
                    )
                )
            )
        ),
        evidence = AiEvidenceContext(
            sessionsAnalyzed = 3,
            exercisesWithHistory = 1,
            maxDataQuality = AiDataQualityLevel.GOOD
        )
    )

    @Test
    fun `a telemetria nao carrega contexto, prompt nem resposta`() = runTest {
        val telemetry = RecordingTelemetry()
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachTestData.response(summary = "Texto do modelo que também não pode vazar.")
            )
        }

        AnalyzeWorkoutUseCase(
            contextBuilder = EvaluationAnalysisContextBuilder(contextWithMarker()),
            gateway = gateway,
            telemetry = telemetry
        )()

        val record = telemetry.records.single()
        assertTrue("a chamada precisa ter sido bem-sucedida", record.endsWith("SUCCESS"))
        assertFalse("o contexto vazou para a telemetria", record.contains(marker))
        assertFalse("carga do usuário vazou para a telemetria", record.contains("137.5"))
        assertFalse("texto do modelo vazou para a telemetria", record.contains("Texto do modelo"))
        assertFalse("exerciseId vazou para a telemetria", record.contains("supino-reto-barra"))
    }

    @Test
    fun `a telemetria so aceita a metadata permitida`() {
        val parameters = AiCoachTelemetry::class.java.methods
            .single { it.name == "onRequestFinished" }
            .parameterTypes
            .map { it.simpleName }

        // requestId, type, model, promptVersion, schemaVersion, durationMs, result
        assertEquals(
            listOf("String", "AiCoachRequestType", "String", "int", "int", "long", "String"),
            parameters
        )
    }

    @Test
    fun `todo resultado sai identificado pelo requestId e pelo tipo de erro`() = runTest {
        val telemetry = RecordingTelemetry()
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Error(AiCoachErrorKind.RATE_LIMITED, "cota")
        }

        val result = AnalyzeWorkoutUseCase(
            contextBuilder = EvaluationAnalysisContextBuilder(contextWithMarker()),
            gateway = gateway,
            telemetry = telemetry
        )()

        assertTrue(result is AiCoachResult.Failure)
        val record = telemetry.records.single()
        assertTrue("o resultado precisa dizer o tipo de erro", record.endsWith("RATE_LIMITED"))
        assertTrue("requestId ausente", telemetry.requestIds.single().isNotBlank())
        assertTrue("duração precisa ser registrada", telemetry.durations.single() >= 0)
    }

    @Test
    fun `o requestId da telemetria e o mesmo que foi ao provider`() = runTest {
        val telemetry = RecordingTelemetry()
        val gateway = FakeAiCoachGateway(
            generationResponder = {
                AiWorkoutGenerationGatewayResult.Success(WorkoutGenerationTestData.response())
            }
        )

        GenerateWorkoutUseCase(
            contextBuilder = EvaluationGenerationContextBuilder(WorkoutGenerationTestData.context()),
            gateway = gateway,
            telemetry = telemetry
        )(WorkoutGenerationTestData.preferences())

        assertEquals(
            gateway.generationRequests.single().requestId,
            telemetry.requestIds.single()
        )
        // Sem metadata do servidor (o dublê não a informa), a telemetria registra "desconhecido"
        // em vez de inventar um nome de modelo que o app não escolhe mais.
        assertTrue(
            telemetry.records.single()
                .contains(com.example.domain.ai.model.AiCoachCallMetadata.UNKNOWN_MODEL)
        )
    }
}
