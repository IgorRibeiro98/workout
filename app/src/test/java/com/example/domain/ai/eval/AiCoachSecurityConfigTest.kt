package com.example.domain.ai.eval

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.ai.SparkBackendAiCoachGateway
import com.example.data.firebase.SparkAppCheck
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiAthleteContext
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachExplanationContext
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachExplanationRequest
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachRequest
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.AiWorkoutAdaptationRequest
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.AiWorkoutGenerationRequest
import com.example.domain.ai.WorkoutAdaptationTestData
import com.example.domain.ai.WorkoutGenerationTestData
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A configuração de segurança da fronteira de IA, verificada sem provider real.
 *
 * O que quebra em silêncio, e por isso é testado:
 *
 * - uma versão de contrato desconhecida sendo enviada ao provider;
 * - o Firebase AI Logic voltando ao aplicativo por refatoração (T16.2);
 * - credencial de modelo aparecendo no app — ela é exclusivamente do servidor;
 * - o provedor de App Check de depuração indo parar em release;
 * - segredo ou chave hardcoded no código.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AiCoachSecurityConfigTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val unsupportedVersion = AiModelConfig.SCHEMA_VERSION + 1

    // Sem cliente de backend configurado: nenhuma requisição sai, e é assim que o Coach se
    // comporta em um build sem endereço — indisponível, com o núcleo do Spark intacto.
    private fun gateway() = SparkBackendAiCoachGateway(client = null)

    // ------------------------------------------------------------------ schemaVersion

    @Test
    fun `schemaVersion desconhecida e recusada em todos os tipos de request`() = runTest {
        val gateway = gateway()

        val analyze = gateway.request(
            AiCoachRequest(
                requestId = "req-1",
                schemaVersion = unsupportedVersion,
                type = AiCoachRequestType.ANALYZE_WORKOUT,
                context = AiCoachContext(athlete = AiAthleteContext())
            )
        )
        assertEquals(
            AiCoachErrorKind.INVALID_RESPONSE,
            (analyze as AiCoachGatewayResult.Error).kind
        )

        val generate = gateway.generateWorkout(
            AiWorkoutGenerationRequest(
                requestId = "req-2",
                schemaVersion = unsupportedVersion,
                context = WorkoutGenerationTestData.context()
            )
        )
        assertEquals(
            AiCoachErrorKind.INVALID_RESPONSE,
            (generate as AiWorkoutGenerationGatewayResult.Error).kind
        )

        val adapt = gateway.adaptWorkout(
            AiWorkoutAdaptationRequest(
                requestId = "req-3",
                schemaVersion = unsupportedVersion,
                context = WorkoutAdaptationTestData.context()
            )
        )
        assertEquals(
            AiCoachErrorKind.INVALID_RESPONSE,
            (adapt as AiWorkoutAdaptationGatewayResult.Error).kind
        )

        val explain = gateway.explain(
            AiCoachExplanationRequest(
                requestId = "req-4",
                schemaVersion = unsupportedVersion,
                type = AiCoachRequestType.EXPLAIN_PROGRESS,
                context = AiCoachExplanationContext(
                    origin = "PROGRESS",
                    contextId = "progress",
                    subject = "Progressão"
                )
            )
        )
        assertEquals(
            AiCoachErrorKind.INVALID_RESPONSE,
            (explain as AiCoachExplanationGatewayResult.Error).kind
        )
    }

    @Test
    fun `a versao suportada passa da guarda e falha por falta de configuracao, nao por contrato`() =
        runTest {
            // Sem endereço de backend, a chamada vira UNAVAILABLE — o que prova que a guarda de
            // contrato deixou passar e o núcleo do Spark continua intacto.
            val result = gateway().request(
                AiCoachRequest(
                    requestId = "req-5",
                    schemaVersion = AiModelConfig.SCHEMA_VERSION,
                    type = AiCoachRequestType.ANALYZE_WORKOUT,
                    context = AiCoachContext(athlete = AiAthleteContext())
                )
            )

            assertEquals(
                AiCoachErrorKind.UNAVAILABLE,
                (result as AiCoachGatewayResult.Error).kind
            )
        }

    // ---------------------------------------------------------------------- App Check

    @Test
    fun `o aplicativo nao conhece mais o Firebase AI Logic`() {
        // A migração da T16.2 não é "o backend também funciona": o caminho antigo saiu. Se ele
        // voltar por refatoração, os dois providers passariam a divergir em silêncio.
        val offenders = (sourceSet("main") + sourceSet("debug") + sourceSet("release")).filter { file ->
            val text = file.readText()
            text.contains("com.google.firebase.ai") || text.contains("GenerativeModel") ||
                text.contains("FirebaseAI")
        }

        assertTrue(
            "Firebase AI Logic ainda é referenciado em: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `nenhuma chave de provider de IA existe no aplicativo`() {
        // A credencial do Gemini vive exclusivamente no servidor. Aqui não pode existir nem
        // configuração para ela.
        val offenders = (sourceSet("main") + sourceSet("debug") + sourceSet("release")).filter { file ->
            val text = file.readText()
            text.contains("GEMINI_API_KEY") || Regex("(?i)gemini[_a-z]*(key|token)").containsMatchIn(text)
        }

        assertTrue(
            "referência a credencial do Gemini no aplicativo: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `a variante de teste usa o provedor de depuracao`() {
        // Os testes unitários rodam sobre a variante debug: aqui o provedor precisa ser o de
        // depuração, e o de release precisa ser Play Integrity (verificado por source set abaixo).
        assertEquals("debug", SparkAppCheck.PROVIDER_NAME)
        assertTrue(SparkAppCheck.SUPPORTS_DEBUG_TOKEN)
    }

    @Test
    fun `nenhum token de depuracao vem embutido no app`() {
        assertNull(
            "o app não pode trazer um token de depuração pronto",
            SparkAppCheck.customDebugToken(context)
        )
    }

    @Test
    fun `o source set de release nao conhece o provedor de depuracao`() {
        val releaseSources = sourceSet("release")
        assertTrue("source set de release não encontrado", releaseSources.isNotEmpty())

        val offenders = releaseSources.filter { file ->
            val text = file.readText()
            text.contains("DebugAppCheckProvider") || text.contains("appcheck.debug")
        }

        assertTrue(
            "o provedor de depuração aparece em release: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
        assertTrue(
            "release precisa usar Play Integrity",
            releaseSources.any { it.readText().contains("PlayIntegrityAppCheckProviderFactory") }
        )
    }

    @Test
    fun `o source set principal nao referencia provedor de App Check concreto`() {
        // A escolha é por variante de build, não por condição de runtime: `src/main` só conhece
        // `SparkAppCheck`.
        val offenders = sourceSet("main").filter { file ->
            val text = file.readText()
            text.contains("DebugAppCheckProviderFactory") ||
                text.contains("PlayIntegrityAppCheckProviderFactory")
        }

        assertTrue(
            "provedor concreto de App Check em src/main: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------------ segredos

    @Test
    fun `nenhuma chave ou segredo hardcoded no codigo do app`() {
        val patterns = listOf(
            Regex("AIza[0-9A-Za-z_-]{20,}"),
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY"),
            Regex("(?i)\\bDEBUG_SECRET\\s*=\\s*\"[0-9A-Fa-f-]{16,}\""),
            Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")
        )

        val offenders = (sourceSet("main") + sourceSet("debug") + sourceSet("release"))
            .filter { file -> patterns.any { it.containsMatchIn(file.readText()) } }

        assertTrue(
            "credencial ou segredo hardcoded em: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    private fun sourceSet(name: String): List<File> {
        val root = File("src/$name/java").takeIf { it.isDirectory }
            ?: File("app/src/$name/java")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
