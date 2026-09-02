package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.domain.engine.ExerciseDbRateLimiter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Instantâneo local do catálogo do ExerciseDB.
 *
 * [complete] falso significa que o download foi interrompido (rate limit, offline)
 * e pode ser retomado a partir de [nextCursor] sem refazer as páginas já baixadas.
 */
data class ExerciseDbSnapshot(
    val updatedAt: Long = 0L,
    val complete: Boolean = false,
    val nextCursor: String? = null,
    val items: List<ExternalExerciseDto> = emptyList()
)

data class CatalogSyncOutcome(
    val snapshot: ExerciseDbSnapshot,
    /** true quando nenhuma requisição foi feita (instantâneo local ainda válido). */
    val fromCache: Boolean,
    val pagesFetched: Int = 0,
    val rateLimited: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null
) {
    val isUsable: Boolean get() = snapshot.items.isNotEmpty()
}

/**
 * Baixa o catálogo do ExerciseDB **uma única vez** e o mantém em disco, para que a
 * sincronização de mídia case os exercícios locais offline em vez de fazer uma busca
 * por exercício.
 *
 * O servidor OSS fica atrás da Cloudflare e responde `HTTP 429 / error code: 1015`
 * após algumas dezenas de requisições em sequência. Por isso o download é espaçado
 * por [ExerciseDbRateLimiter], aplica recuo longo no 429 e grava progresso parcial a
 * cada [PERSIST_EVERY_PAGES] páginas para poder ser retomado.
 */
