package com.example.data.repository

import com.example.data.local.XpTransactionDao
import com.example.data.local.XpTransactionEntity
import com.example.domain.gamification.model.UserProgress
import com.example.domain.gamification.model.XpTransaction
import com.example.domain.gamification.repository.XpTransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class XpTransactionRepositoryImpl(
    private val xpTransactionDao: XpTransactionDao
) : XpTransactionRepository {

    override suspend fun saveTransaction(transaction: XpTransaction) {
        val entity = XpTransactionEntity(
            id = UUID.randomUUID().toString(),
            eventId = transaction.eventId,
            amount = transaction.amount,
            reason = transaction.reason,
            createdAt = transaction.createdAt
        )
        xpTransactionDao.insertTransaction(entity)
    }

    override suspend fun hasTransactionForEvent(eventId: String): Boolean {
        return xpTransactionDao.hasTransactionForEvent(eventId)
    }

    override fun getTransactions(): Flow<List<XpTransaction>> {
        return xpTransactionDao.getAllTransactions().map { entities ->
            entities.map { entity ->
                XpTransaction(
                    eventId = entity.eventId,
                    amount = entity.amount,
                    reason = entity.reason,
                    createdAt = entity.createdAt
                )
            }
        }
    }

    override fun getUserProgress(): Flow<UserProgress> {
        return xpTransactionDao.getTotalXp().map { total ->
            calculateProgress(total ?: 0)
        }
    }

    private fun calculateProgress(totalXp: Int): UserProgress {
        var level = 1
        var xpForNext = 500
        var xpRemaining = totalXp

        while (xpRemaining >= xpForNext) {
            xpRemaining -= xpForNext
            level++
            xpForNext = level * 500
        }

        return UserProgress(
            currentLevel = level,
            totalXp = totalXp,
            currentLevelXp = xpRemaining,
            xpForNextLevel = xpForNext
        )
    }
}
