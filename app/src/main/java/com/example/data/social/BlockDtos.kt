package com.example.data.social

import com.example.domain.social.BlockedUser
import kotlinx.serialization.Serializable

@Serializable
data class BlockUserRequestDto(
    val blockedSocialId: String
)

@Serializable
data class BlockedUserItemDto(
    val socialId: String,
    val displayName: String,
    val blockedAt: Long
) {
    fun toDomain() = BlockedUser(
        socialId = socialId,
        displayName = displayName,
        blockedAt = blockedAt
    )
}

@Serializable
data class BlockUserResponseDto(
    val result: String,
    val blockedSocialId: String
)

@Serializable
data class UnblockUserResponseDto(
    val result: String,
    val unblockedSocialId: String
)

@Serializable
data class ListBlockedUsersResponseDto(
    val blockedUsers: List<BlockedUserItemDto> = emptyList()
)
