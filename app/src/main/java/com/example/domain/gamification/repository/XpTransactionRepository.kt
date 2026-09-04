package com.example.domain.gamification.repository

import com.example.domain.gamification.model.UserProgress
import com.example.domain.gamification.model.XpTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

interface XpTransactionRepository {
    suspend fun saveTransaction(transaction: XpTransaction): Boolean
    suspend fun hasTransactionForEvent(eventId: String): Boolean
    fun getTransactions(): Flow<List<XpTransaction>>
    fun getUserProgress(): Flow<UserProgress>
    suspend fun deleteAllTransactions()
    
    val newTransactions: SharedFlow<XpTransaction>
}
