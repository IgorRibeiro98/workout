package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    object NotFound : NetworkResult<Nothing>()
    object Offline : NetworkResult<Nothing>()
    data class HttpError(val code: Int, val message: String?) : NetworkResult<Nothing>()
    data class ParserError(val throwable: Throwable) : NetworkResult<Nothing>()
}

data class ExternalExerciseDto(
    @Json(name = "exerciseId") val exerciseId: String? = null,
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String = "",
    @Json(name = "gifUrl") val gifUrl: String? = null,
    @Json(name = "targetMuscles") val targetMuscles: List<String>? = null,
    @Json(name = "target") val target: String? = null,
    @Json(name = "bodyParts") val bodyParts: List<String>? = null,
    @Json(name = "bodyPart") val bodyPart: String? = null,
    @Json(name = "equipments") val equipments: List<String>? = null,
    @Json(name = "equipment") val equipment: String? = null,
    @Json(name = "secondaryMuscles") val secondaryMuscles: List<String>? = null,
    @Json(name = "instructions") val instructions: List<String>? = null
) {
    val realId: String
        get() = exerciseId ?: id ?: ""

    val realTargetMuscles: List<String>
        get() = targetMuscles ?: listOfNotNull(target)

    val realBodyParts: List<String>
        get() = bodyParts ?: listOfNotNull(bodyPart)

    val realEquipments: List<String>
        get() = equipments ?: listOfNotNull(equipment)
}

interface ExerciseApiService {
    @GET("exercises")
    suspend fun getExercises(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): List<ExternalExerciseDto>

    @GET("exercises/name/{name}")
    suspend fun getExercisesByName(
        @Path("name", encoded = false) name: String
    ): List<ExternalExerciseDto>

    @GET("exercises/exercise/{id}")
    suspend fun getExerciseById(
        @Path("id", encoded = false) id: String
    ): ExternalExerciseDto
}

interface ExerciseRemoteDataSource {
    suspend fun fetchExternalCatalog(limit: Int = 100, offset: Int = 0): NetworkResult<List<ExternalExerciseDto>>
    suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>>
    suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto>
}

class NetworkExerciseRemoteDataSource(
    baseUrl: String = "https://oss.exercisedb.dev/api/v1/",
    private val apiService: ExerciseApiService? = null
) : ExerciseRemoteDataSource {

    private val activeApiService: ExerciseApiService

    init {
        if (apiService != null) {
            activeApiService = apiService
        } else {
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

            activeApiService = retrofit.create(ExerciseApiService::class.java)
        }
    }

    override suspend fun fetchExternalCatalog(limit: Int, offset: Int): NetworkResult<List<ExternalExerciseDto>> {
        return safeApiCall { activeApiService.getExercises(limit = limit, offset = offset) }
    }

    override suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>> {
        // Do NOT replace spaces with %20 manually. Retrofit handles URL encoding natively.
        val cleanQuery = query.trim().lowercase()
        return safeApiCall { activeApiService.getExercisesByName(name = cleanQuery) }
    }

    override suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto> {
        val cleanId = id.trim()
        return safeApiCall { activeApiService.getExerciseById(id = cleanId) }
    }

    private suspend fun <T> safeApiCall(apiCall: suspend () -> T): NetworkResult<T> {
        return try {
            NetworkResult.Success(apiCall())
        } catch (e: UnknownHostException) {
            NetworkResult.Offline
        } catch (e: IOException) {
            NetworkResult.Offline
        } catch (e: HttpException) {
            if (e.code() == 404) {
                NetworkResult.NotFound
            } else {
                NetworkResult.HttpError(e.code(), e.message())
            }
        } catch (e: JsonDataException) {
            NetworkResult.ParserError(e)
        } catch (e: Exception) {
            NetworkResult.ParserError(e)
        }
    }
}
