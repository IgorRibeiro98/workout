package com.example.data.ai

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachExplanationContext
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachExplanationRequest
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiEvidenceContext
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiWorkoutAdaptationContext
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.AiWorkoutAdaptationRequest
import com.example.domain.ai.model.AiWorkoutContext
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.AiWorkoutGenerationRequest
import com.example.domain.auth.AuthError
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import java.io.IOException
/**
 * `runBlocking`, e não `runTest`: o gateway atravessa `Dispatchers.IO` e aplica um teto de tempo
 * real. O relógio virtual do `runTest` adiantaria esse teto e todo teste viraria um timeout
 * artificial — medindo o scheduler, não o gateway.
 */
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A fronteira do Coach com o Spark Backend (T16.2).
 *
 * Nenhum teste aqui abre socket, fala com Firebase ou toca o Gemini: um interceptor terminal
 * responde no lugar da rede, o que permite inspecionar exatamente a requisição que **teria**
 * saído — e provar as duas coisas que mais importam: sem conta não sai requisição, e cada status
 * HTTP vira o erro tipado que a UI já sabe mostrar desde a T14.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkBackendAiCoachGatewayTest {

    // ---------------------------------------------------------------------------- sem conta

    @Test
    fun `sem conta conectada o Coach pede login e nao faz requisicao nenhuma`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, token = AuthTokenResult.SignedOut)

        val result = gateway.request(analyzeRequest()) as AiCoachGatewayResult.Error

        assertEquals(AiCoachErrorKind.AUTH_REQUIRED, result.kind)
        assertTrue("sem conta, nada pode ir para a rede", sent.isEmpty())
    }

    @Test
    fun `sem endereco de backend o Coach fica indisponivel, sem rede`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, baseUrl = "")

        val result = gateway.request(analyzeRequest()) as AiCoachGatewayResult.Error

        assertEquals(AiCoachErrorKind.UNAVAILABLE, result.kind)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `sem cliente configurado o Coach fica indisponivel`() = runBlocking {
        val result = SparkBackendAiCoachGateway(client = null).request(analyzeRequest())

        assertEquals(AiCoachErrorKind.UNAVAILABLE, (result as AiCoachGatewayResult.Error).kind)
    }

    // -------------------------------------------------------------------------- com conta

    @Test
    fun `com conta a requisicao sai autenticada, no contrato do backend`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = successEnvelope(ANALYSIS_RESULT))

        val result = gateway.request(analyzeRequest())

        assertTrue(result is AiCoachGatewayResult.Success)
        val request = sent.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE_URL/v1/ai/coach", request.url.toString())
        assertEquals("Bearer token-abc", request.header("Authorization"))

        val body = bodyOf(request)
        assertTrue("requestType ausente", body.contains("\"requestType\":\"ANALYZE_WORKOUT\""))
        assertTrue("schemaVersion ausente", body.contains("\"schemaVersion\":1"))
        assertTrue("clientRequestId ausente", body.contains("\"clientRequestId\":\"cli-"))
        assertTrue("contexto ausente", body.contains("\"context\""))

        // O cliente não manda prompt, modelo, temperatura nem esforço de raciocínio: isso é
        // decisão do servidor desde a T16.2.
        for (forbidden in listOf("systemPrompt", "prompt", "temperature", "model", "thinking")) {
            assertTrue("o cliente não pode enviar '$forbidden'", !body.contains("\"$forbidden\""))
        }
    }

    @Test
    fun `a resposta traz o modelo e a versao de prompt que o servidor decidiu`() = runBlocking {
        val gateway = gatewayWith(mutableListOf(), body = successEnvelope(ANALYSIS_RESULT))

        val result = gateway.request(analyzeRequest()) as AiCoachGatewayResult.Success

        assertEquals("modelo-do-servidor", result.metadata.model)
        assertEquals(9, result.metadata.promptVersion)
        assertEquals("srv-req-1", result.metadata.requestId)
        assertEquals("Resumo do treino.", result.response.summary)
    }

    @Test
    fun `cada tentativa carrega um clientRequestId proprio e opaco`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = successEnvelope(ANALYSIS_RESULT))

        gateway.request(analyzeRequest())
        gateway.request(analyzeRequest())

        val ids = sent.map {
            Regex("\"clientRequestId\":\"([^\"]+)\"").find(bodyOf(it))!!.groupValues[1]
        }
        assertEquals("duas tentativas, dois identificadores", 2, ids.distinct().size)
        ids.forEach { id ->
            assertTrue("o id precisa ser opaco: $id", Regex("^[A-Za-z0-9._-]{8,128}$").matches(id))
        }
    }

    @Test
    fun `os quatro tipos de request usam a mesma fronteira`() = runBlocking {
        val sent = mutableListOf<Request>()

        val analyze = gatewayWith(sent, body = successEnvelope(ANALYSIS_RESULT))
        assertTrue(analyze.request(analyzeRequest()) is AiCoachGatewayResult.Success)

        val generate = gatewayWith(sent, body = successEnvelope(GENERATION_RESULT))
        assertTrue(
            generate.generateWorkout(generationRequest()) is AiWorkoutGenerationGatewayResult.Success
        )

        val adapt = gatewayWith(sent, body = successEnvelope(ADAPTATION_RESULT))
        assertTrue(adapt.adaptWorkout(adaptationRequest()) is AiWorkoutAdaptationGatewayResult.Success)

        val explain = gatewayWith(sent, body = successEnvelope(EXPLANATION_RESULT))
        assertTrue(explain.explain(explanationRequest()) is AiCoachExplanationGatewayResult.Success)

        assertEquals(4, sent.size)
        assertEquals(
            listOf("ANALYZE_WORKOUT", "GENERATE_WORKOUT", "ADAPT_WORKOUT", "EXPLAIN_ADAPTATION"),
            sent.map { Regex("\"requestType\":\"([A-Z_]+)\"").find(bodyOf(it))!!.groupValues[1] }
        )
    }

    // ------------------------------------------------------------------- mapeamento de erro

    @Test
    fun `401 vira pedido de conta, nunca crash nem erro cru`() = runBlocking {
        val gateway = gatewayWith(mutableListOf(), status = 401, body = errorEnvelope("UNAUTHENTICATED"))

        val result = gateway.request(analyzeRequest()) as AiCoachGatewayResult.Error

        assertEquals(AiCoachErrorKind.AUTH_REQUIRED, result.kind)
    }

    @Test
    fun `429 vira limite de uso`() = runBlocking {
        val gateway = gatewayWith(
            mutableListOf(),
            status = 429,
            body = errorEnvelope("AI_USER_QUOTA_EXCEEDED")
        )

        val result = gateway.request(analyzeRequest()) as AiCoachGatewayResult.Error

        assertEquals(AiCoachErrorKind.RATE_LIMITED, result.kind)
        // O código do envelope é vocabulário do Spark; nada do provider atravessa.
        assertEquals("AI_USER_QUOTA_EXCEEDED", result.detail)
    }

    @Test
    fun `504 vira timeout e 502-503 viram indisponivel`() = runBlocking {
        val timeout = gatewayWith(mutableListOf(), status = 504, body = errorEnvelope("AI_PROVIDER_TIMEOUT"))
        assertEquals(
            AiCoachErrorKind.TIMEOUT,
            (timeout.request(analyzeRequest()) as AiCoachGatewayResult.Error).kind
        )

        for (status in listOf(502, 503)) {
            val unavailable = gatewayWith(mutableListOf(), status = status, body = errorEnvelope("X"))
            assertEquals(
                AiCoachErrorKind.UNAVAILABLE,
                (unavailable.request(analyzeRequest()) as AiCoachGatewayResult.Error).kind
            )
        }
    }

    @Test
    fun `422 e 400 viram resposta invalida`() = runBlocking {
        for (status in listOf(400, 422)) {
            val gateway = gatewayWith(mutableListOf(), status = status, body = errorEnvelope("Y"))
            assertEquals(
                AiCoachErrorKind.INVALID_RESPONSE,
                (gateway.request(analyzeRequest()) as AiCoachGatewayResult.Error).kind
            )
        }
    }

    @Test
    fun `409 vira erro recuperavel do provider`() = runBlocking {
        val gateway = gatewayWith(mutableListOf(), status = 409, body = errorEnvelope("AI_REQUEST_CONFLICT"))

        val result = gateway.request(analyzeRequest()) as AiCoachGatewayResult.Error

        assertEquals(AiCoachErrorKind.PROVIDER, result.kind)
        assertEquals("AI_REQUEST_CONFLICT", result.detail)
    }

    @Test
    fun `backend fora do ar vira erro de rede, e o nucleo do app nao muda`() = runBlocking {
        val tokens = TokenProvider(AuthTokenResult.Token("token-abc"))
        val gateway = SparkBackendAiCoachGateway(
            SparkBackendClient(
                baseUrl = BASE_URL,
                tokens = tokens,
                httpClient = OkHttpClient.Builder()
                    .addInterceptor(SparkAuthInterceptor(tokens))
                    .addInterceptor(Interceptor { throw IOException("sem rota para o host") })
                    .build()
            )
        )

        val result = gateway.request(analyzeRequest()) as AiCoachGatewayResult.Error

        assertEquals(AiCoachErrorKind.NETWORK, result.kind)
    }

    @Test
    fun `falha ao obter token vira pedido de conta, nao requisicao sem credencial`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, token = AuthTokenResult.Failure(AuthError.NETWORK))

        val result = gateway.request(analyzeRequest()) as AiCoachGatewayResult.Error

        assertEquals(AiCoachErrorKind.AUTH_REQUIRED, result.kind)
        assertTrue(sent.isEmpty())
    }

    // ---------------------------------------------------------------- contrato da resposta

    @Test
    fun `resposta fora do contrato e recusada`() = runBlocking {
        val cases = listOf(
            "não é json",
            """{"requestId":"srv-1"}""",
            """{"result":{"summary":123}}"""
        )

        for (body in cases) {
            val gateway = gatewayWith(mutableListOf(), body = body)
            val result = gateway.request(analyzeRequest())
            assertEquals(
                "corpo aceito indevidamente: $body",
                AiCoachErrorKind.INVALID_RESPONSE,
                (result as AiCoachGatewayResult.Error).kind
            )
        }
    }

    @Test
    fun `resposta de outra requisicao nao e aproveitada`() = runBlocking {
        val gateway = gatewayWith(
            mutableListOf(),
            body = "{\"requestId\":\"srv-1\",\"clientRequestId\":\"cli-de-outra-chamada\"," +
                "\"schemaVersion\":1,\"promptVersion\":1,\"model\":\"m\",\"result\":$ANALYSIS_RESULT}"
        )

        val result = gateway.request(analyzeRequest()) as AiCoachGatewayResult.Error

        assertEquals(AiCoachErrorKind.INVALID_RESPONSE, result.kind)
    }

    @Test
    fun `schemaVersion desconhecida e recusada nos dois sentidos`() = runBlocking {
        val sent = mutableListOf<Request>()

        // Ida: um contrato que este build não sabe interpretar nem sai do aparelho.
        val outgoing = gatewayWith(sent, body = successEnvelope(ANALYSIS_RESULT))
        val ida = outgoing.request(
            analyzeRequest().copy(schemaVersion = AiModelConfig.SCHEMA_VERSION + 1)
        ) as AiCoachGatewayResult.Error
        assertEquals(AiCoachErrorKind.INVALID_RESPONSE, ida.kind)
        assertTrue(sent.isEmpty())

        // Volta: um servidor que respondeu em outro contrato também é recusado.
        val incoming = gatewayWith(
            sent,
            body = "{\"requestId\":\"srv-1\",\"schemaVersion\":99,\"promptVersion\":1," +
                "\"model\":\"m\",\"result\":$ANALYSIS_RESULT}"
        )
        val volta = incoming.request(analyzeRequest()) as AiCoachGatewayResult.Error
        assertEquals(AiCoachErrorKind.INVALID_RESPONSE, volta.kind)
    }

    @Test
    fun `uma chamada explicita produz exatamente uma requisicao`() = runBlocking {
        val sent = mutableListOf<Request>()

        // Erro do servidor não gera nova tentativa: não existe retry automático.
        val gateway = gatewayWith(sent, status = 503, body = errorEnvelope("X"))
        gateway.request(analyzeRequest())

        assertEquals(1, sent.size)
    }

    // --------------------------------------------------------------------------- fixtures

    private fun gatewayWith(
        sent: MutableList<Request>,
        status: Int = 200,
        body: String = "{}",
        token: AuthTokenResult = AuthTokenResult.Token("token-abc"),
        baseUrl: String = BASE_URL
    ): SparkBackendAiCoachGateway {
        val tokens = TokenProvider(token)
        val http = OkHttpClient.Builder()
            .addInterceptor(SparkAuthInterceptor(tokens))
            .addInterceptor(
                Interceptor { chain ->
                    sent += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("stub")
                        .body(body.toResponseBody(null))
                        .build()
                }
            )
            .build()

        return SparkBackendAiCoachGateway(
            SparkBackendClient(baseUrl = baseUrl, tokens = tokens, httpClient = http)
        )
    }

    private fun bodyOf(request: Request): String {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun successEnvelope(result: String): String =
        "{\"requestId\":\"srv-req-1\",\"schemaVersion\":1,\"promptVersion\":9," +
            "\"model\":\"modelo-do-servidor\",\"result\":$result}"

    private fun errorEnvelope(code: String): String =
        "{\"error\":{\"code\":\"$code\",\"message\":\"algo aconteceu\",\"requestId\":\"srv-req-1\"}}"

    private fun analyzeRequest() = AiCoachRequest(
        requestId = "req-1",
        schemaVersion = AiModelConfig.SCHEMA_VERSION,
        type = AiCoachRequestType.ANALYZE_WORKOUT,
        context = AiCoachContext(
            athlete = AiAthleteContext(weeklyGoal = 4, completedSessionsInWindow = 3),
            currentWorkout = AiWorkoutContext(
                templateName = "Treino A",
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
            evidence = AiEvidenceContext(
                sessionsAnalyzed = 3,
                exercisesWithHistory = 1,
                maxDataQuality = AiDataQualityLevel.GOOD
            )
        )
    )

    private fun generationRequest() = AiWorkoutGenerationRequest(
        requestId = "req-2",
        schemaVersion = AiModelConfig.SCHEMA_VERSION,
        context = AiWorkoutGenerationContext(
            goal = "HYPERTROPHY",
            goalGuidance = "Volume moderado.",
            durationMinutes = 60,
            focusMuscleGroups = listOf("Peitoral"),
            candidateExercises = listOf(
                AiCandidateExerciseContext("supino-reto-barra", "Supino reto com barra", "Peitoral", "Barra")
            )
        )
    )

    private fun adaptationRequest() = AiWorkoutAdaptationRequest(
        requestId = "req-3",
        schemaVersion = AiModelConfig.SCHEMA_VERSION,
        context = AiWorkoutAdaptationContext(
            templateName = "Treino A",
            template = AiWorkoutContext(
                templateName = "Treino A",
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
            allowedChangeTypes = listOf("ADJUST_LOAD")
        )
    )

    private fun explanationRequest() = AiCoachExplanationRequest(
        requestId = "req-4",
        schemaVersion = AiModelConfig.SCHEMA_VERSION,
        type = AiCoachRequestType.EXPLAIN_ADAPTATION,
        context = AiCoachExplanationContext(
            origin = "WORKOUT_ADAPTATION",
            contextId = "ADJUST_LOAD:supino-reto-barra",
            subject = "Subir a carga do supino"
        )
    )

    private class TokenProvider(private val result: AuthTokenResult) : AuthTokenProvider {
        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult = result
    }

    private companion object {
        const val BASE_URL = "https://spark.exemplo"

        const val ANALYSIS_RESULT = "{\"summary\":\"Resumo do treino.\",\"positiveSignals\":[]," +
            "\"attentionPoints\":[],\"recommendations\":[]," +
            "\"dataQuality\":{\"level\":\"GOOD\",\"description\":\"3 sessões.\"}}"

        const val GENERATION_RESULT = "{\"name\":\"Treino de peito\",\"exercises\":[" +
            "{\"exerciseId\":\"supino-reto-barra\",\"order\":1,\"sets\":4,\"minReps\":8," +
            "\"maxReps\":12,\"restSeconds\":90,\"weightKg\":null,\"reason\":\"Base.\"}]," +
            "\"explanation\":\"Um composto.\",\"insufficientCandidates\":false}"

        const val ADAPTATION_RESULT = "{\"summary\":\"Dá para subir a carga.\",\"changes\":[]," +
            "\"dataQuality\":{\"level\":\"GOOD\",\"description\":\"3 sessões.\"}}"

        const val EXPLANATION_RESULT = "{\"title\":\"Por quê\"," +
            "\"explanation\":\"As execuções fecharam a faixa.\",\"limitations\":[]," +
            "\"referencedExerciseIds\":[]}"
    }
}
