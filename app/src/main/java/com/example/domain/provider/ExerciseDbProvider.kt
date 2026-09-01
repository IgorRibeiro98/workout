package com.example.domain.provider

import com.example.data.datastore.IntegrationSettings
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.NetworkResult

class ExerciseDbProvider(
    private val remoteDataSource: ExerciseRemoteDataSource,
    private var settings: IntegrationSettings = IntegrationSettings()
) : ExerciseMediaProvider {

    override val providerId: String = "exercisedb"
    override val providerName: String = "ExerciseDB API"
    override val isEnabled: Boolean
        get() = settings.exerciseDbEnabled

    fun updateSettings(newSettings: IntegrationSettings) {
        this.settings = newSettings
    }

    override suspend fun searchMedia(
        exerciseId: String,
        exerciseName: String?,
        query: String?
    ): MediaResult {
        if (!settings.exerciseDbEnabled) {
            return MediaResult(
                providerName = providerName,
                isSuccess = false,
                errorMessage = "Integração ExerciseDB desativada nas configurações."
            )
        }

        val searchQuery = query?.takeIf { it.isNotBlank() }
            ?: exerciseName?.takeIf { it.isNotBlank() }
            ?: exerciseId

        val result = remoteDataSource.searchExercises(searchQuery)
        return when (result) {
            is NetworkResult.Success -> {
                val match = result.data.firstOrNull { !it.gifUrl.isNullOrBlank() }
                if (match != null && !match.gifUrl.isNullOrBlank()) {
                    MediaResult(
                        mediaUri = match.gifUrl,
                        isGif = true,
                        providerName = providerName,
                        externalId = match.realId,
                        isSuccess = true
                    )
                } else {
                    MediaResult(
                        providerName = providerName,
                        isSuccess = false,
                        errorMessage = "Nenhum GIF encontrado no ExerciseDB para '$searchQuery'"
                    )
                }
            }
            is NetworkResult.Offline -> {
                MediaResult(
                    providerName = providerName,
                    isSuccess = false,
                    errorMessage = "Modo offline: Sem conexão com internet."
                )
            }
            is NetworkResult.HttpError -> {
                MediaResult(
                    providerName = providerName,
                    isSuccess = false,
                    errorMessage = "Erro HTTP ${result.code}: ${result.message}"
                )
            }
            else -> {
                MediaResult(
                    providerName = providerName,
                    isSuccess = false,
                    errorMessage = "Falha ao buscar mídia no ExerciseDB."
                )
            }
        }
    }
}
