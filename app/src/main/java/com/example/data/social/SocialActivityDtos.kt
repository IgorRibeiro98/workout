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

fun SocialActivityResponseDto.toDomain(): List<FriendActivityItem>? {
    val result = ArrayList<FriendActivityItem>(items.size)
    for (item in items) {
        val domainItem = item.toDomain() ?: return null
        result.add(domainItem)
    }
    return result
}

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

fun FriendRankingResponseDto.toDomain(): FriendRankingLeaderboard? {
    if (type != "WORKOUTS_COMPLETED_LAST_7_DAYS") return null
    if (participantCount < 0) return null
    if (participantCount < entries.size) return null

    val domainEntries = ArrayList<FriendRankingEntry>(entries.size)
    for (entry in entries) {
        if (entry.socialId.isBlank() || entry.displayName.isBlank()) return null
        if (entry.score < 0) return null
        if (entry.rank < 1) return null

        domainEntries.add(
            FriendRankingEntry(
                socialId = entry.socialId,
                displayName = entry.displayName,
                score = entry.score,
                rank = entry.rank,
                isCurrentUser = entry.isCurrentUser
            )
        )
    }
    return FriendRankingLeaderboard(
        participantCount = participantCount,
        entries = domainEntries
    )
}
