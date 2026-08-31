package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.domain.engine.ExerciseDbNormalizer
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
        var alreadyUpToDateCount = 0
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

            // If exercise already has custom photo, skip remote lookup without overwriting
            if (!exercise.customPhotoUri.isNullOrBlank()) {
                continue
            }

            // 1. Direct lookup by known external ID if already matched
            if (!exercise.externalExerciseId.isNullOrBlank() && 
                exercise.mappingStatus == ExerciseMatchStatus.MATCHED.name && 
                !exercise.gifUrl.isNullOrBlank()) {
                when (val result = remoteDataSource.getExerciseById(exercise.externalExerciseId)) {
                    is NetworkResult.Success -> {
                        val dto = result.data
                        val newGifUrl = dto.gifUrl ?: exercise.gifUrl
                        val updatedEntity = exercise.copy(
                            gifUrl = newGifUrl,
                            lastVerifiedAt = System.currentTimeMillis(),
                            mappingStatus = ExerciseMatchStatus.MATCHED.name
                        )
                        workoutDao.updateExercise(updatedEntity)
                        alreadyUpToDateCount++
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
                            // Do not overwrite an existing valid gifUrl
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
                    val updatedEntity = exercise.copy(
                        mappingStatus = ExerciseMatchStatus.NOT_FOUND.name
                    )
                    workoutDao.updateExercise(updatedEntity)
                    notFoundCount++
                }
            }
        }

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

        // Rule 5: EXACT MATCH PRIORITY
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

