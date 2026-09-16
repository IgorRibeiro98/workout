package com.example.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.TemplateExerciseWithDetails
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

import com.example.domain.engine.ExerciseResolver
import com.example.domain.workout.template.TemplateExerciseOrder
import kotlinx.coroutines.flow.combine

data class ResolvedTemplateExercise(
    val templateExercise: com.example.data.local.WorkoutTemplateExerciseEntity,
    val resolvedExercise: com.example.domain.model.ResolvedExercise
)

class TemplateDetailsViewModel(
    private val repository: WorkoutRepository,
    settingsManager: com.example.data.datastore.SettingsManager
) : ViewModel() {

    /**
     * Preferência de vibração dos gestos de arrastar.
     *
     * Ela vem por aqui porque a tela não pode ler o `SettingsManager` do `MainApplication` direto
     * (§3): a UI consome ViewModel, e não a camada de dados.
     */
    val hapticEnabled: StateFlow<Boolean> = settingsManager.hapticEnabledFlow
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val showGifs: Flow<Boolean> = settingsManager.showGifsFlow.distinctUntilChanged()

    private val _templateId = MutableStateFlow<Long>(-1L)

    fun load(templateId: Long) {
        if (_templateId.value != templateId) _pendingOrder.value = null
        _templateId.value = templateId
    }

    /**
     * A ordem que o usuário acabou de pedir e o Room ainda não refletiu (T19.6).
     *
     * Entre o drop e a emissão do Room há alguns milissegundos em que a lista persistida ainda é
     * a antiga. Sem isto a tela voltaria à ordem velha e pularia para a nova em seguida — com
     * `animateItem`, um vai-e-volta visível. A ordem pendente é projetada por cima da lista do
     * Room até que ele emita: ou a mesma ordem, ou qualquer lista **depois** de a escrita ter sido
     * confirmada (um sync pode ter mudado o treino nesse meio-tempo, e aí quem manda é o banco).
     * Ela nunca é persistida por si: `sortOrder` continua sendo a única autoridade.
     */
    private data class PendingOrder(val ids: List<Long>, val token: Any, val committed: Boolean)

    private val _pendingOrder = MutableStateFlow<PendingOrder?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val template: StateFlow<WorkoutTemplateEntity?> = _templateId
        .flatMapLatest { id ->
            if (id != -1L) flowOf(repository.getTemplate(id)) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val persistedExercises: Flow<List<TemplateExerciseWithDetails>> = _templateId
        .flatMapLatest { id ->
            if (id != -1L) repository.getTemplateExercises(id) else flowOf(emptyList())
        }
        .onEach { persisted ->
            val ids = persisted.map { it.templateExercise.id }
            _pendingOrder.update { pending ->
                pending?.takeUnless { it.committed || it.ids == ids }
            }
        }

    val exercises: StateFlow<List<ResolvedTemplateExercise>> = combine(
        persistedExercises,
        repository.allOverridesFlow,
        showGifs,
        _pendingOrder
    ) { templateExs, overrides, gifs, pending ->
        val overrideMap = overrides.associateBy { it.exerciseId }
        val ordered = if (pending == null) {
            templateExs
        } else {
            val byId = templateExs.associateBy { it.templateExercise.id }
            TemplateExerciseOrder.reorder(templateExs.map { it.templateExercise }, pending.ids)
                .mapNotNull { byId[it.id] }
        }
        ordered.map { te ->
            ResolvedTemplateExercise(
                templateExercise = te.templateExercise,
                resolvedExercise = ExerciseResolver.resolve(te.exercise, overrideMap[te.exercise.id], gifs)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allExercises = repository.activeResolvedExercises
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val rawExercises: StateFlow<List<TemplateExerciseWithDetails>> = _templateId
        .flatMapLatest { id ->
            if (id != -1L) repository.getTemplateExercises(id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addExerciseToTemplate(exerciseId: Long) {
        addExercisesToTemplate(listOf(exerciseId))
    }

    fun addExercisesToTemplate(exerciseIds: List<Long>) {
        val id = _templateId.value
        if (id == -1L || exerciseIds.isEmpty()) return
        val currentSort = exercises.value.size
        viewModelScope.launch {
            exerciseIds.forEachIndexed { index, exerciseId ->
                repository.addExerciseToTemplate(id, exerciseId, currentSort + index)
            }
        }
    }

    fun updateExercise(templateExercise: WorkoutTemplateExerciseEntity) {
        viewModelScope.launch {
            repository.updateTemplateExerciseFull(templateExercise)
        }
    }

    fun removeExercise(templateExercise: WorkoutTemplateExerciseEntity) {
        viewModelScope.launch {
            repository.removeExerciseFromTemplate(templateExercise)
        }
    }

    /**
     * A ordem em vigor **agora**: a última pedida, mesmo que o `StateFlow` ainda não a tenha
     * projetado. Dois gestos no mesmo despacho (soltar e logo "mover para cima") partem um do
     * outro, e não os dois da ordem antiga.
     */
    private fun currentEntities(): List<WorkoutTemplateExerciseEntity> {
        val shown = exercises.value.map { it.templateExercise }
        val pending = _pendingOrder.value ?: return shown
        return TemplateExerciseOrder.reorder(shown, pending.ids)
    }

    /** "Mover para cima/baixo" do action sheet — o mesmo caminho do arrastar, uma posição por vez. */
    fun moveExercise(fromIndex: Int, toIndex: Int) {
        val current = currentEntities()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        reorderExercises(TemplateExerciseOrder.move(current, fromIndex, toIndex).map { it.id })
    }

    /**
     * Persiste a ordem [orderedIds] (ids de `WorkoutTemplateExerciseEntity`) — o drop do arrastar.
     *
     * A ordem final é normalizada por [TemplateExerciseOrder] sobre a lista que a tela está
     * mostrando agora, e todos os `sortOrder` entram numa transação só. Uma ordem igual à atual
     * não escreve nada: soltar o item onde ele já estava, ou soltar duas vezes a mesma ordem, não
     * gera mutação nem entrada de sync (um `sortOrder` com buracos só é normalizado quando há uma
     * mudança real de ordem — o usuário não gera escrita sem ter movido nada).
     */
    fun reorderExercises(orderedIds: List<Long>) {
        val current = currentEntities()
        val updated = TemplateExerciseOrder.reorder(current, orderedIds)
        if (updated.map { it.id } == current.map { it.id }) return
        val token = Any()
        _pendingOrder.value = PendingOrder(ids = updated.map { it.id }, token = token, committed = false)
        viewModelScope.launch {
            try {
                repository.updateTemplateExercises(updated)
            } catch (t: Throwable) {
                // A escrita não aconteceu: o que o banco tem é o que a tela deve mostrar.
                _pendingOrder.update { pending -> pending?.takeUnless { it.token === token } }
                throw t
            }
            _pendingOrder.update { pending ->
                if (pending != null && pending.token === token) pending.copy(committed = true) else pending
            }
        }
    }
}
