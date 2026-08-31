package com.example.domain.engine

import android.content.Context
import com.example.data.datastore.SettingsManager
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.data.remote.ExerciseMediaRepository
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.NetworkExerciseRemoteDataSource
import kotlinx.coroutines.flow.firstOrNull

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
    val alreadyUpToDate: Int = 0,
    val isOffline: Boolean = false,
    val errors: List<String> = emptyList()
)

data class MediaLibraryDiagnostic(
    val totalExercises: Int = 0,
    val withExerciseDbSearch: Int = 0,
    val withoutExerciseDbSearch: Int = 0,
    val matchedCount: Int = 0,
    val ambiguousCount: Int = 0,
    val notFoundCount: Int = 0,
    val gifsCount: Int = 0,
    val customPhotosCount: Int = 0,
    val curatedVideosCount: Int = 0,
    val noMediaCount: Int = 0
)

class ExerciseMediaEngine(
    private val repository: ExerciseMediaRepository,
    private val dao: WorkoutDao? = null,
    private val context: Context? = null
) {
    constructor(
        dao: WorkoutDao,
        remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource(),
        context: Context? = null
    ) : this(ExerciseMediaRepository(dao, remoteDataSource, context), dao, context)

    suspend fun syncExerciseGifs(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): MediaSyncResult = repository.syncExerciseGifs(onProgress)

    suspend fun testConnection(query: String = "bench press"): com.example.data.remote.NetworkTestResult = 
        repository.testConnection(query)

    suspend fun syncOpportunistic(
        settingsManager: SettingsManager,
        currentCatalogVersion: Int = 1
    ): Boolean {
        return try {
            val showGifs = settingsManager.showGifsFlow.firstOrNull() ?: true
            if (!showGifs) return false

            val syncedVersion = settingsManager.mediaSyncContentVersionFlow.firstOrNull() ?: 0
            val lastSync = settingsManager.lastMediaSyncAtFlow.firstOrNull()

            if (syncedVersion >= currentCatalogVersion && lastSync != null) {
                return false
            }

            val syncResult = syncExerciseGifs()
            if (!syncResult.isOffline && syncResult.errors.isEmpty()) {
                settingsManager.setLastMediaSyncAt(System.currentTimeMillis())
                settingsManager.setMediaSyncContentVersion(currentCatalogVersion)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun getLibraryDiagnostic(): MediaLibraryDiagnostic {
        val exercises = dao?.getAllExercisesSync() ?: emptyList()
        var withSearch = 0
        var withoutSearch = 0
        val overrides = dao?.getAllOverrides()?.associateBy { it.exerciseId } ?: emptyMap()

        var matched = 0
        var ambiguous = 0
        var notFound = 0
        var gifs = 0
        var photos = 0
        var videos = 0
        var noMedia = 0

        exercises.forEach { ex ->
            val override = overrides[ex.id]
            val hasCustomPhoto = !override?.customPhotoUri.isNullOrBlank() || !ex.customPhotoUri.isNullOrBlank()
            val hasGif = !ex.gifUrl.isNullOrBlank()
            val hasVideo = ExerciseVideoRegistry.getVideoForExercise(context, ex.canonicalId, ex.slug, ex.name) != null

            when (ex.mappingStatus) {
                ExerciseMatchStatus.MATCHED.name -> matched++
                ExerciseMatchStatus.AMBIGUOUS.name -> ambiguous++
                ExerciseMatchStatus.NOT_FOUND.name -> notFound++
            }

            if (hasCustomPhoto) photos++
            if (hasGif) gifs++
            if (hasVideo) videos++

            if (!hasCustomPhoto && !hasGif && !hasVideo) {
                noMedia++
            }
            if (ex.exerciseDbSearch.isNullOrBlank()) withoutSearch++ else withSearch++
        }

        return MediaLibraryDiagnostic(
            totalExercises = exercises.size,
            withExerciseDbSearch = withSearch,
            withoutExerciseDbSearch = withoutSearch,
            matchedCount = matched,
            ambiguousCount = ambiguous,
            notFoundCount = notFound,
            gifsCount = gifs,
            customPhotosCount = photos,
            curatedVideosCount = videos,
            noMediaCount = noMedia
        )
    }

    fun evaluateCandidates(
        exercise: ExerciseEntity,
        candidates: List<ExternalExerciseDto>,
        targetQuery: String? = null
    ): MatchEvaluation = repository.evaluateCandidates(exercise, candidates, targetQuery)
}
