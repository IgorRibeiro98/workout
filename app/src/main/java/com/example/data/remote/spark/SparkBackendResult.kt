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

/**
 * O desfecho de um download que escreve em arquivo (T16.5).
 *
 * Separado de [SparkHttpOutcome] porque um download bem-sucedido **não tem corpo em memória** para
 * devolver: o que ele produz é um arquivo e um tamanho. Colapsar os dois tipos obrigaria o caso de
 * sucesso a carregar uma `String` que ninguém quer.
 */
sealed interface SparkDownloadOutcome {

    /** O corpo inteiro foi escrito no arquivo. [bytes] é o que foi realmente gravado. */
    data class Downloaded(val bytes: Long) : SparkDownloadOutcome

    /** Não há endereço de backend neste build. Nenhuma requisição foi feita. */
    data object NotConfigured : SparkDownloadOutcome

    /** Não havia conta conectada: a requisição autenticada não chegou a sair. */
    data object SignedOut : SparkDownloadOutcome

    /** Sem rede ou conexão interrompida. O arquivo parcial foi apagado. */
    data object NetworkFailure : SparkDownloadOutcome

    /** O corpo passou do teto do cliente e a escrita foi abortada no meio. */
    data object TooLarge : SparkDownloadOutcome

    /** O servidor respondeu erro. [body] é o envelope pequeno, de onde sai o `code`. */
    data class Rejected(val code: Int, val body: String) : SparkDownloadOutcome
}

/**
 * O desfecho de um download que devolve os **bytes em memória** (T17.9 §49).
 *
 * Separado de [SparkDownloadOutcome] porque a foto de um check-in não pode encostar no disco: §56
 * pede que a mídia social autenticada não tenha cache persistente compartilhado entre contas, e o
 * jeito mais seguro de garantir isso é ela nunca ser escrita. Ela é baixada, decodificada,
 * desenhada e descartada — e o que sobrevive é um cache **em memória**, com escopo de conta.
 *
 * As imagens são pequenas por construção: o servidor as reduz para 1600 px na maior aresta e
 * recusa acima de 1,5 MB (§18/§19), então um buffer em memória é a escolha barata e não um risco.
 */
sealed interface SparkBytesOutcome {

    data class Downloaded(val bytes: ByteArray) : SparkBytesOutcome {
        // `ByteArray` em `data class` compara por referência: declarar os dois explicitamente evita
        // um `equals` que mente. Ninguém compara estes valores hoje; o dia em que alguém comparar,
        // a resposta vai estar certa.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Downloaded && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Não há endereço de backend neste build. Nenhuma requisição foi feita. */
    data object NotConfigured : SparkBytesOutcome

    /** Não havia conta conectada: a requisição autenticada não chegou a sair. */
    data object SignedOut : SparkBytesOutcome

    /** Sem rede ou conexão interrompida. */
    data object NetworkFailure : SparkBytesOutcome

    /** O corpo passou do teto do cliente e a leitura foi abortada no meio. */
    data object TooLarge : SparkBytesOutcome

    /** O servidor respondeu erro. [body] é o envelope pequeno, de onde sai o `code`. */
    data class Rejected(val code: Int, val body: String) : SparkBytesOutcome
}
