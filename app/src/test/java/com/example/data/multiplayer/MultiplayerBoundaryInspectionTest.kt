package com.example.data.multiplayer

import com.example.domain.auth.AuthSourceInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provas estruturais das fronteiras do multiplayer remoto (T19.5).
 *
 * Comportamento não cobre estas. Um `dao.updateSetLog(...)` dentro do coordenador ao receber um
 * evento do peer passaria em muitos testes e ainda assim seria o defeito que a T19.5 proíbe: o
 * servidor virando autoridade do Workout local. Um `Log.d(TAG, roomId)` colocaria a sala e o
 * `socialId` de todo mundo no Logcat. E um campo `weight` no DTO de evento faria a carga de uma
 * pessoa atravessar a rede sem que teste de comportamento nenhum reclamasse.
 *
 * Estes testes olham o **código-fonte**.
 */
class MultiplayerBoundaryInspectionTest {

    private val packages = listOf(
        "app/src/main/java/com/example/data/multiplayer",
        "app/src/main/java/com/example/domain/multiplayer",
        "app/src/main/java/com/example/presentation/multiplayer"
    )

    private fun sources() = packages.flatMap { AuthSourceInspection.sources(it) }

    @Test
    fun `os arquivos do multiplayer existem — senao os testes abaixo nao provam nada`() {
        val names = sources().map { it.name }.toSet()
        assertTrue(
            "fontes do multiplayer não encontradas: $names",
            names.containsAll(
                setOf(
                    "MultiplayerContract.kt",
                    "MultiplayerDtos.kt",
                    "SparkMultiplayerGateway.kt",
                    "MultiplayerSessionCoordinator.kt",
                    "MultiplayerWorkoutStarter.kt",
                    "MultiplayerRoom.kt",
                    "MultiplayerGateway.kt",
                    "MultiplayerEventLog.kt",
                    "LocalMultiplayerEvents.kt",
                    "MultiplayerSessionState.kt",
                    "MultiplayerLobbyViewModel.kt",
                    "MultiplayerLobbyScreen.kt"
                )
            )
        )
    }

    @Test
    fun `nenhum evento recebido escreve no Room — o servidor nao e autoridade do treino`() {
        // O coordenador e o starter são os únicos com acesso ao DAO/motor, e o que eles escrevem
        // é o vínculo e a sessão **deste** aparelho. Nenhum caminho grava série, peso, descanso,
        // PR ou XP por causa do que veio do servidor.
        assertNoneReference(
            listOf(
                "updateSetLog",
                "insertSetLogs",
                "updateSet(",
                "completeSet",
                "updateGuestSet",
                "completeGuestSet",
                "updateParticipantRestEndsAt",
                "startRestTimer",
                "finishSession",
                "registerPersonalRecord",
                "PersonalRecord",
                "XpTransaction",
                "GamificationEvent",
                "updateSession("
            )
        )
    }

    @Test
    fun `o multiplayer nao usa a Outbox, o sync nem o backup`() {
        assertNoneReference(
            listOf(
                "SyncOutbox",
                "syncOutboxDao",
                "SyncMutationCoordinator",
                "SyncEntityType",
                "sync_entities",
                "SyncRemoteApplier",
                "BackupRepository",
                "RestoreRepository",
                "CloudDataBinding",
                "WorkManager"
            )
        )
    }

    @Test
    fun `o multiplayer nao registra log`() {
        assertNoneReference(listOf("android.util.Log", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "println("))
    }

    @Test
    fun `os DTOs nao tem campo para peso, repeticao, RPE, PR, XP, uid nem e-mail`() {
        val dtos = AuthSourceInspection.code(sources().first { it.name == "MultiplayerDtos.kt" })
        listOf("val weight", "val reps", "val repetitions", "val rpe", "val rir", "val notes", "val xp", "val uid", "val ownerUid", "val email", "val deviceId", "val syncId")
            .forEach { field ->
                assertTrue("MultiplayerDtos.kt declara `$field`", !dtos.contains(field))
            }
    }

    @Test
    fun `o coordenador e escopado por conta e cancela a conexao na troca`() {
        val coordinator = AuthSourceInspection.code(sources().first { it.name == "MultiplayerSessionCoordinator.kt" })
        assertTrue(coordinator.contains("link.accountUid != uid"))
        assertTrue(coordinator.contains("collectLatest"))
        val client = AuthSourceInspection.code(java.io.File("app/src/main/java/com/example/data/remote/spark/SparkBackendClient.kt").let { if (it.isFile) it else java.io.File("src/main/java/com/example/data/remote/spark/SparkBackendClient.kt") })
        assertTrue("o long-poll precisa ser cancelável", client.contains("invokeOnCancellation { call.cancel() }"))
    }

    private fun assertNoneReference(needles: List<String>) {
        val offenders = sources().flatMap { file ->
            val code = AuthSourceInspection.code(file)
            needles.filter { code.contains(it) }.map { "${file.name}: $it" }
        }
        assertEquals("referências proibidas encontradas", emptyList<String>(), offenders)
    }
}
