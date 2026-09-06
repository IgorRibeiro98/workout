package com.example.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * O contrato de backup, do lado Kotlin (T16.4).
 *
 * ## Por que este teste existe
 *
 * O formato do backup é escrito em duas linguagens. Se cada lado tivesse a própria definição, elas
 * divergiriam — e a divergência apareceria como conflito de idempotência no aparelho de um
 * usuário, não em teste.
 *
 * Aqui o Kotlin lê **as mesmas fixtures** que o backend lê e chega **aos mesmos hashes**, com sua
 * própria implementação da forma canônica. Os números abaixo estão escritos, não derivados: se a
 * canonicalização de um dos lados mudar, os dois testes quebram juntos.
 */
class BackupContractFixturesTest {

    private val json = Json

    // ------------------------------------------------------------------ forma canônica

    @Test
    fun `a forma canonica ignora espaco e ordem de chave`() {
        val a = json.parseToJsonElement("""{"b":1,"a":{"y":true,"x":null}}""")
        val b = json.parseToJsonElement("  {\n \"a\" : { \"x\" : null , \"y\" : true } ,\n \"b\" : 1 }  ")

        assertEquals("""{"a":{"x":null,"y":true},"b":1}""", BackupCanonicalJson.canonicalize(a))
        assertEquals(
            BackupCanonicalJson.canonicalize(a),
            BackupCanonicalJson.canonicalize(b)
        )
    }

    @Test
    fun `a forma canonica preserva a ordem dos arrays`() {
        // A ordem dos exercícios de um treino é dado de domínio, não detalhe de serialização.
        val ordered = buildJsonArray {
            add(JsonPrimitive(3)); add(JsonPrimitive(1)); add(JsonPrimitive(2))
        }
        assertEquals("[3,1,2]", BackupCanonicalJson.canonicalize(ordered))
    }

    @Test
    fun `a forma canonica copia o token do numero verbatim`() {
        // É esta cópia literal que faz Kotlin e TypeScript fecharem o mesmo hash sem que um
        // precise imitar o formatador de ponto flutuante do outro.
        val withFloat = json.parseToJsonElement("""{"w":60.0}""")
        assertEquals("""{"w":60.0}""", BackupCanonicalJson.canonicalize(withFloat))

        val encoded = buildJsonObject { put("w", JsonPrimitive(60.0f)) }
        assertEquals("""{"w":60.0}""", BackupCanonicalJson.canonicalize(encoded))

        // E, portanto, formas diferentes do mesmo valor não colidem: o hash descreve o texto.
        assertNotEquals(
            BackupCanonicalJson.canonicalize(json.parseToJsonElement("""{"w":60}""")),
            BackupCanonicalJson.canonicalize(withFloat)
        )
    }

    @Test
    fun `a forma canonica escapa o minimo e mantem acento cru`() {
        val element = buildJsonObject {
            put("n", JsonPrimitive("café \"forte\"\n"))
        }
        assertEquals("""{"n":"café \"forte\"\n"}""", BackupCanonicalJson.canonicalize(element))
    }

    @Test
    fun `o hash e o SHA-256 da forma canonica`() {
        // Valor de referência conhecido: SHA-256 de "{}" .
        assertEquals(
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
            BackupCanonicalJson.sha256("{}")
        )
    }

    // ------------------------------------------------------------ fixtures compartilhadas

    @Test
    fun `o hash das fixtures e o mesmo que o backend calcula`() {
        // Estes hashes são o contrato. `backend/test/backup-contract.spec.ts` fixa exatamente os
        // mesmos valores, calculados pela implementação TypeScript da mesma forma canônica.
        assertEquals(
            "4caa9793e9441f2f23a7874c19bda66b6e11b79dc9ca89c74e5f8bd802dd58fd",
            hashOfFixture("backup-v1-minimal")
        )
        assertEquals(
            "432b0f20b96d3b1ba21a38ba554ff5fde6e0bc04bdca732d352494ff9dac3d9a",
            hashOfFixture("backup-v1-complete")
        )
    }

    @Test
    fun `a fixture completa cobre todos os tipos do registry`() {
        val snapshot = json.decodeFromString(
            BackupSnapshotDto.serializer(),
            BackupContractFixtures.text("backup-v1-complete")
        )

        assertEquals(
            BackupEntityType.entries.map { it.name }.sorted(),
            snapshot.items.map { it.entityType }.sorted()
        )
        assertEquals(BackupContract.SCHEMA_VERSION, snapshot.backupSchemaVersion)
        snapshot.items.forEach { item ->
            val type = BackupEntityType.valueOf(item.entityType)
            assertEquals(type.schemaVersion, item.entitySchemaVersion)
        }
    }

    @Test
    fun `a fixture minima e um envelope valido sem item nenhum`() {
        val snapshot = json.decodeFromString(
            BackupSnapshotDto.serializer(),
            BackupContractFixtures.text("backup-v1-minimal")
        )

        assertEquals(0, snapshot.items.size)
        assertEquals(BackupContract.SCHEMA_VERSION, snapshot.backupSchemaVersion)
    }

    @Test
    fun `o envelope nao tem campo de dono`() {
        // `ownerUid` não existe no contrato: o dono sai do token verificado pelo servidor. Não
        // existir é melhor do que existir e ser ignorado — nenhuma versão futura pode "aproveitar"
        // um campo que nunca esteve lá.
        val text = BackupContractFixtures.text("backup-v1-complete")
        assertEquals(false, text.contains("ownerUid"))

        val serialized = json.encodeToString(
            BackupSnapshotDto.serializer(),
            json.decodeFromString(BackupSnapshotDto.serializer(), text)
        )
        assertEquals(false, serialized.contains("ownerUid"))
    }

    private fun hashOfFixture(name: String): String =
        BackupCanonicalJson.canonicalHash(
            json.parseToJsonElement(BackupContractFixtures.text(name))
        ).hash
}
