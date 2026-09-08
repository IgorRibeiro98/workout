package com.example.data.social

import com.example.domain.social.Challenge
import com.example.domain.social.ChallengeDetail
import com.example.domain.social.ChallengeInvitationStatus
import com.example.domain.social.ChallengeInvite
import com.example.domain.social.ChallengeInvitePage
import com.example.domain.social.ChallengePage
import com.example.domain.social.ChallengeParticipantScore
import com.example.domain.social.ChallengeParticipantStatus
import com.example.domain.social.ChallengeRole
import com.example.domain.social.ChallengeStatus
import com.example.domain.social.ChallengeType
import com.example.domain.social.ChallengeViewer
import com.example.domain.social.SocialProfilePreview
import kotlinx.serialization.Serializable

/**
 * Os contratos de serialização dos desafios (T17.3).
 *
 * ## O que **não** existe nestes DTOs, em nenhuma direção
 *
 * `ownerUid`, `uid`, `firebaseUid`, `email`, `friendCode`, `sessionId`, `syncId`, `exerciseId`,
 * séries, cargas, repetições, notas, horários de treino, medidas e payload de sync ou de backup.
 *
 * Na resposta, porque **participar de um desafio dá acesso ao placar, e não aos dados que o
 * produziram**. Na requisição, porque o dono sai do token e a pontuação é derivada no servidor —
 * um campo desses no corpo faz o servidor recusar a requisição inteira.
 *
 * Declarar um deles aqui seria a primeira metade de passar a receber, e há teste estrutural sobre
 * este arquivo.
 *
 * ## Por que quase tudo tem default
 *
 * Um servidor mais novo pode acrescentar campos, e um APK antigo não pode quebrar por isso
 * (`ignoreUnknownKeys` cuida do que sobra). O que **não** tem default é o que identifica: sem
 * `challengeId` ou sem `invitationId` não há o que mostrar, e um objeto pela metade é pior do que
 * dizer que não deu para carregar.
 */

@Serializable
data class ChallengeSummaryDto(
    val challengeId: String,
    val name: String = "",
    val type: String = "",
    val target: Int = 0,
    val startDate: String = "",
    val endDate: String = "",
    val timeZoneId: String = "",
    val status: String = "",
    val creator: SocialProfilePreviewDto? = null,
    val participantCount: Int = 0,
    val createdAt: Long = 0L
)

@Serializable
data class ChallengeParticipantScoreDto(
    val socialId: String,
    val displayName: String = "",
    val score: Int = 0,
    val goalReached: Boolean = false,
    val rank: Int = 0,
    val role: String = "",
    val isViewer: Boolean = false
)

@Serializable
data class ChallengeViewerDto(
    val role: String = "",
    val status: String = "",
    val canCancel: Boolean = false,
    val canLeave: Boolean = false
)

/** `GET /v1/social/challenges/{challengeId}`. */
@Serializable
data class ChallengeDetailResponseDto(
    val challenge: ChallengeSummaryDto,
    val participants: List<ChallengeParticipantScoreDto> = emptyList(),
    /** Ausente para quem não é o criador — e ausente **não** é zero. */
    val pendingInvitationCount: Int? = null,
    val withdrawnCount: Int = 0,
    val viewer: ChallengeViewerDto = ChallengeViewerDto(),
    val resultMayStillChange: Boolean = false
)

/** `POST /v1/social/challenges` — o corpo. Nenhum campo de pontuação, e nenhum de identidade. */
@Serializable
data class CreateChallengeRequestDto(
    val clientRequestId: String,
    val name: String,
    val type: String,
    val target: Int,
    val startDate: String,
    val endDate: String,
    val timeZoneId: String,
    val invitedSocialIds: List<String>
)

@Serializable
data class CreateChallengeResponseDto(
    val result: String = "",
    val challenge: ChallengeSummaryDto
)

@Serializable
data class AcceptChallengeResponseDto(
    val result: String = "",
    val challenge: ChallengeSummaryDto
)

@Serializable
data class ChallengeListResponseDto(
    val challenges: List<ChallengeSummaryDto> = emptyList(),
    val total: Int = 0,
    val nextCursor: String? = null
)

