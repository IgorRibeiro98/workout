package com.example.domain.evolution.repository

import com.example.domain.evolution.model.timeline.EvolutionTimelineEvent
import kotlinx.coroutines.flow.Flow

interface TimelineRepository {
    fun getTimelineFlow(): Flow<List<EvolutionTimelineEvent>>
}
