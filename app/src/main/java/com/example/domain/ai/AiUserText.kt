package com.example.domain.ai

/**
 * Texto escrito pelo usuário, preparado para atravessar a fronteira como **dado**.
 *
 * O que existia em `AiCoachPrompt` e continua fazendo sentido no app depois da T16.2: o prompt
 * virou responsabilidade do Spark Backend, mas sanear a entrada é da fronteira que a coleta.
 *
 * Remove caracteres de controle — que serviriam para simular quebra de bloco ou marcação de papel
 * na serialização — e comprime espaço em branco, preservando o conteúdo legível. Não tenta
 * "detectar injeção" por palavra-chave: filtro de conteúdo não é garantia, e a garantia está nos
 * validadores, no servidor e aqui.
 */
object AiUserText {

    fun sanitize(raw: String?, maxLength: Int): String? = raw
        ?.filter { it == ' ' || !it.isISOControl() }
        ?.replace(WHITESPACE_RUN, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(maxLength)

    private val WHITESPACE_RUN = Regex("\\s+")
}
