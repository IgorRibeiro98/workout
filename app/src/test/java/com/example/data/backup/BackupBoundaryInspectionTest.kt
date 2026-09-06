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
        // A T16.4 começa com ativação explícita e backup manual explícito. Agendador, worker
        // periódico e alarme são T16.6+ — e introduzi-los aqui faria o app enviar dado sem que
        // ninguém tivesse pedido.
        // `WorkManager` é varrido no app inteiro: ele é o caminho pelo qual um envio em segundo
        // plano nasceria sem ninguém decidir. `AlarmManager` fica de fora da varredura global
        // porque o timer de descanso já o usa desde muito antes da T16 — o que importa aqui é que
        // ele não apareça no pacote de backup.
        assertNoneReference(
            listOf("app/src/main/java/com/example"),
            listOf("WorkManager", "androidx.work", "PeriodicWorkRequest")
        )
        assertNoneReference(
            listOf(backupPackage, "app/src/main/java/com/example/presentation/account"),
            listOf("AlarmManager", "JobScheduler", "setRepeating", "scheduleAtFixedRate")
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
    fun `restore e sync incremental nao foram implementados`() {
        // A T16.4 sobe um snapshot completo. Baixar conteúdo, aplicar no Room, mesclar, resolver
        // conflito ou percorrer cursor são T16.5+ — e meio restore é pior que nenhum.
        // Varrido nos pacotes que falam com o Spark Backend. `nextCursor` e afins existem no
        // catálogo de exercícios (paginação da ExerciseDB) desde antes da T16 e não têm relação
        // com sync — varrer o app inteiro por essas palavras acusaria o inocente.
        assertNoneReference(
            listOf(
                backupPackage,
                "app/src/main/java/com/example/data/remote/spark",
                "app/src/main/java/com/example/data/sync",
                "app/src/main/java/com/example/presentation/account"
            ),
            listOf(
                "v1/sync/push",
                "v1/sync/pull",
                "/content",
                "restoreBackup",
                "downloadBackup",
                "applyRemoteSnapshot",
                "nextCursor",
                "serverRevision",
                "baseRevision"
            )
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
