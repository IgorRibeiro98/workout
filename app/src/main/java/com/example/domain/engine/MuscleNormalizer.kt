package com.example.domain.engine

object MuscleNormalizer {
    private val normalizationMap = mapOf(
        "Peitoral superior" to "Peitoral",
        "Peitoral inferior" to "Peitoral",
        "Deltoide anterior" to "Ombros",
        "Deltoide lateral" to "Ombros",
        "Deltoide posterior" to "Ombros",
        "Glúteo médio" to "Glúteos",
        "Glúteo mínimo" to "Glúteos",
        "Glúteo Máximo" to "Glúteos",
        "Glúteo máximo" to "Glúteos",
        "Sóleo" to "Panturrilhas",
        "Tibial anterior" to "Panturrilhas",
        "Posterior de coxa" to "Posterior",
        "Braquial" to "Bíceps",
        "Braquiorradial" to "Antebraço"
    )
    
    fun normalize(muscle: String?): String {
        if (muscle.isNullOrBlank()) return "Outros"
        normalizationMap[muscle]?.let { return it }
        val resolved = MuscleVisualResolver.getDisplayName(muscle)
        return if (resolved != MuscleGroup.FULL_BODY.displayName) resolved else muscle
    }
}

