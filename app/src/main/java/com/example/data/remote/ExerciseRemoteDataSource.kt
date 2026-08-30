package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface ExerciseRemoteDataSource {
    suspend fun fetchExternalCatalog(limit: Int = 100, offset: Int = 0): List<ExternalExerciseDto>
    suspend fun searchExercises(query: String): List<ExternalExerciseDto>
    suspend fun getExerciseById(id: String): ExternalExerciseDto?
}

data class ExternalExerciseDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "target") val muscle: String? = null,
    @Json(name = "equipment") val equipment: String? = null,
    @Json(name = "gifUrl") val gifUrl: String? = null,
    @Json(name = "bodyPart") val bodyPart: String? = null
)

interface ExerciseApiService {
    @GET("exercises")
    suspend fun getExercises(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): List<ExternalExerciseDto>

    @GET("exercises/name/{name}")
    suspend fun getExercisesByName(
        @Path("name") name: String
    ): List<ExternalExerciseDto>

    @GET("exercises/exercise/{id}")
    suspend fun getExerciseById(
        @Path("id") id: String
    ): ExternalExerciseDto
}

class NetworkExerciseRemoteDataSource(
    baseUrl: String = "https://oss.exercisedb.dev/api/v1/"
) : ExerciseRemoteDataSource {

    private val apiService: ExerciseApiService

    init {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val retrofit = Retrofit.Builder()
            .baseUrl(cleanBaseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        apiService = retrofit.create(ExerciseApiService::class.java)
    }

    override suspend fun fetchExternalCatalog(limit: Int, offset: Int): List<ExternalExerciseDto> {
        return try {
            apiService.getExercises(limit = limit, offset = offset)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun searchExercises(query: String): List<ExternalExerciseDto> {
        return try {
            val clean = query.trim().lowercase().replace(" ", "%20")
            apiService.getExercisesByName(name = clean)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getExerciseById(id: String): ExternalExerciseDto? {
        return try {
            apiService.getExerciseById(id)
        } catch (e: Exception) {
            null
        }
    }
}
