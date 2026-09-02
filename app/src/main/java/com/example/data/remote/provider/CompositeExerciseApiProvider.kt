package com.example.data.remote.provider

import com.example.data.remote.CatalogPage
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkResult
import com.example.data.remote.NetworkTestResult

/**
 * Encadeia os provedores de exercícios: API OSS, API V2 (RapidAPI, com chave do
 * usuário) e, por último, o cache local.
 *
 * A ordem importa: o cache local devolve linhas do próprio banco, cujo `gifUrl` é
 * nulo justamente enquanto a mídia ainda não foi sincronizada. Consultá-lo primeiro
 * faria a cadeia responder com dados sem mídia e nunca alcançar a rede — por isso
 * ele é o último recurso, e resultados sem `gifUrl` não contam como acerto.
 */
class CompositeExerciseApiProvider(
    private val v1Provider: ExerciseApiProvider,
    private val v2Provider: ExerciseApiProvider,
    private val localCacheProvider: ExerciseApiProvider
) : ExerciseApiProvider {

    override val providerType: ProviderType = ProviderType.V1_OSS

    private fun List<ExternalExerciseDto>.withMedia() = filter { !it.gifUrl.isNullOrBlank() }

    override suspend fun fetchExternalCatalog(limit: Int, offset: Int): NetworkResult<List<ExternalExerciseDto>> {
        val v1Res = v1Provider.fetchExternalCatalog(limit, offset)
        if (v1Res is NetworkResult.Success && v1Res.data.isNotEmpty()) return v1Res

        val v2Res = v2Provider.fetchExternalCatalog(limit, offset)
        if (v2Res is NetworkResult.Success && v2Res.data.isNotEmpty()) return v2Res

        return localCacheProvider.fetchExternalCatalog(limit, offset)
    }

    override suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>> {
        val v1Res = v1Provider.searchExercises(query)
        if (v1Res is NetworkResult.Success && v1Res.data.isNotEmpty()) return v1Res

        val v2Res = v2Provider.searchExercises(query)
        if (v2Res is NetworkResult.Success && v2Res.data.isNotEmpty()) return v2Res

        val localRes = localCacheProvider.searchExercises(query)
        if (localRes is NetworkResult.Success) {
            val withMedia = localRes.data.withMedia()
            if (withMedia.isNotEmpty()) return NetworkResult.Success(withMedia)
        }

        return v1Res
    }

    override suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto> {
        val v1Res = v1Provider.getExerciseById(id)
        if (v1Res is NetworkResult.Success) return v1Res

        val v2Res = v2Provider.getExerciseById(id)
        if (v2Res is NetworkResult.Success) return v2Res

        val localRes = localCacheProvider.getExerciseById(id)
        if (localRes is NetworkResult.Success && !localRes.data.gifUrl.isNullOrBlank()) return localRes

        return v1Res
    }

    override suspend fun testConnection(query: String): NetworkTestResult {
        val v1Res = v1Provider.testConnection(query)
        if (v1Res is NetworkTestResult.Success) return v1Res

        val v2Res = v2Provider.testConnection(query)
        if (v2Res is NetworkTestResult.Success) return v2Res

        return v1Res
    }

    /**
     * O cursor de paginação é específico de cada provedor, então o provedor é escolhido
     * na primeira página (`cursor == null`) e mantido até o fim da paginação.
     */
    @Volatile
    private var catalogProvider: ExerciseApiProvider? = null

    override suspend fun fetchCatalogPage(limit: Int, cursor: String?): NetworkResult<CatalogPage> {
        if (cursor != null) {
            catalogProvider?.let { return it.fetchCatalogPage(limit, cursor) }
        }

        val v1Res = v1Provider.fetchCatalogPage(limit, null)
        if (v1Res is NetworkResult.Success && v1Res.data.items.isNotEmpty()) {
            catalogProvider = v1Provider
            return v1Res
        }

        val v2Res = v2Provider.fetchCatalogPage(limit, null)
        if (v2Res is NetworkResult.Success && v2Res.data.items.isNotEmpty()) {
            catalogProvider = v2Provider
            return v2Res
        }

        return v1Res
    }
}
