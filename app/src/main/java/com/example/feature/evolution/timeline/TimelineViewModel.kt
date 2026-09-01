package com.example.feature.evolution.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evolution.repository.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class TimelineViewModel(
    private val timelineRepository: TimelineRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState(isLoading = true))
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    init {
        loadTimeline()
    }

    fun loadTimeline() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            timelineRepository.getTimelineFlow()
                .catch { e ->
                    _uiState.value = TimelineUiState(
                        isLoading = false,
                        error = e.message ?: "Não foi possível carregar a linha do tempo."
                    )
                }
                .collect { events ->
                    _uiState.value = TimelineUiState(
                        isLoading = false,
                        events = events,
                        error = null
                    )
                }
        }
    }
}
