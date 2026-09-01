package com.example.presentation.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BodyMeasurementEntity
import com.example.data.repository.BodyMeasurementRepository
import com.example.domain.body.BmiResult
import com.example.domain.body.BodyMeasurementValidator
import com.example.domain.body.BodyMetricsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class BodyEvolutionUiState(
    val isLoading: Boolean = false,
    val allMeasurements: List<BodyMeasurementEntity> = emptyList(),
    val latestMeasurement: BodyMeasurementEntity? = null,
    val weightVariationFromStart: Float? = null,
    val waistVariationFromStart: Float? = null,
    val latestBmiResult: BmiResult? = null,
    val selectedMeasurementForDetails: BodyMeasurementEntity? = null,
    val showDeleteConfirmDialog: Boolean = false,
    val measurementToDelete: BodyMeasurementEntity? = null,
    val userMessage: String? = null
)

data class BodyMeasurementFormState(
    val editingMeasurementId: Long? = null,
    val selectedDateMillis: Long = System.currentTimeMillis(),
    val weightKg: String = "",
    val heightCm: String = "",
    val bodyFatPercentage: String = "",
    val waistCm: String = "",
    val abdomenCm: String = "",
    val chestCm: String = "",
    val rightArmCm: String = "",
    val leftArmCm: String = "",
    val rightThighCm: String = "",
    val leftThighCm: String = "",
    val calfCm: String = "",
    val hipCm: String = "",
    val errors: Map<String, String> = emptyMap(),
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false
) {
    val isEditMode: Boolean get() = editingMeasurementId != null
}

