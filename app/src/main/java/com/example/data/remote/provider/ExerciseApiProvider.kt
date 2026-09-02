package com.example.data.remote.provider

import com.example.data.remote.CatalogPage
import com.example.data.remote.ExerciseDbPaging
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkResult
import com.example.data.remote.NetworkTestResult

enum class ProviderType {
    V1_OSS,
    V2_RAPID,
    LOCAL_CACHE
}

interface ExerciseApiProvider {
    val providerType: ProviderType
    suspend fun fetchExternalCatalog(limit: Int = 100, offset: Int = 0): NetworkResult<List<ExternalExerciseDto>>
    suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>>
    suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto>
    suspend fun testConnection(query: String = "bench press"): NetworkTestResult

    /** Ver [com.example.data.remote.ExerciseRemoteDataSource.fetchCatalogPage]. */
    suspend fun fetchCatalogPage(
        limit: Int = ExerciseDbPaging.MAX_PAGE_SIZE,
        cursor: String? = null
    ): NetworkResult<CatalogPage> = NetworkResult.NotFound
}
