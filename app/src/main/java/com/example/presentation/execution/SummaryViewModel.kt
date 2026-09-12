package com.example.presentation.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.SessionCalendarSummary
import com.example.domain.engine.WorkoutEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SummaryViewModel(
    private val workoutEngine: WorkoutEngine
) : ViewModel() {

    /**
     * O resumo da sessão pedida, por consulta direta.
     *
     * Devolvia `getCalendarHistoryFlow().map { find { id } }`: o histórico completo, com o grafo de
     * cada treino, recarregado a cada alteração em qualquer sessão — para desenhar uma (auditoria
     * 2026-09-12). Continua sendo um `Flow` novo por chamada, então a tela o mantém em `remember`.
     */
    fun getSummary(sessionId: Long): Flow<SessionCalendarSummary?> =
        workoutEngine.getCompletedSessionSummaryFlow(sessionId)
}
