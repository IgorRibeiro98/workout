package com.example.domain.ai.model

import com.example.domain.engine.MuscleGroup

/**
 * Objetivo do treino pedido pelo usuário.
 *
 * Taxonomia mínima: o Spark ainda não possui um conceito persistido de objetivo, e três valores
 * cobrem o que muda de verdade na prescrição (séries, faixa de repetições e descanso). Criar
 * dezenas de objetivos sem uso real só aumentaria a superfície do prompt.
 */
enum class WorkoutGoal(val label: String, val guidance: String) {
    HYPERTROPHY(
        label = "Hipertrofia",
        guidance = "volume moderado a alto, faixas de repetição médias e descansos intermediários"
    ),
    STRENGTH(
        label = "Força",
        guidance = "menos repetições por série, mais séries nos movimentos principais e descansos mais longos"
    ),
    GENERAL_FITNESS(
        label = "Condicionamento geral",
        guidance = "volume moderado, faixas de repetição mais altas e descansos mais curtos"
    )
}

/**
 * Equipamento disponível, como o usuário o enxerga.
 *
 * Cada valor aponta para os rótulos que [com.example.domain.exercise.import.ExerciseNormalizer]
 * já produz a partir do campo `equipment` do catálogo — a classificação continua sendo daquela
 * autoridade, aqui só existe o agrupamento que a tela oferece.
 */
enum class EquipmentAvailability(val label: String, val normalizedLabels: Set<String>) {
    /** Academia completa: nenhum filtro de equipamento é aplicado. */
    FULL_GYM("Academia completa", emptySet()),
    BARBELL("Barra", setOf("Barra")),
    DUMBBELL("Halteres", setOf("Halter")),
    MACHINE("Máquinas", setOf("Máquina", "Máquina Smith")),
    CABLE("Cabo/polia", setOf("Cabo")),
    BODYWEIGHT("Peso corporal", setOf("Peso corporal")),
    BAND("Elástico", setOf("Elástico")),
    KETTLEBELL("Kettlebell", setOf("Kettlebell"))
}

/**
 * O que o usuário pediu, de forma estruturada.
 *
 * Texto livre existe apenas em [notes] e nunca substitui um parâmetro: objetivo, duração e foco
 * são obrigatórios porque sem eles não há como filtrar o catálogo de forma determinística.
 */
data class WorkoutGenerationPreferences(
    val goal: WorkoutGoal,
    val durationMinutes: Int,
    /** Grupos musculares do foco, na ordem escolhida pelo usuário. */
    val focusMuscleGroups: List<MuscleGroup>,
    /** Vazio ou contendo [EquipmentAvailability.FULL_GYM] significa "sem restrição". */
    val availableEquipment: Set<EquipmentAvailability> = setOf(EquipmentAvailability.FULL_GYM),
    /** Exclusões explícitas, sempre por `exerciseId` canônico. */
    val excludedExerciseIds: Set<String> = emptySet(),
    val notes: String? = null
) {
    /** Se a geração tem os dados mínimos para sequer montar candidatos. */
    val isComplete: Boolean
        get() = focusMuscleGroups.isNotEmpty() && durationMinutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES

    /** Sem restrição de equipamento: academia completa ou nenhuma seleção. */
    val acceptsAnyEquipment: Boolean
        get() = availableEquipment.isEmpty() || EquipmentAvailability.FULL_GYM in availableEquipment

    companion object {
        /** Abaixo disso não há treino de musculação para montar. */
        const val MIN_DURATION_MINUTES: Int = 15

        /** Teto de sanidade; o app não planeja sessões de mais de três horas. */
        const val MAX_DURATION_MINUTES: Int = 180

        /** Duração padrão da tela, quando o usuário não mexe no seletor. */
        const val DEFAULT_DURATION_MINUTES: Int = 60

        /** Observações são contexto curto, não um segundo prompt. */
        const val MAX_NOTES_LENGTH: Int = 280

        /** Durações oferecidas pela tela. O usuário escolhe uma; a duração é preferência. */
        val DURATION_OPTIONS: List<Int> = listOf(30, 45, 60, 75, 90)

        /**
         * Grupos musculares que o foco oferece.
         *
         * São os grupos de [MuscleGroup] que o catálogo realmente classifica. `Geral` fica de
         * fora porque é o balde de fallback do classificador — escolhê-lo traria qualquer
         * exercício sem classificação —, e `Cardio` porque o Spark planeja treino de força.
         */
        val SELECTABLE_FOCUS_GROUPS: List<MuscleGroup> = listOf(
            MuscleGroup.CHEST,
            MuscleGroup.BACK,
            MuscleGroup.SHOULDERS,
            MuscleGroup.BICEPS,
            MuscleGroup.TRICEPS,
            MuscleGroup.FOREARMS,
            MuscleGroup.TRAPS,
            MuscleGroup.QUADS,
            MuscleGroup.HAMSTRINGS,
            MuscleGroup.GLUTES,
            MuscleGroup.CALVES,
            MuscleGroup.CORE
        )
    }
}
