package com.example.data.social

import com.example.domain.social.Friend
import com.example.domain.social.FriendRelationship
import com.example.domain.social.FriendRequest
import com.example.domain.social.FriendRequestDirection
import com.example.domain.social.SocialProfilePreview
import kotlinx.serialization.Serializable

/**
 * Os contratos de serialização do grafo social (T17.1).
 *
 * ## O que **não** existe nestes DTOs
 *
 * Não há `ownerUid`, `uid`, `firebaseUid` nem `email` — em nenhuma direção. Na resposta, porque o
 * Firebase UID é identidade privada de infraestrutura. Na requisição, porque o dono sai do token
 * verificado, e um campo desses no corpo faz o servidor recusar a requisição inteira.
 *
 * Também não há `friendCode` em nenhuma resposta do grafo: ele aparece no perfil **do dono**
 * (T17.0) porque o dono precisa lê-lo para convidar, e em nenhum lugar mais.
 *
 * ## `socialId` no corpo é referência, não identidade proposta
 *
 * `SendFriendRequestDto` e `RemoveFriendDto` carregam o `socialId` de **outra** pessoa. Isso não
 * contradiz a regra da T17.0 de que o cliente não propõe `socialId`: ele não está dizendo quem
 * ele é — está nomeando quem ele quer alcançar. Nomear não autoriza nada; quem age continua sendo
 * o `uid` do token.
 */

@Serializable
data class SocialProfilePreviewDto(
    val socialId: String,
    val displayName: String
)

/** `POST /v1/social/friends/lookup`. */
@Serializable
data class FriendLookupRequestDto(
    val friendCode: String
)

@Serializable
data class FriendLookupResponseDto(
    val result: String,
    val profile: SocialProfilePreviewDto? = null,
    val relationship: String? = null,
    val canSendFriendRequest: Boolean = false
)

/** `POST /v1/social/friend-requests` e `POST /v1/social/friends/remove`. */
@Serializable
data class SocialIdTargetDto(
    val socialId: String
)

@Serializable
data class FriendRequestDto(
    val requestId: String,
    val profile: SocialProfilePreviewDto,
    val direction: String,
    val createdAt: Long
)

@Serializable
data class FriendDto(
    val socialId: String,
    val displayName: String,
    val friendsSince: Long
)

@Serializable
data class SendFriendRequestResponseDto(
    val result: String,
    val request: FriendRequestDto? = null,
    val friend: FriendDto? = null
)

@Serializable
data class AcceptFriendRequestResponseDto(
    val result: String,
    val friend: FriendDto
)

@Serializable
data class SimpleResultDto(
    val result: String
)

@Serializable
data class FriendRequestListResponseDto(
    val requests: List<FriendRequestDto> = emptyList(),
    val total: Int = 0,
    val nextCursor: String? = null
)

@Serializable
data class FriendListResponseDto(
    val friends: List<FriendDto> = emptyList(),
    val total: Int = 0,
    val nextCursor: String? = null
)

/**
 * O DTO vira domínio aqui, e um valor desconhecido é recusado.
 *
 * `null` em vez de um default: uma direção ou relação que este APK não conhece — uma versão nova
 * do servidor — não pode virar `NONE` ou `INCOMING` por chute. Escolher um valor faria a tela
 * afirmar uma coisa que ela não sabe, e neste domínio a afirmação errada é "vocês já são amigos"
 * ou "você recebeu um pedido".
 */
fun SocialProfilePreviewDto.toDomain(): SocialProfilePreview? {
    if (socialId.isBlank() || displayName.isBlank()) return null
    return SocialProfilePreview(socialId = socialId, displayName = displayName)
}

fun FriendDto.toDomain(): Friend? {
    if (socialId.isBlank() || displayName.isBlank()) return null
    return Friend(socialId = socialId, displayName = displayName, friendsSince = friendsSince)
}

fun FriendRequestDto.toDomain(): FriendRequest? {
    if (requestId.isBlank()) return null
    val preview = profile.toDomain() ?: return null
    val parsedDirection = FriendRequestDirection.entries.firstOrNull { it.name == direction }
        ?: return null
    return FriendRequest(
        requestId = requestId,
        profile = preview,
        direction = parsedDirection,
        createdAt = createdAt
    )
}

fun parseRelationship(raw: String?): FriendRelationship? =
    FriendRelationship.entries.firstOrNull { it.name == raw }
