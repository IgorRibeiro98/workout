package com.example.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.data.firebase.SparkAppCheck
import com.example.domain.auth.AuthError
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.AuthState
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.auth.SparkAccount
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Único ponto do Spark que conhece Firebase Authentication e Credential Manager.
 *
 * ```text
 * Google Account
 *      ↓  Credential Manager (Sign in with Google)
 * Google ID Token
 *      ↓  GoogleAuthProvider.getCredential
 * Firebase Auth
 *      ↓
 * AuthState.SignedIn(uid, ...)
 * ```
 *
 * ## Só o Firebase é dono da sessão
 *
 * O estado publicado é derivado do `FirebaseAuth` real, por `AuthStateListener` — não é uma cópia
 * mantida na mão, que ficaria divergente na primeira credencial revogada. Uma sessão que já existe
 * quando o app abre vira [AuthState.SignedIn] sozinha, **sem** abrir seletor de contas: restaurar
 * sessão é automático, autenticar de novo exige toque.
 *
 * ## Nada aqui encosta em dado local
 *
 * Este arquivo não conhece Room, DAO, repositório nem `SettingsManager`. Entrar e sair não criam,
 * apagam, sobem, baixam ou reassociam treino, sessão, histórico, medida ou preferência.
 *
 * ## Nada aqui persiste ou registra token
 *
 * O ID Token é obtido sob demanda e devolvido a quem pediu. Não é guardado e não vai para log —
 * nem em debug. O que este arquivo registra é evento técnico, no máximo com o prefixo do uid.
 *
 * Sem configuração válida do Firebase, [isSignInAvailable] é `false` e o núcleo do Spark segue
 * funcionando exatamente como antes.
 */
