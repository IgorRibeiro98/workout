package com.example.presentation.account

import com.example.domain.auth.AuthError
import com.example.domain.auth.AuthState
import com.example.domain.auth.SparkAccount

/**
 * O que a área de Conta Spark, dentro do Perfil, precisa desenhar.
 *
 * É uma projeção de [AuthState] — a autoridade continua sendo o Firebase Auth, atrás do
 * `AuthGateway`. Nada aqui guarda uma segunda cópia do estado da sessão.
 */
data class AccountUiState(
    val authState: AuthState = AuthState.SignedOut,
    /** `false` quando falta configuração de Firebase/Google neste build. */
    val isSignInAvailable: Boolean = false,
    /** Verificação de ponta a ponta contra o Spark Backend. Diagnóstico, não feature. */
    val backendCheck: BackendIdentityCheck = BackendIdentityCheck.Idle
) {
    /** Há uma operação de conta em andamento: os botões ficam bloqueados. */
    val isBusy: Boolean
        get() = authState is AuthState.SigningIn || authState is AuthState.SigningOut

    val account: SparkAccount?
        get() = (authState as? AuthState.SignedIn)?.account

    val isSignedIn: Boolean
        get() = authState is AuthState.SignedIn

    /** O erro da última tentativa, quando houve. Cancelamento não produz erro. */
    val error: AuthError?
        get() = (authState as? AuthState.Error)?.error
}

/**
 * Resultado de perguntar ao Spark Backend "quem você acha que eu sou?".
 *
 * Serve para provar a cadeia `Firebase Auth → ID Token → Firebase Admin → uid` em um aparelho
 * real. Não é fonte de verdade de nada: o Perfil já sabe se há conta sem falar com servidor.
 */
sealed interface BackendIdentityCheck {

    data object Idle : BackendIdentityCheck

    data object Running : BackendIdentityCheck

    /** O uid devolvido pelo servidor é o mesmo que o do Firebase local. */
    data class Verified(val uid: String) : BackendIdentityCheck

    /** O servidor concluiu outra identidade — sinal de configuração errada, nunca silenciado. */
    data class Diverged(val serverUid: String) : BackendIdentityCheck

    data object Unauthenticated : BackendIdentityCheck

    /** Backend fora do ar ou incapaz de verificar. **Não** significa sessão perdida. */
    data object Unavailable : BackendIdentityCheck

    data object NotConfigured : BackendIdentityCheck

    data class Failed(val status: Int?) : BackendIdentityCheck
}
