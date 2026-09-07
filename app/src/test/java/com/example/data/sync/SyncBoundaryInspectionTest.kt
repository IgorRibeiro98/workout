package com.example.data.sync

import com.example.domain.auth.AuthSourceInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provas estruturais dos limites da sincronização (T16.3 → T16.6).
 *
 * Comportamento não cobre estas. Um `outbox.insert(...)` dentro de um `onClick` funcionaria em
 * teste e ainda assim seria exatamente o defeito que a arquitetura proíbe. Um
 * `PeriodicWorkRequest` de cinco minutos passaria em toda suíte e drenaria a bateria de todo
 * usuário. Estes testes olham o **código-fonte**.
 *
 * ## O que mudou na T16.6
 *
 * A T16.3 exigia que `data/sync` **não** conhecesse rede e que o app **não** tivesse WorkManager —
 * porque não havia consumidor da Outbox, e um transporte ali seria envio sem ownership definido.
 * Agora existe o consumidor, e as regras passam a descrever a forma que ele tem de ter: uma
 * fronteira HTTP declarada, um trabalho único com rede, e nada periódico.
 */
class SyncBoundaryInspectionTest {

    private val syncPackage = "app/src/main/java/com/example/data/sync"
    private val uiPackages = listOf(
        "app/src/main/java/com/example/presentation",
        "app/src/main/java/com/example/ui"
    )

    // ------------------------------------------------------------------ a UI não conhece o sync

    @Test
    fun `a UI nao conhece a Outbox nem o mecanismo de sync`() {
        // A tela chama caso de uso/ViewModel, que chama repositório, que abre a transação. Se
        // Compose puder inserir na Outbox ou aplicar mudança remota, mais cedo ou mais tarde
        // alguém grava a intenção sem a alteração — ou escreve por cima de dado sujo.
        assertNoneReference(
            uiPackages,
            listOf(
                "SyncOutbox",
                "syncOutboxDao",
                "SyncMutationCoordinator",
                "SyncEntityType",
                "SyncOperation",
                "SyncRemoteApplier",
                "SyncPushBuilder",
                "SyncCursorDao",
                "SyncConflictDao",
                "EntitySyncMetadata"
            )
        )
    }

