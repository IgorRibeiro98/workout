package com.example.domain.engine

import android.content.Context
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkExerciseRemoteDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaSyncResult(
    val updated: Int = 0,
    val unchanged: Int = 0,
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
        var updated = 0
        var unchanged = 0
        var notFound = 0
        val errors = mutableListOf<String>()

        val exercises = try {
            dao.getAllExercisesSync()
        } catch (e: Exception) {
            return@withContext MediaSyncResult(errors = listOf("Erro ao consultar banco de dados: ${e.message}"))
        }

        if (exercises.isEmpty()) {
            return@withContext MediaSyncResult()
        }

        // Try checking remote connectivity
        val remoteSample = try {
            remoteDataSource.fetchExternalCatalog(limit = 1, offset = 0)
        } catch (e: Exception) {
            return@withContext MediaSyncResult(
                isOffline = true,
                errors = listOf("Não foi possível conectar ao ExerciseDB (Offline ou servidor indisponível).")
            )
        }

        val total = exercises.size
        for ((index, exercise) in exercises.withIndex()) {
            onProgress(index + 1, total)

            if (exercise.isUserCreated && !exercise.customPhotoUri.isNullOrBlank()) {
                unchanged++
                continue
            }

            if (!exercise.gifUrl.isNullOrBlank() && !exercise.externalExerciseId.isNullOrBlank()) {
                unchanged++
                continue
            }

            val searchQuery = exercise.exerciseDbSearch ?: exercise.nameEn ?: exercise.canonicalId?.replace("-", " ")
            if (searchQuery.isNullOrBlank()) {
                notFound++
                continue
            }

            try {
                val matches = remoteDataSource.searchExercises(searchQuery)
                val bestMatch = findBestMatch(exercise, matches)

                if (bestMatch != null && !bestMatch.gifUrl.isNullOrBlank()) {
                    val updatedEntity = exercise.copy(
                        externalExerciseId = bestMatch.id,
                        gifUrl = bestMatch.gifUrl,
                        lastVerifiedAt = System.currentTimeMillis()
                    )
                    dao.updateExercise(updatedEntity)
                    updated++
                } else {
                    notFound++
                }
            } catch (e: Exception) {
                errors.add("Falha ao buscar mídia para '${exercise.name}': ${e.message}")
                notFound++
            }
        }

        MediaSyncResult(
            updated = updated,
            unchanged = unchanged,
            notFound = notFound,
            isOffline = false,
            errors = errors
        )
    }

    private fun findBestMatch(exercise: ExerciseEntity, remoteList: List<ExternalExerciseDto>): ExternalExerciseDto? {
        if (remoteList.isEmpty()) return null

        val targetSearch = (exercise.exerciseDbSearch ?: exercise.nameEn ?: "").trim().lowercase()

        // 1. Exact name match
        val exact = remoteList.firstOrNull { it.name.trim().lowercase() == targetSearch }
        if (exact != null) return exact

        // 2. Contains match
        val containsMatch = remoteList.firstOrNull {
            val rName = it.name.trim().lowercase()
            rName.contains(targetSearch) || targetSearch.contains(rName)
        }
        if (containsMatch != null) return containsMatch

        // 3. Fallback to first item if single or very close
        return remoteList.firstOrNull()
    }
}
