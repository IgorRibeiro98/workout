package com.example.domain.engine

import com.example.data.local.SetLogEntity

enum class ProgressionAction { INCREASE, MAINTAIN, DECREASE }

data class ProgressionRecommendation(
    val action: ProgressionAction,
    val suggestedWeightDelta: Float,
    val reason: String
)

sealed class ProgressionResult {
    data class Increase(val suggestedLoad: Float) : ProgressionResult()
    data class Maintain(val currentLoad: Float) : ProgressionResult()
    data class Decrease(val suggestedLoad: Float) : ProgressionResult()
}

object ProgressionEngine {
    fun evaluateProgression(
        currentSets: List<SetLogEntity>,
        previousSets: List<SetLogEntity>,
        minTargetReps: Int = 8,
        maxTargetReps: Int = 12,
        increment: Float = 2.0f
    ): ProgressionRecommendation {
        val targetSets = if (previousSets.isNotEmpty()) previousSets.filter { it.completed && it.type != "WARMUP" } 
                         else currentSets.filter { it.completed && it.type != "WARMUP" }
                         
        if (targetSets.isEmpty()) {
            return ProgressionRecommendation(ProgressionAction.MAINTAIN, 0f, "Mantenha o foco na técnica e cadência do movimento.")
        }
        
        val allHitCeiling = targetSets.all { it.repetitions >= maxTargetReps }
        if (allHitCeiling) {
            return ProgressionRecommendation(
                ProgressionAction.INCREASE,
                increment,
                "Você atingiu o teto de repetições ($maxTargetReps reps) em todas as séries! Suba a carga."
            )
        }
        
        val anyBelowFloor = targetSets.any { it.repetitions < minTargetReps && it.repetitions > 0 }
        if (anyBelowFloor) {
            return ProgressionRecommendation(
                ProgressionAction.DECREASE,
                -increment,
                "Repetições abaixo da faixa ($minTargetReps reps). Considere reduzir a carga para manter a amplitude."
            )
        }
        
        return ProgressionRecommendation(
            ProgressionAction.MAINTAIN,
            0f,
            "Trabalhando dentro da faixa alvo ($minTargetReps-$maxTargetReps reps). Mantenha a carga até bater o teto."
        )
    }

    fun evaluate(
        targetSets: Int,
        targetRepsMax: Int,
        actualSets: List<SetLogEntity>,
        currentLoad: Float,
        increment: Float = 2.0f
    ): ProgressionResult {
        val completedSets = actualSets.filter { it.completed && it.type != "WARMUP" }
        if (completedSets.isEmpty()) return ProgressionResult.Maintain(currentLoad)
        val meetsOrExceeds = completedSets.size >= targetSets && completedSets.all { it.repetitions >= targetRepsMax }
        if (meetsOrExceeds) {
            return ProgressionResult.Increase(currentLoad + increment)
        }
        return ProgressionResult.Maintain(currentLoad)
    }
}
