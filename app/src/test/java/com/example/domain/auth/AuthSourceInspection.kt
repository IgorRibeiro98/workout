package com.example.domain.auth

import java.io.File

/**
 * Leitura de código-fonte para os testes de arquitetura da conta.
 *
 * Os invariantes da T16.1 ("a fronteira de autenticação não conhece Room", "Firebase Auth não
 * aparece fora de `data/auth`") são sobre **código**. Documentação que explica justamente esses
 * limites cita os mesmos nomes — e uma verificação que lesse o arquivo cru acusaria o comentário
 * que descreve a regra como se fosse a violação dela.
 */
object AuthSourceInspection {

    private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")

    /** `//` que não faz parte de um `http://`, `https://` etc. */
    private val LINE_COMMENT = Regex("""(?<!:)//.*""")

    /** O conteúdo do arquivo sem comentários. */
    fun code(file: File): String =
        file.readText().replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    /** Todos os `.kt` sob [relative], resolvendo tanto a partir de `app/` quanto do módulo. */
    fun sources(relative: String): List<File> {
        val root = File(relative).takeIf { it.isDirectory }
            ?: File(relative.removePrefix("app/")).takeIf { it.isDirectory }
            ?: File("app/$relative").takeIf { it.isDirectory }
            ?: return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
