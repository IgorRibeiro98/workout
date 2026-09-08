package com.example.data.social

import com.example.domain.social.SocialDiscoverability
import com.example.domain.social.SocialPrivacySettings
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import kotlinx.serialization.Serializable

/**
 * Os contratos de serialização do domínio social (T17.0).
 *
 * ## O que **não** existe nestes DTOs, e por quê
 *
 * Não há `ownerUid`, `uid`, `firebaseUid` nem `email` — em nenhuma direção. Na resposta, porque o
 * Firebase UID é identidade privada de infraestrutura e nunca identidade pública. Na requisição,
 * porque o dono sai do token verificado: um campo desses no corpo faz o servidor **recusar** a
 * requisição inteira, e não existir é melhor do que existir e ser ignorado.
 *
 * Também não há `socialId`, `friendCode`, `status` nem timestamps em nenhum corpo de requisição:
 * os quatro são gerados pelo servidor, e propor um deles é um erro de cliente, não uma
 * preferência.
 */

/** `GET /v1/social/me`. `enabled=false` é o estado normal de quem nunca ativou — não é erro. */
@Serializable
data class SocialMeResponseDto(
    val enabled: Boolean,
    val profile: SocialOwnerProfileDto? = null
)

/** Toda rota que devolve o perfil do dono responde com este envelope. */
@Serializable
data class SocialProfileResponseDto(
    val profile: SocialOwnerProfileDto
)

/**
 * O perfil do dono.
 *
 * `friendCode` está aqui porque o **próprio** usuário precisa lê-lo para convidar alguém. Isso não
 * o coloca em toda projeção social: o preview que outra pessoa verá a partir da T17.1 é outro
 * DTO, e não carrega o código.
 */
@Serializable
data class SocialOwnerProfileDto(
    val socialId: String,
    val friendCode: String,
    val displayName: String,
    val status: String,
    val privacy: SocialPrivacyDto,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class SocialPrivacyDto(
    val discoverability: String,
    val friendRequestsEnabled: Boolean,
    val activitySharingEnabled: Boolean,
    val updatedAt: Long
)

/** `POST /v1/social/me/activate`. Só o nome: todo o resto é do servidor. */
@Serializable
data class ActivateSocialRequestDto(
    val displayName: String
)

/** `PATCH /v1/social/me`. */
@Serializable
data class UpdateSocialProfileRequestDto(
    val displayName: String
)

/**
 * `PATCH /v1/social/me/privacy`.
 *
 * Campos nulos são **omitidos** na serialização (`explicitNulls = false` no `Json` do gateway):
 * `null` aqui significa "não mexer neste campo", e mandar `null` explícito faria o servidor
 * recusar por tipo — que é o comportamento certo dele, e o motivo de a omissão ser do cliente.
 */
@Serializable
data class UpdateSocialPrivacyRequestDto(
    val discoverability: String? = null,
    val friendRequestsEnabled: Boolean? = null,
    val activitySharingEnabled: Boolean? = null
)

/**
 * O DTO vira domínio aqui, e um valor desconhecido é recusado.
 *
 * `status` e `discoverability` são enums do servidor. Um valor que este APK não conhece — uma
 * versão nova do servidor — devolve `null` em vez de virar um default: escolher `ACTIVE` para um
 * status desconhecido faria a tela afirmar algo que ela não sabe, e escolher `FRIEND_CODE_ONLY`
 * para uma descoberta desconhecida diria ao usuário que ele está mais protegido do que está.
 */
fun SocialOwnerProfileDto.toDomain(): SocialProfile? {
    val parsedStatus = SocialProfileStatus.entries.firstOrNull { it.name == status } ?: return null
    val parsedDiscoverability = SocialDiscoverability.entries
        .firstOrNull { it.name == privacy.discoverability } ?: return null

    if (socialId.isBlank() || friendCode.isBlank() || displayName.isBlank()) return null

    return SocialProfile(
        socialId = socialId,
        friendCode = friendCode,
        displayName = displayName,
        status = parsedStatus,
        privacy = SocialPrivacySettings(
            discoverability = parsedDiscoverability,
            friendRequestsEnabled = privacy.friendRequestsEnabled,
            activitySharingEnabled = privacy.activitySharingEnabled,
            updatedAt = privacy.updatedAt
        ),
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
