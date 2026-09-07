package com.example.data.restore

import com.example.domain.auth.AuthSourceInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provas estruturais dos limites da T16.5.
 *
 * Comportamento não cobre estas. Um `LaunchedEffect { selectBackup(...) }` passaria em toda suíte
 * de unidade e ainda assim seria exatamente o defeito que a tarefa proíbe — restore disparado por
 * abrir tela. Um snapshot escrito em `Downloads` funcionaria perfeitamente e exporia o histórico
 * inteiro da pessoa para qualquer app do aparelho.
 *
 * Estes testes olham o **código-fonte**.
 */
class RestoreBoundaryInspectionTest {

    private val restorePackage = "app/src/main/java/com/example/data/restore"
    private val uiPackages = listOf(
        "app/src/main/java/com/example/presentation",
        "app/src/main/java/com/example/ui"
    )

    @Test
    fun `nenhum restore automatico foi introduzido`() {
        // Restore é substituição destrutiva. Ele acontece por três toques explícitos e nunca por
        // agendador, worker, alarme ou observador de login.
        assertNoneReference(
            listOf(restorePackage, "app/src/main/java/com/example/presentation/account"),
            listOf(
                "WorkManager",
                "androidx.work",
                "PeriodicWorkRequest",
                "AlarmManager",
                "JobScheduler",
                "setRepeating",
                "scheduleAtFixedRate"
            )
        )
    }

    @Test
    fun `o ViewModel nao restaura ao ser criado`() {
        val viewModel = AuthSourceInspection
            .sources("app/src/main/java/com/example/presentation/account")
            .single { it.name == "RestoreViewModel.kt" }
        val code = AuthSourceInspection.code(viewModel)

        // O `init` observa a sessão e recalcula o que mostrar. Ele não lista, não baixa e não
        // aplica: cada uma dessas coisas é um toque do usuário.
        val initBlock = code.substringAfter("init {").substringBefore("\n    }")
        listOf("loadBackups()", "selectBackup(", "applyRestore()", "confirm(", "prepare(")
            .forEach { forbidden ->
                assertTrue(
                    "o init do RestoreViewModel não pode chamar $forbidden",
                    !initBlock.contains(forbidden)
                )
            }
    }

    @Test
    fun `a confirmacao explicita nao pode ser contornada`() {
        val repository = AuthSourceInspection.sources(restorePackage)
            .single { it.name == "RestoreRepository.kt" }
        val code = AuthSourceInspection.code(repository)

        // A guarda existe no repositório, e não só na tela: é ela que impede um caminho de UI
        // esquecido de substituir o dataset.
        assertTrue(
            "o repositório precisa recusar aplicação sem confirmação",
            code.contains("if (!confirmed) return") &&
                code.contains("RESTORE_CONFIRMATION_REQUIRED")
        )
    }

    @Test
    fun `a UI nao conhece o banco, a transacao nem os arquivos do restore`() {
        // A tela chama o ViewModel, que chama o repositório, que abre a transação. Se Compose
        // puder apagar tabela, mais cedo ou mais tarde alguém apaga antes de validar.
        assertNoneReference(
            uiPackages,
            listOf(
                "RestoreDao",
                "RestoreTransaction",
                "RestoreFileStore",
                "RestoreSafetySnapshotStore",
                "RestoreSnapshotReader",
                "deleteAll",
                "BackupCanonicalJson"
            )
        )
    }

    @Test
    fun `o snapshot baixado nunca vai para armazenamento publico`() {
        // Um snapshot é o histórico inteiro da pessoa: treinos, cargas, medidas e notas. Ele vive
        // no armazenamento privado do app, e em lugar nenhum mais.
        assertNoneReference(
            listOf(restorePackage),
            listOf(
                "getExternalFilesDir",
                "getExternalStorageDirectory",
                "Environment.DIRECTORY",
                "MediaStore",
                "DownloadManager",
                "Downloads"
            )
        )
    }

    @Test
    fun `o restore nao envia nada ao servidor`() {
        // Ele lê. O snapshot de segurança é proteção **local** e temporária: mandá-lo para a VPS
        // criaria um backup que o usuário não pediu, a partir de um estado que ele acabou de
        // decidir descartar.
        assertNoneReference(
            listOf(restorePackage),
            // Tokens precisos: "POST" solto acusaria a palavra "resposta" dentro de um código de
            // erro — um teste que acusa o inocente é abandonado na primeira falha falsa.
            listOf("postJson", "BackupApi", "upload(", ".post(", "RequestBody")
        )
    }

    @Test
    fun `o restore nao registra conteudo em log`() {
        // Nenhum log existe no pacote hoje, e a ausência é o invariante: se algum for adicionado,
        // ele precisa carregar metadata técnica — nunca snapshot, medida, histórico ou token.
        assertNoneReference(
            listOf(restorePackage),
            listOf("android.util.Log", "println(", "printStackTrace")
        )
    }

    @Test
    fun `nenhuma credencial ou token entra no pacote de restore`() {
        assertNoneReference(
            listOf(restorePackage),
            listOf("GEMINI_API_KEY", "Authorization", "idToken", "apiKey", "password", "secret")
        )
    }

    @Test
    fun `o restore nao mescla nem resolve conflito`() {
        // T16.5 é substituição. Merge, *last write wins*, união de campo e tombstone remoto são
        // T16.6/T16.7 — e um merge silencioso seria a forma mais fácil de o usuário perder dado
        // achando que ganhou.
        assertNoneReference(
            listOf(restorePackage, "app/src/main/java/com/example/presentation/account"),
            listOf(
                "lastWriteWins",
                "mergeInto",
                "resolveConflict",
                "keepBoth",
                "tombstone",
                "fieldLevelMerge"
            )
        )
    }

    @Test
    fun `o catalogo canonico nao esta entre o que o restore apaga`() {
        val dao = AuthSourceInspection.sources(restorePackage).single { it.name == "RestoreDao.kt" }
        val code = AuthSourceInspection.code(dao)

        // `exercises` só aparece com o filtro de criado pelo usuário. Apagar o catálogo
        // transformaria um restore em reinstalação — e as referências do próprio backup deixariam
        // de resolver.
        assertTrue(
            "o catálogo canônico não pode ser apagado pelo restore",
            !code.contains("DELETE FROM exercises\"") &&
                code.contains("DELETE FROM exercises WHERE isUserCreated = 1")
        )
        listOf(
            "DELETE FROM exercise_education",
            "DELETE FROM exercise_media",
            "DELETE FROM exercise_alternatives",
            "DELETE FROM backup_attempts",
            "DELETE FROM restore_attempts"
        ).forEach { forbidden ->
            assertTrue("o restore não pode apagar $forbidden", !code.contains(forbidden))
        }
    }

    @Test
    fun `o restore reusa o contrato do backup em vez de criar outro`() {
        val sources = AuthSourceInspection.sources(restorePackage)
        val reader = sources.single { it.name == "RestoreSnapshotReader.kt" }
        val code = AuthSourceInspection.code(reader)

        // Os agregados são lidos com os **mesmos** DTOs que o backup escreve. Um segundo conjunto
        // de DTOs para o mesmo snapshot divergiria, e a divergência apareceria como "backup
        // corrompido" no aparelho de um usuário.
        listOf(
            "com.example.data.sync.dto.WorkoutTemplateSyncDto",
            "com.example.data.sync.dto.WorkoutSessionSyncDto",
            "com.example.data.backup.UserPreferencesBackupDto"
        ).forEach { shared ->
            assertTrue(
                "o leitor de restore precisa reusar $shared",
                code.contains(shared.substringAfterLast('.'))
            )
        }
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
