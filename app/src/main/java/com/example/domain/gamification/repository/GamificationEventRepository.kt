package com.example.domain.gamification.repository

import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventType
import kotlinx.coroutines.flow.Flow

/**
 * Histórico persistente de fatos. Sobrevive ao fechamento do aplicativo para permitir auditoria e
 * recálculo futuro das regras de gamificação.
 */
interface GamificationEventRepository {

    /** @return `true` se o evento foi gravado, `false` se já existia (idempotência). */
    suspend fun record(event: GamificationEvent): Boolean

    suspend fun getEvents(): List<GamificationEvent>

    suspend fun getEventsOfType(type: GamificationEventType): List<GamificationEvent>

    fun observeEvents(): Flow<List<GamificationEvent>>
}
