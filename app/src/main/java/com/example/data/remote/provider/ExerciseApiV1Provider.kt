package com.example.data.remote.provider

import com.example.data.remote.CatalogPage
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkResult
import com.example.data.remote.NetworkTestResult

class ExerciseApiV1Provider(
    private val remoteDataSource: ExerciseRemoteDataSource
) : ExerciseApiProvider {

    override val providerType: ProviderType = ProviderType.V1_OSS

    override suspend fun fetchExternalCatalog(limit: Int, offset: Int): NetworkResult<List<ExternalExerciseDto>> {
        return remoteDataSource.fetchExternalCatalog(limit, offset)
    }

    override suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>> {
        return remoteDataSource.searchExercises(query)
    }

    override suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto> {
        return remoteDataSource.getExerciseById(id)
    }

    override suspend fun testConnection(query: String): NetworkTestResult {
        return remoteDataSource.testConnection(query)
    }

    override suspend fun fetchCatalogPage(limit: Int, cursor: String?): NetworkResult<CatalogPage> {
        return remoteDataSource.fetchCatalogPage(limit, cursor)
    }
}
