package com.example.domain.engine

import com.example.data.local.SetLogEntity
import com.example.data.local.SetType

object VolumeCalculator {

    /**
     * Calculates total tonnage volume (kg * reps) for completed working sets (excluding warmup).
     */
    fun calculateVolume(sets: List<SetLogEntity>, defaultBodyweight: Float = 0f): Double {
        if (defaultBodyweight <= 0f) {
            return com.example.domain.performance.calculator.VolumeCalculator.calculateSetsVolume(sets)
        }
        return sets.filter { it.completed && it.type != SetType.WARMUP.name }
            .sumOf { set ->
                val effectiveWeight = if (set.weight > 0f) set.weight else defaultBodyweight
                (effectiveWeight * set.repetitions).toDouble()
            }
    }

    /**
     * Counts effective working sets (completed and not warmup).
     */
    fun countEffectiveSets(sets: List<SetLogEntity>): Int {
        return sets.count { it.completed && it.type != SetType.WARMUP.name }
    }
    
    /**
     * Calculates estimated 1 Rep Max using the Epley formula for completed working sets (1..12 reps).
     */
    fun calculateOneRepMax(weight: Float, reps: Int): Float {
        if (weight <= 0f || reps <= 0) return 0f
        if (reps == 1) return weight
        if (reps > 12) return weight * 1.33f // Clamp extreme rep ranges for 1RM estimate
        // Epley formula: 1RM = weight * (1 + reps / 30.0)
        return weight * (1f + reps / 30f)
    }

    /**
     * Finds the best estimated 1RM among completed working sets.
     */
    fun findBest1RM(sets: List<SetLogEntity>): Float {
        return sets.filter { it.completed && it.type != SetType.WARMUP.name && it.weight > 0f && it.repetitions > 0 }
            .maxOfOrNull { calculateOneRepMax(it.weight, it.repetitions) } ?: 0f
    }
}

