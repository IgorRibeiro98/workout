package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.domain.engine.ExerciseDbNormalizer
import com.example.domain.engine.ExerciseMatchStatus
import com.example.domain.engine.MatchEvaluation
import com.example.domain.engine.MediaSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class SyncExerciseItemResult {
    object Skipped : SyncExerciseItemResult()
    object Success : SyncExerciseItemResult()
    data class Failed(val reason: String) : SyncExerciseItemResult()
}

class ExerciseMediaRepository(
    private val workoutDao: WorkoutDao?,
    private val remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource(),
    private val context: Context? = null,
    private val catalogCache: ExerciseDbCatalogCache? = context?.let { ExerciseDbCatalogCache(it) }
) {
    constructor(remoteDataSource: ExerciseRemoteDataSource) : this(null, remoteDataSource, null, null)

    fun isOnline(): Boolean {
        if (context == null) return true
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    suspend fun syncSingleExercise(
        exercise: ExerciseEntity,
        rateLimiter: com.example.domain.engine.ExerciseDbRateLimiter,
        onRetryLog: (String) -> Unit = {}
    ): SyncExerciseItemResult = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            return@withContext SyncExerciseItemResult.Failed("Sem conexão com a internet")
        }

        // Priority 1: Custom photo override
        if (!exercise.customPhotoUri.isNullOrBlank()) {
            Log.d("SYNC_LOG", "[SYNC SKIPPED] Exercise: ${exercise.name} -> Foto personalizada presente")
            return@withContext SyncExerciseItemResult.Skipped
        }

        // Detailed Sync Start Log
        Log.d("SYNC_LOG", """
            [SYNC START]
            Exercise: ${exercise.name}
            ExternalId: ${exercise.externalExerciseId ?: "N/A"}
            Request iniciado
        """.trimIndent())

        // Priority 2: Verified remote GIF with MATCHED status already synced
        if (!exercise.externalExerciseId.isNullOrBlank() && 
            exercise.mappingStatus == ExerciseMatchStatus.MATCHED.name && 
            !exercise.gifUrl.isNullOrBlank()) {
            
            val isRecentlyVerified = exercise.lastVerifiedAt != null && 
                (System.currentTimeMillis() - exercise.lastVerifiedAt < 30L * 24 * 3600 * 1000)
            
            if (isRecentlyVerified) {
                Log.d("SYNC_LOG", "[SYNC SKIPPED] Exercise: ${exercise.name} -> Já sincronizado e verificado recentemente")
                return@withContext SyncExerciseItemResult.Success
            }
            
            val idResult = com.example.domain.engine.RetryPolicy.executeWithRetry(
                maxAttempts = 3,
                rateLimiter = rateLimiter,
                exerciseName = exercise.name,
                onRetryAttempt = { attempt, max, delaySec, reason ->
                    onRetryLog("[SYNC RETRY] Exercise: ${exercise.name}, Tentativa: $attempt/$max, Delay: ${delaySec}s, Motivo: $reason")
                }
            ) {
                remoteDataSource.getExerciseById(exercise.externalExerciseId)
            }

            when (idResult) {
                is NetworkResult.Success -> {
                    val dto = idResult.data
                    val newGifUrl = dto.gifUrl ?: exercise.gifUrl
                    val updatedEntity = exercise.copy(
                        gifUrl = newGifUrl,
                        lastVerifiedAt = System.currentTimeMillis(),
                        mappingStatus = ExerciseMatchStatus.MATCHED.name
                    )
                    workoutDao?.updateExercise(updatedEntity)
                    Log.d("SYNC_LOG", """
                        HTTP: 200
                        Mídia encontrada
                        Banco atualizado
                        [SYNC FINISH] Exercise: ${exercise.name}
                    """.trimIndent())
                    return@withContext SyncExerciseItemResult.Success
                }
                is NetworkResult.Offline -> {
                    return@withContext SyncExerciseItemResult.Failed("Sem conexão com a internet")
                }
                is NetworkResult.HttpError -> {
                    if (idResult.code == 429 && !exercise.gifUrl.isNullOrBlank()) {
                        Log.w("SYNC_LOG", "[SYNC WARN] Exercise: ${exercise.name} -> Rate limit (429) atingido na re-verificação, preservando GIF existente.")
                        return@withContext SyncExerciseItemResult.Success
                    }
                    // Fallback to name search queries if direct ID fails
                }
                is NetworkResult.NotFound,
                is NetworkResult.ParserError -> {
                    // Fallback to name search queries if direct ID fails
                }
            }
        }

        val searchQueries = mutableListOf<String>()
        exercise.exerciseDbSearch?.takeIf { it.isNotBlank() }?.let { searchQueries.add(it) }
        exercise.exerciseDbAliases?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.let { searchQueries.addAll(it) }
        exercise.nameEn?.takeIf { it.isNotBlank() }?.let { searchQueries.add(it) }
        val normalName = exercise.canonicalId?.replace("-", " ") ?: exercise.name
        if (normalName.isNotBlank()) searchQueries.add(normalName)

        if (searchQueries.isEmpty()) {
            Log.w("SYNC_LOG", "[SYNC ERROR] Exercise: ${exercise.name} -> Nenhum termo de busca configurado")
            return@withContext SyncExerciseItemResult.Failed("Nenhum termo de busca configurado")
        }

        var lastErrorMsg = "Nenhum resultado encontrado"

        for (query in searchQueries.distinct()) {
            val searchResult = com.example.domain.engine.RetryPolicy.executeWithRetry(
                maxAttempts = 3,
                rateLimiter = rateLimiter,
                exerciseName = exercise.name,
                onRetryAttempt = { attempt, max, delaySec, reason ->
                    onRetryLog("[SYNC RETRY] Exercise: ${exercise.name}, Query: $query, Tentativa: $attempt/$max, Delay: ${delaySec}s, Motivo: $reason")
                }
            ) {
                remoteDataSource.searchExercises(query)
            }

            when (searchResult) {
                is NetworkResult.Success -> {
                    val candidates = searchResult.data
                    val evaluation = evaluateCandidates(exercise, candidates, targetQuery = query)
                    if (evaluation.status == ExerciseMatchStatus.MATCHED) {
                        val best = evaluation.candidate!!
                        val updatedEntity = exercise.copy(
                            externalExerciseId = best.realId,
                            gifUrl = best.gifUrl,
                            lastVerifiedAt = System.currentTimeMillis(),
                            mappingStatus = ExerciseMatchStatus.MATCHED.name
                        )
                        workoutDao?.updateExercise(updatedEntity)
                        Log.d("SYNC_LOG", """
                            HTTP: 200
                            Mídia encontrada (${best.realId})
                            Banco atualizado
                            [SYNC FINISH] Exercise: ${exercise.name}
                        """.trimIndent())
                        return@withContext SyncExerciseItemResult.Success
                    }
                }
                is NetworkResult.Offline -> {
                    return@withContext SyncExerciseItemResult.Failed("Sem conexão com a internet")
                }
                is NetworkResult.NotFound -> {
                    lastErrorMsg = "Não encontrado na ExerciseDB"
                }
                is NetworkResult.HttpError -> {
                    lastErrorMsg = "HTTP ${searchResult.code}"
                }
                is NetworkResult.ParserError -> {
                    lastErrorMsg = searchResult.throwable.message ?: "Erro de parsing"
                }
            }
        }

        val updatedEntity = exercise.copy(
            mappingStatus = ExerciseMatchStatus.NOT_FOUND.name
        )
        workoutDao?.updateExercise(updatedEntity)
        Log.e("SYNC_LOG", "[SYNC ERROR] Exercise: ${exercise.name} -> $lastErrorMsg")
        SyncExerciseItemResult.Failed(lastErrorMsg)
    }

    suspend fun testConnection(query: String = "bench press"): NetworkTestResult {
        return remoteDataSource.testConnection(query)
    }

    /**
     * Sincroniza as demonstrações casando o catálogo local de exercícios contra um
     * instantâneo do ExerciseDB.
     *
     * O catálogo remoto é baixado uma única vez (ver [ExerciseDbCatalogCache]) e o
     * casamento acontece offline. A versão anterior fazia de uma a quatro buscas de
     * rede por exercício — centenas de requisições por execução — e abortava tudo no
     * primeiro `HTTP 429`, de modo que praticamente nenhum GIF chegava ao banco.
     */
    suspend fun syncExerciseGifs(
        force: Boolean = false,
        onCatalogProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): MediaSyncResult = withContext(Dispatchers.IO) {
        val cache = catalogCache
            ?: return@withContext MediaSyncResult(
                errors = listOf("Cache do catálogo indisponível neste contexto.")
            )

        val exercises = try {
            workoutDao?.getAllExercisesSync() ?: emptyList()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Database error: ${e.message}", e)
            return@withContext MediaSyncResult(errors = listOf("Erro ao consultar banco de dados: ${e.message}"))
        }

        if (exercises.isEmpty()) {
            Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Catalog is empty")
            return@withContext MediaSyncResult()
        }

        // 1. Catálogo remoto. Offline ainda funciona se houver instantâneo em disco.
        val outcome = if (!isOnline()) {
            val local = cache.load()
            if (local == null || local.items.isEmpty()) {
                Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Offline e sem instantâneo local")
                return@withContext MediaSyncResult(
                    isOffline = true,
                    errors = listOf("Conecte-se à internet para baixar o catálogo de demonstrações.")
                )
            }
            Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Offline, usando instantâneo local de ${local.items.size} exercícios")
            CatalogSyncOutcome(snapshot = local, fromCache = true)
        } else {
            cache.getOrDownload(
                remote = remoteDataSource,
                force = force,
                onProgress = onCatalogProgress
            )
        }

        if (!outcome.isUsable) {
            return@withContext MediaSyncResult(
                isOffline = outcome.offline,
                errors = listOf(outcome.error ?: "Não foi possível obter o catálogo do ExerciseDB.")
            )
        }

        val index = ExerciseDbCatalogIndex(outcome.snapshot.items)
        Log.d(
            "ExerciseDB_SYNC",
            "[ExerciseDB_SYNC] Catálogo pronto: ${index.size} exercícios com GIF " +
                "(cache=${outcome.fromCache}, completo=${outcome.snapshot.complete}, páginas=${outcome.pagesFetched})"
        )

        if (index.size == 0) {
            return@withContext MediaSyncResult(
                errors = listOf("O catálogo do ExerciseDB não retornou nenhuma mídia utilizável.")
            )
        }

        // 2. Casamento offline, sem nenhuma requisição adicional.
        var matchedCount = 0
        var ambiguousCount = 0
        var notFoundCount = 0
        var alreadyUpToDateCount = 0
        val errors = mutableListOf<String>()

        val total = exercises.size
        val now = System.currentTimeMillis()

        for ((idx, exercise) in exercises.withIndex()) {
            onProgress(idx + 1, total)

            if (!exercise.customPhotoUri.isNullOrBlank()) {
                Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Exercise: ${exercise.name} -> SKIPPED (Has custom photo)")
                continue
            }

            // Revalida um vínculo já existente contra o instantâneo.
            if (!exercise.externalExerciseId.isNullOrBlank() &&
                exercise.mappingStatus == ExerciseMatchStatus.MATCHED.name
            ) {
                val known = index.findById(exercise.externalExerciseId)
                if (known != null) {
                    workoutDao?.updateExercise(
                        exercise.copy(
                            gifUrl = known.gifUrl ?: exercise.gifUrl,
                            lastVerifiedAt = now,
                            mappingStatus = ExerciseMatchStatus.MATCHED.name
                        )
                    )
                    alreadyUpToDateCount++
                    continue
                }
            }

            val searchQueries = mutableListOf<String>()
            exercise.exerciseDbSearch?.takeIf { it.isNotBlank() }?.let { searchQueries.add(it) }
            exercise.exerciseDbAliases?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.let { searchQueries.addAll(it) }
            exercise.nameEn?.takeIf { it.isNotBlank() }?.let { searchQueries.add(it) }
            val normalName = exercise.canonicalId?.replace("-", " ") ?: exercise.name
            if (normalName.isNotBlank()) searchQueries.add(normalName)

            if (searchQueries.isEmpty()) {
                notFoundCount++
                workoutDao?.updateExercise(exercise.copy(mappingStatus = ExerciseMatchStatus.NOT_FOUND.name))
                continue
            }

            // Como não há custo de rede, todas as queries são avaliadas e a melhor vence.
            var bestEvaluation: MatchEvaluation? = null
            var bestQuery: String? = null
            for (query in searchQueries.distinct()) {
                val candidates = index.candidatesFor(query)
                if (candidates.isEmpty()) continue
                val evaluation = evaluateCandidates(exercise, candidates, targetQuery = query)
                if (evaluation.candidate == null) continue
                val current = bestEvaluation
                if (current == null || evaluation.rank > current.rank) {
                    bestEvaluation = evaluation
                    bestQuery = query
                }
            }

            val evaluation = bestEvaluation
            val candidate = evaluation?.candidate

            if (evaluation != null && candidate != null &&
                evaluation.status == ExerciseMatchStatus.MATCHED &&
                !candidate.gifUrl.isNullOrBlank()
            ) {
                workoutDao?.updateExercise(
                    exercise.copy(
                        externalExerciseId = candidate.realId,
                        gifUrl = candidate.gifUrl,
                        lastVerifiedAt = now,
                        mappingStatus = ExerciseMatchStatus.MATCHED.name
                    )
                )
                matchedCount++
                Log.d(
                    "ExerciseDB_SYNC",
                    "[ExerciseDB_SYNC] ${exercise.name} -> MATCHED '${candidate.name}' " +
                        "(${candidate.realId}, score=${evaluation.score}, query='$bestQuery')"
                )
            } else if (evaluation != null && evaluation.status == ExerciseMatchStatus.AMBIGUOUS) {
                // Um candidato ambíguo ainda rende mídia quando é apenas uma variante mais
                // específica do movimento pedido; o status continua AMBIGUOUS para revisão.
                val matchedQuery = bestQuery
                val variantWithMedia = if (
                    candidate != null &&
                    matchedQuery != null &&
                    !candidate.gifUrl.isNullOrBlank() &&
                    isSpecificVariantOf(matchedQuery, candidate.name)
                ) candidate else null

                workoutDao?.updateExercise(
                    if (variantWithMedia != null) {
                        exercise.copy(
                            externalExerciseId = variantWithMedia.realId,
                            gifUrl = variantWithMedia.gifUrl,
                            lastVerifiedAt = now,
                            mappingStatus = ExerciseMatchStatus.AMBIGUOUS.name
                        )
                    } else {
                        exercise.copy(mappingStatus = ExerciseMatchStatus.AMBIGUOUS.name)
                    }
                )
                ambiguousCount++
                Log.d(
                    "ExerciseDB_SYNC",
                    "[ExerciseDB_SYNC] ${exercise.name} -> AMBIGUOUS '${candidate?.name}' " +
                        "(score=${evaluation.score}, mídia=${if (variantWithMedia != null) "aplicada" else "descartada"})"
                )
            } else {
                workoutDao?.updateExercise(exercise.copy(mappingStatus = ExerciseMatchStatus.NOT_FOUND.name))
                notFoundCount++
                Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] ${exercise.name} -> NOT_FOUND")
            }
        }

        // Um catálogo parcial ainda produz resultados; o aviso deixa claro que faltam itens.
        if (!outcome.snapshot.complete) {
            errors.add(
                if (outcome.rateLimited) {
                    "Catálogo baixado parcialmente (${outcome.snapshot.items.size} exercícios): limite de requisições do ExerciseDB. Toque novamente mais tarde para continuar de onde parou."
                } else {
                    "Catálogo baixado parcialmente (${outcome.snapshot.items.size} exercícios). Toque novamente para continuar."
                }
            )
        }

        Log.d("ExerciseDB_SYNC", """
            [ExerciseDB_SYNC] Sync Finished:
            Matched: $matchedCount
            Ambiguous: $ambiguousCount
            Not Found: $notFoundCount
            Already Up To Date: $alreadyUpToDateCount
            Errors: ${errors.size}
        """.trimIndent())

        MediaSyncResult(
            matched = matchedCount,
            ambiguous = ambiguousCount,
            notFound = notFoundCount,
            alreadyUpToDate = alreadyUpToDateCount,
            isOffline = false,
            errors = errors,
            catalogSize = outcome.snapshot.items.size,
            catalogComplete = outcome.snapshot.complete,
            catalogFromCache = outcome.fromCache
        )
    }

    /**
     * Aceita um candidato ambíguo quando ele é uma variante mais específica do que foi
     * buscado — todos os termos significativos da query aparecem no nome do candidato.
     *
     * Aceita "Cable Triceps Pushdown" -> "cable triceps pushdown (v-bar)"; recusa
     * "Reverse Lunge" -> "barbell lunge" e "squat" -> "squat jerk". Queries com um único
     * termo significativo são sempre recusadas, por serem genéricas demais.
     */
    internal fun isSpecificVariantOf(query: String, candidateName: String): Boolean {
        val queryTokens = ExerciseDbNormalizer.normalize(query).split(" ").filter { it.length > 2 }
        if (queryTokens.size < 2) return false
        val candidateTokens = ExerciseDbNormalizer.normalize(candidateName).split(" ").toSet()
        return queryTokens.all { candidateTokens.contains(it) }
    }

    fun evaluateCandidates(
        exercise: ExerciseEntity,
        candidates: List<ExternalExerciseDto>,
        targetQuery: String? = null
    ): MatchEvaluation {
        if (candidates.isEmpty()) {
            return MatchEvaluation(null, 0, ExerciseMatchStatus.NOT_FOUND)
        }

        val targetNameRaw = targetQuery?.takeIf { it.isNotBlank() }
            ?: exercise.exerciseDbSearch
            ?: exercise.nameEn
            ?: exercise.canonicalId?.replace("-", " ")
            ?: exercise.name
        val targetName = ExerciseDbNormalizer.normalize(targetNameRaw)

        data class CandidateScore(
            val candidate: ExternalExerciseDto,
            val nameScore: Int,
            val equipScore: Int,
            val muscleScore: Int,
            val totalScore: Int,
            val isExactName: Boolean
        )

        val scoredList = candidates.map { candidate ->
            val candName = ExerciseDbNormalizer.normalize(candidate.name)
            val isExactName = candName == targetName && targetName.isNotEmpty()

            // 1. Name Scoring
            val nameScore = when {
                isExactName -> 100
                candName.contains(targetName) || targetName.contains(candName) -> 60
                else -> {
                    val targetWords = targetName.split(" ").filter { it.length > 2 }
                    val candWords = candName.split(" ").filter { it.length > 2 }
                    val overlap = targetWords.count { candWords.contains(it) }
                    if (overlap > 0) (overlap * 20).coerceAtMost(40) else 0
                }
            }

            // 2. Equipment Scoring
            val equipScore = ExerciseDbNormalizer.evaluateEquipmentScore(exercise.equipment, candidate.realEquipments)

            // 3. Muscle Scoring
            val candMuscles = candidate.realTargetMuscles + candidate.realBodyParts
            val muscleScore = ExerciseDbNormalizer.evaluateMuscleScore(exercise.primaryMuscle, candMuscles)

            val totalScore = nameScore + equipScore + muscleScore

            CandidateScore(
                candidate = candidate,
                nameScore = nameScore,
                equipScore = equipScore,
                muscleScore = muscleScore,
                totalScore = totalScore,
                isExactName = isExactName
            )
        }.sortedWith(
            compareByDescending<CandidateScore> { it.totalScore }
                .thenByDescending { it.nameScore }
                .thenByDescending { it.equipScore + it.muscleScore }
        )

        val top = scoredList.first()
        val topScore = top.totalScore

        // EXACT MATCH PRIORITY
        if (top.isExactName) {
            if (scoredList.size == 1) {
                return MatchEvaluation(top.candidate, topScore, ExerciseMatchStatus.MATCHED)
            }
            val runnerUp = scoredList[1]
            if (runnerUp.isExactName) {
                val diff = topScore - runnerUp.totalScore
                if (diff > 15) {
                    return MatchEvaluation(top.candidate, topScore, ExerciseMatchStatus.MATCHED)
                } else {
                    return MatchEvaluation(top.candidate, topScore, ExerciseMatchStatus.AMBIGUOUS)
                }
            } else {
                return MatchEvaluation(top.candidate, topScore, ExerciseMatchStatus.MATCHED)
            }
        }

        // For non-exact matches:
        if (topScore < 60) {
            return MatchEvaluation(null, topScore, ExerciseMatchStatus.NOT_FOUND)
        }

        // Check for ambiguous candidates
        if (scoredList.size > 1) {
            val second = scoredList[1]
            val diff = topScore - second.totalScore
            if (second.totalScore >= 60 && diff <= 15) {
                return MatchEvaluation(top.candidate, topScore, ExerciseMatchStatus.AMBIGUOUS)
            }
        }

        return MatchEvaluation(top.candidate, topScore, ExerciseMatchStatus.MATCHED)
    }
}
