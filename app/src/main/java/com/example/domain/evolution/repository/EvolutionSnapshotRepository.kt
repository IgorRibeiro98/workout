package com.example.domain.evolution.repository

import com.example.domain.evolution.model.EvolutionSnapshot
import kotlinx.coroutines.flow.Flow

interface EvolutionSnapshotRepository {
    fun getSnapshotFlow(): Flow<EvolutionSnapshot>
}
