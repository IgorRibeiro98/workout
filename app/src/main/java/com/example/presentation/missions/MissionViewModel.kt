package com.example.presentation.missions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.gamification.model.mission.MissionProgress
import com.example.domain.gamification.model.mission.MissionStatus
import com.example.domain.gamification.repository.MissionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Observa a autoridade de missões e projeta [MissionUiState].
 *
 * ```
 * MissionRepository -> MissionViewModel -> MissionUiState -> MissionsScreen
 * ```
 *
 * O ViewModel não conhece alvo, período nem regra de conclusão: ele só separa o que já veio
 * decidido e ordena para a leitura.
 */
class MissionViewModel(
    private val missionRepository: MissionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MissionUiState())
    val uiState: StateFlow<MissionUiState> = _uiState.asStateFlow()

    init {
        observeMissions()
    }

    private fun observeMissions() {
        viewModelScope.launch {
            missionRepository.getMissionsFlow().collect { missions ->
                val active = missions.filter { it.status == MissionStatus.ACTIVE }
                val completed = missions
                    .filter { it.status == MissionStatus.COMPLETED }
                    .sortedByDescending { it.completedAt ?: 0L }
                    .take(MAX_COMPLETED_PREVIEW)
                val expired = missions.filter { it.status == MissionStatus.EXPIRED }

                _uiState.value = MissionUiState(
                    isLoading = false,
                    activeMissions = active.map { it.toUiItem() },
                    completedMissions = completed.map { it.toUiItem() },
                    expiredMissions = expired.map { it.toUiItem() },
                    availableRewardXp = active.sumOf { it.rewardXp },
                    earnedRewardXp = completed.sumOf { it.rewardXp }
                )
            }
        }
    }

    private fun MissionProgress.toUiItem() = MissionUiItem(
        id = missionId,
        periodKey = periodKey,
        title = title,
        description = description,
        progress = progress,
        target = target,
        progressPercentage = progressPercentage,
        rewardXp = rewardXp,
        expiresAt = periodEndsAt,
        completedAt = completedAt
    )

    private companion object {
        /** A tela mostra as conclusões recentes; o histórico completo não é o objetivo desta área. */
        const val MAX_COMPLETED_PREVIEW = 5
    }
}
