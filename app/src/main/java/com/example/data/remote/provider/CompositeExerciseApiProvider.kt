package com.example.data.remote.provider

import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkResult
import com.example.data.remote.NetworkTestResult

class CompositeExerciseApiProvider(
    private val v1Provider: ExerciseApiProvider,
    private val v2Provider: ExerciseApiProvider,
    private val localCacheProvider: ExerciseApiProvider
) : ExerciseApiProvider {

    override val providerType: ProviderType = ProviderType.V1_OSS

    override suspend fun fetchExternalCatalog(limit: Int, offset: Int): NetworkResult<List<ExternalExerciseDto>> {
        val localRes = localCacheProvider.fetchExternalCatalog(limit, offset)
        if (localRes is NetworkResult.Success && localRes.data.isNotEmpty()) return localRes

        val v1Res = v1Provider.fetchExternalCatalog(limit, offset)
        if (v1Res is NetworkResult.Success && v1Res.data.isNotEmpty()) return v1Res

        return v2Provider.fetchExternalCatalog(limit, offset)
    }

    override suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>> {
        val localRes = localCacheProvider.searchExercises(query)
        if (localRes is NetworkResult.Success && localRes.data.isNotEmpty()) return localRes

        val v1Res = v1Provider.searchExercises(query)
        if (v1Res is NetworkResult.Success && v1Res.data.isNotEmpty()) return v1Res

        return v2Provider.searchExercises(query)
    }

    override suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto> {
        val localRes = localCacheProvider.getExerciseById(id)
        if (localRes is NetworkResult.Success) return localRes

        val v1Res = v1Provider.getExerciseById(id)
        if (v1Res is NetworkResult.Success) return v1Res

        return v2Provider.getExerciseById(id)
    }

    override suspend fun testConnection(query: String): NetworkTestResult {
        val v1Res = v1Provider.testConnection(query)
        if (v1Res is NetworkTestResult.Success) return v1Res

        val v2Res = v2Provider.testConnection(query)
        if (v2Res is NetworkTestResult.Success) return v2Res

        return localCacheProvider.testConnection(query)
    }
}
