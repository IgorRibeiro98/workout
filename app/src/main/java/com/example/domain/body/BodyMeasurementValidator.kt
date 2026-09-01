package com.example.domain.body

sealed class ValidationResult<out T> {
    data class Success<out T>(val value: T) : ValidationResult<T>()
    data class Error(val message: String) : ValidationResult<Nothing>()
}

data class ParsedMeasurementForm(
    val weightKg: Float?,
    val heightCm: Float?,
    val bodyFatPercentage: Float?,
    val waistCm: Float?,
    val abdomenCm: Float?,
    val chestCm: Float?,
    val rightArmCm: Float?,
    val leftArmCm: Float?,
    val rightThighCm: Float?,
    val leftThighCm: Float?,
    val calfCm: Float?,
    val hipCm: Float?
)

object BodyMeasurementValidator {

    fun validateWeight(input: String?): ValidationResult<Float?> {
        val trimmed = input?.trim()?.replace(',', '.') ?: return ValidationResult.Success(null)
        if (trimmed.isEmpty()) return ValidationResult.Success(null)
        val parsed = trimmed.toFloatOrNull()
        return if (parsed != null && parsed > 0f && parsed <= 500f) {
            ValidationResult.Success(parsed)
        } else {
            ValidationResult.Error("Peso inválido. Informe um valor entre 1 e 500 kg.")
        }
    }

    fun validateHeight(input: String?): ValidationResult<Float?> {
        val trimmed = input?.trim()?.replace(',', '.') ?: return ValidationResult.Success(null)
        if (trimmed.isEmpty()) return ValidationResult.Success(null)
        val parsed = trimmed.toFloatOrNull()
        return if (parsed != null && parsed in 50f..300f) {
            ValidationResult.Success(parsed)
        } else {
            ValidationResult.Error("Altura inválida. Informe um valor entre 50 e 300 cm.")
        }
    }

    fun validateBodyFat(input: String?): ValidationResult<Float?> {
        val trimmed = input?.trim()?.replace(',', '.') ?: return ValidationResult.Success(null)
        if (trimmed.isEmpty()) return ValidationResult.Success(null)
        val parsed = trimmed.toFloatOrNull()
        return if (parsed != null && parsed > 0f && parsed < 100f) {
            ValidationResult.Success(parsed)
        } else {
            ValidationResult.Error("Percentual inválido. Informe um valor entre 0 e 100%.")
        }
    }

    fun validateBodyMeasure(input: String?, fieldName: String): ValidationResult<Float?> {
        val trimmed = input?.trim()?.replace(',', '.') ?: return ValidationResult.Success(null)
        if (trimmed.isEmpty()) return ValidationResult.Success(null)
        val parsed = trimmed.toFloatOrNull()
        return if (parsed != null && parsed > 0f && parsed <= 300f) {
            ValidationResult.Success(parsed)
        } else {
            ValidationResult.Error("$fieldName inválido(a). Informe um valor entre 1 e 300 cm.")
        }
    }

    /**
     * Validates all raw fields from the form.
     * Returns a pair of parsed data (if valid) and errors map (key -> errorMessage).
     */
    fun validateAll(
        weightKg: String?,
        heightCm: String?,
        bodyFatPercentage: String?,
        waistCm: String?,
        abdomenCm: String?,
        chestCm: String?,
        rightArmCm: String?,
        leftArmCm: String?,
        rightThighCm: String?,
        leftThighCm: String?,
        calfCm: String?,
        hipCm: String?
    ): Pair<ParsedMeasurementForm?, Map<String, String>> {
        val errors = mutableMapOf<String, String>()

        val weight = when (val res = validateWeight(weightKg)) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["weight"] = res.message
                null
            }
        }

        val height = when (val res = validateHeight(heightCm)) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["height"] = res.message
                null
            }
        }

        val bodyFat = when (val res = validateBodyFat(bodyFatPercentage)) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["bodyFat"] = res.message
                null
            }
        }

        val waist = when (val res = validateBodyMeasure(waistCm, "Cintura")) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["waist"] = res.message
                null
            }
        }

        val abdomen = when (val res = validateBodyMeasure(abdomenCm, "Abdômen")) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["abdomen"] = res.message
                null
            }
        }

        val chest = when (val res = validateBodyMeasure(chestCm, "Peito")) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["chest"] = res.message
                null
            }
        }

        val rightArm = when (val res = validateBodyMeasure(rightArmCm, "Braço direito")) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["rightArm"] = res.message
                null
            }
        }

        val leftArm = when (val res = validateBodyMeasure(leftArmCm, "Braço esquerdo")) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["leftArm"] = res.message
                null
            }
        }

        val rightThigh = when (val res = validateBodyMeasure(rightThighCm, "Coxa direita")) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["rightThigh"] = res.message
                null
            }
        }

        val leftThigh = when (val res = validateBodyMeasure(leftThighCm, "Coxa esquerda")) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["leftThigh"] = res.message
                null
            }
        }

        val calf = when (val res = validateBodyMeasure(calfCm, "Panturrilha")) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["calf"] = res.message
                null
            }
        }

        val hip = when (val res = validateBodyMeasure(hipCm, "Quadril")) {
            is ValidationResult.Success -> res.value
            is ValidationResult.Error -> {
                errors["hip"] = res.message
                null
            }
        }

        val hasAnyValue = listOf(
            weight, height, bodyFat, waist, abdomen, chest,
            rightArm, leftArm, rightThigh, leftThigh, calf, hip
        ).any { it != null }

        if (!hasAnyValue && errors.isEmpty()) {
            errors["general"] = "Informe ao menos uma medida ou peso para salvar."
        }

        return if (errors.isEmpty()) {
            Pair(
                ParsedMeasurementForm(
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
                ),
                emptyMap()
            )
        } else {
            Pair(null, errors)
        }
    }
}
