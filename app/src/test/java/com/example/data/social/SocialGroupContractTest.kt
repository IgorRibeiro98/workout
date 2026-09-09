package com.example.data.social

import com.example.domain.social.SocialGroupRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O contrato dos Squads, do lado do app (T17.11).
 *
 * ## O que este arquivo protege
 *
 * Três coisas que nenhum teste de ViewModel pegaria, porque nenhuma delas é comportamento de tela:
 *
 * 1. **os caminhos são os do servidor**, e a divergência aparece como `404` em produção;
 * 2. **não existe caminho de descoberta** (§4/§5) — a ausência é o contrato, e um teste é a única
 *    coisa que impede alguém de acrescentar um "por conveniência";
 * 3. **nenhum DTO declara identidade privada nem dado de treino** (§75). O servidor também não os
 *    envia, mas declará-los aqui seria o primeiro passo para alguém decidir que "seria útil se o
 *    servidor mandasse".
 */
class SocialGroupContractTest {

    private fun sourceFile(name: String): File {
        val candidates = listOf(
            File("app/src/main/java/com/example/data/social/$name"),
            File("src/main/java/com/example/data/social/$name")
        )
        return candidates.first { it.isFile }
    }

    // ------------------------------------------------------------------ §127 caminhos

    @Test
    fun `os caminhos batem com as rotas do servidor`() {
        assertEquals("v1/social/groups", SocialGroupContract.GROUPS_PATH)
        assertEquals("v1/social/groups/invitations", SocialGroupContract.INVITATIONS_PATH)
        assertEquals("v1/social/groups/g1", SocialGroupContract.groupPath("g1"))
        assertEquals("v1/social/groups/g1/members", SocialGroupContract.membersPath("g1"))
        assertEquals("v1/social/groups/g1/members/m1", SocialGroupContract.memberPath("g1", "m1"))
        assertEquals(
            "v1/social/groups/g1/invitations",
            SocialGroupContract.groupInvitationsPath("g1")
        )
        assertEquals(
            "v1/social/group-invitations/i1/accept",
            SocialGroupContract.acceptInvitationPath("i1")
        )
        assertEquals(
            "v1/social/group-invitations/i1/decline",
            SocialGroupContract.declineInvitationPath("i1")
        )
        assertEquals(
            "v1/social/group-invitations/i1/cancel",
            SocialGroupContract.cancelInvitationPath("i1")
        )
        assertEquals("v1/social/groups/g1/leave", SocialGroupContract.leavePath("g1"))
        assertEquals(
            "v1/social/groups/g1/transfer-ownership",
            SocialGroupContract.transferOwnershipPath("g1")
        )
        assertEquals("v1/social/groups/g1/checkins/c1", SocialGroupContract.sharePath("g1", "c1"))
        assertEquals("v1/social/groups/g1/feed", SocialGroupContract.feedPath("g1"))
        assertEquals("v1/social/groups/g1/feed?limit=20", SocialGroupContract.feedPath("g1", 20))
        assertEquals(
            "v1/social/workout-checkins/c1/groups",
            SocialGroupContract.checkInGroupsPath("c1")
        )
    }

