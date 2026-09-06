package com.example.data.auth

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.AuthError
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.AuthSourceInspection
import com.example.domain.auth.AuthState
import com.example.domain.auth.AuthTokenResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A implementação real da fronteira de autenticação, exercitada **sem** Firebase configurado —
 * exatamente a situação do CI: sem service account, sem conta Google, sem rede.
 *
 * O que isso prova: a ausência de configuração é um estado normal e silencioso, e não uma exceção
 * que derruba tela. Conta é opcional, então "não dá para entrar" precisa ser tão bem tratado
 * quanto "entrou".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class FirebaseAuthGatewayTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun gateway() = FirebaseAuthGateway(context)

    @Test
    fun `sem configuracao o estado inicial e SignedOut, nao um erro`() = runTest {
        assertEquals(AuthState.SignedOut, gateway().state.value)
    }

    @Test
    fun `sem configuracao a entrada de login se declara indisponivel`() {
        assertFalse(gateway().isSignInAvailable)
    }

    @Test
    fun `sem configuracao o login falha como NOT_CONFIGURED e nao abre seletor`() = runTest {
        val result = gateway().signIn(context)

        assertEquals(AuthOutcome.Failure(AuthError.NOT_CONFIGURED), result)
    }

    @Test
    fun `sem conta conectada o provider de token responde SignedOut`() = runTest {
        assertEquals(AuthTokenResult.SignedOut, gateway().currentToken())
    }

    @Test
    fun `logout sem sessao e inofensivo e termina em SignedOut`() = runTest {
        val gateway = gateway()

        gateway.signOut()

        assertEquals(AuthState.SignedOut, gateway.state.value)
    }

    @Test
    fun `o Web Client ID nao vem de constante no codigo`() {
        // Sem `oauth_client` no `google-services.json`, o recurso não existe e a resolução é
        // `null`. Se alguém colar o client ID numa constante, este teste passa a falhar.
        val resolved = GoogleServerClientId.resolve(context)

        assertTrue(
            "o Web Client ID precisa vir da configuração, não do código: $resolved",
            resolved == null || resolved.endsWith(".apps.googleusercontent.com")
        )
    }

    @Test
    fun `nenhum client ID, chave ou token esta hardcoded na fronteira de conta`() {
        val patterns = listOf(
            Regex("[0-9]{6,}-[0-9a-z]{10,}\\.apps\\.googleusercontent\\.com"),
            Regex("AIza[0-9A-Za-z_-]{20,}"),
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY"),
            Regex("\"type\"\\s*:\\s*\"service_account\"")
        )

        val offenders = authSources().filter { file ->
            patterns.any { it.containsMatchIn(file.readText()) }
        }

        assertTrue(
            "credencial hardcoded em: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `nenhum token e registrado em log na fronteira de conta`() {
        // Log com interpolação de algo chamado token/credential/authorization é o caminho mais
        // curto para um ID Token cair no Logcat, inclusive em debug.
        val suspicious = Regex(
            """Log\.[a-z]\([^)]*\$\{?[A-Za-z.]*(?i:token|credential|authorization)""",
        )

        val offenders = authSources().filter {
            suspicious.containsMatchIn(AuthSourceInspection.code(it))
        }

        assertTrue(
            "possível token em log: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `Firebase Auth e Credential Manager ficam atras da fronteira`() {
        // A UI não pode falar com Firebase espalhado pelas telas: `FirebaseAuth`,
        // `CredentialManager` e `GoogleIdTokenCredential` só existem em `data/auth`.
        val forbidden = listOf(
            "com.google.firebase.auth",
            "androidx.credentials",
            "GoogleIdTokenCredential",
            "GetSignInWithGoogleOption"
        )
        // Comentários fora: a documentação da fronteira cita esses nomes justamente para dizer
        // que eles não podem aparecer no código de fora dela.
        val offenders = mainSources()
            .filterNot { it.path.replace('\\', '/').contains("/com/example/data/auth/") }
            .filter { file ->
                val code = AuthSourceInspection.code(file)
                forbidden.any { code.contains(it) }
            }

        assertTrue(
            "Firebase Auth / Credential Manager fora de data/auth: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `App Check continua existindo e separado da autenticacao de usuario`() {
        // App Check atesta o app; Firebase Auth identifica o usuário. A T16.1 não pode ter
        // removido nem enfraquecido o primeiro ao introduzir o segundo.
        val appCheckFiles = mainSources().filter {
            AuthSourceInspection.code(it).contains("FirebaseAppCheck")
        }

        assertTrue("App Check sumiu de src/main", appCheckFiles.isNotEmpty())
        assertTrue(
            "App Check e autenticação de usuário não podem se confundir",
            authSources().none { AuthSourceInspection.code(it).contains("FirebaseAppCheck") }
        )
    }

    private fun authSources(): List<File> =
        AuthSourceInspection.sources("app/src/main/java/com/example/data/auth")

    private fun mainSources(): List<File> =
        AuthSourceInspection.sources("app/src/main/java")
}
