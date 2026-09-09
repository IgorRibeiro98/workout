package com.example.data.social

import com.example.domain.social.SocialGroup
import com.example.domain.social.SocialGroupDetail
import com.example.domain.social.SocialGroupFeedItem
import com.example.domain.social.SocialGroupInvitation
import com.example.domain.social.SocialGroupMember
import com.example.domain.social.SocialGroupRole
import kotlinx.serialization.Serializable

/**
 * Os DTOs dos Squads (T17.11).
 *
 * ## O que estes tipos deliberadamente não declaram
 *
 * Nenhum campo de identidade privada — `ownerUid`, `firebaseUid`, e-mail, `friendCode` — e nenhum
 * dado de treino (§75). O servidor também não os envia; declará-los aqui seria o primeiro passo
 * para alguém decidir que "seria útil se o servidor mandasse", e a segunda tela a depender disso
 * tornaria a remoção cara.
 *
 * `memberUid` também não existe: o identificador de um participante nesta fronteira é o
 * `membershipId`, que é opaco e válido só dentro do grupo (§37).
 *
 * ## Por que quase todo campo tem default
 *
 * Porque um servidor mais novo não pode quebrar uma tela antiga. `ignoreUnknownKeys` cobre campos
 * a mais; os defaults cobrem campos a menos, e `toDomainOrNull` recusa a linha quando o que falta é
 * essencial — um card com `groupId` vazio não é um Squad, é um item que nenhum toque resolve.
 */
@Serializable
internal data class CreateSocialGroupRequestDto(
    val name: String,
    val clientRequestId: String
)

@Serializable
internal data class CreateGroupInvitationRequestDto(
    val socialId: String,
    val clientRequestId: String
)

@Serializable
internal data class TransferGroupOwnershipRequestDto(
    val membershipId: String
)

@Serializable
internal data class SocialGroupSummaryDto(
    val groupId: String = "",
    val name: String = "",
    val memberCount: Int = 0,
    val role: String = "",
    val createdAt: Long = 0L
) {
    fun toDomainOrNull(): SocialGroup? {
        if (groupId.isBlank()) return null
        val parsedRole = SocialGroupRole.fromWire(role) ?: return null
        return SocialGroup(
            groupId = groupId,
            name = name,
            memberCount = memberCount,
            role = parsedRole,
            createdAt = createdAt
        )
    }
}

@Serializable
internal data class SocialGroupListDto(
    val items: List<SocialGroupSummaryDto> = emptyList()
)

@Serializable
internal data class SocialGroupDetailDto(
    val groupId: String = "",
    val name: String = "",
    val memberCount: Int = 0,
    val role: String = "",
    val createdAt: Long = 0L,
    val pendingInvitationCount: Int? = null
) {
    fun toDomainOrNull(): SocialGroupDetail? {
        if (groupId.isBlank()) return null
        val parsedRole = SocialGroupRole.fromWire(role) ?: return null
        return SocialGroupDetail(
            groupId = groupId,
            name = name,
            memberCount = memberCount,
            role = parsedRole,
            createdAt = createdAt,
            pendingInvitationCount = pendingInvitationCount
        )
    }
}

/**
 * Um participante.
 *
 * `socialId` e `displayName` são **nuláveis por contrato**, e não por conveniência: quando existe
 * bloqueio entre o viewer e este membro, o servidor os omite e a tela desenha "Participante
 * indisponível" (§34/§136). Um default `""` aqui esconderia a diferença entre "sem identidade por
 * bloqueio" e "identidade vazia por defeito", e a tela acabaria mostrando um item em branco.
 */
@Serializable
internal data class SocialGroupMemberDto(
    val membershipId: String = "",
    val role: String = "",
    val joinedAt: Long = 0L,
    val available: Boolean = true,
    val socialId: String? = null,
    val displayName: String? = null,
    val isCurrentUser: Boolean = false
) {
    fun toDomainOrNull(): SocialGroupMember? {
        if (membershipId.isBlank()) return null
        val parsedRole = SocialGroupRole.fromWire(role) ?: return null
        return SocialGroupMember(
            membershipId = membershipId,
            role = parsedRole,
            joinedAt = joinedAt,
            available = available,
            // Defesa em profundidade: se um servidor futuro mandasse identidade junto de
            // `available = false`, a tela ainda não a mostraria.
            socialId = if (available) socialId else null,
            displayName = if (available) displayName else null,
            isCurrentUser = isCurrentUser
        )
    }
}

@Serializable
internal data class SocialGroupMembersDto(
    val items: List<SocialGroupMemberDto> = emptyList()
)

@Serializable
internal data class SocialGroupInvitationDto(
    val invitationId: String = "",
    val groupId: String = "",
    val groupName: String = "",
    val memberCount: Int = 0,
    val inviterSocialId: String? = null,
    val inviterDisplayName: String? = null,
    val status: String = "",
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L
) {
    fun toDomainOrNull(): SocialGroupInvitation? {
        if (invitationId.isBlank() || groupId.isBlank()) return null
        return SocialGroupInvitation(
            invitationId = invitationId,
            groupId = groupId,
            groupName = groupName,
            memberCount = memberCount,
            inviterSocialId = inviterSocialId,
            inviterDisplayName = inviterDisplayName,
            createdAt = createdAt,
            expiresAt = expiresAt
        )
    }
}

@Serializable
internal data class SocialGroupInvitationListDto(
    val items: List<SocialGroupInvitationDto> = emptyList()
)

/**
 * Um item do feed do Squad.
 *
 * Ele **envelopa** o `WorkoutCheckInDto` da T17.8/T17.9 em vez de redeclarar os campos (§50): é a
 * mesma publicação, e uma segunda declaração divergiria no próximo campo novo.
 */
@Serializable
internal data class SocialGroupFeedItemDto(
    val checkIn: WorkoutCheckInDto = WorkoutCheckInDto(),
    val sharedToGroupAt: Long = 0L
) {
    fun toDomainOrNull(): SocialGroupFeedItem? {
        val domain = checkIn.toDomainOrNull() ?: return null
        return SocialGroupFeedItem(checkIn = domain, sharedToGroupAt = sharedToGroupAt)
    }
}

@Serializable
internal data class SocialGroupFeedDto(
    val items: List<SocialGroupFeedItemDto> = emptyList()
)

@Serializable
internal data class SocialGroupShareDto(
    val item: SocialGroupFeedItemDto = SocialGroupFeedItemDto(),
    val sharedGroupCount: Int = 0
)

@Serializable
internal data class CheckInGroupSharesDto(
    val groupIds: List<String> = emptyList()
)