    @Test
    fun `a UI nao fala o jargao do protocolo`() {
        // "cursor=1842" e "revision mismatch" não são texto de tela. O que o usuário lê é
        // "Atualizado", "3 alterações aguardando envio" e "1 item precisa de atenção".
        val offenders = uiPackages
            .flatMap { AuthSourceInspection.sources(it) }
            .filter { file ->
                val code = AuthSourceInspection.code(file)
                listOf("baseRevision", "serverSequence", "lastPulledServerSequence")
                    .any { code.contains(it) }
            }
        assertEquals(
            "vocabulário de protocolo vazou para a UI: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }

    // ------------------------------------------------------------------ origem REMOTE_SYNC

    @Test
    fun `o apply remoto nao passa pelo coordenador de mutacoes`() {
        // Se ele passasse, aplicar 40 mudanças remotas registraria 40 `UPSERT` na Outbox e o
        // aparelho devolveria ao servidor o que acabou de receber dele: um laço que nunca fecha.
        val applier = AuthSourceInspection.sources(syncPackage)
            .single { it.name == "SyncRemoteApplier.kt" }
        val code = AuthSourceInspection.code(applier)

        assertFalse(
            "o apply remoto não pode registrar mutação de saída",
            code.contains("syncMutations") || code.contains("coordinator.mutate")
        )
    }

    @Test
    fun `o sync nao dispara efeito colateral de dominio`() {
        // Receber uma sessão concluída de outro aparelho não é executá-la: sem XP, sem conquista,
        // sem recorde, sem notificação. O jeito de garantir isso é não ter por onde.
        assertNoneReference(
            listOf(syncPackage),
            listOf(
                "WorkoutEngine",
                "GamificationEventPublisher",
                "XpCalculatorService",
                "AchievementRepository",
                "WorkoutNotificationManager"
            )
        )
    }

    // ------------------------------------------------------------------ agendamento

    @Test
    fun `o pacote de sync nao conhece WorkManager`() {
        // O agendamento mora em `service/`, atrás da interface `SyncScheduler`. Assim o
        // coordenador é testável sem subir o WorkManager, e a decisão de agendar fica em um
        // arquivo só.
        assertNoneReference(listOf(syncPackage), listOf("androidx.work", "WorkManager"))
    }

    @Test
    fun `nao existe polling nem trabalho periodico no app`() {
        // Repetição é o que a regra proíbe, e não agendamento em si: o timer de descanso agenda
        // **um** alarme exato para um instante que o usuário viu na tela, e isso continua certo.
        // O que não pode existir é algo que acorde sozinho de tempos em tempos para perguntar ao
        // servidor se mudou alguma coisa.
        val offenders = AuthSourceInspection.sources("app/src/main/java/com/example")
            .filter { file ->
                val code = AuthSourceInspection.code(file)
                listOf(
                    "PeriodicWorkRequest",
                    "setRepeating",
                    "scheduleAtFixedRate",
                    "setPeriodic",
                    "setInexactRepeating"
                ).any { code.contains(it) }
            }
        assertEquals(
            "o Spark não precisa de tempo real e não pode martelar a VPS: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }

    @Test
    fun `o trabalho de sync e unico, exige rede e tem backoff`() {
        val worker = AuthSourceInspection.sources("app/src/main/java/com/example/service")
            .single { it.name == "SparkSyncWorker.kt" }
        val code = AuthSourceInspection.code(worker)

        // Único: vinte alterações seguidas agendam um trabalho, não vinte.
        assertTrue("o trabalho precisa ser único", code.contains("enqueueUniqueWork"))
        assertTrue("e manter o já agendado", code.contains("ExistingWorkPolicy.KEEP"))
        // Com rede: acordar sem conexão é gastar bateria para descobrir que não dá.
        assertTrue("precisa de restrição de rede", code.contains("NetworkType.CONNECTED"))
        // Com backoff: um servidor fora do ar não é martelado enquanto volta.
        assertTrue("precisa de backoff", code.contains("BackoffPolicy.EXPONENTIAL"))
    }

    // ------------------------------------------------------------------ conflito

    @Test
    fun `o sync nao resolve conflito sozinho`() {
        // T16.6 detecta e preserva; T16.7 resolve. Nenhuma heurística de desempate pode existir
        // aqui — nem por relógio, nem por "o mais novo vence", nem por campo.
        val offenders = AuthSourceInspection.sources(syncPackage)
            .filter { file ->
                val code = AuthSourceInspection.code(file).lowercase()
                listOf("lastwritewins", "last_write_wins", "mergefields", "autoresolve")
                    .any { code.contains(it) }
            }
        assertEquals(
            "resolução automática de conflito não existe na T16.6: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }

    @Test
    fun `o desempate nunca usa relogio de aparelho`() {
        // `updatedAt`/`createdAt` continuam existindo como metadado. O que não pode existir é
        // compará-los para decidir quem vence: relógio de aparelho diverge, e o usuário perderia
        // dado sem receber erro nenhum.
        val offenders = AuthSourceInspection.sources(syncPackage)
            .filter { file ->
                val code = AuthSourceInspection.code(file)
                Regex("""(updatedAt|createdAt|lastSyncedAt)\s*[<>]""").containsMatchIn(code)
            }
        assertEquals(
            "comparação de relógio como árbitro: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }

    // ------------------------------------------------------------------ observabilidade

    @Test
    fun `o pacote de sync nao registra log`() {
        // O payload de um push é o treino, a nota e a medida da pessoa. A regra é a mesma do
        // restore: se algum dia houver log aqui, ele carrega metadata técnica — e a ausência é
        // testada até lá.
        assertNoneReference(listOf(syncPackage), listOf("android.util.Log", "Log.d(", "Log.i(", "println("))
    }

    private fun assertNoneReference(paths: List<String>, forbidden: List<String>) {
        val files = paths.flatMap { AuthSourceInspection.sources(it) }
        assertTrue("nenhum arquivo encontrado em $paths", files.isNotEmpty())
        val offenders = files.filter { file ->
            val code = AuthSourceInspection.code(file)
            forbidden.any { code.contains(it) }
        }
        assertEquals(
            "$paths encostou no que não devia: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }
}
