package com.example.feature.evolution.timeline

import com.example.domain.evolution.model.timeline.EvolutionTimelineEvent

data class TimelineUiState(
    val isLoading: Boolean = true,
    val events: List<EvolutionTimelineEvent> = emptyList(),
    val error: String? = null
) {
    val isEmpty: Boolean
        get() = !isLoading && error == null && events.isEmpty()
}
