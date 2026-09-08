package com.example.data.social

import com.example.domain.social.FriendActivityItem
import com.example.domain.social.FriendRankingEntry
import com.example.domain.social.FriendRankingLeaderboard
import kotlinx.serialization.Serializable

@Serializable
data class SocialActivityActorDto(
    val socialId: String,
    val displayName: String
)

@Serializable
data class SocialActivityItemDto(
    val type: String,
    val actor: SocialActivityActorDto,
    val daysAgo: Int
)

@Serializable
data class SocialActivityResponseDto(
    val items: List<SocialActivityItemDto> = emptyList()
)

@Serializable
data class FriendRankingEntryDto(
    val socialId: String,
    val displayName: String,
    val score: Int,
    val rank: Int,
    val isCurrentUser: Boolean
)

@Serializable
data class FriendRankingResponseDto(
    val type: String,
    val participantCount: Int,
    val entries: List<FriendRankingEntryDto> = emptyList()
)

fun SocialActivityItemDto.toDomain(): FriendActivityItem? {
    if (type != "TRAINING_DAY") return null
    if (actor.socialId.isBlank() || actor.displayName.isBlank()) return null
    if (daysAgo !in 0..13) return null
    return FriendActivityItem(
        socialId = actor.socialId,
        displayName = actor.displayName,
        daysAgo = daysAgo
    )
}

fun FriendRankingResponseDto.toDomain(): FriendRankingLeaderboard {
    val domainEntries = entries.map {
        FriendRankingEntry(
            socialId = it.socialId,
            displayName = it.displayName,
            score = it.score,
            rank = it.rank,
            isCurrentUser = it.isCurrentUser
        )
    }
    return FriendRankingLeaderboard(
        participantCount = participantCount,
        entries = domainEntries
    )
}
