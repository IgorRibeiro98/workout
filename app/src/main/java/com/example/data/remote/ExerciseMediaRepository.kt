package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SyncMediaResult(
    val updated: Int,
    val unchanged: Int,
    val failedOrAmbiguous: Int,
    val isOffline: Boolean = false,
    val message: String
)

class ExerciseMediaRepository(
    private val workoutDao: WorkoutDao,
    private val remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource(),
    private val context: Context
) {

    private fun isOnline(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun syncExerciseMedia(): SyncMediaResult = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            return@withContext SyncMediaResult(
                updated = 0,
                unchanged = 0,
                failedOrAmbiguous = 0,
                isOffline = true,
                message = "Sem conexão com a internet. O catálogo continua funcionando normalmente offline."
            )
        }

        try {
            val externalCatalog = remoteDataSource.fetchExternalCatalog()
            val localExercises = workoutDao.getAllExercisesList()
            var updated = 0
            var unchanged = 0
            var ambiguous = 0

            for (local in localExercises) {
                // Search for a confident match
                val targetSearch = local.exerciseDbSearch ?: local.nameEn ?: local.name
                val matches = externalCatalog.filter { ext ->
                    ext.name.contains(targetSearch, ignoreCase = true) ||
                    targetSearch.contains(ext.name, ignoreCase = true)
                }

                if (matches.size == 1) {
                    val match = matches.first()
                    if (local.gifUrl != match.gifUrl || local.externalExerciseId != match.id) {
                        val updatedEntity = local.copy(
                            externalExerciseId = match.id,
                            gifUrl = match.gifUrl,
                            lastVerifiedAt = System.currentTimeMillis()
                        )
                        workoutDao.updateExercise(updatedEntity)
                        updated++
                    } else {
                        unchanged++
                    }
                } else if (matches.size > 1) {
                    // Ambiguous match - avoid false positive binding
                    ambiguous++
                } else {
                    unchanged++
                }
            }

            SyncMediaResult(
                updated = updated,
                unchanged = unchanged,
                failedOrAmbiguous = ambiguous,
                isOffline = false,
                message = "Mídias atualizadas: $updated | Inalteradas: $unchanged | Ignoradas/Ambíguas: $ambiguous"
            )
        } catch (e: Exception) {
            SyncMediaResult(
                updated = 0,
                unchanged = 0,
                failedOrAmbiguous = 0,
                isOffline = false,
                message = "Erro ao sincronizar mídias: ${e.message}"
            )
        }
    }
}
