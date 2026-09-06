package com.example.domain.auth

/**
 * Fornece um Firebase ID Token válido sob demanda, para requisições autenticadas.
 *
 * ## O token não é persistido
 *
 * O ciclo de vida da sessão pertence ao Firebase Authentication. O Spark **não** guarda o ID Token
 * em Room, DataStore, `SharedPreferences`, arquivo ou banco, e **não** emite refresh token, JWT
 * próprio ou cookie de sessão: o backend é verificador de token, nunca emissor
 * (`docs/architecture/identity-contract.md`).
 *
 * Cada requisição pede um token aqui, na hora. O SDK renova quando precisa.
 */
interface AuthTokenProvider {

    /**
     * @param forceRefresh ignora o token em cache do SDK e busca um novo. Use apenas depois de um
     * 401 do servidor — não a cada requisição.
     */
    suspend fun currentToken(forceRefresh: Boolean = false): AuthTokenResult
}

/** O resultado de pedir um token. */
sealed interface AuthTokenResult {

    /**
     * Um ID Token válido.
     *
     * `toString` é sobrescrito: um `data class` imprimiria o token inteiro no primeiro log,
     * `Log.d(TAG, "$result")` ou mensagem de exceção que o encostasse.
     */
    class Token(val value: String) : AuthTokenResult {
        override fun toString(): String = "AuthTokenResult.Token(***)"
    }

    /** Não há conta conectada. Nenhuma requisição autenticada deve ser feita. */
    data object SignedOut : AuthTokenResult

    /** Havia conta, mas não foi possível obter um token agora (rede, provider). Recuperável. */
    data class Failure(val error: AuthError) : AuthTokenResult
}
