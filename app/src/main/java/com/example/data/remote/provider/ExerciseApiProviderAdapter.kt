package com.example.data.remote.provider

import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkResult
import com.example.data.remote.NetworkTestResult

class ExerciseApiProviderAdapter(
    private val provider: ExerciseApiProvider
) : ExerciseRemoteDataSource {

    override suspend fun fetchExternalCatalog(limit: Int, offset: Int): NetworkResult<List<ExternalExerciseDto>> {
        return provider.fetchExternalCatalog(limit, offset)
    }

    override suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>> {
        return provider.searchExercises(query)
    }

    override suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto> {
        return provider.getExerciseById(id)
    }

    override suspend fun testConnection(query: String): NetworkTestResult {
        return provider.testConnection(query)
    }
}
