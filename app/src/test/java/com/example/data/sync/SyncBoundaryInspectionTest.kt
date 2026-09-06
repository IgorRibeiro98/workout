package com.example.data.sync

import com.example.domain.auth.AuthSourceInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provas estruturais dos limites da T16.3.
 *
 * Comportamento não cobre estas: um `outbox.insert(...)` dentro de um `onClick` funcionaria em
 * teste e ainda assim seria exatamente o defeito que a arquitetura proíbe. Estes testes olham o
 * código-fonte.
 */
class SyncBoundaryInspectionTest {

    @Test
    fun `a UI nao conhece a Outbox`() {
        // A tela chama caso de uso/ViewModel, que chama repositório, que abre a transação. Se
        // Compose puder inserir na Outbox, mais cedo ou mais tarde alguém grava a intenção sem a
        // alteração — ou a alteração sem a intenção.
        assertNoneReference(
            listOf("app/src/main/java/com/example/presentation", "app/src/main/java/com/example/ui"),
            listOf("SyncOutbox", "syncOutboxDao", "SyncMutationCoordinator", "SyncEntityType", "SyncOperation")
        )
    }

    @Test
    fun `a Outbox nao e consumida por rede, worker ou agendador`() {
        // T16.3 é fundação: persistir e registrar. Push, ack, retry e worker são T16.4/T16.6 — e
        // introduzi-los aqui criaria envio sem ownership remoto definido.
        val forbidden = listOf(
            "WorkManager",
            "androidx.work",
            "OkHttp",
            "Retrofit",
            "HttpURLConnection",
            "SparkBackendClient"
        )
        assertNoneReference(listOf("app/src/main/java/com/example/data/sync"), forbidden)
    }

    @Test
    fun `nenhum WorkManager de sync foi introduzido no app`() {
        val offenders = AuthSourceInspection.sources("app/src/main/java/com/example")
            .filter { file ->
                val code = AuthSourceInspection.code(file)
                code.contains("androidx.work") || code.contains("WorkManager")
            }
        assertEquals("nenhum WorkManager deveria existir no Spark: ${offenders.map { it.name }}", emptyList<String>(), offenders.map { it.name })
    }

    private fun assertNoneReference(paths: List<String>, forbidden: List<String>) {
        val files = paths.flatMap { AuthSourceInspection.sources(it) }
        assertTrue("nenhum arquivo encontrado em $paths", files.isNotEmpty())
        val offenders = files.filter { file ->
            val code = AuthSourceInspection.code(file)
            forbidden.any { code.contains(it) }
        }
        assertEquals(
            "$paths encostou na Outbox: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }
}
