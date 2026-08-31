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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ExerciseMediaRepository(
    private val workoutDao: WorkoutDao,
    private val remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource(),
    private val context: Context? = null
) {

    private fun isOnline(): Boolean {
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

    suspend fun testConnection(query: String = "bench press"): NetworkTestResult {
        return remoteDataSource.testConnection(query)
    }

    suspend fun syncExerciseGifs(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): MediaSyncResult = withContext(Dispatchers.IO) {
        var matchedCount = 0
        var ambiguousCount = 0
        var notFoundCount = 0
        var alreadyUpToDateCount = 0
        val errors = mutableListOf<String>()

        if (!isOnline()) {
            Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Offline detected")
            return@withContext MediaSyncResult(
                isOffline = true,
                errors = listOf("Conecte-se à internet para atualizar as demonstrações.")
            )
        }

        val exercises = try {
            workoutDao.getAllExercisesSync()
        } catch (e: Exception) {
            Log.e("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Database error: ${e.message}", e)
            return@withContext MediaSyncResult(errors = listOf("Erro ao consultar banco de dados: ${e.message}"))
        }

        if (exercises.isEmpty()) {
            Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Catalog is empty")
            return@withContext MediaSyncResult()
        }

        val total = exercises.size
        Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Starting sync for $total exercises...")

        for ((index, exercise) in exercises.withIndex()) {
            onProgress(index + 1, total)

            // If exercise already has custom photo, skip remote lookup without overwriting
            if (!exercise.customPhotoUri.isNullOrBlank()) {
                Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Exercise: ${exercise.name} -> SKIPPED (Has custom photo)")
                continue
            }

            // 1. Direct lookup by known external ID if already matched
            if (!exercise.externalExerciseId.isNullOrBlank() && 
                exercise.mappingStatus == ExerciseMatchStatus.MATCHED.name && 
                !exercise.gifUrl.isNullOrBlank()) {
                
                var idResult = remoteDataSource.getExerciseById(exercise.externalExerciseId)
                if (idResult is NetworkResult.HttpError && (idResult.code == 429 || idResult.code in 500..504)) {
                    delay(1500)
                    idResult = remoteDataSource.getExerciseById(exercise.externalExerciseId)
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
                        workoutDao.updateExercise(updatedEntity)
                        alreadyUpToDateCount++
                        Log.d("ExerciseDB_SYNC", """
                            [ExerciseDB_SYNC]
                            Exercise: ${exercise.name}
                            ID Lookup: ${exercise.externalExerciseId}
                            Request: GET /exercises/${exercise.externalExerciseId}
                            Response: HTTP 200
                            Result: SUCCESS (Already matched & verified)
                        """.trimIndent())
                        delay(200)
                        continue
                    }
                    is NetworkResult.Offline -> {
                        return@withContext MediaSyncResult(
                            isOffline = true,
                            errors = listOf("Conecte-se à internet para atualizar as demonstrações.")
                        )
                    }
                    else -> {
                        // Fallback to name search if direct ID lookup fails
                    }
                }
            }

            val searchQuery = exercise.exerciseDbSearch ?: exercise.nameEn ?: exercise.canonicalId?.replace("-", " ") ?: exercise.name
            if (searchQuery.isBlank()) {
                notFoundCount++
                Log.d("ExerciseDB_SYNC", """
                    [ExerciseDB_SYNC]
                    Exercise: ${exercise.name}
                    Search: (empty)
                    Result: NOT_FOUND
                    Reason: No search term configured
                """.trimIndent())
                continue
            }

            var searchResult = remoteDataSource.searchExercises(searchQuery)
            if (searchResult is NetworkResult.HttpError && (searchResult.code == 429 || searchResult.code in 500..504)) {
                // Backoff on rate limit / transient server overload
                Log.w("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Transient HTTP ${searchResult.code} for '${exercise.name}'. Backing off 2000ms...")
                delay(2000)
                searchResult = remoteDataSource.searchExercises(searchQuery)
            }

            when (searchResult) {
                is NetworkResult.Success -> {
                    val candidates = searchResult.data
                    val evaluation = evaluateCandidates(exercise, candidates)
                    when (evaluation.status) {
                        ExerciseMatchStatus.MATCHED -> {
                            val best = evaluation.candidate!!
                            val updatedEntity = exercise.copy(
                                externalExerciseId = best.realId,
                                gifUrl = best.gifUrl,
                                lastVerifiedAt = System.currentTimeMillis(),
                                mappingStatus = ExerciseMatchStatus.MATCHED.name
                            )
                            workoutDao.updateExercise(updatedEntity)
                            matchedCount++
                            Log.d("ExerciseDB_SYNC", """
                                [ExerciseDB_SYNC]
                                Exercise: ${exercise.name}
                                Search: $searchQuery
                                Request: GET /exercises/search?search=$searchQuery
                                Response: HTTP 200 (${candidates.size} candidates)
                                Result: SUCCESS (MATCHED -> ID: ${best.realId}, Score: ${evaluation.score}, GIF: ${best.gifUrl})
                            """.trimIndent())
                        }
                        ExerciseMatchStatus.AMBIGUOUS -> {
                            val updatedEntity = exercise.copy(
                                mappingStatus = ExerciseMatchStatus.AMBIGUOUS.name
                            )
                            workoutDao.updateExercise(updatedEntity)
                            ambiguousCount++
                            Log.d("ExerciseDB_SYNC", """
                                [ExerciseDB_SYNC]
                                Exercise: ${exercise.name}
                                Search: $searchQuery
                                Request: GET /exercises/search?search=$searchQuery
                                Response: HTTP 200 (${candidates.size} candidates)
                                Result: AMBIGUOUS (Score: ${evaluation.score})
                            """.trimIndent())
                        }
                        else -> {
                            val updatedEntity = exercise.copy(
                                mappingStatus = ExerciseMatchStatus.NOT_FOUND.name
                            )
                            workoutDao.updateExercise(updatedEntity)
                            notFoundCount++
                            Log.d("ExerciseDB_SYNC", """
                                [ExerciseDB_SYNC]
                                Exercise: ${exercise.name}
                                Search: $searchQuery
                                Request: GET /exercises/search?search=$searchQuery
                                Response: HTTP 200 (${candidates.size} candidates)
                                Result: NOT_FOUND (Score: ${evaluation.score} < 60)
                            """.trimIndent())
                        }
                    }
                }
                is NetworkResult.Offline -> {
                    Log.d("ExerciseDB_SYNC", "[ExerciseDB_SYNC] Network became offline during sync")
                    return@withContext MediaSyncResult(
                        isOffline = true,
                        errors = listOf("Conecte-se à internet para atualizar as demonstrações.")
                    )
                }
                is NetworkResult.HttpError -> {
                    val statusDesc = when (searchResult.code) {
                        503 -> "Servidor temporariamente sobrecarregado (HTTP 503)"
                        429 -> "Limite de requisições atingido (HTTP 429)"
                        else -> searchResult.message ?: "Erro HTTP ${searchResult.code}"
                    }
                    val errMsg = "HTTP ${searchResult.code} ($statusDesc) ao buscar '${exercise.name}' [query: $searchQuery]"
                    Log.e("ExerciseDB_SYNC", """
                        [ExerciseDB_SYNC]
                        Exercise: ${exercise.name}
                        Search: $searchQuery
                        Request: GET /exercises/search?search=$searchQuery
                        Response: HTTP ${searchResult.code}
                        Result: ERROR
                        Reason: $statusDesc
                    """.trimIndent())
                    errors.add(errMsg)
                    notFoundCount++
                }
                is NetworkResult.ParserError -> {
                    val errMsg = "Erro de parser para '${exercise.name}': ${searchResult.rawMessage ?: searchResult.throwable.message}"
                    Log.e("ExerciseDB_SYNC", """
                        [ExerciseDB_SYNC]
                        Exercise: ${exercise.name}
                        Search: $searchQuery
                        Request: GET /exercises/search?search=$searchQuery
                        Result: ERROR
                        Reason: Parser error - ${searchResult.throwable.message}
                    """.trimIndent(), searchResult.throwable)
                    errors.add(errMsg)
                    notFoundCount++
                }
                is NetworkResult.NotFound -> {
                    val updatedEntity = exercise.copy(
                        mappingStatus = ExerciseMatchStatus.NOT_FOUND.name
                    )
                    workoutDao.updateExercise(updatedEntity)
                    notFoundCount++
                    Log.d("ExerciseDB_SYNC", """
                        [ExerciseDB_SYNC]
                        Exercise: ${exercise.name}
                        Search: $searchQuery
                        Request: GET /exercises/search?search=$searchQuery
                        Response: HTTP 404
                        Result: NOT_FOUND
                    """.trimIndent())
                }
            }

            // Respect rate-limiting
            delay(280)
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
            errors = errors
        )
    }

    fun evaluateCandidates(
        exercise: ExerciseEntity,
        candidates: List<ExternalExerciseDto>
    ): MatchEvaluation {
        if (candidates.isEmpty()) {
            return MatchEvaluation(null, 0, ExerciseMatchStatus.NOT_FOUND)
        }

        val targetNameRaw = exercise.exerciseDbSearch ?: exercise.nameEn ?: exercise.canonicalId?.replace("-", " ") ?: exercise.name
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
