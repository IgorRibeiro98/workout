package com.example.domain.evolution.usecase

import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.repository.EvolutionRepository
import kotlinx.coroutines.flow.Flow

class GetEvolutionSummaryUseCase(
    private val evolutionRepository: EvolutionRepository
) {
    suspend operator fun invoke(): EvolutionSummary {
        return evolutionRepository.getEvolutionSummary()
    }

    fun asFlow(): Flow<EvolutionSummary> {
        return evolutionRepository.getEvolutionSummaryFlow()
    }
}
