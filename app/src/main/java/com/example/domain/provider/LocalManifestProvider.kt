package com.example.domain.provider

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity

class LocalManifestProvider : ExerciseMediaProvider {
    override val providerId: String = "local_manifest"
    override val providerName: String = "Manifesto Local / Premium"
    override val isEnabled: Boolean = true

    override suspend fun searchMedia(
        exerciseId: String,
        exerciseName: String?,
        query: String?
    ): MediaResult {
        return MediaResult(
            mediaUri = null,
            providerName = providerName,
            isSuccess = false,
            errorMessage = "Use resolveLocalMedia method with ExerciseEntity"
        )
    }

    fun resolveLocalMedia(
        exercise: ExerciseEntity?,
        override: ExerciseUserOverrideEntity? = null
    ): MediaResult {
        if (exercise == null) {
            return MediaResult(providerName = providerName, isSuccess = false)
        }

        val customPhoto = override?.customPhotoUri ?: exercise.customPhotoUri
        if (!customPhoto.isNullOrBlank()) {
            return MediaResult(
                mediaUri = customPhoto,
                isCustomPhoto = true,
                isGif = false,
                providerName = providerName,
                isSuccess = true
            )
        }

        val gifUrl = exercise.gifUrl
        if (!gifUrl.isNullOrBlank()) {
            return MediaResult(
                mediaUri = gifUrl,
                isCustomPhoto = false,
                isGif = true,
                providerName = providerName,
                externalId = exercise.externalExerciseId,
                isSuccess = true
            )
        }

        val mediaUrl = exercise.mediaUrl
        if (!mediaUrl.isNullOrBlank()) {
            return MediaResult(
                mediaUri = mediaUrl,
                isCustomPhoto = false,
                isGif = false,
                providerName = providerName,
                isSuccess = true
            )
        }

        return MediaResult(
            providerName = providerName,
            isSuccess = false,
            errorMessage = "Nenhuma mídia local encontrada"
        )
    }
}
