package com.example.data.social

import com.example.domain.social.Challenge
import com.example.domain.social.ChallengeDetail
import com.example.domain.social.ChallengeError
import com.example.domain.social.ChallengeGateway
import com.example.domain.social.ChallengeInvite
import com.example.domain.social.ChallengeInvitationStatus
import com.example.domain.social.ChallengeInvitePage
import com.example.domain.social.ChallengeOutcome
import com.example.domain.social.ChallengePage
import com.example.domain.social.ChallengeParticipantScore
import com.example.domain.social.ChallengeParticipantStatus
import com.example.domain.social.ChallengeRole
import com.example.domain.social.ChallengeStatus
import com.example.domain.social.ChallengeType
import com.example.domain.social.ChallengeViewer
import com.example.domain.social.SocialProfilePreview
import kotlinx.coroutines.CompletableDeferred

/**
 * O Spark Backend de desafios, em memória (T17.3).
 *
 * Não é um mock de chamadas: é um **servidor de mentira com estado**, que reproduz as regras que o
 * servidor de verdade aplica — isolamento por conta, idempotência de criação por
 * `clientRequestId`, aceite que vira participação, e "não participo" respondendo a mesma coisa que
 * "não existe". É isso que permite ao teste de troca de conta e ao de toque duplo afirmarem
 * alguma coisa: contra um mock que devolve objetos fixos, os dois passariam sem provar nada.
 *
 * **A pontuação é de mentira, e isso é deliberado.** Nenhum teste do Android decide um placar: o
 * app não calcula pontuação, e o teste que fingisse calculá-la estaria testando uma regra que não
 * existe deste lado. O que estes testes provam é que a tela **mostra** o que o servidor mandou, e
 * que ela nunca **envia** pontuação — o parâmetro não existe.
 *
 * Vive em `test/`, e só em `test/`.
 */