@Serializable
data class ChallengePreviewDto(
    val invitationId: String,
    val challenge: ChallengeSummaryDto,
    val status: String = "",
    val createdAt: Long = 0L
)

@Serializable
data class ChallengeInvitationListResponseDto(
    val invitations: List<ChallengePreviewDto> = emptyList(),
    val total: Int = 0,
    val nextCursor: String? = null
)

/** Recusar, sair e cancelar respondem só um desfecho. */
@Serializable
data class ChallengeResultDto(
    val result: String = ""
)

// ------------------------------------------------------------------------------- para o domínio

/**
 * O DTO vira domínio aqui, e o que este APK não sabe ler é **recusado**.
 *
 * `null` em vez de um default: um desafio cujo tipo ou status este APK não conhece não pode ser
 * mostrado pela metade — a tela mostraria uma disputa com regras que ela não sabe descrever. Dizer
 * "atualize o app" é melhor do que renderizar um desafio errado.
 */
fun ChallengeSummaryDto.toDomain(): Challenge? {
    if (challengeId.isBlank() || name.isBlank()) return null
    val parsedType = ChallengeType.entries.firstOrNull { it.name == type } ?: return null
    val parsedStatus = ChallengeStatus.entries.firstOrNull { it.name == status } ?: return null
    val parsedCreator = creator?.let {
        if (it.socialId.isBlank()) null else SocialProfilePreview(it.socialId, it.displayName)
    } ?: return null

    return Challenge(
        challengeId = challengeId,
        name = name,
        type = parsedType,
        target = target,
        startDate = startDate,
        endDate = endDate,
        timeZoneId = timeZoneId,
        status = parsedStatus,
        creator = parsedCreator,
        participantCount = participantCount,
        createdAt = createdAt
    )
}

fun ChallengeParticipantScoreDto.toDomain(): ChallengeParticipantScore? {
    if (socialId.isBlank() || displayName.isBlank()) return null
    val parsedRole = ChallengeRole.entries.firstOrNull { it.name == role } ?: return null
    return ChallengeParticipantScore(
        socialId = socialId,
        displayName = displayName,
        score = score,
        goalReached = goalReached,
        rank = rank,
        role = parsedRole,
        isViewer = isViewer
    )
}

fun ChallengeDetailResponseDto.toDomain(): ChallengeDetail? {
    val parsedChallenge = challenge.toDomain() ?: return null
    val parsedRole = ChallengeRole.entries.firstOrNull { it.name == viewer.role } ?: return null
    val parsedStatus = ChallengeParticipantStatus.entries
        .firstOrNull { it.name == viewer.status } ?: return null

    return ChallengeDetail(
        challenge = parsedChallenge,
        // Uma linha ilegível é descartada; o resto do placar continua útil. Diferente do desafio
        // em si, onde não dá para mostrar nada sem as regras.
        participants = participants.mapNotNull { it.toDomain() },
        pendingInvitationCount = pendingInvitationCount,
        withdrawnCount = withdrawnCount,
        viewer = ChallengeViewer(
            role = parsedRole,
            status = parsedStatus,
            canCancel = viewer.canCancel,
            canLeave = viewer.canLeave
        ),
        resultMayStillChange = resultMayStillChange
    )
}

fun ChallengePreviewDto.toDomain(): ChallengeInvite? {
    if (invitationId.isBlank()) return null
    val parsedChallenge = challenge.toDomain() ?: return null
    // Um estado de convite que este APK não conhece vira `PENDING`? Não: ele é descartado. Mostrar
    // "responda" para um convite que o servidor já resolveu produziria um toque que sempre falha.
    val parsedStatus = ChallengeInvitationStatus.entries.firstOrNull { it.name == status }
        ?: return null

    return ChallengeInvite(
        invitationId = invitationId,
        challenge = parsedChallenge,
        status = parsedStatus,
        createdAt = createdAt
    )
}

fun ChallengeListResponseDto.toDomain(): ChallengePage = ChallengePage(
    challenges = challenges.mapNotNull { it.toDomain() },
    total = total,
    nextCursor = nextCursor
)

fun ChallengeInvitationListResponseDto.toDomain(): ChallengeInvitePage = ChallengeInvitePage(
    invites = invitations.mapNotNull { it.toDomain() },
    total = total,
    nextCursor = nextCursor
)
