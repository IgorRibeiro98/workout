package com.example.domain.engine

import android.content.Context
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkExerciseRemoteDataSource
import com.example.data.remote.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ExerciseMatchStatus {
    MATCHED,
    AMBIGUOUS,
    NOT_FOUND,
    UNVERIFIED
}

data class MatchEvaluation(
    val candidate: ExternalExerciseDto?,
    val score: Int,
    val status: ExerciseMatchStatus
)

data class MediaSyncResult(
    val matched: Int = 0,
    val ambiguous: Int = 0,
    val notFound: Int = 0,
    val isOffline: Boolean = false,
    val errors: List<String> = emptyList()
)

class ExerciseMediaEngine(
    private val dao: WorkoutDao,
    private val remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource(),
    private val context: Context? = null
) {
    suspend fun syncExerciseGifs(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): MediaSyncResult = withContext(Dispatchers.IO) {
        var matchedCount = 0
        var ambiguousCount = 0
        var notFoundCount = 0
        val errors = mutableListOf<String>()

        val exercises = try {
            dao.getAllExercisesSync()
        } catch (e: Exception) {
            return@withContext MediaSyncResult(errors = listOf("Erro ao consultar banco de dados: ${e.message}"))
        }

        if (exercises.isEmpty()) {
            return@withContext MediaSyncResult()
        }

        // Connectivity check
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
                            dao.updateExercise(updatedEntity)
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
                            dao.updateExercise(updatedEntity)
                            matchedCount++
                        }
                        ExerciseMatchStatus.AMBIGUOUS -> {
                            // Do NOT persist automatically when AMBIGUOUS
                            val updatedEntity = exercise.copy(
                                mappingStatus = ExerciseMatchStatus.AMBIGUOUS.name
                            )
                            dao.updateExercise(updatedEntity)
                            ambiguousCount++
                        }
                        else -> {
                            val updatedEntity = exercise.copy(
                                mappingStatus = ExerciseMatchStatus.NOT_FOUND.name
                            )
                            dao.updateExercise(updatedEntity)
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
