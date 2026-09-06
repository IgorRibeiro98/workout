package com.example.domain.ai.eval

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
    fun `nenhum nome de modelo existe no aplicativo`() {
        // Desde a T16.2 o modelo é decisão do servidor: trocar de modelo não pode exigir publicar
        // um APK, e um nome de modelo no app seria uma segunda autoridade divergente.
        val offenders = mainSources.filter { file -> file.readText().contains("gemini-") }

        assertTrue("nome de modelo no aplicativo: ${offenders.map { it.name }}", offenders.isEmpty())
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
    fun `o aplicativo nao declara versao de prompt`() {
        // A versão de prompt acompanha o prompt, e o prompt vive no Spark Backend desde a T16.2.
        // Declarar um número aqui criaria duas versões divergentes para a mesma conversa.
        val offenders = mainSources.filter { file ->
            Regex("(const\\s+val|val|var)\\s+PROMPT_VERSION\\b").containsMatchIn(file.readText())
        }

        assertTrue(
            "versão de prompt declarada no aplicativo: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )

        // O app carrega a versão que o servidor informou; quando não há chamada, ele diz
        // "desconhecida" em vez de inventar um número.
        assertEquals(0, com.example.domain.ai.model.AiCoachCallMetadata.UNKNOWN_PROMPT_VERSION)
        assertEquals(
            com.example.domain.ai.model.AiCoachCallMetadata.UNKNOWN_PROMPT_VERSION,
            com.example.domain.ai.model.AiCoachCallMetadata.Unknown.promptVersion
        )
    }

    @Test
    fun `o contrato de ida carrega requestId, schemaVersion e tipo`() {
        val request = AiWorkoutGenerationRequest(
            requestId = "req-abc",
            schemaVersion = AiModelConfig.SCHEMA_VERSION,
            context = WorkoutGenerationTestData.context()
        )

        assertEquals("req-abc", request.requestId)
        assertEquals(AiModelConfig.SCHEMA_VERSION, request.schemaVersion)
        assertEquals(AiCoachRequestType.GENERATE_WORKOUT, request.type)
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
    fun `a telemetria registra o modelo e a versao de prompt que o servidor informou`() = runTest {
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
                ),
                // Metadata do servidor: é ela, e não uma constante local, que a telemetria registra.
                metadata = com.example.domain.ai.model.AiCoachCallMetadata(
                    requestId = "srv-1",
                    model = "modelo-do-servidor",
                    promptVersion = 7
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
            listOf("ANALYZE_WORKOUT|modelo-do-servidor|7|${AiModelConfig.SCHEMA_VERSION}|SUCCESS"),
            recorded
        )
    }
}
