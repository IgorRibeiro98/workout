package com.example.domain.engine

import android.content.Context
import android.util.Log
import com.example.data.datastore.IntegrationSettings
import com.example.data.datastore.SettingsManager
import com.example.data.datastore.SyncStatus
import com.example.data.local.SyncCheckpointStatus
import com.example.data.local.WorkoutDao
import com.example.data.remote.ExerciseMediaRepository
import com.example.data.remote.NetworkExerciseRemoteDataSource
import com.example.data.remote.NetworkTestResult
import com.example.data.remote.SyncExerciseItemResult
import com.example.domain.provider.ExerciseDbProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ExerciseMediaSyncManager(
    private val dao: WorkoutDao,
    private val settingsManager: SettingsManager,
    private val context: Context? = null
) {
    private val v1RemoteDataSource = NetworkExerciseRemoteDataSource()
    private val v1Provider = com.example.data.remote.provider.ExerciseApiV1Provider(v1RemoteDataSource)
    private val v2Provider = com.example.data.remote.provider.ExerciseApiV2Provider(
        apiKeyProvider = { kotlinx.coroutines.runBlocking { settingsManager.exerciseDbV2ApiKeyFlow.first() } }
    )
    private val localCacheProvider = com.example.data.remote.provider.ExerciseLocalCacheProvider(dao)
    private val compositeProvider = com.example.data.remote.provider.CompositeExerciseApiProvider(
        v1Provider = v1Provider,
        v2Provider = v2Provider,
        localCacheProvider = localCacheProvider
    )
    private val remoteDataSource = com.example.data.remote.provider.ExerciseApiProviderAdapter(compositeProvider)

    private val repository = ExerciseMediaRepository(
        workoutDao = dao,
        remoteDataSource = remoteDataSource,
        context = context
    )
    val exerciseDbProvider = ExerciseDbProvider(remoteDataSource)
    val syncQueue = ExerciseSyncQueue(dao)
    val rateLimiter = ExerciseDbRateLimiter(requestsPerSecond = 2.0)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    @Volatile
    private var isCancelled = false

    val integrationSettingsFlow: Flow<IntegrationSettings> = settingsManager.integrationSettingsFlow

    val syncStatusFlow: Flow<SyncStatus> = settingsManager.integrationSettingsFlow.map { settings ->
        exerciseDbProvider.updateSettings(settings)
        if (!settings.exerciseDbEnabled) {
            SyncStatus.DISABLED
        } else {
            settings.lastSyncStatus
        }
    }

    suspend fun hasIncompleteSync(): Boolean = withContext(Dispatchers.IO) {
        syncQueue.hasIncompleteSync()
    }

    suspend fun testConnection(): NetworkTestResult {
        return repository.testConnection("bench press")
    }

    fun cancelSync() {
        isCancelled = true
        Log.w("ExerciseMediaSync", "Sincronização cancelada pelo usuário")
    }

    fun resetState() {
        _syncState.value = SyncState.Idle
    }

    suspend fun startSync(forceRestart: Boolean = false): SyncState = withContext(Dispatchers.IO) {
        isCancelled = false
        _syncState.value = SyncState.Preparing

        val settings = settingsManager.integrationSettingsFlow.first()
        exerciseDbProvider.updateSettings(settings)

        if (!settings.exerciseDbEnabled) {
            settingsManager.setLastSyncStatus(SyncStatus.DISABLED)
            val errState = SyncState.Error("Integração com ExerciseDB está desativada nas configurações.")
            _syncState.value = errState
            return@withContext errState
        }

        if (!repository.isOnline()) {
            val errState = SyncState.Error("Sem conexão de internet. Verifique sua rede e tente novamente.")
            _syncState.value = errState
            return@withContext errState
        }

        settingsManager.setLastSyncStatus(SyncStatus.SYNCING)

        val allExercises = dao.getAllExercisesSync()
        if (allExercises.isEmpty()) {
            val emptyState = SyncState.Success(processed = 0, updated = 0, skipped = 0, failed = 0)
            _syncState.value = emptyState
            settingsManager.setLastSyncStatus(SyncStatus.SUCCESS)
            return@withContext emptyState
        }

        val checkpoints = syncQueue.prepareQueue(allExercises, forceRestart = forceRestart)
        val total = checkpoints.size
        val exercisesMap = allExercises.associateBy { it.id }

        var processedCount = 0
        var updatedCount = 0
        var skippedCount = 0
        var failedCount = 0
        val errorDetails = mutableListOf<SyncErrorItem>()

        for ((index, checkpoint) in checkpoints.withIndex()) {
            if (isCancelled) {
                Log.w("ExerciseMediaSync", "Interrompendo loop de sincronização por cancelamento.")
                settingsManager.setLastSyncStatus(SyncStatus.READY)
                break
            }

            val currentNum = index + 1
            val percentage = (currentNum * 100) / total
            val exercise = exercisesMap[checkpoint.exerciseId]

            if (exercise == null) {
                skippedCount++
                processedCount++
                continue
            }

            _syncState.value = SyncState.Running(
                current = currentNum,
                total = total,
                exerciseName = exercise.name,
                percentage = percentage
            )

            // If checkpoint already marked as SUCCESS or SKIPPED (and not force restart), skip
            if (!forceRestart && (checkpoint.status == SyncCheckpointStatus.SUCCESS.name || checkpoint.status == SyncCheckpointStatus.SKIPPED.name)) {
                if (checkpoint.status == SyncCheckpointStatus.SUCCESS.name) updatedCount++
                else skippedCount++
                processedCount++
                continue
            }

            // Check if queue logic considers it skippable
            if (syncQueue.shouldSkipExercise(exercise)) {
                syncQueue.markSkipped(exercise.id, exercise.name)
                skippedCount++
                processedCount++
                continue
            }

            syncQueue.markProcessing(exercise.id, exercise.name, checkpoint.attempts + 1)

            val itemResult = repository.syncSingleExercise(
                exercise = exercise,
                rateLimiter = rateLimiter,
                onRetryLog = { retryMsg ->
                    Log.d("ExerciseMediaSync", retryMsg)
                }
            )

            processedCount++

            when (itemResult) {
                is SyncExerciseItemResult.Success -> {
                    syncQueue.markSuccess(exercise.id, exercise.name)
                    updatedCount++
                }
                is SyncExerciseItemResult.Skipped -> {
                    syncQueue.markSkipped(exercise.id, exercise.name)
                    skippedCount++
                }
                is SyncExerciseItemResult.Failed -> {
                    syncQueue.markFailed(exercise.id, exercise.name, checkpoint.attempts + 1, itemResult.reason)
                    failedCount++
                    errorDetails.add(
                        SyncErrorItem(
                            exerciseId = exercise.id,
                            exerciseName = exercise.name,
                            reason = itemResult.reason
                        )
                    )
                }
            }
        }

        val finalState = if (isCancelled) {
            SyncState.Error("Sincronização cancelada pelo usuário.")
        } else {
            val now = System.currentTimeMillis()
            settingsManager.setLastMediaSyncAt(now)
            settingsManager.setLastSyncStatus(if (failedCount == 0) SyncStatus.SUCCESS else SyncStatus.READY)

            SyncState.Success(
                processed = processedCount,
                updated = updatedCount,
                skipped = skippedCount,
                failed = failedCount,
                errorDetails = errorDetails
            )
        }

        _syncState.value = finalState
        return@withContext finalState
    }
}
