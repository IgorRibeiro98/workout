package com.example.domain.evolution.provider

import kotlinx.coroutines.flow.Flow

data class WorkoutMilestoneDates(
    val firstWorkoutDate: Long? = null,
    val tenthWorkoutDate: Long? = null,
    val fiftiethWorkoutDate: Long? = null,
    val hundredthWorkoutDate: Long? = null
)

interface WorkoutMilestoneProvider {
    fun getWorkoutMilestoneDatesFlow(): Flow<WorkoutMilestoneDates>
}
