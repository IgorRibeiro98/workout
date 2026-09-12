package com.example.domain.engine

import android.content.Context

/**
 * Lê o `contentVersion` de um manifesto de catálogo **sem** abrir o arquivo inteiro.
 *
 * ## Por que isto existe
 *
 * Os dois importadores rodam a cada abertura do app e a primeira coisa que faziam era ler e
 * parsear o asset **completo** — 392 KB no catálogo base e 2,07 MB no manifesto premium, este
 * último via `org.json`, que materializa a árvore toda em memória — só para depois comparar a
 * versão e, na esmagadora maioria das aberturas, descartar tudo. Eram ~2,4 MB de JSON lidos,
 * decodificados e coletados pelo GC em todo cold start, competindo com a primeira renderização
 * num aparelho modesto.
 *
 * A versão fica no cabeçalho dos dois arquivos (`"contentVersion"` aparece nas primeiras linhas),
 * então ler o começo do asset responde a pergunta. Quando a resposta é "já instalada", o arquivo
 * grande nunca é aberto.
 *
 * O caminho completo continua intacto: se a versão for nova, ausente ou ilegível, cai-se na
 * leitura e validação de sempre. Este atalho só pode **pular** trabalho, nunca decidir importar.
 */
internal object CatalogAssetVersion {

    /**
     * Quanto do início do asset é lido. Generoso de propósito: o campo aparece antes dos 100
     * primeiros bytes nos manifestos atuais, e 8 KB continua sendo três ordens de grandeza menos
     * do que abrir o arquivo.
     */
    private const val HEAD_BYTES = 8 * 1024

    private val CONTENT_VERSION = Regex("\"contentVersion\"\\s*:\\s*(\\d+)")

    /**
     * O `contentVersion` declarado no cabeçalho do asset, ou `null` quando ele não está no trecho
     * lido, o arquivo não existe ou o texto não casa. `null` significa "não sei" — e quem não sabe
     * segue pelo caminho completo.
     */
    fun peek(context: Context, assetPath: String): Int? = runCatching {
        context.assets.open(assetPath).use { stream ->
            val buffer = ByteArray(HEAD_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) return@use null
            // O campo é ASCII e vive no cabeçalho; um corte no meio de um caractere multibyte mais
            // adiante no buffer não afeta a busca.
            val head = String(buffer, 0, read, Charsets.UTF_8)
            CONTENT_VERSION.find(head)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }.getOrNull()
}
