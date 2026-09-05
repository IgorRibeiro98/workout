package com.example.presentation.missions

/**
 * Projeção das missões para a tela.
 *
 * Cada campo é cópia do que a autoridade de missões já decidiu. A Composable só formata: nenhuma
 * regra de conclusão, alvo, período ou recompensa nasce aqui.
 */
data class MissionUiState(
    val isLoading: Boolean = true,
    val activeMissions: List<MissionUiItem> = emptyList(),
    val completedMissions: List<MissionUiItem> = emptyList(),
    val expiredMissions: List<MissionUiItem> = emptyList(),
    /** Soma das recompensas ainda em aberto — origem: recompensa das missões ativas. */
    val availableRewardXp: Int = 0,
    /** Soma das recompensas já concedidas nas conclusões exibidas. */
    val earnedRewardXp: Int = 0
) {
    val hasMissions: Boolean
        get() = activeMissions.isNotEmpty() ||
            completedMissions.isNotEmpty() ||
            expiredMissions.isNotEmpty()
}

/** Uma missão pronta para render. */
data class MissionUiItem(
    val id: String,
    val periodKey: String,
    val title: String,
    val description: String,
    val progress: Int,
    val target: Int,
    val progressPercentage: Float,
    val rewardXp: Int,
    val expiresAt: Long? = null,
    val completedAt: Long? = null
)
