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

    fun getSummary(sessionId: Long): Flow<SessionCalendarSummary?> {
        return workoutEngine.getCalendarHistoryFlow().map { list ->
            list.find { it.session.id == sessionId }
        }
    }
}
