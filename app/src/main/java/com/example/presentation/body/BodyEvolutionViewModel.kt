package com.example.presentation.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BodyMeasurementEntity
import com.example.data.repository.BodyMeasurementRepository
import com.example.domain.body.BmiResult
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
        val errors = mutableMapOf<String, String>()

        // Validation Rules:
        // Peso: 0 < peso <= 500
        val weight = parseAndValidateWeight(state.weightKg, errors)

        // Altura: 50 <= altura <= 300
        val height = parseAndValidateHeight(state.heightCm, errors)

        // Gordura: 0 < percentual < 100
        val bodyFat = parseAndValidateBodyFat(state.bodyFatPercentage, errors)

        // Medidas corporais: 0 < medida <= 300
        val waist = parseAndValidateBodyMeasure(state.waistCm, "Cintura", "waist", errors)
        val abdomen = parseAndValidateBodyMeasure(state.abdomenCm, "Abdômen", "abdomen", errors)
        val chest = parseAndValidateBodyMeasure(state.chestCm, "Peito", "chest", errors)
        val rightArm = parseAndValidateBodyMeasure(state.rightArmCm, "Braço direito", "rightArm", errors)
        val leftArm = parseAndValidateBodyMeasure(state.leftArmCm, "Braço esquerdo", "leftArm", errors)
        val rightThigh = parseAndValidateBodyMeasure(state.rightThighCm, "Coxa direita", "rightThigh", errors)
        val leftThigh = parseAndValidateBodyMeasure(state.leftThighCm, "Coxa esquerda", "leftThigh", errors)
        val calf = parseAndValidateBodyMeasure(state.calfCm, "Panturrilha", "calf", errors)
        val hip = parseAndValidateBodyMeasure(state.hipCm, "Quadril", "hip", errors)

        val hasAnyValue = listOf(weight, height, bodyFat, waist, abdomen, chest, rightArm, leftArm, rightThigh, leftThigh, calf, hip).any { it != null }

        if (!hasAnyValue && errors.isEmpty()) {
            errors["general"] = "Informe ao menos uma medida ou peso para salvar."
        }

        if (errors.isNotEmpty()) {
            _formState.update { it.copy(errors = errors) }
            return false
        }

        val entity = BodyMeasurementEntity(
            id = state.editingMeasurementId ?: 0L,
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
            saveMeasurementInternal(entity, state.isEditMode, onSuccess)
        }
        return true
    }

    suspend fun saveMeasurementSuspending(onSuccess: (() -> Unit)? = null): Boolean {
        val state = _formState.value
        val errors = mutableMapOf<String, String>()

        val weight = parseAndValidateWeight(state.weightKg, errors)
        val height = parseAndValidateHeight(state.heightCm, errors)
        val bodyFat = parseAndValidateBodyFat(state.bodyFatPercentage, errors)

        val waist = parseAndValidateBodyMeasure(state.waistCm, "Cintura", "waist", errors)
        val abdomen = parseAndValidateBodyMeasure(state.abdomenCm, "Abdômen", "abdomen", errors)
        val chest = parseAndValidateBodyMeasure(state.chestCm, "Peito", "chest", errors)
        val rightArm = parseAndValidateBodyMeasure(state.rightArmCm, "Braço direito", "rightArm", errors)
        val leftArm = parseAndValidateBodyMeasure(state.leftArmCm, "Braço esquerdo", "leftArm", errors)
        val rightThigh = parseAndValidateBodyMeasure(state.rightThighCm, "Coxa direita", "rightThigh", errors)
        val leftThigh = parseAndValidateBodyMeasure(state.leftThighCm, "Coxa esquerda", "leftThigh", errors)
        val calf = parseAndValidateBodyMeasure(state.calfCm, "Panturrilha", "calf", errors)
        val hip = parseAndValidateBodyMeasure(state.hipCm, "Quadril", "hip", errors)

        val hasAnyValue = listOf(weight, height, bodyFat, waist, abdomen, chest, rightArm, leftArm, rightThigh, leftThigh, calf, hip).any { it != null }

        if (!hasAnyValue && errors.isEmpty()) {
            errors["general"] = "Informe ao menos uma medida ou peso para salvar."
        }

        if (errors.isNotEmpty()) {
            _formState.update { it.copy(errors = errors) }
            return false
        }

        val entity = BodyMeasurementEntity(
            id = state.editingMeasurementId ?: 0L,
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

    private fun parseAndValidateWeight(input: String, errors: MutableMap<String, String>): Float? {
        val trimmed = input.trim().replace(',', '.')
        if (trimmed.isEmpty()) return null
        val parsed = trimmed.toFloatOrNull()
        if (parsed == null || parsed <= 0f || parsed > 500f) {
            errors["weight"] = "Peso inválido. Informe um valor entre 1 e 500 kg."
            return null
        }
        return parsed
    }

    private fun parseAndValidateHeight(input: String, errors: MutableMap<String, String>): Float? {
        val trimmed = input.trim().replace(',', '.')
        if (trimmed.isEmpty()) return null
        val parsed = trimmed.toFloatOrNull()
        if (parsed == null || parsed < 50f || parsed > 300f) {
            errors["height"] = "Altura inválida. Informe um valor entre 50 e 300 cm."
            return null
        }
        return parsed
    }

    private fun parseAndValidateBodyFat(input: String, errors: MutableMap<String, String>): Float? {
        val trimmed = input.trim().replace(',', '.')
        if (trimmed.isEmpty()) return null
        val parsed = trimmed.toFloatOrNull()
        if (parsed == null || parsed <= 0f || parsed >= 100f) {
            errors["bodyFat"] = "Percentual inválido. Informe um valor entre 0 e 100%."
            return null
        }
        return parsed
    }

    private fun parseAndValidateBodyMeasure(
        input: String,
        fieldName: String,
        key: String,
        errors: MutableMap<String, String>
    ): Float? {
        val trimmed = input.trim().replace(',', '.')
        if (trimmed.isEmpty()) return null
        val parsed = trimmed.toFloatOrNull()
        if (parsed == null || parsed <= 0f || parsed > 300f) {
            errors[key] = "$fieldName inválido(a). Informe um valor entre 1 e 300 cm."
            return null
        }
        return parsed
    }
}
