package com.example.feature.evolution.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementCatalog
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.repository.AchievementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AchievementsViewModel(
    private val achievementRepository: AchievementRepository
) : ViewModel() {

    private val _rawAchievements = MutableStateFlow<List<Achievement>>(emptyList())
    private val _selectedCategory = MutableStateFlow<AchievementCategory?>(null)
    private val _selectedAchievementForDetail = MutableStateFlow<Achievement?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow(AchievementsUiState(isLoading = true))
    val uiState: StateFlow<AchievementsUiState> = _uiState.asStateFlow()

    init {
        observeState()
        loadAchievements()
    }

    private fun observeState() {
        viewModelScope.launch {
            combine(
                _rawAchievements,
                _selectedCategory,
                _selectedAchievementForDetail,
                _isLoading,
                _error
            ) { rawList, category, selectedDetail, loading, err ->
                val filtered = if (category != null) {
                    rawList.filter { it.category == category }
                } else {
                    rawList
                }
                val sorted = sortAchievements(filtered)
                AchievementsUiState(
                    isLoading = loading,
                    allAchievements = rawList,
                    displayedAchievements = sorted,
                    unlockedCount = rawList.count { it.unlockedAt != null },
                    totalCount = rawList.size,
                    selectedCategory = category,
                    selectedAchievementForDetail = selectedDetail,
                    error = err
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun loadAchievements() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            achievementRepository.getAchievementsFlow()
                .catch { e ->
                    _isLoading.value = false
                    _error.value = e.message ?: "Não foi possível carregar as conquistas."
                }
                .collect { achievements ->
                    _rawAchievements.value = achievements
                    _isLoading.value = false
                    _error.value = null
                }
        }
    }

    fun selectCategory(category: AchievementCategory?) {
        _selectedCategory.value = category
    }

    fun selectAchievementForDetail(achievement: Achievement?) {
        _selectedAchievementForDetail.value = achievement
    }

    private fun sortAchievements(list: List<Achievement>): List<Achievement> {
        return list.sortedWith(
            Comparator { a, b ->
                val aUnlocked = a.unlockedAt != null
                val bUnlocked = b.unlockedAt != null
                when {
                    aUnlocked && bUnlocked -> {
                        // 1 & 2: recém-desbloqueada, desbloqueadas (unlockedAt descending)
                        (b.unlockedAt ?: 0L).compareTo(a.unlockedAt ?: 0L)
                    }
                    aUnlocked && !bUnlocked -> -1
                    !aUnlocked && bUnlocked -> 1
                    else -> {
                        // 3 & 4: próxima(s) com maior progresso relativo, restantes
                        val progressCompare = b.progress.compareTo(a.progress)
                        if (progressCompare != 0) {
                            progressCompare
                        } else {
                            val orderA = AchievementCatalog.getDefinition(a.id)?.order ?: 0
                            val orderB = AchievementCatalog.getDefinition(b.id)?.order ?: 0
                            orderA.compareTo(orderB)
                        }
                    }
                }
            }
        )
    }
}
