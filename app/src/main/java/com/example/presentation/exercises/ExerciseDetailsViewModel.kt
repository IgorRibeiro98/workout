package com.example.presentation.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.PersonalRecordEntity
import com.example.data.repository.CustomExerciseDeleteResult
import com.example.data.repository.CustomExerciseFields
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ExerciseDetailsViewModel(
    private val workoutEngine: WorkoutEngine,
    private val repository: WorkoutRepository,
    val settingsManager: SettingsManager
) : ViewModel() {

    private val workoutDao = repository.dao

    /**
     * Resultado da última exclusão pedida nesta tela (T19.7C). A UI consome e limpa com
     * [consumeDeleteResult]; `Deleted`/`Archived` fecham a tela, `UsedByTemplates` só avisa.
     */
    private val _deleteResult = MutableStateFlow<CustomExerciseDeleteResult?>(null)
    val deleteResult: StateFlow<CustomExerciseDeleteResult?> = _deleteResult.asStateFlow()

    fun canSaveCustom(name: String): Boolean = CustomExerciseFields.isValidName(name)

    /** Edita a linha de um `CUSTOM`; para um canônico não faz nada (ver [WorkoutRepository.updateCustomExercise]). */
    fun updateCustomExercise(exerciseId: Long, name: String, muscle: String?, equipment: String?, description: String?) {
        if (!canSaveCustom(name)) return
        viewModelScope.launch {
            repository.updateCustomExercise(exerciseId, name, muscle, equipment, description)
        }
    }

    fun deleteCustomExercise(exerciseId: Long) {
        viewModelScope.launch {
            val exercise = workoutDao.getExerciseById(exerciseId) ?: return@launch
            _deleteResult.value = repository.deleteExercise(exercise)
        }
    }

    fun consumeDeleteResult() {
        _deleteResult.value = null
    }

    val showGifs = settingsManager.showGifsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun getExerciseInfo(exerciseId: Long): Flow<ExerciseEntity?> = 
        workoutDao.getExerciseByIdFlow(exerciseId)

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

    
    fun getPremiumInfo(exerciseId: Long): Flow<PremiumExerciseInfo?> = flow {
        val education = workoutDao.getExerciseEducation(exerciseId)
        val media = workoutDao.getExerciseMedia(exerciseId)
        val progression = workoutDao.getExerciseProgression(exerciseId)
        val safety = workoutDao.getExerciseSafety(exerciseId)
        val substitution = workoutDao.getExerciseSubstitutionPremium(exerciseId)
        val aiContext = workoutDao.getExerciseAiContext(exerciseId)
        val biomechanics = workoutDao.getExerciseBiomechanics(exerciseId)
        val execution = workoutDao.getExerciseExecution(exerciseId)
        
        if (education != null || media != null || progression != null || safety != null || substitution != null || aiContext != null || biomechanics != null || execution != null) {
            emit(PremiumExerciseInfo(education, media, progression, safety, substitution, aiContext, biomechanics, execution))
        } else {
            emit(null)
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

