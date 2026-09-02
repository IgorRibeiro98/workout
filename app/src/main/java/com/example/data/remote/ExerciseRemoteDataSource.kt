package com.example.data.remote

import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
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
import kotlinx.coroutines.CancellationException

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    object NotFound : NetworkResult<Nothing>()
    object Offline : NetworkResult<Nothing>()
    data class HttpError(val code: Int, val message: String?, val url: String? = null) : NetworkResult<Nothing>()
    data class ParserError(val throwable: Throwable, val rawMessage: String? = null) : NetworkResult<Nothing>()
}

sealed class NetworkTestResult {
    data class Success(
        val query: String,
        val foundName: String,
        val exerciseId: String,
        val gifUrl: String?,
        val totalResults: Int
    ) : NetworkTestResult()

    data class Failure(
        val httpCode: Int? = null,
        val url: String? = null,
        val errorMessage: String
    ) : NetworkTestResult()
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

data class ExerciseDbEnvelopeList(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "data") val data: List<ExternalExerciseDto>? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "meta") val meta: ExerciseDbMeta? = null
)

data class ExerciseDbEnvelopeItem(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "data") val data: ExternalExerciseDto? = null,
    @Json(name = "message") val message: String? = null
)

data class ExerciseDbMeta(
    @Json(name = "total") val total: Int? = null,
    @Json(name = "hasNextPage") val hasNextPage: Boolean? = null,
    @Json(name = "nextCursor") val nextCursor: String? = null
)

/** Uma página do catálogo remoto. [nextCursor] nulo indica fim da paginação. */
data class CatalogPage(
    val items: List<ExternalExerciseDto>,
    val nextCursor: String? = null,
    val hasNextPage: Boolean = false,
    val total: Int? = null
)

interface ExerciseApiService {
    /**
     * Paginação por cursor. A API OSS limita a página a 25 itens e ignora `offset`;
     * a navegação é feita por `meta.nextCursor`.
     */
    @GET("exercises")
    suspend fun getExercisesPage(
        @Query("limit") limit: Int = ExerciseDbPaging.MAX_PAGE_SIZE,
        @Query("cursor") cursor: String? = null
    ): ExerciseDbEnvelopeList

    @GET("exercises")
    suspend fun getExercises(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): ExerciseDbEnvelopeList

    @GET("exercises/search")
    suspend fun searchExercises(
        @Query("search") query: String
    ): ExerciseDbEnvelopeList

    @GET("exercises")
    suspend fun getExercisesByName(
        @Query("name") name: String,
        @Query("limit") limit: Int = 20
    ): ExerciseDbEnvelopeList

    @GET("exercises/{id}")
    suspend fun getExerciseById(
        @Path("id") id: String
    ): ExerciseDbEnvelopeItem
}

object ExerciseDbPaging {
    /** A API OSS devolve no máximo 25 itens por página, independentemente do `limit` pedido. */
    const val MAX_PAGE_SIZE = 25
}

interface ExerciseRemoteDataSource {
    suspend fun fetchExternalCatalog(limit: Int = ExerciseDbPaging.MAX_PAGE_SIZE, offset: Int = 0): NetworkResult<List<ExternalExerciseDto>>
    suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>>
    suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto>
    suspend fun testConnection(query: String = "bench press"): NetworkTestResult

    /**
     * Busca uma página do catálogo completo. Implementações que não suportam
     * download em massa devolvem [NetworkResult.NotFound].
     */
    suspend fun fetchCatalogPage(
        limit: Int = ExerciseDbPaging.MAX_PAGE_SIZE,
        cursor: String? = null
    ): NetworkResult<CatalogPage> = NetworkResult.NotFound
}

