package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Quanta evidência sustenta uma análise.
 *
 * Poucos níveis de propósito: o objetivo é o Coach reconhecer que sabe pouco, não produzir uma
 * métrica. A ordem da declaração é a ordem crescente de evidência.
 */
@Serializable
enum class AiDataQualityLevel {
    /** Nenhuma execução concluída: dá para ler a estrutura planejada, não a evolução. */
    INSUFFICIENT,

    /** Alguma execução concluída, mas pouca para afirmar tendência. */
    LIMITED,

    /** Histórico suficiente para falar de evolução com base em dados. */
    GOOD
}