class FakeChallengeGateway(
    override val isConfigured: Boolean = true
) : ChallengeGateway {

    /** A conta autenticada, do ponto de vista deste "servidor". Trocá-la é trocar de conta. */
    var currentUid: String? = null

    /** O que a próxima operação deve responder, quando não for para funcionar. */
    var failWith: ChallengeError? = null

    /**
     * Quando presente, a próxima operação **espera** aqui.
     *
     * É o que permite observar o estado no meio do voo — em particular, provar que trocar de conta
     * descarta a resposta que estava a caminho.
     */
    var gate: CompletableDeferred<Unit>? = null

    /** Quantas requisições saíram. O contador que prova "uma por toque, e nenhuma sozinha". */
    var requestCount: Int = 0
        private set

    /** Quantas criações chegaram — inclusive as que o ledger de idempotência deduplicou. */
    var createCallCount: Int = 0
        private set

    /** Os `clientRequestId` recebidos. É por eles que a idempotência é observada. */
    val seenClientRequestIds = mutableListOf<String>()

    /** O corpo bruto da última criação, para o teste provar que ele não carrega pontuação. */
    var lastCreateArguments: Map<String, Any?>? = null
        private set

    /** Os desafios de cada conta. */
    private val challengesByUid = mutableMapOf<String, MutableList<Challenge>>()

    /** Os detalhes, por `challengeId`. O teste monta o placar que quer provar. */
    private val detailsById = mutableMapOf<String, ChallengeDetail>()

    /** Quem participa de quê — a autorização, do lado do dublê. */
    private val participantsByChallenge = mutableMapOf<String, MutableSet<String>>()

    /** Os convites de cada conta. */
    private val invitesByUid = mutableMapOf<String, MutableList<ChallengeInvite>>()

    /** O ledger de idempotência: `(uid, clientRequestId) → challengeId`. */
    private val creationLedger = mutableMapOf<Pair<String, String>, String>()

    /** Os desafios já cancelados e as participações já encerradas — para provar idempotência. */
    val cancelledChallengeIds = mutableSetOf<String>()
    val leftChallengeIds = mutableSetOf<String>()
    val declinedInvitationIds = mutableSetOf<String>()

    // ------------------------------------------------------------------------------- montagem

    fun seedChallenge(uid: String, challenge: Challenge, detail: ChallengeDetail? = null) {
        challengesByUid.getOrPut(uid) { mutableListOf() }.add(challenge)
        participantsByChallenge.getOrPut(challenge.challengeId) { mutableSetOf() }.add(uid)
        detailsById[challenge.challengeId] = detail ?: defaultDetail(challenge)
    }

    fun seedInvite(uid: String, invite: ChallengeInvite) {
        invitesByUid.getOrPut(uid) { mutableListOf() }.add(invite)
    }

    // ------------------------------------------------------------------------------- gateway

    override suspend fun create(
        clientRequestId: String,
        name: String,
        type: ChallengeType,
        target: Int,
        startDate: String,
        endDate: String,
        timeZoneId: String,
        invitedSocialIds: List<String>
    ): ChallengeOutcome<Challenge> {
        createCallCount += 1
        seenClientRequestIds += clientRequestId
        lastCreateArguments = mapOf(
            "clientRequestId" to clientRequestId,
            "name" to name,
            "type" to type.name,
            "target" to target,
            "startDate" to startDate,
            "endDate" to endDate,
            "timeZoneId" to timeZoneId,
            "invitedSocialIds" to invitedSocialIds
        )

        val uid = await() ?: return ChallengeOutcome.Failure(ChallengeError.AUTH_REQUIRED)
        failWith?.let { return ChallengeOutcome.Failure(it) }

        // A idempotência do servidor de verdade: o mesmo `clientRequestId` da mesma conta devolve
        // o desafio que a primeira tentativa criou, e não cria um segundo.
        creationLedger[uid to clientRequestId]?.let { existingId ->
            val existing = challengesByUid[uid]?.firstOrNull { it.challengeId == existingId }
            if (existing != null) return ChallengeOutcome.Success(existing)
        }

        val challenge = Challenge(
            challengeId = "challenge-${creationLedger.size + 1}",
            name = name,
            type = type,
            target = target,
            startDate = startDate,
            endDate = endDate,
            timeZoneId = timeZoneId,
            status = ChallengeStatus.UPCOMING,
            creator = SocialProfilePreview("social-$uid", "Dono"),
            participantCount = 1,
            createdAt = 1_000L
        )
        creationLedger[uid to clientRequestId] = challenge.challengeId
        seedChallenge(uid, challenge)
        return ChallengeOutcome.Success(challenge)
    }

    override suspend fun list(cursor: String?): ChallengeOutcome<ChallengePage> {
        val uid = await() ?: return ChallengeOutcome.Failure(ChallengeError.AUTH_REQUIRED)
        failWith?.let { return ChallengeOutcome.Failure(it) }
        val mine = challengesByUid[uid].orEmpty()
        return ChallengeOutcome.Success(ChallengePage(mine.toList(), mine.size))
    }

    override suspend fun detail(challengeId: String): ChallengeOutcome<ChallengeDetail> {
        val uid = await() ?: return ChallengeOutcome.Failure(ChallengeError.AUTH_REQUIRED)
        failWith?.let { return ChallengeOutcome.Failure(it) }

        // "Não existe" e "não participo" respondem a mesma coisa — como no servidor de verdade.
        if (uid !in participantsByChallenge[challengeId].orEmpty()) {
            return ChallengeOutcome.Failure(ChallengeError.NOT_FOUND)
        }
        val detail = detailsById[challengeId]
            ?: return ChallengeOutcome.Failure(ChallengeError.NOT_FOUND)
        return ChallengeOutcome.Success(detail)
    }

    override suspend fun invites(cursor: String?): ChallengeOutcome<ChallengeInvitePage> {
        val uid = await() ?: return ChallengeOutcome.Failure(ChallengeError.AUTH_REQUIRED)
        failWith?.let { return ChallengeOutcome.Failure(it) }
        val mine = invitesByUid[uid].orEmpty()
        return ChallengeOutcome.Success(ChallengeInvitePage(mine.toList(), mine.size))
    }

    override suspend fun accept(invitationId: String): ChallengeOutcome<Challenge> {
        val uid = await() ?: return ChallengeOutcome.Failure(ChallengeError.AUTH_REQUIRED)
        failWith?.let { return ChallengeOutcome.Failure(it) }

        val invites = invitesByUid[uid] ?: mutableListOf()
        val invite = invites.firstOrNull { it.invitationId == invitationId }
            ?: return ChallengeOutcome.Failure(ChallengeError.INVITATION_NOT_FOUND)

        invites.remove(invite)
        // Aceitar vira participação — e é o que faz o desafio aparecer na lista dele depois.
        seedChallenge(uid, invite.challenge)
        return ChallengeOutcome.Success(invite.challenge)
    }

    override suspend fun decline(invitationId: String): ChallengeOutcome<Unit> {
        val uid = await() ?: return ChallengeOutcome.Failure(ChallengeError.AUTH_REQUIRED)
        failWith?.let { return ChallengeOutcome.Failure(it) }

        // Recusar duas vezes é **sucesso**, como no servidor de verdade: o estado que o cliente
        // queria é o estado que existe. Por isso não há ramo de falha aqui — remover um convite
        // que já saiu da lista não é erro.
        invitesByUid[uid]?.removeIf { it.invitationId == invitationId }
        declinedInvitationIds += invitationId
        return ChallengeOutcome.Success(Unit)
    }

    override suspend fun leave(challengeId: String): ChallengeOutcome<Unit> {
        val uid = await() ?: return ChallengeOutcome.Failure(ChallengeError.AUTH_REQUIRED)
        failWith?.let { return ChallengeOutcome.Failure(it) }
        leftChallengeIds += challengeId
        return ChallengeOutcome.Success(Unit)
    }

    override suspend fun cancel(challengeId: String): ChallengeOutcome<Unit> {
        val uid = await() ?: return ChallengeOutcome.Failure(ChallengeError.AUTH_REQUIRED)
        failWith?.let { return ChallengeOutcome.Failure(it) }
        cancelledChallengeIds += challengeId
        return ChallengeOutcome.Success(Unit)
    }

    /** Conta a requisição, espera o portão quando há um, e devolve a conta autenticada. */
    private suspend fun await(): String? {
        requestCount += 1
        gate?.await()
        return currentUid
    }

    private fun defaultDetail(challenge: Challenge): ChallengeDetail = ChallengeDetail(
        challenge = challenge,
        participants = emptyList(),
        pendingInvitationCount = null,
        withdrawnCount = 0,
        viewer = ChallengeViewer(
            role = ChallengeRole.MEMBER,
            status = ChallengeParticipantStatus.JOINED,
            canCancel = false,
            canLeave = true
        ),
        resultMayStillChange = false
    )

    companion object {
        /** Um desafio de referência, para os testes não repetirem a montagem. */
        fun challenge(
            id: String = "challenge-1",
            name: String = "12 treinos",
            type: ChallengeType = ChallengeType.WORKOUTS_COMPLETED,
            target: Int = 12,
            status: ChallengeStatus = ChallengeStatus.ACTIVE,
            participantCount: Int = 3,
            creatorName: String = "Igor"
        ): Challenge = Challenge(
            challengeId = id,
            name = name,
            type = type,
            target = target,
            startDate = "2026-09-10",
            endDate = "2026-10-09",
            timeZoneId = "America/Sao_Paulo",
            status = status,
            creator = SocialProfilePreview("social-igor", creatorName),
            participantCount = participantCount,
            createdAt = 1_000L
        )

        /** Um placar de referência: 8 / 7 / 5, como o exemplo da tarefa. */
        fun leaderboard(
            challenge: Challenge = challenge(),
            viewerSocialId: String = "social-igor",
            resultMayStillChange: Boolean = false,
            canCancel: Boolean = false,
            canLeave: Boolean = true,
            role: ChallengeRole = ChallengeRole.MEMBER,
            status: ChallengeParticipantStatus = ChallengeParticipantStatus.JOINED,
            scores: List<Triple<String, String, Int>> = listOf(
                Triple("social-igor", "Igor", 8),
                Triple("social-joao", "João", 7),
                Triple("social-jonathas", "Jonathas", 5)
            )
        ): ChallengeDetail {
            var rank = 0
            var previous: Int? = null
            val participants = scores.sortedByDescending { it.third }
                .mapIndexed { index, (socialId, name, score) ->
                    if (previous == null || score != previous) {
                        rank = index + 1
                        previous = score
                    }
                    ChallengeParticipantScore(
                        socialId = socialId,
                        displayName = name,
                        score = score,
                        goalReached = score >= challenge.target,
                        rank = rank,
                        role = if (socialId == "social-igor") {
                            ChallengeRole.CREATOR
                        } else {
                            ChallengeRole.MEMBER
                        },
                        isViewer = socialId == viewerSocialId
                    )
                }

            return ChallengeDetail(
                challenge = challenge,
                participants = participants,
                pendingInvitationCount = null,
                withdrawnCount = 0,
                viewer = ChallengeViewer(role, status, canCancel, canLeave),
                resultMayStillChange = resultMayStillChange
            )
        }

        /** Um convite de referência. */
        fun invite(
            id: String = "invite-1",
            challenge: Challenge = challenge(status = ChallengeStatus.UPCOMING)
        ): ChallengeInvite = ChallengeInvite(
            invitationId = id,
            challenge = challenge,
            status = ChallengeInvitationStatus.PENDING,
            createdAt = 900L
        )
    }
}