class ExerciseDbCatalogCache(
    private val context: Context,
    private val fileName: String = "exercisedb_catalog.json"
) {
    companion object {
        private const val TAG = "ExerciseDB_CACHE"

        /** Um instantâneo completo é reaproveitado por este período sem tocar na rede. */
        const val FRESHNESS_MS = 7L * 24 * 3600 * 1000

        /** Recuo aplicado quando a Cloudflare devolve 429. */
        private const val RATE_LIMIT_COOLDOWN_SECONDS = 45L
        private const val MAX_ATTEMPTS_PER_PAGE = 5
        private const val PERSIST_EVERY_PAGES = 5

        /** Trava de segurança: 1500 exercícios / 25 por página, com folga. */
        private const val MAX_PAGES = 120
    }

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(ExerciseDbSnapshot::class.java)

    private val file: File get() = File(context.filesDir, fileName)

    suspend fun load(): ExerciseDbSnapshot? = withContext(Dispatchers.IO) {
        val f = file
        if (!f.exists()) return@withContext null
        try {
            adapter.fromJson(f.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Instantâneo local ilegível, será descartado: ${e.message}")
            runCatching { f.delete() }
            null
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        Unit
    }

    private suspend fun persist(snapshot: ExerciseDbSnapshot) = withContext(Dispatchers.IO) {
        try {
            file.writeText(adapter.toJson(snapshot))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao gravar instantâneo: ${e.message}", e)
        }
    }

    fun isFresh(snapshot: ExerciseDbSnapshot?, now: Long = System.currentTimeMillis()): Boolean {
        if (snapshot == null || !snapshot.complete || snapshot.items.isEmpty()) return false
        return now - snapshot.updatedAt < FRESHNESS_MS
    }

    /**
     * Devolve o catálogo, baixando-o apenas se o instantâneo local estiver ausente,
     * incompleto ou vencido. Um download interrompido é retomado do cursor gravado.
     *
     * @param onProgress recebe (itens acumulados, total informado pela API ou null).
     */
    suspend fun getOrDownload(
        remote: ExerciseRemoteDataSource,
        force: Boolean = false,
        rateLimiter: ExerciseDbRateLimiter = ExerciseDbRateLimiter(requestsPerSecond = 0.7),
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> }
    ): CatalogSyncOutcome = withContext(Dispatchers.IO) {
        val cached = load()

        if (cached != null && !force && isFresh(cached)) {
            Log.d(TAG, "Instantâneo local válido com ${cached.items.size} exercícios; rede não utilizada.")
            return@withContext CatalogSyncOutcome(snapshot = cached, fromCache = true)
        }

        // Um instantâneo incompleto é retomado; um completo porém vencido é refeito do zero.
        val resuming = cached != null && !cached.complete && cached.items.isNotEmpty() && !force
        val items: MutableList<ExternalExerciseDto> =
            if (resuming) cached!!.items.toMutableList() else mutableListOf()
        val seen = items.mapTo(mutableSetOf()) { it.realId }
        var cursor = if (resuming) cached!!.nextCursor else null

        if (resuming) {
            Log.d(TAG, "Retomando download com ${items.size} exercícios já baixados (cursor=$cursor).")
        }

        var pages = 0
        var total: Int? = null
        var rateLimited = false
        var offline = false
        var error: String? = null
        var complete = false

        onProgress(items.size, null)

        pageLoop@ while (pages < MAX_PAGES) {
            var attempt = 1
            var page: CatalogPage? = null

            attemptLoop@ while (attempt <= MAX_ATTEMPTS_PER_PAGE) {
                rateLimiter.acquire()
                when (val res = remote.fetchCatalogPage(cursor = cursor)) {
                    is NetworkResult.Success -> {
                        page = res.data
                        break@attemptLoop
                    }
                    is NetworkResult.HttpError -> {
                        if (res.code == 429) {
                            rateLimited = true
                            if (attempt == MAX_ATTEMPTS_PER_PAGE) {
                                error = "Limite de requisições do ExerciseDB atingido (HTTP 429)."
                                break@attemptLoop
                            }
                            Log.w(TAG, "HTTP 429 na página $pages; aguardando ${RATE_LIMIT_COOLDOWN_SECONDS}s (tentativa $attempt/$MAX_ATTEMPTS_PER_PAGE).")
                            rateLimiter.cooldown(RATE_LIMIT_COOLDOWN_SECONDS)
                        } else if (res.code in 500..504 && attempt < MAX_ATTEMPTS_PER_PAGE) {
                            Log.w(TAG, "HTTP ${res.code} na página $pages; nova tentativa em 5s.")
                            delay(5_000)
                        } else {
                            error = "Erro HTTP ${res.code} ao baixar o catálogo."
                            break@attemptLoop
                        }
                    }
                    is NetworkResult.Offline -> {
                        offline = true
                        error = "Sem conexão com a internet."
                        break@attemptLoop
                    }
                    is NetworkResult.NotFound -> {
                        error = "Este provedor não suporta download do catálogo completo."
                        break@attemptLoop
                    }
                    is NetworkResult.ParserError -> {
                        if (attempt < MAX_ATTEMPTS_PER_PAGE) {
                            delay(3_000)
                        } else {
                            error = res.rawMessage ?: "Resposta inválida do ExerciseDB."
                        }
                    }
                }
                attempt++
            }

            val fetched = page ?: break@pageLoop

            for (dto in fetched.items) {
                val id = dto.realId
                if (id.isNotBlank() && seen.add(id)) items.add(dto)
            }
            pages++
            total = fetched.total ?: total
            cursor = fetched.nextCursor
            onProgress(items.size, total)

            if (pages % PERSIST_EVERY_PAGES == 0) {
                persist(ExerciseDbSnapshot(System.currentTimeMillis(), false, cursor, items))
            }

            if (!fetched.hasNextPage || cursor.isNullOrBlank() || fetched.items.isEmpty()) {
                complete = true
                break@pageLoop
            }
        }

        val snapshot = ExerciseDbSnapshot(
            updatedAt = System.currentTimeMillis(),
            complete = complete,
            nextCursor = if (complete) null else cursor,
            items = items
        )
        persist(snapshot)

        Log.d(
            TAG,
            "Download encerrado: ${items.size} exercícios, $pages páginas, completo=$complete, rateLimited=$rateLimited, erro=$error"
        )

        CatalogSyncOutcome(
            snapshot = snapshot,
            fromCache = false,
            pagesFetched = pages,
            rateLimited = rateLimited,
            offline = offline,
            error = if (complete) null else error
        )
    }
}
