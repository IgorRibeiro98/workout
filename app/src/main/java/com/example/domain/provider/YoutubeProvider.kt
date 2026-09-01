package com.example.domain.provider

class YoutubeProvider : ExerciseMediaProvider {
    override val providerId: String = "youtube"
    override val providerName: String = "YouTube Video Provider"
    override val isEnabled: Boolean = false

    override suspend fun searchMedia(
        exerciseId: String,
        exerciseName: String?,
        query: String?
    ): MediaResult {
        return MediaResult(
            providerName = providerName,
            isSuccess = false,
            errorMessage = "Provedor YouTube não ativado nesta versão"
        )
    }
}
