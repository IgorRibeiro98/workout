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
