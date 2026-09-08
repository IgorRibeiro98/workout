package com.example.domain.social

/**
 * Atividade recente de um amigo (T17.4).
 *
 * Exclusivamente projeção agregada `TRAINING_DAY` nos últimos 14 dias (dias 0 a 13).
 * Não expõe horários absolutos, payloads, exercícios, cargas ou notas.
 */
data class FriendActivityItem(
    val socialId: String,
    val displayName: String,
    val daysAgo: Int
)

/**
 * Entrada de um participante no ranking contextual de 7 dias entre amigos (T17.4).
 */
data class FriendRankingEntry(
    val socialId: String,
    val displayName: String,
    val score: Int,
    val rank: Int,
    val isCurrentUser: Boolean
)

/**
 * Leaderboard contextual dos últimos 7 dias (T17.4).
 */
data class FriendRankingLeaderboard(
    val participantCount: Int,
    val entries: List<FriendRankingEntry>
)

/**
 * Resultado de operações de atividade e ranking social.
 */
sealed interface SocialActivityOutcome<out T> {
    data class Success<T>(val data: T) : SocialActivityOutcome<T>
    data class Failure(val error: SocialActivityError) : SocialActivityOutcome<Nothing>
}

enum class SocialActivityError {
    NOT_CONFIGURED,
    AUTH_REQUIRED,
    NOT_ENABLED,
    RANKING_NOT_ENABLED,
    NETWORK,
    REJECTED,
    UNAVAILABLE
}

/**
 * A fronteira com o Spark Backend para atividade de amigos e rankings (T17.4).
 */
interface SocialActivityGateway {
    val isConfigured: Boolean

    suspend fun getRecentFriendActivity(): SocialActivityOutcome<List<FriendActivityItem>>

    suspend fun getFriendRankingLast7Days(): SocialActivityOutcome<FriendRankingLeaderboard>
}
