package com.example.data.repository

import com.example.data.local.XpTransactionDao
import com.example.data.local.XpTransactionEntity
import com.example.domain.gamification.GamificationEvents
import com.example.domain.gamification.XpCalculatorService
import com.example.domain.gamification.model.XpTransaction
import com.example.domain.gamification.repository.XpTransactionOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quem decide o que vira feedback de XP é o repositório: apenas o ganho LIVE alimenta
 * `newTransactions`. Reconstruções históricas gravam o mesmo XP em silêncio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XpTransactionRepositoryImplTest {

    /** DAO em memória com a mesma regra do Room: `eventId` é único e o insert em conflito é ignorado. */
    private class FakeXpTransactionDao : XpTransactionDao {

        private val rows = MutableStateFlow<List<XpTransactionEntity>>(emptyList())

        override suspend fun insertTransaction(transaction: XpTransactionEntity): Long {
            if (rows.value.any { it.eventId == transaction.eventId }) return -1L
            rows.value = rows.value + transaction
            return rows.value.size.toLong()
        }

        override suspend fun insertTransactions(transactions: List<XpTransactionEntity>): List<Long> {
            return transactions.map { insertTransaction(it) }
        }

        override fun getAllTransactions(): Flow<List<XpTransactionEntity>> =
            rows.map { entities -> entities.sortedByDescending { it.createdAt } }

        override fun getTotalXp(): Flow<Int?> = rows.map { entities -> entities.sumOf { it.amount } }

        override suspend fun hasTransactionForEvent(eventId: String): Boolean =
            rows.value.any { it.eventId == eventId }

        override suspend fun deleteAllTransactions() {
            rows.value = emptyList()
        }

        fun currentEventIds(): List<String> = rows.value.map { it.eventId }
    }

    private fun transaction(eventId: String, amount: Int = 100): XpTransaction = XpTransaction(
        eventId = eventId,
        amount = amount,
        reason = "Treino Concluído",
        createdAt = 1_000L
    )

    @Test
    fun `ganho LIVE emite feedback`() = runTest {
        val repository = XpTransactionRepositoryImpl(FakeXpTransactionDao())
        val emitted = mutableListOf<XpTransaction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.newTransactions.collect { emitted += it }
        }
        runCurrent()

        val saved = repository.saveTransaction(transaction("evento-live"), XpTransactionOrigin.LIVE)
        runCurrent()

        assertTrue(saved)
        assertEquals(listOf("evento-live"), emitted.map { it.eventId })
    }

    @Test
    fun `transacao de reconciliation nao emite feedback`() = runTest {
        val dao = FakeXpTransactionDao()
        val repository = XpTransactionRepositoryImpl(dao)
        val emitted = mutableListOf<XpTransaction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.newTransactions.collect { emitted += it }
        }
        runCurrent()

        val saved = repository.saveTransaction(
            transaction("evento-historico"),
            XpTransactionOrigin.RECONCILIATION
        )
        runCurrent()

        assertTrue(saved)
        assertTrue(emitted.isEmpty())
        assertEquals(listOf("evento-historico"), dao.currentEventIds())
    }

    @Test
    fun `substituicao do historico troca o estado sem emitir feedback`() = runTest {
        val dao = FakeXpTransactionDao()
        val repository = XpTransactionRepositoryImpl(dao)
        repository.saveTransaction(transaction("evento-antigo"), XpTransactionOrigin.RECONCILIATION)

        val emitted = mutableListOf<XpTransaction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.newTransactions.collect { emitted += it }
        }
        runCurrent()

        repository.replaceAllTransactions(
            listOf(transaction("evento-novo-1"), transaction("evento-novo-2", amount = 50))
        )
        runCurrent()

        assertEquals(listOf("evento-novo-1", "evento-novo-2"), dao.currentEventIds())
        assertTrue(emitted.isEmpty())
        assertFalse(repository.hasTransactionForEvent("evento-antigo"))
        assertTrue(repository.hasTransactionForEvent("evento-novo-1"))
    }

    @Test
    fun `treino concluido ao vivo percorre calculadora e repositorio emitindo feedback`() = runTest {
        val repository = XpTransactionRepositoryImpl(FakeXpTransactionDao())
        val calculator = XpCalculatorService(repository)
        val emitted = mutableListOf<XpTransaction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.newTransactions.collect { emitted += it }
        }
        runCurrent()

        val event = GamificationEvents.workoutCompleted(sessionId = 1L, timestamp = 1_000L)
        calculator.processEvent(event)
        runCurrent()

        assertEquals(listOf(event.id), emitted.map { it.eventId })
        assertEquals(100, emitted.single().amount)
        assertEquals(1_000L, emitted.single().createdAt)
    }

    @Test
    fun `mesmo evento nao gera XP duas vezes`() = runTest {
        val dao = FakeXpTransactionDao()
        val repository = XpTransactionRepositoryImpl(dao)

        val first = repository.saveTransaction(transaction("evento-unico"), XpTransactionOrigin.LIVE)
        val second = repository.saveTransaction(transaction("evento-unico"), XpTransactionOrigin.LIVE)

        assertTrue(first)
        assertFalse(second)
        assertEquals(listOf("evento-unico"), dao.currentEventIds())
    }
}
