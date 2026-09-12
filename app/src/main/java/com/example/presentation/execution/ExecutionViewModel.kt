package com.example.presentation.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SessionWithDetails
import com.example.data.local.SetLogEntity
import com.example.domain.engine.SyncResult
import com.example.domain.engine.WorkoutEngine
import com.example.service.WorkoutNotificationManager
import com.example.domain.workout.execution.ExerciseExecutionStatus
import com.example.domain.workout.execution.WorkoutExerciseExecution
import com.example.domain.workout.execution.WorkoutExecutionOrderManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ExecutionPhase {
    ACTIVE_SET,
    RESTING,
    EXERCISE_TRANSITION,
    WORKOUT_COMPLETE
}

enum class FeedbackType {
    NEW_RECORD,
    PROGRESSION,
    GOAL_ACHIEVED,
    FIRST_TIME,
    NORMAL
}

data class SetCompletionFeedback(
    val type: FeedbackType,
    val title: String,
    val subtitle: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Só valem enquanto o motor não respondeu: são os mesmos padrões de `SettingsManager`, e existem
 * para que o estado inicial não precise mentir um número.
 */
private const val DEFAULT_REST_BETWEEN_SETS_SECONDS = 90
private const val DEFAULT_REST_AFTER_EXERCISE_SECONDS = 120

data class ExecutionState(
    val sessionWithDetails: SessionWithDetails? = null,
    val currentExerciseIndex: Int = 0,
    val isLoading: Boolean = true,
    val previousExecutionSets: List<SetLogEntity> = emptyList(),
    val currentResolvedExercise: com.example.domain.model.ResolvedExercise? = null,
    val exerciseExecutionContext: com.example.domain.workout.execution.ExerciseExecutionContext? = null,
    val isResting: Boolean = false,
    val pendingMoveConfirmation: WorkoutExerciseExecution? = null,
    val lastSetFeedback: SetCompletionFeedback? = null,
    /**
     * Os dois descansos recomendados para o exercício em foco, resolvidos pelo motor.
     *
     * Estão no estado — e não calculados na tela — porque a regra é uma só e vive em
     * `WorkoutEngine.resolveRestRecommendation`. A tela de transição entre exercícios calculava a
     * sua própria versão, com um `90` escrito à mão, e recomendava um valor diferente do que o
     * temporizador automático usava no mesmo instante.
     */
    val restSecondsBetweenSets: Int = DEFAULT_REST_BETWEEN_SETS_SECONDS,
    val restSecondsAfterExercise: Int = DEFAULT_REST_AFTER_EXERCISE_SECONDS
) {
    val currentExercise: ExerciseSessionWithSets?
        get() = sessionWithDetails?.exercises?.getOrNull(currentExerciseIndex)

    val isLastExercise: Boolean
        get() = currentExerciseIndex >= (sessionWithDetails?.exercises?.size ?: 1) - 1

    /**
     * True only when no other exercise still has pending sets, so finishing the current one
     * finishes the workout.
     *
     * Being positionally last is not enough: after a reorder the user can be standing on the
     * last card while an earlier exercise is still pending, and offering "concluir treino"
     * there would end the session with work left to do.
     */
    val isLastPendingExercise: Boolean
        get() {
            val exercises = sessionWithDetails?.exercises ?: return false
            if (exercises.isEmpty()) return false
            return exercises.withIndex().none { (idx, ex) ->
                idx != currentExerciseIndex && (ex.sets.isEmpty() || ex.sets.any { !it.completed })
            }
        }

    val isFirstExercise: Boolean
        get() = currentExerciseIndex == 0

    val activeSetIndex: Int?
        get() = currentExercise?.sets?.indexOfFirst { !it.completed }?.takeIf { it >= 0 }

    val activeSet: SetLogEntity?
        get() = activeSetIndex?.let { currentExercise?.sets?.getOrNull(it) }

    val isExerciseCompleted: Boolean
        get() = currentExercise?.sets?.isNotEmpty() == true && currentExercise?.sets?.all { it.completed } == true

    val isAllExercisesCompleted: Boolean
        get() = sessionWithDetails?.exercises?.isNotEmpty() == true &&
                sessionWithDetails.exercises.all { ex -> ex.sets.isNotEmpty() && ex.sets.all { it.completed } }

    val isOrderAdapted: Boolean
        get() = sessionWithDetails?.exercises?.any { it.exerciseSession.executionOrder != it.exerciseSession.plannedOrder } == true

    val nextPendingExercise: ExerciseSessionWithSets?
        get() {
            val exercises = sessionWithDetails?.exercises ?: return null
            if (exercises.isEmpty()) return null
            val cur = currentExerciseIndex
            // First search forward from current index
            val forward = (cur + 1 until exercises.size).asSequence()
                .map { exercises[it] }
                .firstOrNull { it.sets.isEmpty() || it.sets.any { s -> !s.completed } }
            if (forward != null) return forward
            // Then wrap around to earlier exercises if pending
            return (0 until cur).asSequence()
                .map { exercises[it] }
                .firstOrNull { it.sets.isEmpty() || it.sets.any { s -> !s.completed } }
        }

    val exerciseExecutions: List<WorkoutExerciseExecution>
        get() = sessionWithDetails?.exercises?.mapIndexed { idx, ex ->
            val totalSets = ex.sets.size
            val completedCount = ex.sets.count { it.completed }
            val status = when {
                totalSets > 0 && completedCount == totalSets -> ExerciseExecutionStatus.COMPLETED
                idx == currentExerciseIndex || completedCount > 0 -> ExerciseExecutionStatus.IN_PROGRESS
                else -> ExerciseExecutionStatus.PENDING
            }
            val exIdStr = ex.exerciseSession.id.toString()
            WorkoutExerciseExecution(
                exerciseSessionId = ex.exerciseSession.id,
                exerciseId = exIdStr,
                name = ex.exerciseSession.exerciseNameSnapshot,
                plannedOrder = ex.exerciseSession.plannedOrder,
                executionOrder = ex.exerciseSession.executionOrder,
                status = status
            )
        } ?: emptyList()

    val phase: ExecutionPhase
        get() = when {
            isAllExercisesCompleted -> ExecutionPhase.WORKOUT_COMPLETE
            isResting -> ExecutionPhase.RESTING
            isExerciseCompleted -> ExecutionPhase.EXERCISE_TRANSITION
            else -> ExecutionPhase.ACTIVE_SET
        }
}

class ExecutionViewModel(
    private val workoutEngine: WorkoutEngine,
    private val notificationManager: WorkoutNotificationManager,
    val settingsManager: SettingsManager
) : ViewModel() {

    /**
     * Fronteira de exceção do ViewModel (auditoria 2026-09-12).
     *
     * Todo `viewModelScope.launch` daqui escreve no Room ou no DataStore. Sem handler, uma
     * `SQLiteException` em `finishSession` ou em `updateSet` derruba o processo no meio do treino,
     * e o que o usuário perde não é a tela — é a confiança de que a série que ele acabou de fazer
     * foi registrada. O estado canônico está no banco: registrar e seguir é o comportamento
     * correto, porque a próxima interação relê a verdade de lá.
     */
    private val executionExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e(TAG, "falha não tratada na execução do treino", throwable)
    }

    private fun launchGuarded(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        viewModelScope.launch(executionExceptionHandler, block = block)

    val keepScreenOn = settingsManager.keepScreenOnFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val hapticEnabled = settingsManager.hapticEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val soundEnabled = settingsManager.soundEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val preAlertEnabled = settingsManager.preAlertEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val rirRpeEnabled = settingsManager.rirRpeEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showGifs = settingsManager.showGifsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showCoachTip = settingsManager.showCoachTipFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /**
     * Exposta aqui para que a tela pare de abrir o DataStore no meio da composição.
     *
     * `ExecutionScreen` chamava `settingsManager.soundEnabledFlow.collectAsState(initial = true)`
     * a cada entrada em RESTING e em ACTIVE_SET: uma coleta nova por entrada e, até a primeira
     * emissão, o valor `true` — ou seja, o alerta podia tocar com o som desligado.
     */
    val timerNotificationEnabled = settingsManager.timerNotificationEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /**
     * O exercício em foco, identificado por `exerciseSession.id` — nunca por posição.
     *
     * Antes da auditoria de 2026-09-12 havia **duas** fontes concorrentes (um índice e um id), e a
     * reconciliação entre elas era feita por efeito colateral dentro do `combine` que produz o
     * estado: o transform escrevia em um dos seus próprios `upstream`. Isso convergia só pela
     * guarda de igualdade, gerava uma emissão extra a cada correção e dependia da ordem do
     * dispatcher — era a origem do "índice pisca para o exercício anterior". Agora o id é a única
     * fonte e o índice é derivado dele.
     */
    private val _currentExerciseSessionId = MutableStateFlow<Long?>(null)

    private val _setFeedback = MutableStateFlow<SetCompletionFeedback?>(null)

    val restTimerTarget = workoutEngine.restTimerTarget
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _pendingMoveConfirmation = MutableStateFlow<WorkoutExerciseExecution?>(null)

    /** A sessão ativa já ordenada, com o índice do exercício em foco resolvido. */
    private data class SessionSnapshot(
        val session: SessionWithDetails? = null,
        val currentIndex: Int = 0
    ) {
        val currentExercise: ExerciseSessionWithSets?
            get() = session?.exercises?.getOrNull(currentIndex)
    }

    /** O que depende do exercício em foco e precisa ir ao banco para ser respondido. */
    private data class ExerciseContextSnapshot(
        val context: com.example.domain.workout.execution.ExerciseExecutionContext? = null,
        val previousSets: List<SetLogEntity> = emptyList(),
        val rest: com.example.domain.engine.RestRecommendation = com.example.domain.engine.RestRecommendation(
            betweenSets = DEFAULT_REST_BETWEEN_SETS_SECONDS,
            afterExercise = DEFAULT_REST_AFTER_EXERCISE_SECONDS
        )
    )

    private data class ExerciseKey(
        val exerciseId: Long?,
        val templateId: Long?,
        val restSnapshotSeconds: Int?
    )

    /**
     * Fonte única da sessão ativa, compartilhada por todo o pipeline.
     *
     * `shareIn` aqui não é detalhe de performance: sem ele, os três consumidores abaixo (estado,
     * chave do exercício e exercício resolvido) abririam **três** consultas idênticas ao Room, e
     * cada série concluída dispararia as três.
     */
    private val sessionSnapshot: Flow<SessionSnapshot> = combine(
        workoutEngine.activeSessionWithDetailsFlow,
        _currentExerciseSessionId
    ) { raw, trackedId ->
        // Normaliza a sessão **uma vez**, aqui: exercícios em ordem de execução e séries em ordem
        // de domínio. `@Relation` não ordena (ver `ExerciseSessionWithSets.sortedSets`), e daqui
        // para baixo tudo — `activeSetIndex`, "Série N de M", a folha de todas as séries, o card
        // da próxima série — assume ordem por `setNumber`. Ordenar em cada consumidor seria a
        // mesma regra escrita oito vezes, e a ordem física por `rowid` só coincide até o primeiro
        // restore ou a primeira sessão recebida pelo sync.
        val session = raw?.let { s ->
            s.copy(
                exercises = s.exercises
                    .sortedBy { it.exerciseSession.executionOrder }
                    .map { it.copy(sets = it.sortedSets) }
            )
        }
        val exercises = session?.exercises.orEmpty()
        val index = when {
            exercises.isEmpty() -> 0
            else -> {
                val tracked = trackedId
                    ?.let { id -> exercises.indexOfFirst { it.exerciseSession.id == id } }
                    ?: -1
                // Sem exercício fixado — abertura do app, ou uma sessão nova — o foco nasce no
                // primeiro exercício com série pendente, que é onde o usuário parou.
                if (tracked >= 0) tracked
                else exercises.indexOfFirst { ex -> ex.sets.any { !it.completed } }.takeIf { it >= 0 } ?: 0
            }
        }
        SessionSnapshot(session, index)
    }.onEach { snapshot ->
        // Fixa o exercício derivado. É o que impede o foco de "andar sozinho": sem isto, concluir
        // a última série do exercício 1 faria o índice derivado pular para o 2 e a fase de
        // transição entre exercícios nunca apareceria. Escreve o mesmo valor que já está lá na
        // maioria das emissões, e `ExecutionState` é `data class` — emissão idêntica não desce.
        val current = snapshot.currentExercise?.exerciseSession?.id
        if (current != null && _currentExerciseSessionId.value != current) {
            _currentExerciseSessionId.value = current
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    private val currentExerciseKey: Flow<ExerciseKey> = sessionSnapshot
        .map {
            ExerciseKey(
                exerciseId = it.currentExercise?.exerciseSession?.actualExerciseId,
                templateId = it.session?.session?.templateId,
                restSnapshotSeconds = it.currentExercise?.exerciseSession?.restDurationSecondsSnapshot
            )
        }
        .distinctUntilChanged()

    /**
     * Contexto e execução anterior do exercício em foco.
     *
     * Isto era um `collect` no `init` gravando em dois `MutableStateFlow`. O efeito colateral disso
     * era o pipeline inteiro ficar **quente para sempre**: o `WhileSubscribed(5000)` do estado não
     * valia nada, porque o próprio ViewModel era um assinante permanente — e o ViewModel vive no
     * escopo da Activity. Resultado: consulta de sessão ativa e resolução de exercício rodando
     * durante toda a vida do app, mesmo sem treino em andamento.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val exerciseContext: Flow<ExerciseContextSnapshot> = currentExerciseKey
        .mapLatest { key ->
            val exerciseId = key.exerciseId ?: return@mapLatest ExerciseContextSnapshot()
            ExerciseContextSnapshot(
                context = workoutEngine.getExerciseExecutionContext(exerciseId, key.templateId),
                previousSets = workoutEngine.getLastExecutionSetsForExercise(exerciseId),
                rest = workoutEngine.resolveRestRecommendation(exerciseId, key.restSnapshotSeconds)
            )
        }
        // `combine` só emite depois que **todas** as fontes emitiram uma vez. Sem este valor
        // inicial, a tela ficaria em "carregando" até as duas consultas voltarem.
        .onStart { emit(ExerciseContextSnapshot()) }

    private val resolvedCurrentExercise: Flow<com.example.domain.model.ResolvedExercise?> =
        workoutEngine.resolvedExerciseFlow(currentExerciseKey.map { it.exerciseId })

    private val baseSessionState: Flow<ExecutionState> = combine(
        sessionSnapshot,
        exerciseContext,
        resolvedCurrentExercise
    ) { snapshot, context, resolved ->
        ExecutionState(
            sessionWithDetails = snapshot.session,
            currentExerciseIndex = snapshot.currentIndex,
            isLoading = snapshot.session == null,
            previousExecutionSets = context.previousSets,
            currentResolvedExercise = resolved,
            exerciseExecutionContext = context.context,
            restSecondsBetweenSets = context.rest.betweenSets,
            restSecondsAfterExercise = context.rest.afterExercise
        )
    }

    val state: StateFlow<ExecutionState> = combine(
        baseSessionState,
        restTimerTarget,
        _setFeedback,
        _pendingMoveConfirmation
    ) { baseState, timerTarget, feedback, pendingMove ->
        val isTimerActive = timerTarget != null && timerTarget > System.currentTimeMillis()
        baseState.copy(
            isResting = isTimerActive,
            lastSetFeedback = feedback,
            pendingMoveConfirmation = pendingMove
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExecutionState())

    // Quem **mostra** a notificação de descanso não é mais este ViewModel.
    //
    // Havia três autoridades para a mesma notificação: este ViewModel mostrava e cancelava, o
    // `MainApplication` cancelava de novo e o `RestNotificationReceiver` remostrava no "+30s". Pior:
    // o nome do exercício vinha de `state.value`, que logo após a restauração do processo ainda é
    // `ExecutionState()` — e a notificação saía como "Descanso: Exercício". Quem observa o alvo do
    // descanso agora é só o `MainApplication`, que sobrevive à tela e lê o nome canônico por
    // `getActiveExerciseNameForTimer()`. O que sobrou aqui é o cancelamento explícito ao encerrar
    // a sessão — redundante com o alvo indo a nulo, mas imediato, e é o que o usuário espera ao
    // tocar em "finalizar".

    fun selectExercise(index: Int) {
        val exercises = state.value.sessionWithDetails?.exercises ?: return
        if (index in exercises.indices) {
            _currentExerciseSessionId.value = exercises[index].exerciseSession.id
        }
    }

    fun setPendingMoveConfirmation(execution: WorkoutExerciseExecution?) {
        _pendingMoveConfirmation.value = execution
    }

    fun dismissPendingMoveConfirmation() {
        _pendingMoveConfirmation.value = null
    }

    fun moveExercise(exerciseId: String, newPosition: Int) {
        val currentSession = state.value.sessionWithDetails ?: return
        val executions = state.value.exerciseExecutions
        val reorderedExecutions = WorkoutExecutionOrderManager.moveExercise(executions, exerciseId, newPosition)

        val map = currentSession.exercises.associateBy {
            it.exerciseSession.id.toString()
        }

        val updatedEntities = reorderedExecutions.mapNotNull { ex ->
            map[ex.exerciseId]?.exerciseSession?.copy(
                executionOrder = ex.executionOrder,
                sortOrder = ex.executionOrder
            )
        }

        launchGuarded {
            workoutEngine.reorderExercises(currentSession.session.id, updatedEntities)
        }
    }

    fun moveExerciseToLater(exerciseId: String) {
        val currentSession = state.value.sessionWithDetails ?: return
        val executions = state.value.exerciseExecutions
        val reorderedExecutions = WorkoutExecutionOrderManager.moveExerciseToLater(executions, exerciseId)

        val map = currentSession.exercises.associateBy {
            it.exerciseSession.id.toString()
        }

        val updatedEntities = reorderedExecutions.mapNotNull { ex ->
            map[ex.exerciseId]?.exerciseSession?.copy(
                executionOrder = ex.executionOrder,
                sortOrder = ex.executionOrder
            )
        }

        launchGuarded {
            workoutEngine.reorderExercises(currentSession.session.id, updatedEntities)
        }
    }

    fun nextExercise() {
        val exercises = state.value.sessionWithDetails?.exercises ?: return
        if (exercises.isEmpty()) return

        val cur = state.value.currentExerciseIndex
        // 1. Search forward from cur + 1 for next pending/incomplete exercise
        val nextPendingIndex = (cur + 1 until exercises.size).firstOrNull { idx ->
            val ex = exercises[idx]
            ex.sets.isEmpty() || ex.sets.any { !it.completed }
        } ?: (0 until cur).firstOrNull { idx ->
            // 2. Wrap around from beginning if earlier exercises are pending
            val ex = exercises[idx]
            ex.sets.isEmpty() || ex.sets.any { !it.completed }
        } ?: (cur + 1).takeIf { it in exercises.indices } // 3. Fallback to immediate next

        if (nextPendingIndex != null) {
            _currentExerciseSessionId.value = exercises[nextPendingIndex].exerciseSession.id
        }
    }

    fun previousExercise() {
        val exercises = state.value.sessionWithDetails?.exercises ?: return
        val cur = state.value.currentExerciseIndex
        if (cur > 0) {
            _currentExerciseSessionId.value = exercises[cur - 1].exerciseSession.id
        }
    }

    fun updateSet(setLog: SetLogEntity) {
        launchGuarded {
            workoutEngine.updateSet(setLog)
        }
    }

    fun completeSet(setLog: SetLogEntity) {
        val currentContext = state.value.exerciseExecutionContext
        val lastPerf = currentContext?.lastPerformance
        val pr = currentContext?.personalRecord
        val targetWeight = currentContext?.suggestedLoad ?: 0f
        val targetReps = currentContext?.targetReps

        val feedback = when {
            // Caso 1: Novo recorde pessoal de carga (maior que o maior PR histórico)
            pr != null && setLog.weight > pr.maxWeight && setLog.weight > 0f -> {
                val diff = setLog.weight - pr.maxWeight
                val diffStr = if (diff % 1f == 0f) "+${diff.toInt()}kg" else "+${diff}kg"
                SetCompletionFeedback(
                    type = FeedbackType.NEW_RECORD,
                    title = "🔥 Novo recorde!",
                    subtitle = "$diffStr comparado ao melhor histórico"
                )
            }
            // Caso 2A: Evolução de carga em relação ao último treino
            lastPerf != null && setLog.weight > lastPerf.weight && setLog.weight > 0f -> {
                val diff = setLog.weight - lastPerf.weight
                val diffStr = if (diff % 1f == 0f) "+${diff.toInt()}kg" else "+${diff}kg"
                SetCompletionFeedback(
                    type = FeedbackType.PROGRESSION,
                    title = "🚀 Evolução de carga!",
                    subtitle = "↑ $diffStr desde o último treino"
                )
            }
            // Caso 2B: Evolução de repetições com a mesma carga em relação ao último treino
            lastPerf != null && setLog.weight >= lastPerf.weight && setLog.repetitions > lastPerf.reps -> {
                val diffReps = setLog.repetitions - lastPerf.reps
                SetCompletionFeedback(
                    type = FeedbackType.PROGRESSION,
                    title = "💪 Evolução!",
                    subtitle = "↑ +$diffReps reps comparado ao último treino"
                )
            }
            // Caso 4: Primeira execução
            currentContext?.isFirstTime == true || (lastPerf == null && pr == null) -> {
                SetCompletionFeedback(
                    type = FeedbackType.FIRST_TIME,
                    title = "Histórico iniciado ✨",
                    subtitle = "Primeira execução deste exercício"
                )
            }
            // Prescrição atingida
            (targetReps != null && setLog.repetitions in targetReps.first..targetReps.last) ||
            (targetWeight > 0f && setLog.weight >= targetWeight) -> {
                SetCompletionFeedback(
                    type = FeedbackType.GOAL_ACHIEVED,
                    title = "Prescrição atingida 💪",
                    subtitle = "Dentro da faixa esperada"
                )
            }
            // Caso 3: Dentro da média / Mantendo consistência
            else -> {
                SetCompletionFeedback(
                    type = FeedbackType.NORMAL,
                    title = "Série registrada",
                    subtitle = "Mantendo consistência"
                )
            }
        }

        _setFeedback.value = feedback

        launchGuarded {
            workoutEngine.updateSet(setLog.copy(completed = true, finishedAt = System.currentTimeMillis()))

            // O recorde pessoal **não** é gravado aqui (auditoria 2026-09-12).
            //
            // Este ponto gravava `MAX_WEIGHT` para qualquer série com carga — inclusive aquecimento
            // e série por tempo — e `uncompleteSet` não desfazia nada. Uma roda que parasse em
            // 120 kg por acidente deixava um recorde e o XP dele para sempre, e "novo recorde"
            // nunca mais aparecia naquele exercício. Pior: era uma **segunda** regra, diferente da
            // que o motor aplica em `finishSession` (que exclui aquecimento e duração).
            //
            // O texto comemorativo acima continua: ele compara com o recorde histórico e é só
            // feedback da série. Quem grava é `WorkoutEngine.evaluatePersonalRecords`, no fim do
            // treino, com a única regra que existe.

            // Auto dismiss feedback after delay
            kotlinx.coroutines.delay(3500)
            if (_setFeedback.value == feedback) {
                _setFeedback.value = null
            }
        }
    }

    fun dismissFeedback() {
        _setFeedback.value = null
    }
    
    fun uncompleteSet(setLog: SetLogEntity) {
        launchGuarded {
            workoutEngine.updateSet(setLog.copy(completed = false, finishedAt = null))
        }
    }

    fun replicateCurrentSet(onResult: (SyncResult) -> Unit) {
        val currentEx = state.value.currentExercise ?: return
        val currentSet = state.value.activeSet ?: currentEx.sets.firstOrNull { !it.completed } ?: currentEx.sets.lastOrNull() ?: return
        launchGuarded {
            val result = workoutEngine.replicateCurrentSet(currentEx.exerciseSession.id, currentSet)
            onResult(result)
        }
    }

    fun restoreLastExecutionValues(onResult: (SyncResult) -> Unit) {
        val currentEx = state.value.currentExercise ?: return
        val actualExId = currentEx.exerciseSession.actualExerciseId ?: currentEx.exerciseSession.plannedExerciseId
        launchGuarded {
            val result = workoutEngine.restoreLastExecutionSets(currentEx.exerciseSession.id, actualExId)
            onResult(result)
        }
    }

    fun addSet() {
        val currentEx = state.value.currentExercise ?: return
        val currentSets = currentEx.sets
        val newSetNumber = if (currentSets.isEmpty()) 1 else currentSets.maxOf { it.setNumber } + 1
        
        val lastSet = currentSets.lastOrNull()
        val reps = lastSet?.repetitions ?: 10
        val weight = lastSet?.weight ?: 0f

        launchGuarded {
            workoutEngine.addSet(currentEx.exerciseSession.id, newSetNumber, reps, weight)
        }
    }

    fun removeSet(setLog: SetLogEntity) {
        launchGuarded {
            workoutEngine.removeSet(setLog)
        }
    }

    fun adjustRestTimer(seconds: Int) {
        launchGuarded {
            workoutEngine.adjustRestTimer(seconds)
        }
    }

    fun startRestTimer(durationSeconds: Int) {
        launchGuarded {
            val currentEx = state.value.currentExercise
            workoutEngine.startRestTimer(
                durationSeconds = durationSeconds,
                workoutSessionId = state.value.sessionWithDetails?.session?.id,
                exerciseSessionId = currentEx?.exerciseSession?.id,
                timerType = "REST_EXERCISE"
            )
        }
    }

    fun skipRestTimer() {
        launchGuarded {
            workoutEngine.skipRestTimer()
        }
    }

    fun finishSession() {
        val sessionId = state.value.sessionWithDetails?.session?.id ?: return
        launchGuarded {
            workoutEngine.finishSession(sessionId)
            notificationManager.cancelNotification()
        }
    }

    fun cancelSession() {
        val sessionId = state.value.sessionWithDetails?.session?.id ?: return
        launchGuarded {
            workoutEngine.cancelSession(sessionId)
            notificationManager.cancelNotification()
        }
    }

    // Alternatives
    private val _alternatives = MutableStateFlow<List<com.example.domain.model.ResolvedExercise>>(emptyList())
    val alternatives: StateFlow<List<com.example.domain.model.ResolvedExercise>> = _alternatives

    fun loadAlternatives() {
        val exerciseId = state.value.currentExercise?.exerciseSession?.actualExerciseId ?: return
        launchGuarded {
            val exList = workoutEngine.getAlternativesForExercise(exerciseId)
            val showGifsValue = showGifs.value
            val resolved = exList.map { ex ->
                val override = workoutEngine.dao.getOverrideForExercise(ex.id)
                com.example.domain.engine.ExerciseResolver.resolve(ex, override, showGifsValue)
            }
            _alternatives.value = resolved
        }
    }

    fun swapCurrentExercise(newExerciseId: Long, permanent: Boolean) {
        val session = state.value.sessionWithDetails ?: return
        val currentEx = state.value.currentExercise ?: return
        
        launchGuarded {
            workoutEngine.swapExercise(
                exerciseSessionId = currentEx.exerciseSession.id,
                oldExerciseId = currentEx.exerciseSession.actualExerciseId ?: 0,
                newExerciseId = newExerciseId,
                permanent = permanent,
                templateId = session.session.templateId
            )
            // Clear alternatives list after swapping
            _alternatives.value = emptyList()
        }
    }

    fun clearAlternatives() {
        _alternatives.value = emptyList()
    }

    fun getPremiumInfo(exerciseId: Long): Flow<com.example.presentation.exercises.PremiumExerciseInfo?> = flow {
        val education = workoutEngine.dao.getExerciseEducation(exerciseId)
        val media = workoutEngine.dao.getExerciseMedia(exerciseId)
        val progression = workoutEngine.dao.getExerciseProgression(exerciseId)
        val safety = workoutEngine.dao.getExerciseSafety(exerciseId)
        val substitution = workoutEngine.dao.getExerciseSubstitutionPremium(exerciseId)
        val aiContext = workoutEngine.dao.getExerciseAiContext(exerciseId)
        val biomechanics = workoutEngine.dao.getExerciseBiomechanics(exerciseId)
        val execution = workoutEngine.dao.getExerciseExecution(exerciseId)

        if (education == null && media == null && progression == null && safety == null &&
            substitution == null && aiContext == null && biomechanics == null && execution == null) {
            emit(null)
        } else {
            emit(
                com.example.presentation.exercises.PremiumExerciseInfo(
                    education = education,
                    media = media,
                    progression = progression,
                    safety = safety,
                    substitution = substitution,
                    aiContext = aiContext,
                    biomechanics = biomechanics,
                    execution = execution
                )
            )
        }
    }

    private companion object {
        const val TAG = "ExecutionViewModel"
    }

}

