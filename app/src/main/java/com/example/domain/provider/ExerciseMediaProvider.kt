package com.example.domain.provider

interface ExerciseMediaProvider {
    val providerId: String
    val providerName: String
    val isEnabled: Boolean

    suspend fun searchMedia(
        exerciseId: String,
        exerciseName: String? = null,
        query: String? = null
    ): MediaResult
}
