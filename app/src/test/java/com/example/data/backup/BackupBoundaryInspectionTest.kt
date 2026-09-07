package com.example.data.backup

import com.example.domain.auth.AuthSourceInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provas estruturais dos limites da T16.4.
 *
 * Comportamento não cobre estas. Um `LaunchedEffect { backupNow() }` funcionaria em teste e ainda
 * assim seria exatamente o defeito que a tarefa proíbe — backup disparado por abrir tela. Um
 * `WorkManager` agendando upload passaria em toda suíte de unidade e mudaria o que o app é.
 *
 * Estes testes olham o **código-fonte**.
 */
class BackupBoundaryInspectionTest {

    private val backupPackage = "app/src/main/java/com/example/data/backup"
    private val uiPackages = listOf(
        "app/src/main/java/com/example/presentation",
        "app/src/main/java/com/example/ui"
    )

    @Test
    fun `nenhum backup automatico foi introduzido`() {
        // O backup continua sendo **manual**: um toque em "Ativar backup" com confirmação, ou um
        // toque em "Fazer backup agora". Nada o dispara sozinho.
        //
        // A T16.6 trouxe `WorkManager` para o **sync**, então a varredura global por ele deixou de
        // ser possível — e deixou de ser o invariante certo. O que continua valendo, e é o que
        // este teste passa a exigir, é mais preciso: nenhum agendador encosta no pacote de backup
        // nem na tela dele, e nada no app é periódico. A forma do trabalho de sync (único, com
        // rede, com backoff) é provada em `SyncBoundaryInspectionTest`.
        assertNoneReference(
            listOf(backupPackage, "app/src/main/java/com/example/presentation/account"),
            listOf(
                "WorkManager",
                "androidx.work",
                "AlarmManager",
                "JobScheduler",
                "setRepeating",
                "scheduleAtFixedRate"
            )
        )
        assertNoneReference(
            listOf("app/src/main/java/com/example"),
            listOf("PeriodicWorkRequest", "setPeriodic", "setInexactRepeating")
        )
    }

    @Test
    fun `o backup nao usa object storage nem servico pago novo`() {
        // A política de custo do ADR-0001: uma VPS, um backend, um SQLite. Mídia continua local na
        // T16.4, e a saída para ela **não** é contrabandear binário para outro provedor.
        assertNoneReference(
            listOf("app/src/main/java/com/example", "backend/src"),
            listOf(
                "FirebaseStorage",
                "firebase-storage",
                "amazonaws",
                "s3.",
                "cloudflare",
                "r2.dev",
                "getSignedUrl"
            )
        )
    }

    @Test
    fun `a UI nao conhece a Outbox nem a fronteira transacional`() {
        // A tela chama o ViewModel, que chama o repositório, que abre a transação. Se Compose
        // puder mexer na Outbox, mais cedo ou mais tarde alguém libera a fila sem o servidor ter
        // confirmado nada.
        assertNoneReference(
            uiPackages,
            listOf("SyncOutbox", "syncOutboxDao", "SyncMutationCoordinator", "SyncEntityType", "SyncOperation")
        )
    }

    @Test
    fun `a UI nao monta snapshot nem fala HTTP`() {
        assertNoneReference(
            uiPackages,
            listOf(
                "BackupSnapshotBuilder",
                "SparkBackupApi",
                "BackupCanonicalJson",
                "BackupAttemptDao",
                "CloudDataBindingDao",
                "OkHttpClient"
            )
        )
    }

    @Test
    fun `o backup nao virou sync`() {
        // O sync incremental existe desde a T16.6 — em `data/sync`, com contrato, cursor e
        // revisão próprios. O que este teste protege é a separação: **backup não é sync**.
        //
        // Um snapshot completo e imutável e um fluxo de mudanças resolvem problemas diferentes, e
        // fundir os dois custaria as duas coisas: o backup deixaria de ser um ponto no tempo ao
        // qual dá para voltar, e o sync ganharia um caminho que reenvia o dataset inteiro.
        assertNoneReference(
            listOf(backupPackage, "app/src/main/java/com/example/data/remote/spark"),
            listOf(
                "v1/sync/push",
                "v1/sync/pull",
                "nextCursor",
                "serverRevision",
                "baseRevision",
                "SyncRepository",
                "SyncRemoteApplier"
            )
        )
    }

    @Test
    fun `o sync nao cria backup`() {
        // O caminho inverso: um ciclo de sincronização não pode disparar um snapshot completo.
        // Backup é decisão do usuário, e um ciclo que criasse um em cada rodada encheria a
        // retenção da conta com cópias que ninguém pediu.
        assertNoneReference(
            listOf("app/src/main/java/com/example/data/sync"),
            listOf("BackupRepository", "BackupSnapshotBuilder", "v1/backups", "backupNow")
        )
    }

    @Test
    fun `nenhuma credencial ou token entra no pacote de backup`() {
        assertNoneReference(
            listOf(backupPackage),
            listOf("GEMINI_API_KEY", "Authorization", "idToken", "apiKey", "password", "secret")
        )
    }

    @Test
    fun `o backup nao escreve no dominio`() {
        // O montador de snapshot tem DAOs porque precisa ler. O que ele não pode ter é caminho de
        // escrita: `insert`, `update`, `delete` e `save` do domínio não aparecem aqui.
        val builder = AuthSourceInspection.sources(backupPackage)
            .single { it.name == "BackupSnapshotBuilder.kt" }
        val code = AuthSourceInspection.code(builder)

        listOf(
            "insertTemplate",
            "updateTemplate",
            "insertSession",
            "updateSession",
            "updateSetLog",
            "insertExercise",
            "deleteTemplate",
            "insertOrUpdateOverride",
            "insertGoal",
            "insertPersonalRecord"
        ).forEach { write ->
            assertTrue("o montador de snapshot precisa ser read-only: $write", !code.contains(write))
        }
    }

    @Test
    fun `o snapshot nao serializa caminho local nem URI de midia`() {
        val sources = AuthSourceInspection.sources(backupPackage)
        val offenders = sources.filter { file ->
            val code = AuthSourceInspection.code(file)
            code.contains("customPhotoUri") ||
                code.contains("content://") ||
                code.contains("file://") ||
                code.contains("Base64")
        }
        assertEquals(
            "mídia local não pode virar referência remota: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }

    private fun assertNoneReference(paths: List<String>, forbidden: List<String>) {
        val files = paths.flatMap { AuthSourceInspection.sources(it) }
        assertTrue("nenhum arquivo encontrado em $paths", files.isNotEmpty())
        val offenders = files.filter { file ->
            val code = AuthSourceInspection.code(file)
            forbidden.any { code.contains(it) }
        }
        assertEquals(
            "$paths violou o limite: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name }
        )
    }
}
