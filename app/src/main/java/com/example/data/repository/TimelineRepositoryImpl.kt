package com.example.data.repository

import com.example.domain.evolution.calculator.TimelineCalculator
import com.example.domain.evolution.model.timeline.EvolutionTimelineEvent
import com.example.domain.evolution.repository.EvolutionSnapshotRepository
import com.example.domain.evolution.repository.TimelineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TimelineRepositoryImpl(
    private val evolutionSnapshotRepository: EvolutionSnapshotRepository
) : TimelineRepository {

    override fun getTimelineFlow(): Flow<List<EvolutionTimelineEvent>> {
        return evolutionSnapshotRepository.getSnapshotFlow().map { snapshot ->
            TimelineCalculator.calculateTimelineEvents(snapshot)
        }
    }
}
