package com.example.domain.engine

import android.content.Context
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.data.remote.ExerciseMediaRepository
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkExerciseRemoteDataSource

enum class ExerciseMatchStatus {
    MATCHED,
    AMBIGUOUS,
    NOT_FOUND,
    UNVERIFIED
}

data class MatchEvaluation(
    val candidate: ExternalExerciseDto?,
    val score: Int,
    val status: ExerciseMatchStatus
)

data class MediaSyncResult(
    val matched: Int = 0,
    val ambiguous: Int = 0,
    val notFound: Int = 0,
    val isOffline: Boolean = false,
    val errors: List<String> = emptyList()
)

class ExerciseMediaEngine(
    private val repository: ExerciseMediaRepository
) {
    constructor(
        dao: WorkoutDao,
        remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource(),
        context: Context? = null
    ) : this(ExerciseMediaRepository(dao, remoteDataSource, context))

    suspend fun syncExerciseGifs(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): MediaSyncResult = repository.syncExerciseGifs(onProgress)

    fun evaluateCandidates(
        exercise: ExerciseEntity,
        candidates: List<ExternalExerciseDto>
    ): MatchEvaluation = repository.evaluateCandidates(exercise, candidates)
}
