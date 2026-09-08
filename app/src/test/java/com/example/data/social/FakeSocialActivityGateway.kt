package com.example.data.social

import com.example.domain.social.FriendActivityItem
import com.example.domain.social.FriendRankingEntry
import com.example.domain.social.FriendRankingLeaderboard
import com.example.domain.social.SocialActivityError
import com.example.domain.social.SocialActivityGateway
import com.example.domain.social.SocialActivityOutcome
import kotlinx.coroutines.CompletableDeferred

/**
 * Fake em memória do gateway de atividade e ranking social (T17.4) para testes unitários no Android.
 */
class FakeSocialActivityGateway(
    override val isConfigured: Boolean = true
) : SocialActivityGateway {

    var currentUid: String? = "test-uid"
    var failWithActivity: SocialActivityError? = null
    var failWithRanking: SocialActivityError? = null

    var gate: CompletableDeferred<Unit>? = null

    var activityCalls = 0
        private set
    var rankingCalls = 0
        private set

    var seededActivity: List<FriendActivityItem> = emptyList()
    var seededRanking: FriendRankingLeaderboard = FriendRankingLeaderboard(0, emptyList())

    override suspend fun getRecentFriendActivity(): SocialActivityOutcome<List<FriendActivityItem>> {
        activityCalls++
        gate?.await()
        if (!isConfigured) return SocialActivityOutcome.Failure(SocialActivityError.NOT_CONFIGURED)
        if (currentUid == null) return SocialActivityOutcome.Failure(SocialActivityError.AUTH_REQUIRED)
        failWithActivity?.let { return SocialActivityOutcome.Failure(it) }
        return SocialActivityOutcome.Success(seededActivity)
    }

    override suspend fun getFriendRankingLast7Days(): SocialActivityOutcome<FriendRankingLeaderboard> {
        rankingCalls++
        gate?.await()
        if (!isConfigured) return SocialActivityOutcome.Failure(SocialActivityError.NOT_CONFIGURED)
        if (currentUid == null) return SocialActivityOutcome.Failure(SocialActivityError.AUTH_REQUIRED)
        failWithRanking?.let { return SocialActivityOutcome.Failure(it) }
        return SocialActivityOutcome.Success(seededRanking)
    }
}
