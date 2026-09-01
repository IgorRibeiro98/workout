package com.example.domain.engine

import android.content.Context
import com.example.data.datastore.IntegrationSettings
import com.example.data.datastore.SettingsManager
import com.example.data.datastore.SyncStatus
import com.example.data.local.WorkoutDao
import com.example.data.remote.ExerciseMediaRepository
import com.example.data.remote.NetworkExerciseRemoteDataSource
import com.example.data.remote.NetworkTestResult
import com.example.domain.provider.ExerciseDbProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ExerciseMediaSyncManager(
    private val dao: WorkoutDao,
    private val settingsManager: SettingsManager,
    private val context: Context? = null
) {
    private val remoteDataSource = NetworkExerciseRemoteDataSource()

    private val repository = ExerciseMediaRepository(
        workoutDao = dao,
        remoteDataSource = remoteDataSource,
        context = context
    )

    val exerciseDbProvider = ExerciseDbProvider(remoteDataSource)

    val integrationSettingsFlow: Flow<IntegrationSettings> = settingsManager.integrationSettingsFlow

    val syncStatusFlow: Flow<SyncStatus> = settingsManager.integrationSettingsFlow.map { settings ->
        exerciseDbProvider.updateSettings(settings)
        if (!settings.exerciseDbEnabled) {
            SyncStatus.DISABLED
        } else {
            settings.lastSyncStatus
        }
    }

    suspend fun testConnection(): NetworkTestResult {
        return repository.testConnection("bench press")
    }

    suspend fun syncNow(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): MediaSyncResult {
        val settings = settingsManager.integrationSettingsFlow.first()
        exerciseDbProvider.updateSettings(settings)

        if (!settings.exerciseDbEnabled) {
            settingsManager.setLastSyncStatus(SyncStatus.DISABLED)
            return MediaSyncResult(
                isOffline = false,
                errors = listOf("Integração com ExerciseDB está desativada nas configurações.")
            )
        }

        settingsManager.setLastSyncStatus(SyncStatus.SYNCING)

        val result = repository.syncExerciseGifs(onProgress)

        val now = System.currentTimeMillis()
        if (!result.isOffline && result.errors.isEmpty()) {
            settingsManager.setLastMediaSyncAt(now)
            settingsManager.setLastSyncStatus(SyncStatus.SUCCESS)
        } else {
            settingsManager.setLastSyncStatus(SyncStatus.ERROR)
        }

        return result
    }
}
