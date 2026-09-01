package com.example.presentation.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BodyMeasurementEntity
import com.example.data.repository.BodyMeasurementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BodyEvolutionUiState(
    val isLoading: Boolean = false,
    val allMeasurements: List<BodyMeasurementEntity> = emptyList(),
    val latestMeasurement: BodyMeasurementEntity? = null,
    val selectedMeasurementForDetails: BodyMeasurementEntity? = null,
    val showDeleteConfirmDialog: Boolean = false,
    val measurementToDelete: BodyMeasurementEntity? = null,
    val userMessage: String? = null
)

data class BodyMeasurementFormState(
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
)

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
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        allMeasurements = measurements,
                        latestMeasurement = latest
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
     * Validates and saves the measurement.
     * Returns true if successfully validated and saved, false otherwise.
     */
    fun saveMeasurement(onSuccess: (() -> Unit)? = null): Boolean {
        val state = _formState.value
        val errors = mutableMapOf<String, String>()

        val weight = parseAndValidateNumber(state.weightKg, min = 0.001f, max = 500f, fieldName = "Peso", errors = errors, key = "weight", minExcl = true, maxExcl = true)
        val height = parseAndValidateNumber(state.heightCm, min = 0.001f, max = 300f, fieldName = "Altura", errors = errors, key = "height", minExcl = true, maxExcl = true)
        val bodyFat = parseAndValidateNumber(state.bodyFatPercentage, min = 0.001f, max = 100f, fieldName = "% Gordura", errors = errors, key = "bodyFat", minExcl = true, maxExcl = true)
        val waist = parseAndValidateNumber(state.waistCm, min = 0.001f, max = 300f, fieldName = "Cintura", errors = errors, key = "waist", minExcl = true, maxExcl = true)
        val abdomen = parseAndValidateNumber(state.abdomenCm, min = 0.001f, max = 300f, fieldName = "Abdômen", errors = errors, key = "abdomen", minExcl = true, maxExcl = true)
        val chest = parseAndValidateNumber(state.chestCm, min = 0.001f, max = 300f, fieldName = "Peito", errors = errors, key = "chest", minExcl = true, maxExcl = true)
        val rightArm = parseAndValidateNumber(state.rightArmCm, min = 0.001f, max = 300f, fieldName = "Braço direito", errors = errors, key = "rightArm", minExcl = true, maxExcl = true)
        val leftArm = parseAndValidateNumber(state.leftArmCm, min = 0.001f, max = 300f, fieldName = "Braço esquerdo", errors = errors, key = "leftArm", minExcl = true, maxExcl = true)
        val rightThigh = parseAndValidateNumber(state.rightThighCm, min = 0.001f, max = 300f, fieldName = "Coxa direita", errors = errors, key = "rightThigh", minExcl = true, maxExcl = true)
        val leftThigh = parseAndValidateNumber(state.leftThighCm, min = 0.001f, max = 300f, fieldName = "Coxa esquerda", errors = errors, key = "leftThigh", minExcl = true, maxExcl = true)
        val calf = parseAndValidateNumber(state.calfCm, min = 0.001f, max = 300f, fieldName = "Panturrilha", errors = errors, key = "calf", minExcl = true, maxExcl = true)
        val hip = parseAndValidateNumber(state.hipCm, min = 0.001f, max = 300f, fieldName = "Quadril", errors = errors, key = "hip", minExcl = true, maxExcl = true)

        val hasAnyValue = listOf(weight, height, bodyFat, waist, abdomen, chest, rightArm, leftArm, rightThigh, leftThigh, calf, hip).any { it != null }

        if (!hasAnyValue && errors.isEmpty()) {
            errors["general"] = "Informe ao menos uma medida ou peso para salvar."
        }

        if (errors.isNotEmpty()) {
            _formState.update { it.copy(errors = errors) }
            return false
        }

        val entity = BodyMeasurementEntity(
            date = state.selectedDateMillis,
            weightKg = weight,
            heightCm = height,
            bodyFatPercentage = bodyFat,
            waistCm = waist,
            abdomenCm = abdomen,
            chestCm = chest,
            rightArmCm = rightArm,
            leftArmCm = leftArm,
            rightThighCm = rightThigh,
            leftThighCm = leftThigh,
            calfCm = calf,
            hipCm = hip
        )

        _formState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            repository.insertMeasurement(entity)
            _formState.update { it.copy(isSaving = false, saveSuccess = true) }
            _uiState.update { it.copy(userMessage = "Medição registrada com sucesso!") }
            onSuccess?.invoke()
        }
        return true
    }

    private fun parseAndValidateNumber(
        input: String,
        min: Float,
        max: Float,
        fieldName: String,
        errors: MutableMap<String, String>,
        key: String,
        minExcl: Boolean = false,
        maxExcl: Boolean = false
    ): Float? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val parsed = trimmed.toFloatOrNull()
        if (parsed == null) {
            errors[key] = "Valor inválido"
            return null
        }

        val tooLow = if (minExcl) parsed <= 0f else parsed < min
        val tooHigh = if (maxExcl) parsed >= max else parsed > max

        if (tooLow || tooHigh) {
            errors[key] = if (max == 500f) "Deve ser entre 0 e 500" else if (max == 100f) "Deve ser entre 0 e 100%" else "Deve ser entre 0 e 300 cm"
            return null
        }

        return parsed
    }
}