class BodyEvolutionViewModel(
    private val repository: BodyMeasurementRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BodyEvolutionUiState(isLoading = true))
    val uiState: StateFlow<BodyEvolutionUiState> = _uiState.asStateFlow()

    private val _formState = MutableStateFlow(BodyMeasurementFormState())
    val formState: StateFlow<BodyMeasurementFormState> = _formState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allMeasurements.collect { measurements ->
                val latest = measurements.maxByOrNull { it.date }
                val oldest = measurements.minByOrNull { it.date }

                var weightVar: Float? = null
                var waistVar: Float? = null

                if (latest != null && oldest != null && latest.id != oldest.id) {
                    val oldestWithWeight = measurements.sortedBy { it.date }.firstOrNull { it.weightKg != null }
                    if (latest.weightKg != null && oldestWithWeight?.weightKg != null && latest.id != oldestWithWeight.id) {
                        weightVar = latest.weightKg - oldestWithWeight.weightKg
                    }

                    val oldestWithWaist = measurements.sortedBy { it.date }.firstOrNull { it.waistCm != null }
                    if (latest.waistCm != null && oldestWithWaist?.waistCm != null && latest.id != oldestWithWaist.id) {
                        waistVar = latest.waistCm - oldestWithWaist.waistCm
                    }
                }

                val bmiResult = BodyMetricsCalculator.calculateBmi(
                    weightKg = latest?.weightKg,
                    heightCm = latest?.heightCm
                )

                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        allMeasurements = measurements,
                        latestMeasurement = latest,
                        weightVariationFromStart = weightVar,
                        waistVariationFromStart = waistVar,
                        latestBmiResult = bmiResult
                    )
                }
            }
        }
    }

    fun selectMeasurementForDetails(measurement: BodyMeasurementEntity?) {
        _uiState.update { it.copy(selectedMeasurementForDetails = measurement) }
    }

    fun requestDeleteMeasurement(measurement: BodyMeasurementEntity) {
        _uiState.update {
            it.copy(
                showDeleteConfirmDialog = true,
                measurementToDelete = measurement
            )
        }
    }

    fun dismissDeleteDialog() {
        _uiState.update {
            it.copy(
                showDeleteConfirmDialog = false,
                measurementToDelete = null
            )
        }
    }

    fun confirmDeleteMeasurement() {
        val toDelete = _uiState.value.measurementToDelete ?: return
        viewModelScope.launch {
            repository.deleteMeasurementById(toDelete.id)
            _uiState.update {
                it.copy(
                    showDeleteConfirmDialog = false,
                    measurementToDelete = null,
                    selectedMeasurementForDetails = null,
                    userMessage = "Medição excluída com sucesso."
                )
            }
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    // Form Handlers
    fun initNewMeasurement() {
        _formState.value = BodyMeasurementFormState(
            editingMeasurementId = null,
            selectedDateMillis = System.currentTimeMillis()
        )
    }

    fun loadForEdit(measurement: BodyMeasurementEntity) {
        _formState.value = BodyMeasurementFormState(
            editingMeasurementId = measurement.id,
            selectedDateMillis = measurement.date,
            weightKg = measurement.weightKg?.let { formatValue(it) } ?: "",
            heightCm = measurement.heightCm?.let { formatValue(it) } ?: "",
            bodyFatPercentage = measurement.bodyFatPercentage?.let { formatValue(it) } ?: "",
            waistCm = measurement.waistCm?.let { formatValue(it) } ?: "",
            abdomenCm = measurement.abdomenCm?.let { formatValue(it) } ?: "",
            chestCm = measurement.chestCm?.let { formatValue(it) } ?: "",
            rightArmCm = measurement.rightArmCm?.let { formatValue(it) } ?: "",
            leftArmCm = measurement.leftArmCm?.let { formatValue(it) } ?: "",
            rightThighCm = measurement.rightThighCm?.let { formatValue(it) } ?: "",
            leftThighCm = measurement.leftThighCm?.let { formatValue(it) } ?: "",
            calfCm = measurement.calfCm?.let { formatValue(it) } ?: "",
            hipCm = measurement.hipCm?.let { formatValue(it) } ?: "",
            errors = emptyMap()
        )
    }

    private fun formatValue(v: Float): String {
        return if (v % 1.0f == 0f) {
            v.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", v)
        }
    }

    fun updateDate(millis: Long) {
        _formState.update { it.copy(selectedDateMillis = millis) }
    }

    fun updateWeight(value: String) {
        _formState.update {
            it.copy(
                weightKg = value,
                errors = it.errors - "weight" - "general"
            )
        }
    }

    fun updateHeight(value: String) {
        _formState.update {
            it.copy(
                heightCm = value,
                errors = it.errors - "height" - "general"
            )
        }
    }

    fun updateBodyFat(value: String) {
        _formState.update {
            it.copy(
                bodyFatPercentage = value,
                errors = it.errors - "bodyFat" - "general"
            )
        }
    }

    fun updateWaist(value: String) {
        _formState.update {
            it.copy(
                waistCm = value,
                errors = it.errors - "waist" - "general"
            )
        }
    }

    fun updateAbdomen(value: String) {
        _formState.update {
            it.copy(
                abdomenCm = value,
                errors = it.errors - "abdomen" - "general"
            )
        }
    }

    fun updateChest(value: String) {
        _formState.update {
            it.copy(
                chestCm = value,
                errors = it.errors - "chest" - "general"
            )
        }
    }

    fun updateRightArm(value: String) {
        _formState.update {
            it.copy(
                rightArmCm = value,
                errors = it.errors - "rightArm" - "general"
            )
        }
    }

    fun updateLeftArm(value: String) {
        _formState.update {
            it.copy(
                leftArmCm = value,
                errors = it.errors - "leftArm" - "general"
            )
        }
    }

    fun updateRightThigh(value: String) {
        _formState.update {
            it.copy(
                rightThighCm = value,
                errors = it.errors - "rightThigh" - "general"
            )
        }
    }

    fun updateLeftThigh(value: String) {
        _formState.update {
            it.copy(
                leftThighCm = value,
                errors = it.errors - "leftThigh" - "general"
            )
        }
    }

    fun updateCalf(value: String) {
        _formState.update {
            it.copy(
                calfCm = value,
                errors = it.errors - "calf" - "general"
            )
        }
    }

    fun updateHip(value: String) {
        _formState.update {
            it.copy(
                hipCm = value,
                errors = it.errors - "hip" - "general"
            )
        }
    }

    fun resetForm() {
        _formState.value = BodyMeasurementFormState()
    }

    /**
     * Validates and saves the measurement (insert or update).
     * Returns true if successfully validated and saved, false otherwise.
     */
    fun saveMeasurement(onSuccess: (() -> Unit)? = null): Boolean {
        val state = _formState.value
        val (parsedForm, errors) = BodyMeasurementValidator.validateAll(
            weightKg = state.weightKg,
            heightCm = state.heightCm,
            bodyFatPercentage = state.bodyFatPercentage,
            waistCm = state.waistCm,
            abdomenCm = state.abdomenCm,
            chestCm = state.chestCm,
            rightArmCm = state.rightArmCm,
            leftArmCm = state.leftArmCm,
            rightThighCm = state.rightThighCm,
            leftThighCm = state.leftThighCm,
            calfCm = state.calfCm,
            hipCm = state.hipCm
        )

        if (parsedForm == null || errors.isNotEmpty()) {
            _formState.update { it.copy(errors = errors) }
            return false
        }

        val entity = BodyMeasurementEntity(
            id = state.editingMeasurementId ?: 0L,
            date = state.selectedDateMillis,
            weightKg = parsedForm.weightKg,
            heightCm = parsedForm.heightCm,
            bodyFatPercentage = parsedForm.bodyFatPercentage,
            waistCm = parsedForm.waistCm,
            abdomenCm = parsedForm.abdomenCm,
            chestCm = parsedForm.chestCm,
            rightArmCm = parsedForm.rightArmCm,
            leftArmCm = parsedForm.leftArmCm,
            rightThighCm = parsedForm.rightThighCm,
            leftThighCm = parsedForm.leftThighCm,
            calfCm = parsedForm.calfCm,
            hipCm = parsedForm.hipCm
        )

        _formState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            saveMeasurementInternal(entity, state.isEditMode, onSuccess)
        }
        return true
    }

    suspend fun saveMeasurementSuspending(onSuccess: (() -> Unit)? = null): Boolean {
        val state = _formState.value
        val (parsedForm, errors) = BodyMeasurementValidator.validateAll(
            weightKg = state.weightKg,
            heightCm = state.heightCm,
            bodyFatPercentage = state.bodyFatPercentage,
            waistCm = state.waistCm,
            abdomenCm = state.abdomenCm,
            chestCm = state.chestCm,
            rightArmCm = state.rightArmCm,
            leftArmCm = state.leftArmCm,
            rightThighCm = state.rightThighCm,
            leftThighCm = state.leftThighCm,
            calfCm = state.calfCm,
            hipCm = state.hipCm
        )

        if (parsedForm == null || errors.isNotEmpty()) {
            _formState.update { it.copy(errors = errors) }
            return false
        }

        val entity = BodyMeasurementEntity(
            id = state.editingMeasurementId ?: 0L,
            date = state.selectedDateMillis,
            weightKg = parsedForm.weightKg,
            heightCm = parsedForm.heightCm,
            bodyFatPercentage = parsedForm.bodyFatPercentage,
            waistCm = parsedForm.waistCm,
            abdomenCm = parsedForm.abdomenCm,
            chestCm = parsedForm.chestCm,
            rightArmCm = parsedForm.rightArmCm,
            leftArmCm = parsedForm.leftArmCm,
            rightThighCm = parsedForm.rightThighCm,
            leftThighCm = parsedForm.leftThighCm,
            calfCm = parsedForm.calfCm,
            hipCm = parsedForm.hipCm
        )

        _formState.update { it.copy(isSaving = true) }
        saveMeasurementInternal(entity, state.isEditMode, onSuccess)
        return true
    }

    private suspend fun saveMeasurementInternal(
        entity: BodyMeasurementEntity,
        isEditMode: Boolean,
        onSuccess: (() -> Unit)?
    ) {
        if (isEditMode) {
            repository.updateMeasurement(entity)
            _uiState.update {
                it.copy(
                    userMessage = "Medição atualizada com sucesso!",
                    selectedMeasurementForDetails = entity
                )
            }
        } else {
            repository.insertMeasurement(entity)
            _uiState.update { it.copy(userMessage = "Medição registrada com sucesso!") }
        }
        _formState.update { it.copy(isSaving = false, saveSuccess = true) }
        onSuccess?.invoke()
    }
}