    /**
     * §4/§5 — um Squad é privado, e a privacidade é **estrutural**.
     *
     * Não existe busca, não existe listagem pública, não existe entrada por código e não existe
     * link de convite. A ausência não é "ainda não implementamos": um caminho desses só poderia
     * existir se o servidor tivesse a rota, e ele não tem.
     */
    @Test
    fun `nao existe caminho de descoberta de squad`() {
        val source = sourceFile("SocialGroupContract.kt").readText()
        for (forbidden in listOf(
            "search",
            "discover",
            "public",
            "explore",
            "join-code",
            "invite-link",
            "/qr"
        )) {
            assertFalse(
                "o contrato de Squad não pode conter um caminho de descoberta: $forbidden",
                Regex(""""[^"]*$forbidden[^"]*"""").containsMatchIn(source)
            )
        }
    }

    // ------------------------------------------------------------------ §75 privacidade

    @Test
    fun `nenhum DTO de Squad declara identidade privada ou dado de treino`() {
        val source = sourceFile("SocialGroupDtos.kt").readText()
        val forbidden = listOf(
            "ownerUid",
            "memberUid",
            "firebaseUid",
            "uid",
            "email",
            "friendCode",
            "sessionSyncId",
            "sourceSessionSyncId",
            "exercises",
            "sets",
            "reps",
            "load",
            "duration",
            "notes",
            "storageKey",
            "photoUrl",
            "imageUrl"
        )
        val offenders = forbidden.filter { field ->
            Regex("""\b(val|var)\s+$field\b""").containsMatchIn(source)
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * §34/§136 — a entrada opaca de um participante bloqueado.
     *
     * O DTO precisa aceitar `socialId`/`displayName` **nulos**, e a conversão precisa mantê-los
     * nulos quando `available` é `false`. Um default `""` esconderia a diferença entre "sem
     * identidade por bloqueio" e "identidade vazia por defeito", e a tela mostraria um item em
     * branco em vez de "Participante indisponível".
     */
    @Test
    fun `membro bloqueado chega sem identidade, e a conversao a mantem ausente`() {
        val blocked = SocialGroupMemberDto(
            membershipId = "m1",
            role = "MEMBER",
            joinedAt = 1L,
            available = false,
            socialId = null,
            displayName = null
        ).toDomainOrNull()

        requireNotNull(blocked)
        assertFalse(blocked.available)
        assertNull(blocked.socialId)
        assertNull(blocked.displayName)
        // O `membershipId` continua: é ele que permite ao dono administrar sem receber identidade.
        assertEquals("m1", blocked.membershipId)
    }

    @Test
    fun `identidade enviada junto de available false ainda assim nao passa`() {
        // Defesa em profundidade: se um servidor futuro mandasse os dois campos por engano, a
        // conversão continuaria descartando-os.
        val blocked = SocialGroupMemberDto(
            membershipId = "m1",
            role = "MEMBER",
            available = false,
            socialId = "social-x",
            displayName = "Fulano"
        ).toDomainOrNull()

        requireNotNull(blocked)
        assertNull(blocked.socialId)
        assertNull(blocked.displayName)
    }

    // ------------------------------------------------------------------ tolerância a versão

    @Test
    fun `um papel desconhecido descarta a linha em vez de derrubar a leitura`() {
        // §13 — dois papéis hoje. Um `ADMIN` vindo de um servidor mais novo não pode virar exceção
        // de desserialização: o item some, e o resto da lista continua.
        assertNull(SocialGroupRole.fromWire("ADMIN"))
        assertNull(SocialGroupSummaryDto(groupId = "g1", role = "ADMIN").toDomainOrNull())
        assertEquals(
            SocialGroupRole.OWNER,
            SocialGroupSummaryDto(groupId = "g1", role = "OWNER").toDomainOrNull()?.role
        )
    }

    @Test
    fun `um squad sem identificador nao vira card`() {
        assertNull(SocialGroupSummaryDto(groupId = "", role = "OWNER").toDomainOrNull())
        assertNull(SocialGroupInvitationDto(invitationId = "", groupId = "g1").toDomainOrNull())
        assertNull(SocialGroupInvitationDto(invitationId = "i1", groupId = "").toDomainOrNull())
    }

    // ------------------------------------------------------------------ §11/§18/§68 limites

    @Test
    fun `os limites da tela espelham os do servidor`() {
        assertEquals(3, SocialGroupContract.Limits.MIN_NAME_LENGTH)
        assertEquals(40, SocialGroupContract.Limits.MAX_NAME_LENGTH)
        assertEquals(20, SocialGroupContract.Limits.MAX_MEMBERS)
        assertEquals(5, SocialGroupContract.Limits.MAX_OWNED_GROUPS)
        assertEquals(5, SocialGroupContract.Limits.MAX_SHARES_PER_CHECKIN)
    }

    // ------------------------------------------------------------------ §50 um feed só

    /**
     * O item do feed de Squad **envelopa** o check-in da T17.8/T17.9, e não o redeclara.
     *
     * É a mesma publicação lida por outra audiência (§50/§67). Uma segunda declaração seria o
     * primeiro passo para dois contratos que divergem no próximo campo — e é exatamente o "segundo
     * modelo de publicação" que a T17.11 proíbe.
     */
    @Test
    fun `o feed do squad reusa o DTO do check-in`() {
        val source = sourceFile("SocialGroupDtos.kt").readText()
        assertTrue(
            "o item do feed precisa conter um WorkoutCheckInDto",
            Regex("""val\s+checkIn:\s*WorkoutCheckInDto""").containsMatchIn(source)
        )
        for (redeclared in listOf("caption", "publishedAt", "media", "reactions")) {
            assertFalse(
                "o feed do squad não pode redeclarar $redeclared",
                Regex("""\bval\s+$redeclared\b""").containsMatchIn(source)
            )
        }
    }
}
