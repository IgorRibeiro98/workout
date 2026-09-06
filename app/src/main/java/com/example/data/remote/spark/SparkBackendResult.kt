package com.example.data.remote.spark

/**
 * O desfecho de uma chamada ao Spark Backend.
 *
 * Modelado para que o app **nunca** confunda "o servidor não respondeu" com "você não está
 * logado". Indisponibilidade do backend significa serviço online temporariamente indisponível —
 * não identidade apagada (ARCHITECTURE §17). Quem decide se há conta é o Firebase Auth local.
 */
sealed interface SparkBackendResult<out T> {

    data class Success<T>(val value: T) : SparkBackendResult<T>

    /** Não há endereço de backend neste build. Nenhuma requisição foi feita. */
    data object NotConfigured : SparkBackendResult<Nothing>

    /** Não havia token para enviar, ou o servidor recusou o token (401). */
    data object Unauthenticated : SparkBackendResult<Nothing>

    /** Sem rede, servidor fora do ar ou incapaz de verificar (5xx). Recuperável, tente depois. */
    data object Unavailable : SparkBackendResult<Nothing>

    /** O servidor respondeu algo que o app não sabe usar. [status] é `null` para corpo inválido. */
    data class Failure(val status: Int?) : SparkBackendResult<Nothing>
}

/**
 * O desfecho cru de uma requisição ao Spark Backend, com o status HTTP preservado.
 *
 * Existe ao lado de [SparkBackendResult], e não no lugar dele: a verificação de identidade
 * (T16.1) só precisa saber "deu, não deu, ou não dá para saber", enquanto o Coach (T16.2)
 * precisa distinguir 401 de 429 e de 504 para dizer coisas diferentes ao usuário. Um tipo só
 * teria que escolher entre esconder informação de um ou vazar detalhe HTTP para o outro.
 *
 * Nenhuma variante carrega header: `Authorization` não sai da fronteira de transporte.
 */
sealed interface SparkHttpOutcome {

    /** O servidor respondeu. [code] e [body] são crus — quem interpreta é quem chamou. */
    data class Response(val code: Int, val body: String) : SparkHttpOutcome

    /** Não há endereço de backend neste build. Nenhuma requisição foi feita. */
    data object NotConfigured : SparkHttpOutcome

    /** Não havia conta conectada: a requisição autenticada não chegou a sair. */
    data object SignedOut : SparkHttpOutcome

    /** Sem rede ou servidor inalcançável. Recuperável — e o núcleo do Spark não muda. */
    data object NetworkFailure : SparkHttpOutcome
}
