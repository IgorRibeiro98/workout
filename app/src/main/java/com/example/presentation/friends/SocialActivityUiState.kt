package com.example.presentation.friends

import com.example.domain.social.FriendActivityItem
import com.example.domain.social.FriendRankingEntry

sealed interface RankingUiState {
    data object Loading : RankingUiState
    data object OptedOut : RankingUiState
    data class Success(val entries: List<FriendRankingEntry>, val participantCount: Int) : RankingUiState
    data class Error(val message: String) : RankingUiState
}

sealed interface ActivityFeedUiState {
    data object Loading : ActivityFeedUiState
    data class Success(val items: List<FriendActivityItem>) : ActivityFeedUiState
    data class Error(val message: String) : ActivityFeedUiState
}

data class SocialActivityUiState(
    val rankingState: RankingUiState = RankingUiState.Loading,
    val activityState: ActivityFeedUiState = ActivityFeedUiState.Loading,
    val isOptingIn: Boolean = false,
    val optInErrorMessage: String? = null
)
