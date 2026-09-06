package com.example.data.remote.spark

import com.example.domain.auth.AuthError
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Anexa o Firebase ID Token a toda requisição autenticada ao Spark Backend.
 *
 * Existe para que nenhum endpoint futuro (T16.2+) precise se lembrar de montar o header. Uma
 * requisição protegida sai daqui — sempre — como:
 *
 * ```text
 * Authorization: Bearer <Firebase ID Token>
 * ```
 *
 * O token é pedido ao [AuthTokenProvider] na hora e usado na hora: não é guardado em campo, em
 * cache próprio, em disco, e não aparece em log. Quando não há token, a requisição **não sai**:
 * mandar uma chamada protegida sem credencial só renderia um 401 previsível.
 */
class SparkAuthInterceptor(
    private val tokens: AuthTokenProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Interceptor do OkHttp é síncrono por contrato; o provider é suspenso.
        val result = runBlocking { tokens.currentToken() }

        val token = when (result) {
            is AuthTokenResult.Token -> result.value
            is AuthTokenResult.SignedOut -> throw MissingAuthTokenException(null)
            is AuthTokenResult.Failure -> throw MissingAuthTokenException(result.error)
        }

        val authorized = chain.request().newBuilder()
            .header(AUTHORIZATION, "$BEARER_PREFIX$token")
            .build()

        return chain.proceed(authorized)
    }

    companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}

/**
 * Não foi possível obter um token para uma requisição autenticada.
 *
 * A mensagem é fixa: mensagem de exceção acaba em log, e uma que carregasse o token o vazaria.
 */
class MissingAuthTokenException(
    val error: AuthError?
) : IOException("Spark Backend: requisição autenticada sem token disponível")
