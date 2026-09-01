package com.example.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.engine.ExerciseMediaSyncManager
import com.example.domain.engine.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExerciseMediaSyncViewModel(
    val syncManager: ExerciseMediaSyncManager
) : ViewModel() {

    val syncState: StateFlow<SyncState> = syncManager.syncState

    private val _hasIncompleteSync = MutableStateFlow(false)
    val hasIncompleteSync: StateFlow<Boolean> = _hasIncompleteSync.asStateFlow()

    init {
        checkIncompleteSync()
    }

    fun checkIncompleteSync() {
        viewModelScope.launch(Dispatchers.IO) {
            _hasIncompleteSync.value = syncManager.hasIncompleteSync()
        }
    }

    fun startSync(forceRestart: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            syncManager.startSync(forceRestart = forceRestart)
            checkIncompleteSync()
        }
    }

    fun cancelSync() {
        syncManager.cancelSync()
    }

    fun resetState() {
        syncManager.resetState()
    }
}
