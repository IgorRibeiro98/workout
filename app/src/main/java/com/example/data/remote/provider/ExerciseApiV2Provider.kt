package com.example.data.remote.provider

import android.util.Log
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkResult
import com.example.data.remote.NetworkTestResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

interface RapidApiV2Service {
    @GET("exercises")
    suspend fun getExercises(
        @Header("X-RapidAPI-Key") apiKey: String,
        @Header("X-RapidAPI-Host") host: String = "exercisedb.p.rapidapi.com",
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): List<ExternalExerciseDto>

    @GET("exercises/name/{name}")
    suspend fun getExercisesByName(
        @Path("name") name: String,
        @Header("X-RapidAPI-Key") apiKey: String,
        @Header("X-RapidAPI-Host") host: String = "exercisedb.p.rapidapi.com",
        @Query("limit") limit: Int = 20
    ): List<ExternalExerciseDto>

    @GET("exercises/exercise/{id}")
    suspend fun getExerciseById(
        @Path("id") id: String,
        @Header("X-RapidAPI-Key") apiKey: String,
        @Header("X-RapidAPI-Host") host: String = "exercisedb.p.rapidapi.com"
    ): ExternalExerciseDto
}

class ExerciseApiV2Provider(
    private val apiKeyProvider: () -> String,
    baseUrl: String = "https://exercisedb.p.rapidapi.com/"
) : ExerciseApiProvider {

    override val providerType: ProviderType = ProviderType.V2_RAPID

    private val service: RapidApiV2Service

    init {
        val effectiveUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.d("ExerciseDB_V2_HTTP", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(effectiveUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        service = retrofit.create(RapidApiV2Service::class.java)
    }

    override suspend fun fetchExternalCatalog(limit: Int, offset: Int): NetworkResult<List<ExternalExerciseDto>> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return NetworkResult.Offline
        }
        return safeCall {
            service.getExercises(apiKey = apiKey, limit = limit, offset = offset)
        }
    }

    override suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return NetworkResult.Offline
        }
        val cleanQuery = query.trim().lowercase()
        return safeCall {
            service.getExercisesByName(name = cleanQuery, apiKey = apiKey)
        }
    }

    override suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return NetworkResult.Offline
        }
        return safeCall {
            service.getExerciseById(id = id.trim(), apiKey = apiKey)
        }
    }

    override suspend fun testConnection(query: String): NetworkTestResult {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return NetworkTestResult.Failure(
                errorMessage = "Chave de API ExerciseDB V2 não configurada nas opções."
            )
        }
        val cleanQuery = query.trim().lowercase()
        return try {
            val list = service.getExercisesByName(name = cleanQuery, apiKey = apiKey)
            if (list.isNotEmpty()) {
                val first = list.first()
                NetworkTestResult.Success(
                    query = cleanQuery,
                    foundName = first.name,
                    exerciseId = first.realId,
                    gifUrl = first.gifUrl,
                    totalResults = list.size
                )
            } else {
                NetworkTestResult.Failure(
                    httpCode = 200,
                    errorMessage = "Conexão V2 realizada, mas nenhum exercício foi retornado para '$cleanQuery'."
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            NetworkTestResult.Failure(
                errorMessage = "Erro na conexão V2: ${e.message}"
            )
        }
    }

    private suspend fun <T> safeCall(block: suspend () -> T): NetworkResult<T> {
        return try {
            NetworkResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            NetworkResult.Offline
        } catch (e: IOException) {
            NetworkResult.Offline
        } catch (e: Exception) {
            NetworkResult.ParserError(e, e.message)
        }
    }
}
