package com.example.data.remote.provider

import com.example.data.datastore.SettingsManager
import com.example.data.local.WorkoutDao
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.NetworkExerciseRemoteDataSource
import kotlinx.coroutines.flow.first

/**
 * Monta a cadeia de provedores do ExerciseDB: API OSS, API V2 (RapidAPI, usando a
 * chave gravada nas configurações) e cache local.
 *
 * Sem esta fiação as classes de provedor ficam órfãs e o app cai no
 * [NetworkExerciseRemoteDataSource] padrão — só OSS, sem chave e sem fallback,
 * que é o estado em que a integração parou de funcionar.
 */
object ExerciseProviderFactory {

    fun create(
        dao: WorkoutDao,
        settingsManager: SettingsManager,
        ossBaseUrl: String = "https://oss.exercisedb.dev/api/v1/"
    ): ExerciseRemoteDataSource {
        val v1Provider = ExerciseApiV1Provider(NetworkExerciseRemoteDataSource(ossBaseUrl))
        val v2Provider = ExerciseApiV2Provider(
            apiKeyProvider = { settingsManager.exerciseDbV2ApiKeyFlow.first() }
        )
        val localCacheProvider = ExerciseLocalCacheProvider(dao)

        return ExerciseApiProviderAdapter(
            CompositeExerciseApiProvider(
                v1Provider = v1Provider,
                v2Provider = v2Provider,
                localCacheProvider = localCacheProvider
            )
        )
    }
}
