package com.example.data.social

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A normalização de `friendCode` do Android, contra a fixture **compartilhada** com o servidor.
 *
 * ## Por que uma fixture, e não um teste escrito à mão de cada lado
 *
 * O Android precisa conhecer o formato para responder na hora: ligar o botão "Procurar" e recusar
 * um QR que não é do Spark sem uma ida ao servidor. E o momento em que dois lados conhecem o mesmo
 * formato é o momento em que eles começam a divergir — alguém muda o alfabeto no servidor, o app
 * continua recusando, e o defeito aparece como "o convite do meu amigo não funciona", sem nenhum
 * teste ficando vermelho.
 *
 * Este arquivo lê **o mesmo JSON** que `backend/test/friend-code-contract.spec.ts`
 * (`contracts/social/v1/friend-code-normalization.json`). Uma mudança unilateral quebra o teste de
 * quem mudou.
 *
 * **A autoridade continua sendo o servidor**: o app manda o que o usuário digitou, e quem decide
 * se existe um perfil é `POST /v1/social/friends/lookup`.
 */
class FriendCodeContractTest {

    /**
     * A fixture, lida com `kotlinx.serialization`.
     *
     * `org.json` **não** serve aqui: no teste unitário do Android ele é um stub que lança
     * "not mocked", e usá-lo exigiria Robolectric — um emulador de framework para ler um arquivo
     * de texto. `kotlinx.serialization` já é dependência do app e roda em JVM pura.
     */
    private fun fixture(): JsonObject {
        val relative = "contracts/social/v1/friend-code-normalization.json"
        val file = File(relative).takeIf { it.isFile }
            ?: File("../$relative").takeIf { it.isFile }
            ?: error("fixture compartilhada não encontrada: $relative")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun `a fixture descreve o formato que o app realmente usa`() {
        // Se isto falhar, os casos abaixo estariam provando algo sobre um formato que não existe.
        val canonical = fixture().getValue("canonical").jsonObject
        assertEquals(
            FriendshipContract.FRIEND_CODE_PREFIX,
            canonical.getValue("prefix").jsonPrimitive.content
        )
        assertEquals(
            FriendshipContract.FRIEND_CODE_SEPARATOR,
            canonical.getValue("separator").jsonPrimitive.content
        )
        assertEquals(
            FriendshipContract.FRIEND_CODE_ALPHABET,
            canonical.getValue("alphabet").jsonPrimitive.content
        )
        assertEquals(
            FriendshipContract.FRIEND_CODE_RANDOM_LENGTH,
            canonical.getValue("randomLength").jsonPrimitive.content.toInt()
        )
    }

    @Test
    fun `todos os casos compartilhados normalizam igual ao servidor`() {
        val cases = fixture().getValue("cases").jsonArray
        assertTrue("a fixture está vazia", cases.size >= 15)

        var accepted = 0
        var refused = 0
        for (element in cases) {
            val case = element.jsonObject
            val input = case.getValue("input").jsonPrimitive.content
            val normalized = case.getValue("normalized")
            val expected = if (normalized == JsonNull) null else normalized.jsonPrimitive.content
            val why = case.getValue("why").jsonPrimitive.content

            assertEquals(
                "normalizeFriendCode(\"$input\") — $why",
                expected,
                FriendshipContract.normalizeFriendCode(input)
            )
            if (expected == null) refused += 1 else accepted += 1
        }

        // A fixture precisa exercitar os dois lados: uma que só recusasse passaria com uma função
        // que devolve `null` sempre.
        assertTrue("a fixture não cobre aceitação", accepted > 0)
        assertTrue("a fixture não cobre recusa", refused > 0)
    }

    @Test
    fun `os ambiguos nao sao corrigidos para um vizinho`() {
        // Recusar é a resposta certa: adivinhar que `O` era `0` inventaria o código de outra
        // pessoa. O alfabeto existe para que essa dúvida nunca apareça num código gerado.
        for (ambiguous in listOf('O', '0', 'I', '1', 'L')) {
            assertFalse(ambiguous in FriendshipContract.FRIEND_CODE_ALPHABET)
            assertNull(FriendshipContract.normalizeFriendCode("SPK-7K2P9D8$ambiguous"))
        }
    }

    @Test
    fun `a normalizacao nao depende do idioma do aparelho`() {
        val original = java.util.Locale.getDefault()
        try {
            // Em turco, `i` maiúsculo vira `İ`. Um app cuja normalização dependesse do locale
            // recusaria o código do amigo dependendo de quem está segurando o telefone.
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            assertEquals("SPK-7K2P9D8Q", FriendshipContract.normalizeFriendCode("spk-7k2p9d8q"))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