class NetworkExerciseRemoteDataSource(
    baseUrl: String = "https://oss.exercisedb.dev/api/v1/",
    private val apiService: ExerciseApiService? = null
) : ExerciseRemoteDataSource {

    private val activeApiService: ExerciseApiService
    private val effectiveBaseUrl: String

    init {
        effectiveBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (apiService != null) {
            activeApiService = apiService
        } else {
            val loggingInterceptor = HttpLoggingInterceptor { message ->
                Log.d("ExerciseDB_HTTP", message)
            }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val userAgentInterceptor = Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "FitTrack/1.0 (Android; Linux; okhttp/4.12.0)")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(userAgentInterceptor)
                .addInterceptor(loggingInterceptor)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(effectiveBaseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

            activeApiService = retrofit.create(ExerciseApiService::class.java)
        }
    }

    override suspend fun fetchExternalCatalog(limit: Int, offset: Int): NetworkResult<List<ExternalExerciseDto>> {
        return safeApiCall {
            val envelope = activeApiService.getExercises(
                limit = limit.coerceAtMost(ExerciseDbPaging.MAX_PAGE_SIZE),
                offset = offset
            )
            envelope.data ?: emptyList()
        }
    }

    override suspend fun fetchCatalogPage(limit: Int, cursor: String?): NetworkResult<CatalogPage> {
        return safeApiCall {
            val envelope = activeApiService.getExercisesPage(
                limit = limit.coerceAtMost(ExerciseDbPaging.MAX_PAGE_SIZE),
                cursor = cursor
            )
            val meta = envelope.meta
            CatalogPage(
                items = envelope.data ?: emptyList(),
                nextCursor = meta?.nextCursor,
                hasNextPage = meta?.hasNextPage == true && !meta.nextCursor.isNullOrBlank(),
                total = meta?.total
            )
        }
    }

    override suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>> {
        val cleanQuery = query.trim().lowercase()
        return safeApiCall {
            // First try /exercises/search?search={query}
            val searchEnvelope = activeApiService.searchExercises(query = cleanQuery)
            val searchData = searchEnvelope.data
            if (!searchData.isNullOrEmpty()) {
                searchData
            } else {
                // Fallback to /exercises?name={query}
                val nameEnvelope = activeApiService.getExercisesByName(name = cleanQuery)
                nameEnvelope.data ?: emptyList()
            }
        }
    }

    override suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto> {
        val cleanId = id.trim()
        return safeApiCall {
            val envelope = activeApiService.getExerciseById(id = cleanId)
            envelope.data ?: throw NoSuchElementException("Exercise $cleanId not found in response")
        }
    }

    override suspend fun testConnection(query: String): NetworkTestResult {
        val cleanQuery = query.trim().lowercase()
        return try {
            val envelope = activeApiService.searchExercises(query = cleanQuery)
            val items = envelope.data
            if (!items.isNullOrEmpty()) {
                val first = items.first()
                NetworkTestResult.Success(
                    query = cleanQuery,
                    foundName = first.name,
                    exerciseId = first.realId,
                    gifUrl = first.gifUrl,
                    totalResults = items.size
                )
            } else {
                // Try fallback query
                val fallbackEnvelope = activeApiService.getExercisesByName(name = cleanQuery)
                val fallbackItems = fallbackEnvelope.data
                if (!fallbackItems.isNullOrEmpty()) {
                    val first = fallbackItems.first()
                    NetworkTestResult.Success(
                        query = cleanQuery,
                        foundName = first.name,
                        exerciseId = first.realId,
                        gifUrl = first.gifUrl,
                        totalResults = fallbackItems.size
                    )
                } else {
                    NetworkTestResult.Failure(
                        httpCode = 200,
                        url = "${effectiveBaseUrl}exercises/search?search=$cleanQuery",
                        errorMessage = "Conexão estabelecida com sucesso, mas nenhum resultado retornado para '$cleanQuery'."
                    )
                }
            }
        } catch (e: UnknownHostException) {
            NetworkTestResult.Failure(
                errorMessage = "Sem conexão com a internet (Host inacessível)."
            )
        } catch (e: IOException) {
            NetworkTestResult.Failure(
                errorMessage = "Falha de rede/timeout: ${e.message}"
            )
        } catch (e: HttpException) {
            NetworkTestResult.Failure(
                httpCode = e.code(),
                url = "${effectiveBaseUrl}exercises/search?search=$cleanQuery",
                errorMessage = "Erro HTTP ${e.code()}: ${e.message()}"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkTestResult.Failure(
                errorMessage = "Erro inesperado: ${e.javaClass.simpleName} - ${e.message}"
            )
        }
    }

    private suspend fun <T> safeApiCall(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000L,
        apiCall: suspend () -> T
    ): NetworkResult<T> {
        var currentDelay = initialDelayMs
        for (attempt in 1..maxRetries) {
            try {
                return NetworkResult.Success(apiCall())
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnknownHostException) {
                return NetworkResult.Offline
            } catch (e: HttpException) {
                val code = e.code()
                if (code == 404) {
                    return NetworkResult.NotFound
                }
                if (code == 429 || code in 500..504) {
                    if (attempt < maxRetries) {
                        val retryDelay = if (code == 429) (currentDelay * 2).coerceAtLeast(2500L) else currentDelay
                        Log.w("ExerciseDB_HTTP", "Transient HTTP $code encountered. Retrying in ${retryDelay}ms (attempt $attempt/$maxRetries)...")
                        kotlinx.coroutines.delay(retryDelay)
                        currentDelay = (currentDelay * 2).coerceAtMost(10000L)
                        continue
                    }
                }
                val msg = if (!e.message().isNullOrBlank()) e.message() else "HTTP $code"
                return NetworkResult.HttpError(code, msg)
            } catch (e: IOException) {
                if (attempt < maxRetries) {
                    Log.w("ExerciseDB_HTTP", "Network IO failure (${e.message}). Retrying in ${currentDelay}ms (attempt $attempt/$maxRetries)...")
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * 1.5).toLong()
                    continue
                }
                return NetworkResult.Offline
            } catch (e: JsonDataException) {
                Log.e("ExerciseDB_PARSER", "Moshi parsing failed", e)
                return NetworkResult.ParserError(e, e.message)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("ExerciseDB_ERROR", "API call exception", e)
                return NetworkResult.ParserError(e, e.message)
            }
        }
        return NetworkResult.Offline
    }
}
