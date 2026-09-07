package com.example.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A política por agregado (T16.7).
 *
 * O que estes testes protegem não é uma tabela de valores: é a **ausência de um padrão**. O caminho
 * fácil, num sistema de sync, é tratar todo agregado igual e deixar o último a chegar vencer — e
 * isso é indistinguível de perda de dado do ponto de vista do usuário, porque nenhum erro aparece.
 */
class SyncEntityPolicyTest {

    @Test
    fun todoAgregadoSincronizadoDeclaraUmaPolitica() {
        // Sem `else`: acrescentar um agregado ao sync sem declarar a política dele quebra aqui, e
        // não vira silenciosamente "genérico".
        SyncEntityType.entries.forEach { type ->
            val policy = SyncEntityPolicies.of(type)
            assertTrue(
                "$type precisa declarar uma estratégia de conflito",
                policy.conflictStrategy in SyncConflictStrategy.entries
            )
        }
    }

    @Test
    fun lastWriteWinsNaoEEstrategiaDeNenhumAgregado() {
        // Ele existe como valor declarável e ninguém o usa. Usá-lo precisa ser uma linha escrita
        // de propósito, com motivo de domínio — e não o efeito colateral de um padrão.
        val usando = SyncEntityType.entries.filter {
            SyncEntityPolicies.of(it).conflictStrategy == SyncConflictStrategy.LAST_WRITE_WINS_ALLOWED
        }

        assertEquals(emptyList<SyncEntityType>(), usando)
    }

    @Test
    fun historicoConcluidoEImutavelEAindaAssimExcluivel() {
        // As duas coisas juntas são o ponto: editar reescreveria um fato, apagar remove o registro.
        // Tratar as duas como "mudar a sessão" faria o Spark ou perder histórico ou proibir o
        // usuário de apagar o próprio.
        val sessao = SyncEntityPolicies.of(SyncEntityType.WORKOUT_SESSION)

        assertEquals(SyncMutability.IMMUTABLE_HISTORY, sessao.mutability)
        assertEquals(SyncConflictStrategy.IMMUTABLE_CONFLICT, sessao.conflictStrategy)
        assertTrue("apagar o próprio histórico é direito do usuário", sessao.deleteAllowed)
    }

    @Test
    fun checkInNaoAceitaExclusaoRemota() {
        // Não existe tela, repositório ou mutação de domínio que apague um check-in. Uma exclusão
        // deste tipo só poderia vir de defeito, e ela não vira tombstone por isso.
        assertFalse(SyncEntityPolicies.isDeleteAllowed(SyncEntityType.CHECK_IN))
    }

    @Test
    fun todoAgregadoMutavelResolveConflitoPorEscolhaDoUsuario() {
        val mutaveis = SyncEntityType.entries.filter {
            SyncEntityPolicies.of(it).mutability == SyncMutability.MUTABLE_SNAPSHOT
        }

        assertTrue(mutaveis.isNotEmpty())
        mutaveis.forEach {
            assertEquals(
                "$it precisa oferecer escolha explícita",
                SyncConflictStrategy.USER_CHOICE,
                SyncEntityPolicies.of(it).conflictStrategy
            )
        }
    }

    @Test
    fun historicoDivergenteNaoOfereceEscolhaEntreVersoes() {
        // "Manter local"/"usar remoto" para um treino concluído sobrescreveria um registro do que
        // aconteceu. A tela informa e não decide.
        val conflict = SyncConflictEntity(
            ownerUid = "uid",
            entityType = SyncEntityType.WORKOUT_SESSION.name,
            entitySyncId = "sessao",
            kind = SyncConflictKind.IMMUTABLE_HISTORY.name,
            remotePayload = "{}",
            remotePayloadHash = "hash",
            detectedAt = 0
        )

        assertEquals(
            emptySet<SyncConflictChoice>(),
            SyncConflictPreview.choicesFor(conflict, SyncEntityType.WORKOUT_SESSION)
        )
    }

    @Test
    fun semCopiaRemotaGuardadaNaoSeOfereceUsarANuvem() {
        // O conflito nasceu no push: o servidor devolveu a revision atual, não o conteúdo. Oferecer
        // o botão ali seria oferecer algo que sempre falharia.
        val semCopia = SyncConflictEntity(
            ownerUid = "uid",
            entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
            entitySyncId = "treino",
            kind = SyncConflictKind.STALE_LOCAL_CHANGE.name,
            remoteRevision = 5,
            detectedAt = 0
        )

        val choices = SyncConflictPreview.choicesFor(semCopia, SyncEntityType.WORKOUT_TEMPLATE)

        assertEquals(setOf(SyncConflictChoice.KEEP_LOCAL), choices)
    }

    @Test
    fun umaDecisaoJaTomadaNaoOfereceBotaoDeNovo() {
        val aguardando = SyncConflictEntity(
            ownerUid = "uid",
            entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
            entitySyncId = "treino",
            kind = SyncConflictKind.STALE_LOCAL_CHANGE.name,
            status = SyncConflictStatus.AWAITING_PUSH.name,
            remoteRevision = 5,
            detectedAt = 0
        )

        assertEquals(
            emptySet<SyncConflictChoice>(),
            SyncConflictPreview.choicesFor(aguardando, SyncEntityType.WORKOUT_TEMPLATE)
        )
    }
}
