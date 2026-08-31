package com.example.presentation.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.WorkoutDao
import com.example.domain.engine.WorkoutEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ExerciseDetailsViewModel(
    private val workoutEngine: WorkoutEngine,
    private val workoutDao: WorkoutDao,
    val settingsManager: SettingsManager
) : ViewModel() {

    val showGifs = settingsManager.showGifsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun getExerciseInfo(exerciseId: Long): Flow<ExerciseEntity?> = flow {
        emit(workoutDao.getExerciseById(exerciseId))
    }

    fun getResolvedExercise(exerciseId: Long): Flow<com.example.domain.model.ResolvedExercise?> {
        return combine(
            getExerciseInfo(exerciseId),
            getUserOverride(exerciseId),
            showGifs
        ) { exercise, override, showGifs ->
            if (exercise == null) null
            else com.example.domain.engine.ExerciseResolver.resolve(exercise, override, showGifs)
        }
    }

    fun getUserOverride(exerciseId: Long): Flow<ExerciseUserOverrideEntity?> {
        return workoutDao.getOverrideForExerciseFlow(exerciseId)
    }

    fun saveUserOverride(override: ExerciseUserOverrideEntity) {
        viewModelScope.launch {
            workoutDao.insertOrUpdateOverride(override)

        }
    }

    fun removeCustomPhoto(exerciseId: Long) {
        viewModelScope.launch {
            val existing = workoutDao.getOverrideForExercise(exerciseId)
            if (existing != null) {
                workoutDao.insertOrUpdateOverride(existing.copy(customPhotoUri = null))
            }
            val ex = workoutDao.getExerciseById(exerciseId)
            if (ex != null) {
                workoutDao.updateExercise(ex.copy(customPhotoUri = null))
            }
        }
    }

    fun getAlternatives(exerciseId: Long): Flow<List<com.example.domain.model.ResolvedExercise>> = combine(
        flow {
            val alts = workoutDao.getAlternativesForExercise(exerciseId)
            val exList = alts.mapNotNull { workoutDao.getExerciseById(it.alternativeExerciseId) }
            emit(exList)
        },
        workoutDao.getAllOverridesFlow(),
        showGifs
    ) { exList, overrides, showGifsEnabled ->
        val overrideMap = overrides.associateBy { it.exerciseId }
        exList.map { com.example.domain.engine.ExerciseResolver.resolve(it, overrideMap[it.id], showGifsEnabled) }
    }

    fun getPersonalRecords(exerciseId: Long): Flow<List<PersonalRecordEntity>> {
        return workoutDao.getPRsForExerciseFlow(exerciseId)
    }

    fun getExerciseHistory(exerciseId: Long): Flow<List<ExerciseHistoryItem>> {
        return workoutEngine.getCalendarHistoryFlow().map { summaries ->
            val history = mutableListOf<ExerciseHistoryItem>()
            summaries.forEach { summary ->
                summary.exercises.filter { (it.exerciseSession.actualExerciseId ?: it.exerciseSession.plannedExerciseId) == exerciseId }.forEach { ex ->
                    if (ex.sets.isNotEmpty()) {
                        history.add(ExerciseHistoryItem(
                            date = summary.session.startedAt,
                            sessionName = summary.session.templateNameSnapshot ?: "Treino",
                            sets = ex
                        ))
                    }
                }
            }
            history.sortedByDescending { it.date }
        }
    }
}

data class ExerciseHistoryItem(
    val date: Long,
    val sessionName: String,
    val sets: ExerciseSessionWithSets
)

