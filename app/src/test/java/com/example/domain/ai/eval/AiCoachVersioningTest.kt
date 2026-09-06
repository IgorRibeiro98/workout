package com.example.domain.ai.eval

import com.example.domain.ai.AiCoachPrompt
import com.example.domain.ai.AiCoachTelemetry
import com.example.domain.ai.AiCoachTestData
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.FakeAiCoachGateway
import com.example.domain.ai.WorkoutGenerationTestData
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiWorkoutGenerationRequest
import com.example.domain.ai.usecase.AnalyzeWorkoutUseCase
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modelo, prompt e schema precisam ser rastreáveis — e ter uma autoridade só.
 *
 * Sem estes testes, trocar o modelo, editar um prompt ou avançar a versão de schema não faz
 * nenhum teste falhar: é exatamente a classe de regressão silenciosa que a T14.5 fecha.
 */
class AiCoachVersioningTest {

    private val mainSources: List<File> by lazy {
        val root = File("src/main/java").takeIf { it.isDirectory }
            ?: File("app/src/main/java")
        assertTrue("não encontrei o source set principal em ${root.absolutePath}", root.isDirectory)
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `o nome do modelo existe em um unico ponto de configuracao`() {
        val offenders = mainSources.filter { file ->
            file.name != "AiModelConfig.kt" && file.readText().contains("gemini-")
        }

        assertTrue(
            "nome de modelo fora de AiModelConfig: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `nenhum ViewModel, tela ou caso de uso conhece o modelo concreto`() {
        val domainAndUi = mainSources.filter { file ->
            val path = file.path.replace('\\', '/')
            path.contains("/presentation/") || path.contains("/domain/ai/usecase/")
        }

        val offenders = domainAndUi.filter { file ->
            val text = file.readText()
            text.contains("gemini") || text.contains("GenerativeModel") ||
                text.contains("FirebaseAI")
        }

        assertTrue(
            "o modelo concreto vazou para ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `a versao de prompt e uma autoridade unica`() {
        assertTrue("PROMPT_VERSION precisa ser positiva", AiModelConfig.PROMPT_VERSION >= 1)

        val offenders = mainSources.filter { file ->
            file.name != "AiModelConfig.kt" &&
                Regex("(?i)(const\\s+val\\s+)?PROMPT_VERSION\\s*[:=]").containsMatchIn(file.readText())
        }

        assertTrue(
            "há outra versão de prompt declarada fora de AiModelConfig: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `todo prompt de usuario carrega requestId, schemaVersion e promptVersion`() {
        val prompt = AiCoachPrompt.userPrompt(
            AiWorkoutGenerationRequest(
                requestId = "req-abc",
                schemaVersion = AiModelConfig.SCHEMA_VERSION,
                context = WorkoutGenerationTestData.context()
            )
        )

        assertTrue(prompt.contains("requestId: req-abc"))
        assertTrue(prompt.contains("schemaVersion: ${AiModelConfig.SCHEMA_VERSION}"))
        assertTrue(prompt.contains("promptVersion: ${AiModelConfig.PROMPT_VERSION}"))
    }

    @Test
    fun `a versao de schema atual e suportada e uma desconhecida nao e`() {
        assertTrue(AiModelConfig.isSupportedSchemaVersion(AiModelConfig.SCHEMA_VERSION))
        assertFalse(AiModelConfig.isSupportedSchemaVersion(AiModelConfig.SCHEMA_VERSION + 1))
        assertFalse(AiModelConfig.isSupportedSchemaVersion(0))
        assertFalse(AiModelConfig.isSupportedSchemaVersion(-1))
        assertTrue(
            "a versão configurada precisa estar entre as suportadas",
            AiModelConfig.SCHEMA_VERSION in AiModelConfig.SUPPORTED_SCHEMA_VERSIONS
        )
    }

    @Test
    fun `a telemetria registra modelo, prompt e schema da configuracao`() = runTest {
        val recorded = mutableListOf<String>()
        val telemetry = object : AiCoachTelemetry {
            override fun onRequestFinished(
                requestId: String,
                type: AiCoachRequestType,
                model: String,
                promptVersion: Int,
                schemaVersion: Int,
                durationMs: Long,
                result: String
            ) {
                recorded += "$type|$model|$promptVersion|$schemaVersion|$result"
            }
        }
        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachTestData.response(
                    summary = "Resumo.",
                    // Sem contexto não há evidência: o teto é INSUFFICIENT.
                    dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.INSUFFICIENT)
                )
            )
        }

        AnalyzeWorkoutUseCase(
            contextBuilder = EvaluationAnalysisContextBuilder(
                AiCoachContext(athlete = AiAthleteContext())
            ),
            gateway = gateway,
            telemetry = telemetry
        )()

        assertEquals(
            listOf(
                "ANALYZE_WORKOUT|${AiModelConfig.MODEL_NAME}|${AiModelConfig.PROMPT_VERSION}|" +
                    "${AiModelConfig.SCHEMA_VERSION}|SUCCESS"
            ),
            recorded
        )
    }
}