class FirebaseAuthGateway(
    context: Context,
    private val credentialManagerFactory: (Context) -> CredentialManager = CredentialManager::create
) : AuthGateway, AuthTokenProvider {

    private val appContext: Context = context.applicationContext

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)

    /** Um fluxo de autenticação por vez: dez toques rápidos abrem um seletor, não dez. */
    private val signInInProgress = AtomicBoolean(false)

    @Volatile
    private var listening = false

    @Volatile
    private var appCheckInstalled: Boolean = false

    override val state: StateFlow<AuthState>
        get() {
            startObservingFirebase()
            return _state.asStateFlow()
        }

    override val isSignInAvailable: Boolean
        get() = firebaseAuthOrNull() != null && GoogleServerClientId.resolve(appContext) != null

    // ------------------------------------------------------------------------------- entrar

    override suspend fun signIn(host: Context): AuthOutcome {
        if (!signInInProgress.compareAndSet(false, true)) {
            // Já existe um seletor de contas aberto. Não abrimos outro, e não iniciamos um
            // segundo `signInWithCredential` concorrente.
            return AuthOutcome.AlreadyInProgress
        }

        startObservingFirebase()
        _state.value = AuthState.SigningIn

        return try {
            val outcome = performSignIn(host)
            settle(outcome)
            outcome
        } catch (e: CancellationException) {
            // A corrotina foi cancelada (a tela saiu): o estado volta a refletir o Firebase.
            settle(AuthOutcome.Cancelled)
            throw e
        } finally {
            signInInProgress.set(false)
        }
    }

    private suspend fun performSignIn(host: Context): AuthOutcome {
        val auth = firebaseAuthOrNull() ?: return AuthOutcome.Failure(AuthError.NOT_CONFIGURED)
        val serverClientId = GoogleServerClientId.resolve(appContext)
            ?: return AuthOutcome.Failure(AuthError.NOT_CONFIGURED)

        // `GetSignInWithGoogleOption` é o fluxo de botão: ele só existe dentro de uma ação
        // explícita e sempre mostra o seletor. Nada aqui roda em `init`, ao abrir tela ou em
        // recomposição.
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()

        val googleIdToken = try {
            val response = credentialManagerFactory(host).getCredential(host, request)
            extractGoogleIdToken(response.credential)
        } catch (e: GetCredentialCancellationException) {
            // O usuário fechou o seletor. Isso é uma escolha, não uma falha.
            return AuthOutcome.Cancelled
        } catch (e: NoCredentialException) {
            return AuthOutcome.Failure(AuthError.NO_CREDENTIAL)
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager recusou: ${e.javaClass.simpleName}")
            return AuthOutcome.Failure(AuthError.PROVIDER)
        } ?: return AuthOutcome.Failure(AuthError.PROVIDER)

        return try {
            val user = auth.signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null))
                .awaitResult()
                .user
                ?: return AuthOutcome.Failure(AuthError.PROVIDER)
            AuthOutcome.Success(user.toAccount())
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseNetworkException) {
            AuthOutcome.Failure(AuthError.NETWORK)
        } catch (e: IOException) {
            AuthOutcome.Failure(AuthError.NETWORK)
        } catch (e: Exception) {
            Log.w(TAG, "Firebase recusou a credencial: ${e.javaClass.simpleName}")
            AuthOutcome.Failure(AuthError.PROVIDER)
        }
    }

    /** O Google ID Token de dentro da credencial, ou `null` se veio outro tipo de credencial. */
    private fun extractGoogleIdToken(credential: androidx.credentials.Credential): String? {
        if (credential !is CustomCredential) return null
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return null
        return runCatching { GoogleIdTokenCredential.createFrom(credential.data).idToken }
            .onFailure { Log.w(TAG, "Credencial Google ilegível: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    // -------------------------------------------------------------------------------- sair

    override suspend fun signOut() {
        val auth = firebaseAuthOrNull()
        startObservingFirebase()
        _state.value = AuthState.SigningOut

        // Nenhuma linha de Room, DataStore ou preferência é tocada aqui. Sair da conta desconecta
        // a identidade online; o treino, o histórico e a gamificação continuam onde estavam.
        runCatching { auth?.signOut() }
            .onFailure { Log.w(TAG, "signOut falhou: ${it.javaClass.simpleName}") }

        // A documentação atual do Firebase pede limpar também o estado de credencial: sem isso o
        // Credential Manager pode reconectar a mesma conta na próxima tentativa.
        try {
            credentialManagerFactory(appContext)
                .clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClearCredentialException) {
            Log.w(TAG, "Credential state não limpo: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w(TAG, "Credential state não limpo: ${e.javaClass.simpleName}")
        }

        _state.value = currentUser()?.let { AuthState.SignedIn(it.toAccount()) } ?: AuthState.SignedOut
    }

    // ------------------------------------------------------------------------------- token

    override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult {
        val user = currentUser() ?: return AuthTokenResult.SignedOut

        return try {
            val token = user.getIdToken(forceRefresh).awaitResult().token
            if (token.isNullOrBlank()) {
                AuthTokenResult.Failure(AuthError.PROVIDER)
            } else {
                AuthTokenResult.Token(token)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseNetworkException) {
            AuthTokenResult.Failure(AuthError.NETWORK)
        } catch (e: IOException) {
            AuthTokenResult.Failure(AuthError.NETWORK)
        } catch (e: Exception) {
            // O nome da exceção, nunca o token nem a mensagem, que pode conter o payload.
            Log.w(TAG, "ID token indisponível: ${e.javaClass.simpleName}")
            AuthTokenResult.Failure(AuthError.PROVIDER)
        }
    }

    // ------------------------------------------------------------------------------ estado

    /**
     * Liga o estado publicado ao `FirebaseAuth` real, uma vez por instância.
     *
     * Só acontece quando alguém observa o estado ou pede uma operação — abrir o Spark não
     * inicializa autenticação, do mesmo jeito que não inicializa o Coach.
     */
    @Synchronized
    private fun startObservingFirebase() {
        if (listening) return
        val auth = firebaseAuthOrNull() ?: return
        listening = true

        // A sessão restaurada aparece por aqui, sem seletor de contas e sem pedir nada ao usuário.
        auth.addAuthStateListener { updated -> onFirebaseUserChanged(updated.currentUser) }
        onFirebaseUserChanged(auth.currentUser)
    }

    private fun onFirebaseUserChanged(user: FirebaseUser?) {
        if (user != null) {
            _state.value = AuthState.SignedIn(user.toAccount())
            return
        }
        // Sem usuário: preserva um estado transitório em andamento, que a própria operação encerra.
        val current = _state.value
        if (current is AuthState.SigningIn || current is AuthState.SigningOut) return
        _state.value = AuthState.SignedOut
    }

    /** O estado terminal depois de uma tentativa: o Firebase decide, o desfecho só complementa. */
    private fun settle(outcome: AuthOutcome) {
        val user = currentUser()
        _state.value = when {
            user != null -> AuthState.SignedIn(user.toAccount())
            outcome is AuthOutcome.Failure -> AuthState.Error(outcome.error)
            else -> AuthState.SignedOut
        }
    }

    private fun currentUser(): FirebaseUser? = firebaseAuthOrNull()?.currentUser

    /**
     * `null` quando falta a configuração do console (`google-services.json`) ou o SDK não está
     * presente. Não é exceção: conta é opcional, e a ausência dela é um estado normal do app.
     */
    private fun firebaseAuthOrNull(): FirebaseAuth? = try {
        FirebaseAuth.getInstance().also { installAppCheck() }
    } catch (e: IllegalStateException) {
        null
    } catch (e: NoClassDefFoundError) {
        null
    } catch (e: Exception) {
        null
    }

    /**
     * Instala o provedor de App Check da variante de build, uma vez por processo.
     *
     * App Check atesta o **aplicativo** perante o Firebase; Firebase Auth identifica o
     * **usuário**. São responsabilidades diferentes, e nenhuma substitui a outra — por isso a
     * instalação vive aqui desde a T16.2: o Coach deixou de falar com o Firebase, e o produto
     * Firebase que resta em uso é a autenticação.
     *
     * Qual provedor é decidido em tempo de **compilação** por [SparkAppCheck]: debug usa o
     * provedor de depuração, release usa Play Integrity, e nenhum dos dois consegue aparecer no
     * outro APK. Nada aqui é feito no startup: só quando a área de conta é realmente usada.
     * Nenhum segredo é registrado em log.
     */
    private fun installAppCheck() {
        if (appCheckInstalled) return
        try {
            SparkAppCheck.publishDebugToken(appContext)
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(SparkAppCheck.providerFactory())
            appCheckInstalled = true
            Log.i(TAG, "App Check instalado: provider=${SparkAppCheck.PROVIDER_NAME}")
        } catch (e: Exception) {
            Log.w(TAG, "App Check indisponível: ${e.javaClass.simpleName}")
        }
    }

    private fun FirebaseUser.toAccount(): SparkAccount = SparkAccount(
        uid = uid,
        displayName = displayName?.takeIf { it.isNotBlank() },
        email = email?.takeIf { it.isNotBlank() },
        photoUrl = photoUrl?.toString()?.takeIf { it.isNotBlank() }
    )

    companion object {
        const val TAG = "SparkAuth"
    }
}
