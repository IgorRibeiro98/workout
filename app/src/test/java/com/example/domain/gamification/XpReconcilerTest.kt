package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventMetadata
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.model.UserProgress
import com.example.domain.gamification.model.XpTransaction
import com.example.domain.gamification.repository.GamificationEventRepository
import com.example.domain.gamification.repository.XpTransactionOrigin
import com.example.domain.gamification.repository.XpTransactionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T13.3+ — endurecimento da reconciliação de XP.
 *
 * Os testes cobrem os riscos reais da reconstrução: interrupção no meio do rebuild, nova tentativa
 * na inicialização seguinte, duplicação de XP e feedback indevido de ganho ao vivo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XpReconcilerTest {

    // ------------------------------------------------------------------------------------------
    // Dublês
    // ------------------------------------------------------------------------------------------

    /**
     * Reproduz o contrato do Room: `eventId` é único e a substituição do histórico é atômica —
     * quando ela falha, o estado anterior continua exatamente como estava.
     */
    private class FakeXpTransactionRepository(
        private val operations: MutableList<String>
    ) : XpTransactionRepository {

        private val stored = mutableListOf<XpTransaction>()
        private val _newTransactions = MutableSharedFlow<XpTransaction>()

        val savedOrigins = mutableListOf<XpTransactionOrigin>()
        var replaceCalls = 0
            private set
        var failNextReplace = false

        override val newTransactions: SharedFlow<XpTransaction> = _newTransactions.asSharedFlow()

        override suspend fun saveTransaction(
            transaction: XpTransaction,
            origin: XpTransactionOrigin
        ): Boolean {
            savedOrigins += origin
            if (stored.any { it.eventId == transaction.eventId }) return false
            stored += transaction
            if (origin == XpTransactionOrigin.LIVE) {
                _newTransactions.emit(transaction)
            }
            return true
        }

        override suspend fun hasTransactionForEvent(eventId: String): Boolean =
            stored.any { it.eventId == eventId }

        override fun getTransactions(): Flow<List<XpTransaction>> = MutableStateFlow(stored.toList())

        override fun getUserProgress(): Flow<UserProgress> = emptyFlow()

        override suspend fun replaceAllTransactions(transactions: List<XpTransaction>) {
            replaceCalls++
            if (failNextReplace) {
                failNextReplace = false
                // Room desfaria a transação inteira: nada é apagado nem inserido.
                throw IllegalStateException("banco indisponível durante o rebuild")
            }
            operations += "replace"
            stored.clear()
            stored += transactions
        }

        fun seed(transactions: List<XpTransaction>) {
            stored += transactions
        }

        fun current(): List<XpTransaction> = stored.toList()

        fun totalXp(): Int = stored.sumOf { it.amount }
    }

    /** Histórico de fatos em memória com a mesma regra de idempotência do Room (`dedupeKey`). */
    private class InMemoryEventRepository(
        events: List<GamificationEvent> = emptyList()
    ) : GamificationEventRepository {

        private val storage = events.toMutableList()

        override suspend fun record(event: GamificationEvent): Boolean {
            if (storage.any { it.dedupeKey == event.dedupeKey }) return false
            storage += event
            return true
        }

        override suspend fun getEvents(): List<GamificationEvent> = storage.toList()

        override suspend fun getEventsOfType(type: GamificationEventType): List<GamificationEvent> =
            storage.filter { it.type == type }

        override fun observeEvents(): Flow<List<GamificationEvent>> = MutableStateFlow(storage.toList())
    }

    // ------------------------------------------------------------------------------------------
    // Infraestrutura do teste
    // ------------------------------------------------------------------------------------------

    private val operations = mutableListOf<String>()
    private val xpRepository = FakeXpTransactionRepository(operations)
    private var storedPolicyVersion = 0
    private var firstWorkoutLookups = 0

    private fun reconciler(
        events: InMemoryEventRepository,
        firstCompletedWorkout: CompletedWorkoutReference? = null
    ): XpReconciler = XpReconciler(
        xpTransactionRepository = xpRepository,
        eventRepository = events,
        xpCalculatorService = XpCalculatorService(xpRepository),
        xpPolicyVersionProvider = { storedPolicyVersion },
        xpPolicyVersionWriter = {
            operations += "version"
            storedPolicyVersion = it
        },
        firstCompletedWorkoutProvider = {
            firstWorkoutLookups++
            firstCompletedWorkout
        }
    )

    private fun workoutCompleted(sessionId: Long, timestamp: Long): GamificationEvent =
        GamificationEvents.workoutCompleted(sessionId = sessionId, timestamp = timestamp)

    private fun personalRecord(exerciseId: Long, timestamp: Long): GamificationEvent =
        GamificationEvents.personalRecordCreated(
            exerciseId = exerciseId,
            prType = "WEIGHT",
            value = 100f,
            previousValue = 90f,
            timestamp = timestamp
        )

    // ------------------------------------------------------------------------------------------
    // Rebuild normal
    // ------------------------------------------------------------------------------------------

    @Test
    fun `rebuild reconstroi o XP do historico e so entao atualiza a versao da politica`() = runTest {
        val events = InMemoryEventRepository(
            listOf(
                workoutCompleted(sessionId = 1L, timestamp = 1_000L),
                workoutCompleted(sessionId = 2L, timestamp = 2_000L),
                personalRecord(exerciseId = 7L, timestamp = 2_500L)
            )
        )

        reconciler(events, CompletedWorkoutReference(sessionId = 1L, completedAt = 1_000L)).reconcile()

        // 2 treinos (100 cada) + 1 recorde (50) + primeiro treino derivado (100).
        assertEquals(350, xpRepository.totalXp())
        assertEquals(XpRewardPolicy.VERSION, storedPolicyVersion)
        // A versão só pode ser gravada depois de o histórico já ter sido substituído.
        assertEquals(listOf("replace", "version"), operations)
    }

    // ------------------------------------------------------------------------------------------
    // Falha e nova tentativa
    // ------------------------------------------------------------------------------------------

    @Test
    fun `falha no rebuild preserva o estado anterior e nao marca a politica como aplicada`() = runTest {
        val previousTransaction = XpTransaction(
            eventId = "evento-antigo",
            amount = 100,
            reason = "Treino Concluído",
            createdAt = 500L
        )
        xpRepository.seed(listOf(previousTransaction))
        val events = InMemoryEventRepository(listOf(workoutCompleted(sessionId = 1L, timestamp = 1_000L)))
        xpRepository.failNextReplace = true

        val failure = runCatching {
            reconciler(events, CompletedWorkoutReference(1L, 1_000L)).reconcile()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, storedPolicyVersion)
        assertEquals(listOf(previousTransaction), xpRepository.current())
        assertTrue(operations.isEmpty())
    }

    @Test
    fun `nova tentativa apos falha reconstroi o XP completo sem duplicar`() = runTest {
        val events = InMemoryEventRepository(
            listOf(
                workoutCompleted(sessionId = 1L, timestamp = 1_000L),
                workoutCompleted(sessionId = 2L, timestamp = 2_000L)
            )
        )
        xpRepository.failNextReplace = true
        val failure = runCatching {
            reconciler(events, CompletedWorkoutReference(1L, 1_000L)).reconcile()
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, storedPolicyVersion)

        // Próxima inicialização: a política continua antiga, então o rebuild acontece de novo.
        reconciler(events, CompletedWorkoutReference(1L, 1_000L)).reconcile()

        assertEquals(XpRewardPolicy.VERSION, storedPolicyVersion)
        assertEquals(300, xpRepository.totalXp())
        assertEquals(
            xpRepository.current().map { it.eventId }.distinct().size,
            xpRepository.current().size
        )
    }

    @Test
    fun `reconciliation executada duas vezes nao duplica XP`() = runTest {
        val events = InMemoryEventRepository(
            listOf(
                workoutCompleted(sessionId = 1L, timestamp = 1_000L),
                personalRecord(exerciseId = 3L, timestamp = 1_500L)
            )
        )

        reconciler(events, CompletedWorkoutReference(1L, 1_000L)).reconcile()
        val afterFirstRun = xpRepository.totalXp()
        val transactionsAfterFirstRun = xpRepository.current().size

        reconciler(events, CompletedWorkoutReference(1L, 1_000L)).reconcile()

        assertEquals(afterFirstRun, xpRepository.totalXp())
        assertEquals(transactionsAfterFirstRun, xpRepository.current().size)
    }

    // ------------------------------------------------------------------------------------------
    // LIVE x RECONCILIATION
    // ------------------------------------------------------------------------------------------

    @Test
    fun `reconciliation nao emite ganho LIVE`() = runTest {
        val emitted = mutableListOf<XpTransaction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            xpRepository.newTransactions.collect { emitted += it }
        }
        runCurrent()

        val events = InMemoryEventRepository(
            listOf(
                workoutCompleted(sessionId = 1L, timestamp = 1_000L),
                personalRecord(exerciseId = 3L, timestamp = 1_500L)
            )
        )

        reconciler(events, CompletedWorkoutReference(1L, 1_000L)).reconcile()
        runCurrent()

        assertTrue(xpRepository.totalXp() > 0)
        assertTrue(emitted.isEmpty())
        assertTrue(xpRepository.savedOrigins.none { it == XpTransactionOrigin.LIVE })
    }

    @Test
    fun `lacuna preenchida com a politica atual usa origem de reconciliation`() = runTest {
        storedPolicyVersion = XpRewardPolicy.VERSION
        val emitted = mutableListOf<XpTransaction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            xpRepository.newTransactions.collect { emitted += it }
        }
        runCurrent()

        val events = InMemoryEventRepository(listOf(workoutCompleted(sessionId = 9L, timestamp = 9_000L)))

        reconciler(events, CompletedWorkoutReference(9L, 9_000L)).reconcile()
        runCurrent()

        // Nenhum rebuild: a política já estava aplicada.
        assertEquals(0, xpRepository.replaceCalls)
        assertEquals(200, xpRepository.totalXp())
        assertTrue(emitted.isEmpty())
        assertEquals(
            listOf(XpTransactionOrigin.RECONCILIATION, XpTransactionOrigin.RECONCILIATION),
            xpRepository.savedOrigins
        )
    }

    // ------------------------------------------------------------------------------------------
    // Primeiro treino histórico
    // ------------------------------------------------------------------------------------------

    @Test
    fun `primeiro treino historico vem do historico real de sessoes e nao do primeiro evento`() = runTest {
        // 50 treinos aconteceram, mas o histórico de eventos só conhece os mais recentes.
        val incompleteEvents = (41L..50L).map { session ->
            workoutCompleted(sessionId = session, timestamp = session * 1_000L)
        }
        val events = InMemoryEventRepository(incompleteEvents)
        val realFirstWorkout = CompletedWorkoutReference(sessionId = 1L, completedAt = 1_000L)

        reconciler(events, realFirstWorkout).reconcile()

        val firstWorkoutEvents = events.getEventsOfType(GamificationEventType.FIRST_WORKOUT_COMPLETED)
        assertEquals(1, firstWorkoutEvents.size)
        val firstWorkoutEvent = firstWorkoutEvents.single()
        assertEquals(1_000L, firstWorkoutEvent.timestamp)
        assertEquals("1", firstWorkoutEvent.metadata[GamificationEventMetadata.SESSION_ID])
        assertEquals("first_workout_completed", firstWorkoutEvent.dedupeKey)
        // O primeiro evento disponível (sessão 41) não pode ser confundido com o primeiro treino.
        assertTrue(firstWorkoutEvent.timestamp < incompleteEvents.first().timestamp)

        val firstWorkoutTransaction = xpRepository.current().firstOrNull { it.eventId == firstWorkoutEvent.id }
        assertNotNull(firstWorkoutTransaction)
        assertEquals(1_000L, firstWorkoutTransaction!!.createdAt)
    }

    @Test
    fun `sem historico real de sessoes o primeiro treino nao e inventado`() = runTest {
        val events = InMemoryEventRepository(listOf(workoutCompleted(sessionId = 5L, timestamp = 5_000L)))

        reconciler(events, firstCompletedWorkout = null).reconcile()

        assertTrue(events.getEventsOfType(GamificationEventType.FIRST_WORKOUT_COMPLETED).isEmpty())
        assertEquals(100, xpRepository.totalXp())
        assertNull(xpRepository.current().firstOrNull { it.reason == "Primeiro Treino" })
    }

    @Test
    fun `primeiro treino ja registrado nao consulta nem reescreve o historico`() = runTest {
        val existingFirstWorkout = GamificationEvents.firstWorkoutCompleted(sessionId = 1L, timestamp = 1_000L)
        val events = InMemoryEventRepository(
            listOf(existingFirstWorkout, workoutCompleted(sessionId = 1L, timestamp = 1_000L))
        )

        reconciler(events, CompletedWorkoutReference(99L, 99_000L)).reconcile()

        assertEquals(0, firstWorkoutLookups)
        val firstWorkoutEvents = events.getEventsOfType(GamificationEventType.FIRST_WORKOUT_COMPLETED)
        assertEquals(1, firstWorkoutEvents.size)
        assertEquals(1_000L, firstWorkoutEvents.single().timestamp)
    }
}
