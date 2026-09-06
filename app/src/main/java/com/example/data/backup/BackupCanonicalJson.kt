package com.example.data.backup

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A forma canônica de um documento JSON, e o SHA-256 sobre ela (T16.4).
 *
 * O espelho TypeScript é `backend/src/modules/backup/canonical-json.ts`, e a definição está em
 * `contracts/backup/v1/README.md` §7:
 *
 * 1. nenhum espaço insignificante;
 * 2. membros de objeto ordenados pelo **token** da chave (unidade de código UTF-16);
 * 3. arrays na ordem em que estão — inclusive `items`;
 * 4. todo token escalar copiado verbatim;
 * 5. chave repetida no mesmo objeto é erro (impossível em [JsonObject], que é um mapa).
 *
 * ## Por que a regra 4 é a que importa
 *
 * `Float.toString()` do Kotlin e `JSON.stringify` do JavaScript não formatam o mesmo número do
 * mesmo jeito — um serializa `Float`, o outro `double`. Se cada lado reserializasse a partir do
 * valor, uma carga de `60.0 kg` viraria dois textos e dois hashes, e a idempotência do backup
 * passaria a depender de os dois imitarem o formatador do outro.
 *
 * Copiando o token, o Android decide como escreve o número, e o servidor **reproduz** o que
 * recebeu. Os dois chegam ao mesmo hash sem que nenhum precise conhecer o outro.
 *
 * ## O texto canônico é o corpo enviado
 *
 * Uma função só produz o que é hasheado e o que é enviado. Se fossem duas, elas divergiriam — e a
 * divergência apareceria como conflito de idempotência num aparelho de usuário, não em teste.
 */
object BackupCanonicalJson {

    /** O texto canônico de [element]. */
    fun canonicalize(element: JsonElement): String = buildString { write(element, this) }

    /** SHA-256 hexadecimal minúsculo do texto, em UTF-8. */
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** O par (texto canônico, hash) — a combinação usada em todo lugar. */
    fun canonicalHash(element: JsonElement): CanonicalDocument {
        val text = canonicalize(element)
        return CanonicalDocument(text = text, hash = sha256(text))
    }

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")

            is JsonPrimitive ->
                // `content` de um literal não-string é o token original — `60.0` continua `60.0`,
                // e não vira `60`. É a regra 4, e é ela que faz o hash fechar com o servidor.
                if (element.isString) out.append(escape(element.content)) else out.append(element.content)

            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }

            is JsonObject -> {
                // Ordenar pelo token com aspas, e não pela chave nua, é o que casa exatamente com
                // o servidor — que ordena pelo texto que leu, sem decodificar escape nenhum.
                val members = element.entries
                    .map { (key, value) -> escape(key) to value }
                    .sortedBy { it.first }
                out.append('{')
                members.forEachIndexed { index, (keyToken, value) ->
                    if (index > 0) out.append(',')
                    out.append(keyToken).append(':')
                    write(value, out)
                }
                out.append('}')
            }
        }
    }

    /**
     * O token de uma string JSON.
     *
     * Escapa o mínimo exigido pelo JSON — aspas, contrabarra e controles. Caractere não-ASCII sai
     * cru, em UTF-8: o servidor copia o token verbatim, então a escolha de escape do Android é a
     * canônica, e "café" não precisa virar "café" para atravessar.
     */
    private fun escape(value: String): String = buildString {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char == '\b' -> append("\\b")
                char == '\u000C' -> append("\\f")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}

/** O texto canônico e o hash dele. */
data class CanonicalDocument(val text: String, val hash: String)
