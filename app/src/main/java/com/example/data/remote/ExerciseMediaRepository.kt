package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.domain.engine.ExerciseMatchStatus
import com.example.domain.engine.MatchEvaluation
import com.example.domain.engine.MediaSyncResult
import kotlinx.coroutines.Dispatchers
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

    suspend fun syncExerciseGifs(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): MediaSyncResult = withContext(Dispatchers.IO) {
        var matchedCount = 0
        var ambiguousCount = 0
        var notFoundCount = 0
        val errors = mutableListOf<String>()

        if (!isOnline()) {
            return@withContext MediaSyncResult(
                isOffline = true,
                errors = listOf("Conecte-se à internet para atualizar as demonstrações.")
            )
        }

        val exercises = try {
            workoutDao.getAllExercisesSync()
        } catch (e: Exception) {
            return@withContext MediaSyncResult(errors = listOf("Erro ao consultar banco de dados: ${e.message}"))
        }

        if (exercises.isEmpty()) {
            return@withContext MediaSyncResult()
        }

        // Connectivity test
        val testResult = remoteDataSource.fetchExternalCatalog(limit = 1, offset = 0)
        if (testResult is NetworkResult.Offline) {
            return@withContext MediaSyncResult(
                isOffline = true,
                errors = listOf("Conecte-se à internet para atualizar as demonstrações.")
            )
        }

        val total = exercises.size
        for ((index, exercise) in exercises.withIndex()) {
            onProgress(index + 1, total)

            // If exercise already has custom photo, skip remote lookup
            if (!exercise.customPhotoUri.isNullOrBlank()) {
                continue
            }

            // 1. Direct lookup by known external ID if present
            if (!exercise.externalExerciseId.isNullOrBlank()) {
                when (val result = remoteDataSource.getExerciseById(exercise.externalExerciseId)) {
                    is NetworkResult.Success -> {
                        val dto = result.data
                        if (!dto.gifUrl.isNullOrBlank()) {
                            val updatedEntity = exercise.copy(
                                gifUrl = dto.gifUrl,
                                lastVerifiedAt = System.currentTimeMillis(),
                                mappingStatus = ExerciseMatchStatus.MATCHED.name
                            )
                            workoutDao.updateExercise(updatedEntity)
                            matchedCount++
                            continue
                        }
                    }
                    is NetworkResult.Offline -> {
                        return@withContext MediaSyncResult(
                            isOffline = true,
                            errors = listOf("Conecte-se à internet para atualizar as demonstrações.")
                        )
                    }
                    else -> { /* Fallback to search if lookup fails */ }
                }
            }

            val searchQuery = exercise.exerciseDbSearch ?: exercise.nameEn ?: exercise.canonicalId?.replace("-", " ")
            if (searchQuery.isNullOrBlank()) {
                notFoundCount++
                continue
            }

            when (val searchResult = remoteDataSource.searchExercises(searchQuery)) {
                is NetworkResult.Success -> {
                    val evaluation = evaluateCandidates(exercise, searchResult.data)
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
                        }
                        ExerciseMatchStatus.AMBIGUOUS -> {
                            val updatedEntity = exercise.copy(
                                mappingStatus = ExerciseMatchStatus.AMBIGUOUS.name
                            )
                            workoutDao.updateExercise(updatedEntity)
                            ambiguousCount++
                        }
                        else -> {
                            val updatedEntity = exercise.copy(
                                mappingStatus = ExerciseMatchStatus.NOT_FOUND.name
                            )
                            workoutDao.updateExercise(updatedEntity)
                            notFoundCount++
                        }
                    }
                }
                is NetworkResult.Offline -> {
                    return@withContext MediaSyncResult(
                        isOffline = true,
                        errors = listOf("Conecte-se à internet para atualizar as demonstrações.")
                    )
                }
                is NetworkResult.HttpError -> {
                    errors.add("Erro HTTP ${searchResult.code} ao buscar '${exercise.name}'")
                    notFoundCount++
                }
                is NetworkResult.ParserError -> {
                    errors.add("Erro ao interpretar resposta para '${exercise.name}'")
                    notFoundCount++
                }
                is NetworkResult.NotFound -> {
                    notFoundCount++
                }
            }
        }

        MediaSyncResult(
            matched = matchedCount,
            ambiguous = ambiguousCount,
            notFound = notFoundCount,
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

        val targetName = (exercise.exerciseDbSearch ?: exercise.nameEn ?: exercise.name).trim().lowercase()
        val expectedEquipment = exercise.equipment?.trim()?.lowercase()
        val expectedMuscle = exercise.primaryMuscle?.trim()?.lowercase()

        val scoredList = candidates.map { candidate ->
            val candName = candidate.name.trim().lowercase()
            var score = 0

            // 1. Name Scoring
            if (candName == targetName) {
                score += 100
            } else if (candName.contains(targetName) || targetName.contains(candName)) {
                score += 60
            } else {
                val targetWords = targetName.split(" ").filter { it.length > 2 }
                val candWords = candName.split(" ").filter { it.length > 2 }
                val overlap = targetWords.count { candWords.contains(it) }
                if (overlap > 0) {
                    score += (overlap * 20).coerceAtMost(40)
                }
            }

            // 2. Equipment Scoring
            if (!expectedEquipment.isNullOrBlank()) {
                val candEquipments = candidate.realEquipments.map { it.lowercase() }
                if (candEquipments.any { it.contains(expectedEquipment) || expectedEquipment.contains(it) }) {
                    score += 20
                } else if (candEquipments.isNotEmpty()) {
                    score -= 20
                }
            }

            // 3. Muscle Scoring
            if (!expectedMuscle.isNullOrBlank()) {
                val candMuscles = (candidate.realTargetMuscles + candidate.realBodyParts).map { it.lowercase() }
                if (candMuscles.any { it.contains(expectedMuscle) || expectedMuscle.contains(it) }) {
                    score += 20
                } else if (candMuscles.isNotEmpty()) {
                    score -= 20
                }
            }

            Pair(candidate, score)
        }.sortedByDescending { it.second }

        val topScore = scoredList.first().second
        if (topScore < 70) {
            return MatchEvaluation(null, topScore, ExerciseMatchStatus.NOT_FOUND)
        }

        // Check for ambiguous candidates (if runner-up score is close to top score)
        if (scoredList.size > 1) {
            val secondScore = scoredList[1].second
            if (secondScore >= 60 && (topScore - secondScore) <= 15) {
                return MatchEvaluation(scoredList.first().first, topScore, ExerciseMatchStatus.AMBIGUOUS)
            }
        }

        return MatchEvaluation(scoredList.first().first, topScore, ExerciseMatchStatus.MATCHED)
    }
}
