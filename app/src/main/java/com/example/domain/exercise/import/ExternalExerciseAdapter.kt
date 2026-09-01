package com.example.domain.exercise.import

import com.example.data.remote.NetworkResult
import com.example.data.remote.provider.ExerciseApiProvider

interface ExternalExerciseAdapter {
    val sourceName: String
    suspend fun fetchCatalog(limit: Int = 100, offset: Int = 0): List<ExternalExerciseDTO>
}

class ExerciseDbV1Adapter(
    private val provider: ExerciseApiProvider
) : ExternalExerciseAdapter {
    override val sourceName: String = "EXERCISE_DB_V1"

    override suspend fun fetchCatalog(limit: Int, offset: Int): List<ExternalExerciseDTO> {
        val result = provider.fetchExternalCatalog(limit, offset)
        if (result is NetworkResult.Success) {
            return result.data.map { dto ->
                ExternalExerciseDTO(
                    externalId = dto.id ?: dto.name ?: "",
                    name = dto.name ?: "",
                    bodyParts = listOfNotNull(dto.bodyPart),
                    targetMuscles = listOfNotNull(dto.target),
                    equipment = dto.equipment,
                    instructions = dto.instructions ?: emptyList(),
                    mediaUrls = listOfNotNull(dto.gifUrl)
                )
            }
        }
        return emptyList()
    }
}

class ExerciseDbV2Adapter(
    private val provider: ExerciseApiProvider
) : ExternalExerciseAdapter {
    override val sourceName: String = "EXERCISE_DB_V2"

    override suspend fun fetchCatalog(limit: Int, offset: Int): List<ExternalExerciseDTO> {
        val result = provider.fetchExternalCatalog(limit, offset)
        if (result is NetworkResult.Success) {
            return result.data.map { dto ->
                ExternalExerciseDTO(
                    externalId = dto.id ?: dto.name ?: "",
                    name = dto.name ?: "",
                    bodyParts = listOfNotNull(dto.bodyPart),
                    targetMuscles = listOfNotNull(dto.target),
                    equipment = dto.equipment,
                    instructions = dto.instructions ?: emptyList(),
                    mediaUrls = listOfNotNull(dto.gifUrl)
                )
            }
        }
        return emptyList()
    }
}
