package com.example.domain.engine

import android.content.Context
import android.net.Uri
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity

object ExerciseMediaResolver {

    fun resolveMedia(
        exercise: ExerciseEntity?,
        override: ExerciseUserOverrideEntity? = null,
        showGifs: Boolean = true
    ): ResolvedMedia {
        // Priority 1: User custom photo URI (from override or exercise entity)
        val customPhoto = override?.customPhotoUri ?: exercise?.customPhotoUri
        if (!customPhoto.isNullOrBlank()) {
            return ResolvedMedia(
                mediaUri = customPhoto,
                isCustomPhoto = true,
                isGif = false
            )
        }

        // Priority 2: GIF URL (if showGifs is enabled)
        if (showGifs) {
            val gifUrl = exercise?.gifUrl
            if (!gifUrl.isNullOrBlank()) {
                return ResolvedMedia(
                    mediaUri = gifUrl,
                    isCustomPhoto = false,
                    isGif = true
                )
            }
        }

        // Priority 3: Static media URL / fallback
        val mediaUrl = exercise?.mediaUrl
        if (!mediaUrl.isNullOrBlank()) {
            return ResolvedMedia(
                mediaUri = mediaUrl,
                isCustomPhoto = false,
                isGif = false
            )
        }

        // Fallback: None
        return ResolvedMedia(
            mediaUri = null,
            isCustomPhoto = false,
            isGif = false
        )
    }

    fun resolveDisplayName(
        exercise: ExerciseEntity?,
        override: ExerciseUserOverrideEntity?,
        fallbackName: String = ""
    ): String {
        return override?.displayName?.takeIf { it.isNotBlank() }
            ?: exercise?.name?.takeIf { it.isNotBlank() }
            ?: fallbackName
    }

    fun resolveNotes(
        exercise: ExerciseEntity?,
        override: ExerciseUserOverrideEntity?
    ): String? {
        return override?.notes?.takeIf { it.isNotBlank() }
            ?: exercise?.description
    }

    fun resolveRestSeconds(
        exercise: ExerciseEntity?,
        override: ExerciseUserOverrideEntity?,
        templateRest: Int?,
        defaultRest: Int = 90
    ): Int {
        return override?.defaultRestSeconds
            ?: templateRest
            ?: defaultRest
    }
}

data class ResolvedMedia(
    val mediaUri: String?,
    val isCustomPhoto: Boolean,
    val isGif: Boolean
)
