package com.example.domain.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * A fronteira da identidade online.
 *
 * É o único caminho do Spark para autenticação. `FirebaseAuth`, `CredentialManager` e
 * `GoogleIdTokenCredential` vivem atrás dela e não aparecem em tela, ViewModel ou caso de uso —
 * o mesmo desenho do `AiCoachGateway` (ARCHITECTURE §15.2).
 *
 * ```text
 * UI -> AccountViewModel -> AuthGateway -> FirebaseAuth + Credential Manager
 * ```
 *
 * ## O que esta fronteira não faz
 *
 * Entrar e sair da conta **não tocam em dado local**. Não existe DAO, repositório, `SettingsManager`
 * ou banco atrás desta interface — nem por dependência, nem por chamada. É por construção, e não
 * por disciplina, que um login não sobe treino, um logout não apaga histórico e uma troca de conta
 * não reassocia nada (T16.1; a associação entre dado local e conta é assunto da T16.3+).
 */
interface AuthGateway {

    /**
     * O estado observável da conta.
     *
     * Reflete o `FirebaseAuth` real: uma sessão já existente aparece como
     * [AuthState.SignedIn] sozinha, **sem** abrir seletor de contas. Restaurar sessão é
     * automático; iniciar uma autenticação nova exige [signIn].
     */
    val state: StateFlow<AuthState>

    /** `false` quando falta configuração (Firebase Auth ou Web Client ID) para autenticar. */
    val isSignInAvailable: Boolean

    /**
     * Inicia a autenticação com Google. Só deve ser chamado a partir de ação explícita do usuário.
     *
     * Um fluxo por vez: enquanto houver um em andamento, chamadas novas devolvem
     * [AuthOutcome.AlreadyInProgress] sem abrir uma segunda requisição ao Credential Manager.
     *
     * @param host contexto de UI (a Activity), exigido pelo Credential Manager para exibir o
     * seletor de contas.
     */
    suspend fun signIn(host: android.content.Context): AuthOutcome

    /**
     * Desconecta a identidade online.
     *
     * Faz o `signOut` do Firebase **e** limpa o estado de credencial do Credential Manager, como
     * a documentação atual recomenda. Não apaga Room, DataStore, treinos, sessões, histórico nem
     * gamificação: sair da conta é desconectar identidade, não apagar dados.
     */
    suspend fun signOut()
}

/** O desfecho de uma tentativa de autenticação. */
sealed interface AuthOutcome {

    data class Success(val account: SparkAccount) : AuthOutcome

    /**
     * O usuário fechou o seletor de contas.
     *
     * Não é falha: o estado final é [AuthState.SignedOut] e a UI não mostra erro.
     */
    data object Cancelled : AuthOutcome

    /** Já havia um fluxo em andamento; nenhuma segunda requisição foi aberta. */
    data object AlreadyInProgress : AuthOutcome

    data class Failure(val error: AuthError) : AuthOutcome
}
