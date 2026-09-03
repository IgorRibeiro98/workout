package com.example.data.repository

import com.example.data.local.GamificationEventDao
import com.example.data.mapper.GamificationEventMapper
import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.repository.GamificationEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GamificationEventRepositoryImpl(
    private val dao: GamificationEventDao
) : GamificationEventRepository {

    override suspend fun record(event: GamificationEvent): Boolean {
        return dao.insert(GamificationEventMapper.toEntity(event)) != -1L
    }

    override suspend fun getEvents(): List<GamificationEvent> =
        dao.getAll().mapNotNull { GamificationEventMapper.toDomain(it) }

    override suspend fun getEventsOfType(type: GamificationEventType): List<GamificationEvent> =
        dao.getByType(type.name).mapNotNull { GamificationEventMapper.toDomain(it) }

    override fun observeEvents(): Flow<List<GamificationEvent>> =
        dao.observeAll().map { events -> events.mapNotNull { GamificationEventMapper.toDomain(it) } }
}
