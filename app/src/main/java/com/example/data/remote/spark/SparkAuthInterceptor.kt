package com.example.data.remote.spark

import com.example.domain.auth.AuthError
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 *
 * ## Duas proteções que o interceptor precisa ter, e não tinha
 *
 * **Teto de tempo.** `runBlocking` aqui bloqueia uma thread de I/O do OkHttp **dentro** de
 * `execute()`, e quem chama está segurando o `Mutex` do sync ou a `CloudOperationLock`. Um provedor
 * de token que nunca responde — Firebase sem rede, com DNS pendurado — prendia as duas travas até o
 * processo morrer, e nem backup nem sync voltavam a rodar naquela sessão. O timeout transforma isso
 * em uma falha de rede comum, que todo chamador já sabe tratar.
 *
 * **Um 401 merece uma segunda tentativa.** Um token em cache pode ter sido revogado, ou o relógio
 * do aparelho pode ter andado o suficiente para o servidor recusá-lo. Sem renovar, o 401 virava
 * `AuthRequired` e a tela pedia login a quem já estava logado. Uma renovação forçada resolve o caso
 * comum; se ela também for recusada, aí sim o 401 é verdade.
 */
class SparkAuthInterceptor(
    private val tokens: AuthTokenProvider,
    private val tokenTimeoutMillis: Long = TOKEN_TIMEOUT_MILLIS
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(authorized(chain, forceRefresh = false))
        if (response.code != HTTP_UNAUTHORIZED) return response

        // **Uma** renovação, e nunca mais. O retry é disparado pelo código da resposta, então
        // repeti-lo sem limite seria um laço contra um servidor que já disse não — e cada volta
        // custaria uma chamada ao Firebase. A segunda resposta é a resposta, seja ela qual for.
        val refreshed = try {
            authorized(chain, forceRefresh = true)
        } catch (e: IOException) {
            // Sem token novo não há o que tentar de novo: o 401 original é o resultado honesto, e
            // descartá-lo por uma exceção esconderia do chamador o que o servidor respondeu.
            return response
        }

        // O corpo da primeira resposta é fechado antes de a segunda sair: uma resposta não
        // consumida segura a conexão no pool até o coletor de lixo passar.
        response.close()
        return chain.proceed(refreshed)
    }

    private fun authorized(chain: Interceptor.Chain, forceRefresh: Boolean): okhttp3.Request {
        // Interceptor do OkHttp é síncrono por contrato; o provider é suspenso.
        val result = try {
            runBlocking { withTimeout(tokenTimeoutMillis) { tokens.currentToken(forceRefresh) } }
        } catch (e: TimeoutCancellationException) {
            // Falha de transporte, e não sessão ausente: o `SparkBackendClient` mapeia
            // `IOException` para indisponibilidade recuperável, que é o que "o Firebase não
            // respondeu a tempo" realmente é. Tratar como `SignedOut` faria a tela dizer que a
            // pessoa não está logada — e ela está.
            throw AuthTokenTimeoutException()
        }

        val token = when (result) {
            is AuthTokenResult.Token -> result.value
            is AuthTokenResult.SignedOut -> throw MissingAuthTokenException(null)
            is AuthTokenResult.Failure -> throw MissingAuthTokenException(result.error)
        }

        return chain.request().newBuilder()
            .header(AUTHORIZATION, "$BEARER_PREFIX$token")
            .build()
    }

    companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "

        const val HTTP_UNAUTHORIZED = 401

        /**
         * Quanto se espera por um ID Token antes de desistir.
         *
         * Generoso o bastante para uma renovação real com rede ruim (a chamada do Firebase faz I/O
         * de verdade) e curto o bastante para não prender as travas de nuvem por mais tempo do que
         * qualquer chamada HTTP do app levaria de qualquer jeito.
         */
        const val TOKEN_TIMEOUT_MILLIS = 15_000L
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

/**
 * O provedor de token não respondeu dentro do teto.
 *
 * Separada de [MissingAuthTokenException] de propósito: aquela significa "não há sessão", e o app
 * a traduz como "entre na sua conta". Esta significa "não deu para perguntar agora", que é
 * indisponibilidade — e é assim que o cliente a trata, por ser uma `IOException` comum.
 */
class AuthTokenTimeoutException :
    IOException("Spark Backend: token não obtido dentro do tempo limite")
