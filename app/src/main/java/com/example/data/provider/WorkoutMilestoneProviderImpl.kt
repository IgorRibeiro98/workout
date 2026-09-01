package com.example.data.provider

import com.example.domain.evolution.provider.WorkoutMilestoneDates
import com.example.domain.evolution.provider.WorkoutMilestoneProvider
import com.example.domain.evolution.repository.ConsistencyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WorkoutMilestoneProviderImpl(
    private val consistencyRepository: ConsistencyRepository
) : WorkoutMilestoneProvider {

    override fun getWorkoutMilestoneDatesFlow(): Flow<WorkoutMilestoneDates> {
        return consistencyRepository.getWorkoutTimestampsFlow().map { rawTimestamps ->
            val sorted = rawTimestamps.filter { it > 0 }.sorted()
            WorkoutMilestoneDates(
                firstWorkoutDate = sorted.getOrNull(0),
                tenthWorkoutDate = sorted.getOrNull(9),
                fiftiethWorkoutDate = sorted.getOrNull(49),
                hundredthWorkoutDate = sorted.getOrNull(99)
            )
        }
    }
}
