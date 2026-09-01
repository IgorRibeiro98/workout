package com.example.data.remote.provider

import com.example.data.local.WorkoutDao
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkResult
import com.example.data.remote.NetworkTestResult
import kotlinx.coroutines.flow.first

class ExerciseLocalCacheProvider(
    private val dao: WorkoutDao
) : ExerciseApiProvider {

    override val providerType: ProviderType = ProviderType.LOCAL_CACHE

    override suspend fun fetchExternalCatalog(limit: Int, offset: Int): NetworkResult<List<ExternalExerciseDto>> {
        val exercises = dao.getAllExercisesList()
        val dtos = exercises.drop(offset).take(limit).map { entity ->
            ExternalExerciseDto(
                id = entity.id.toString(),
                name = entity.name,
                gifUrl = entity.gifUrl,
                target = entity.primaryMuscle,
                equipment = entity.equipment
            )
        }
        return NetworkResult.Success(dtos)
    }

    override suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>> {
        val cleanQuery = query.trim().lowercase()
        val exercises = dao.getAllExercisesList().filter {
            it.name.lowercase().contains(cleanQuery) ||
            (it.primaryMuscle?.lowercase()?.contains(cleanQuery) == true)
        }
        val dtos = exercises.map { entity ->
            ExternalExerciseDto(
                id = entity.id.toString(),
                name = entity.name,
                gifUrl = entity.gifUrl,
                target = entity.primaryMuscle,
                equipment = entity.equipment
            )
        }
        return NetworkResult.Success(dtos)
    }

    override suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto> {
        val longId = id.toLongOrNull() ?: return NetworkResult.NotFound
        val entity = dao.getExerciseById(longId) ?: return NetworkResult.NotFound
        return NetworkResult.Success(
            ExternalExerciseDto(
                id = entity.id.toString(),
                name = entity.name,
                gifUrl = entity.gifUrl,
                target = entity.primaryMuscle,
                equipment = entity.equipment
            )
        )
    }

    override suspend fun testConnection(query: String): NetworkTestResult {
        val res = searchExercises(query)
        return if (res is NetworkResult.Success && res.data.isNotEmpty()) {
            val first = res.data.first()
            NetworkTestResult.Success(
                query = query,
                foundName = first.name,
                exerciseId = first.realId,
                gifUrl = first.gifUrl,
                totalResults = res.data.size
            )
        } else {
            NetworkTestResult.Failure(
                errorMessage = "Nenhum exercício encontrado no cache local para '$query'."
            )
        }
    }
}
