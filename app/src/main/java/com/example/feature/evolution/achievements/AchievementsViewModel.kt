package com.example.feature.evolution.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evolution.repository.AchievementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AchievementsViewModel(
    private val achievementRepository: AchievementRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AchievementsUiState(isLoading = true))
    val uiState: StateFlow<AchievementsUiState> = _uiState.asStateFlow()

    init {
        loadAchievements()
    }

    fun loadAchievements() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            achievementRepository.getAchievementsFlow()
                .catch { e ->
                    _uiState.value = AchievementsUiState(
                        isLoading = false,
                        error = e.message ?: "Não foi possível carregar as conquistas."
                    )
                }
                .collect { achievements ->
                    _uiState.value = AchievementsUiState(
                        isLoading = false,
                        achievements = achievements,
                        unlockedCount = achievements.count { it.unlockedAt != null },
                        error = null
                    )
                }
        }
    }
}
