package com.example.domain.gamification.repository

import com.example.domain.gamification.model.UserProgress
import com.example.domain.gamification.model.XpTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

interface XpTransactionRepository {
    /**
     * Grava a transação (idempotente por `eventId`).
     *
     * A origem decide se o ganho é visível: apenas [XpTransactionOrigin.LIVE] alimenta
     * [newTransactions]. Reconstruções históricas usam [XpTransactionOrigin.RECONCILIATION] e
     * permanecem silenciosas para a camada de apresentação.
     */
    suspend fun saveTransaction(
        transaction: XpTransaction,
        origin: XpTransactionOrigin = XpTransactionOrigin.LIVE
    ): Boolean

    suspend fun hasTransactionForEvent(eventId: String): Boolean
    fun getTransactions(): Flow<List<XpTransaction>>
    fun getUserProgress(): Flow<UserProgress>

    /**
     * Substitui todo o histórico de XP em uma única operação atômica.
     *
     * É a única forma suportada de reconstruir o XP: ou o novo estado inteiro passa a valer, ou o
     * anterior permanece. Nunca emite [newTransactions].
     */
    suspend fun replaceAllTransactions(transactions: List<XpTransaction>)

    /** Ganhos de XP que devem produzir feedback imediato ao usuário. */
    val newTransactions: SharedFlow<XpTransaction>
}

/** Contexto em que uma transação de XP nasce. */
enum class XpTransactionOrigin {
    /** Ganho acontecendo agora, durante o uso do aplicativo. */
    LIVE,

    /** Reconstrução de fatos passados: o usuário não está ganhando XP neste momento. */
    RECONCILIATION
}
