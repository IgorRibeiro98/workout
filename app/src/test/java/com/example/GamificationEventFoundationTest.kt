package com.example

import com.example.data.local.GamificationEventEntity
import com.example.data.mapper.GamificationEventMapper
import com.example.domain.gamification.ConsistencyMilestoneEvaluator
import com.example.domain.gamification.GamificationEventPublisher
import com.example.domain.gamification.GamificationEventRecorder
import com.example.domain.gamification.GamificationEvents
import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventMetadata
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.repository.GamificationEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import com.example.domain.gamification.XpCalculatorService
import com.example.domain.gamification.model.UserProgress
import com.example.domain.gamification.model.XpTransaction
import com.example.domain.gamification.repository.XpTransactionOrigin
import com.example.domain.gamification.repository.XpTransactionRepository
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * T13.0 — Fundação do Sistema de Gamificação e Eventos.
 *
 * Cobre os testes obrigatórios da tarefa: evento de treino, ausência de duplicação, recorde,
 * persistência entre aberturas do aplicativo e regressão do fluxo atual.
 */
class GamificationEventFoundationTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private class DummyXpTransactionRepository : XpTransactionRepository {
        override suspend fun saveTransaction(
            transaction: XpTransaction,
            origin: XpTransactionOrigin
        ): Boolean = true
        override suspend fun hasTransactionForEvent(eventId: String): Boolean = false
        override fun getTransactions(): Flow<List<XpTransaction>> = emptyFlow()
        override fun getUserProgress(): Flow<UserProgress> = emptyFlow()
        override suspend fun replaceAllTransactions(transactions: List<XpTransaction>) {}
        override val newTransactions: SharedFlow<XpTransaction> = MutableSharedFlow()
    }

    /**
     * Repositório em memória que reproduz o contrato do Room: `dedupeKey` é único e os eventos são
     * guardados na forma persistida (entidade), como aconteceria no banco.
     */
    private class InMemoryGamificationEventRepository(
        private val storage: MutableList<GamificationEventEntity> = mutableListOf(),
        var failOnRecord: Boolean = false
    ) : GamificationEventRepository {

        override suspend fun record(event: GamificationEvent): Boolean {
            if (failOnRecord) throw IllegalStateException("banco indisponível")
            if (storage.any { it.dedupeKey == event.dedupeKey }) return false
            storage += GamificationEventMapper.toEntity(event)
            return true
        }

        override suspend fun getEvents(): List<GamificationEvent> =
            storage.sortedByDescending { it.timestamp }.mapNotNull { GamificationEventMapper.toDomain(it) }

        override suspend fun getEventsOfType(type: GamificationEventType): List<GamificationEvent> =
            getEvents().filter { it.type == type }

        override fun observeEvents(): Flow<List<GamificationEvent>> =
            MutableStateFlow(storage.mapNotNull { GamificationEventMapper.toDomain(it) })

        /** Simula reabrir o aplicativo: nova instância lendo a mesma tabela. */
        fun reopen(): InMemoryGamificationEventRepository =
            InMemoryGamificationEventRepository(storage)
    }

    private fun recorder(
        repository: GamificationEventRepository,
        workoutTimestamps: List<Long> = emptyList(),
        weeklyGoal: Int = 3
    ): GamificationEventRecorder {
        val trackingStart = workoutTimestamps.minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } ?: 0L
        return GamificationEventRecorder(
            repository = repository,
            xpCalculatorService = XpCalculatorService(DummyXpTransactionRepository()),
            workoutTimestampsProvider = { workoutTimestamps },
            weeklyGoalProvider = { weeklyGoal },
            goalSnapshotsProvider = { listOf(WeeklyGoalSnapshot(trackingStart, weeklyGoal)) },
            trackingStartedAtProvider = { trackingStart },
            zoneId = zone
        )
    }

    private fun timestampOf(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    // -------------------------------------------------------------------------------------------
    // Teste 1 — Evento de treino
    // -------------------------------------------------------------------------------------------

    @Test
    fun `treino concluido registra WORKOUT_COMPLETED`() = runTest {
        val repository = InMemoryGamificationEventRepository()
        val publisher = recorder(repository)

        val stored = publisher.publish(
            GamificationEvents.workoutCompleted(
                sessionId = 42L,
                timestamp = timestampOf(LocalDate.of(2026, 3, 4)),
                templateName = "Peito + Costas",
                completedExercises = 5,
                completedSets = 15
            )
        )

        assertTrue("O fato deve ser gravado", stored)
        val events = repository.getEventsOfType(GamificationEventType.WORKOUT_COMPLETED)
        assertEquals(1, events.size)
        assertEquals("42", events.first().metadata[GamificationEventMetadata.SESSION_ID])
        assertEquals("5", events.first().metadata[GamificationEventMetadata.COMPLETED_EXERCISES])
    }

    @Test
    fun `treino iniciado registra WORKOUT_STARTED com o template`() = runTest {
        val repository = InMemoryGamificationEventRepository()

        recorder(repository).publish(
            GamificationEvents.workoutStarted(
                sessionId = 7L,
                timestamp = 1_000L,
                templateId = 3L,
                templateName = "Quadríceps"
            )
        )

        val event = repository.getEventsOfType(GamificationEventType.WORKOUT_STARTED).single()
        assertEquals("Quadríceps", event.metadata[GamificationEventMetadata.TEMPLATE_NAME])
        assertEquals("7", event.metadata[GamificationEventMetadata.SESSION_ID])
    }

    // -------------------------------------------------------------------------------------------
    // Teste 2 — Evento duplicado
    // -------------------------------------------------------------------------------------------

    @Test
    fun `concluir o mesmo treino duas vezes nao duplica o evento`() = runTest {
        val repository = InMemoryGamificationEventRepository()
        val publisher = recorder(repository)
        val timestamp = timestampOf(LocalDate.of(2026, 3, 4))

        val first = publisher.publish(GamificationEvents.workoutCompleted(sessionId = 42L, timestamp = timestamp))
        val second = publisher.publish(GamificationEvents.workoutCompleted(sessionId = 42L, timestamp = timestamp))

        assertTrue(first)
        assertFalse("A repetição do mesmo fato não pode entrar no histórico", second)
        assertEquals(1, repository.getEventsOfType(GamificationEventType.WORKOUT_COMPLETED).size)
    }

    @Test
    fun `treinos diferentes geram eventos distintos`() = runTest {
        val repository = InMemoryGamificationEventRepository()
        val publisher = recorder(repository)

        publisher.publish(GamificationEvents.workoutCompleted(sessionId = 1L, timestamp = 10L))
        publisher.publish(GamificationEvents.workoutCompleted(sessionId = 2L, timestamp = 20L))

        assertEquals(2, repository.getEventsOfType(GamificationEventType.WORKOUT_COMPLETED).size)
    }

    @Test
    fun `exercicios repetidos na mesma sessao nao duplicam EXERCISE_COMPLETED`() = runTest {
        val repository = InMemoryGamificationEventRepository()
        val publisher = recorder(repository)

        repeat(2) {
            publisher.publish(
                GamificationEvents.exerciseCompleted(
                    exerciseSessionId = 90L,
                    exerciseId = 5L,
                    sessionId = 42L,
                    timestamp = 100L,
                    completedSets = 3
                )
            )
        }

        assertEquals(1, repository.getEventsOfType(GamificationEventType.EXERCISE_COMPLETED).size)
    }

    // -------------------------------------------------------------------------------------------
    // Teste 3 — Recorde
    // -------------------------------------------------------------------------------------------

    @Test
    fun `aumentar a carga cria PERSONAL_RECORD_CREATED`() = runTest {
        val repository = InMemoryGamificationEventRepository()
        val publisher = recorder(repository)

        // Supino: 80kg era o recorde, agora 85kg.
        publisher.publish(
            GamificationEvents.personalRecordCreated(
                exerciseId = 11L,
                prType = "MAX_WEIGHT",
                value = 85f,
                previousValue = 80f,
                timestamp = 5_000L,
                exerciseName = "Supino Reto"
            )
        )

        val event = repository.getEventsOfType(GamificationEventType.PERSONAL_RECORD_CREATED).single()
        assertEquals("MAX_WEIGHT", event.metadata[GamificationEventMetadata.PR_TYPE])
        assertEquals("85.00", event.metadata[GamificationEventMetadata.PR_VALUE])
        assertEquals("80.00", event.metadata[GamificationEventMetadata.PR_PREVIOUS_VALUE])
        assertEquals("Supino Reto", event.metadata[GamificationEventMetadata.EXERCISE_NAME])
    }

    @Test
    fun `mesmo recorde reavaliado nao duplica mas recorde maior gera novo evento`() = runTest {
        val repository = InMemoryGamificationEventRepository()
        val publisher = recorder(repository)

        publisher.publish(GamificationEvents.personalRecordCreated(11L, "MAX_WEIGHT", 85f, 80f, 5_000L))
        publisher.publish(GamificationEvents.personalRecordCreated(11L, "MAX_WEIGHT", 85f, 80f, 9_000L))
        publisher.publish(GamificationEvents.personalRecordCreated(11L, "MAX_WEIGHT", 90f, 85f, 12_000L))

        assertEquals(2, repository.getEventsOfType(GamificationEventType.PERSONAL_RECORD_CREATED).size)
    }

    // -------------------------------------------------------------------------------------------
    // Teste 4 — Persistência
    // -------------------------------------------------------------------------------------------

    @Test
    fun `evento continua disponivel depois de reabrir o aplicativo`() = runTest {
        val repository = InMemoryGamificationEventRepository()
        recorder(repository).publish(
            GamificationEvents.workoutCompleted(
                sessionId = 42L,
                timestamp = 1_700_000_000_000L,
                templateName = "Full Body",
                completedExercises = 4,
                completedSets = 12
            )
        )

        val afterRestart = repository.reopen().getEvents()

        val event = afterRestart.single { it.type == GamificationEventType.WORKOUT_COMPLETED }
        assertEquals(1_700_000_000_000L, event.timestamp)
        assertEquals("Full Body", event.metadata[GamificationEventMetadata.TEMPLATE_NAME])
    }

    @Test
    fun `evento sobrevive a serializacao para o banco sem perder metadata`() {
        val original = GamificationEvents.exerciseCompleted(
            exerciseSessionId = 3L,
            exerciseId = 9L,
            sessionId = 42L,
            timestamp = 123L,
            exerciseName = "Agachamento Livre",
            completedSets = 4
        )

        val restored = GamificationEventMapper.toDomain(GamificationEventMapper.toEntity(original))

        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `tipo desconhecido gravado por versao futura e ignorado na leitura`() {
        val entity = GamificationEventEntity(
            id = "abc",
            type = "TIPO_QUE_AINDA_NAO_EXISTE",
            timestamp = 1L,
            source = "WORKOUT_ENGINE",
            dedupeKey = "abc",
            metadataJson = "{}"
        )

        assertEquals(null, GamificationEventMapper.toDomain(entity))
    }

    // -------------------------------------------------------------------------------------------
    // Consistência — meta semanal e marcos de streak
    // -------------------------------------------------------------------------------------------

    @Test
    fun `terceiro treino da semana com meta 3 registra WEEKLY_GOAL_COMPLETED`() = runTest {
        val monday = LocalDate.of(2026, 3, 2)
        val timestamps = listOf(monday, monday.plusDays(2), monday.plusDays(4)).map { timestampOf(it) }
        val repository = InMemoryGamificationEventRepository()
        val publisher = recorder(repository, workoutTimestamps = timestamps, weeklyGoal = 3)

        publisher.publish(
            GamificationEvents.workoutCompleted(sessionId = 3L, timestamp = timestamps.last())
        )

        val event = repository.getEventsOfType(GamificationEventType.WEEKLY_GOAL_COMPLETED).single()
        assertEquals("3", event.metadata[GamificationEventMetadata.WEEKLY_GOAL])
        assertEquals("3", event.metadata[GamificationEventMetadata.WEEKLY_COMPLETED])
        assertEquals(monday.toEpochDay().toString(), event.metadata[GamificationEventMetadata.WEEK_START_EPOCH_DAY])
    }

    @Test
    fun `segundo treino da semana com meta 3 nao registra meta semanal`() = runTest {
        val monday = LocalDate.of(2026, 3, 2)
        val timestamps = listOf(monday, monday.plusDays(2)).map { timestampOf(it) }
        val repository = InMemoryGamificationEventRepository()

        recorder(repository, workoutTimestamps = timestamps, weeklyGoal = 3)
            .publish(GamificationEvents.workoutCompleted(sessionId = 2L, timestamp = timestamps.last()))

        assertTrue(repository.getEventsOfType(GamificationEventType.WEEKLY_GOAL_COMPLETED).isEmpty())
    }

    @Test
    fun `meta semanal registrada uma unica vez na mesma semana`() = runTest {
        val monday = LocalDate.of(2026, 3, 2)
        val timestamps = listOf(monday, monday.plusDays(1), monday.plusDays(2), monday.plusDays(3))
            .map { timestampOf(it) }
        val repository = InMemoryGamificationEventRepository()
        val publisher = recorder(repository, workoutTimestamps = timestamps, weeklyGoal = 3)

        publisher.publish(GamificationEvents.workoutCompleted(sessionId = 3L, timestamp = timestamps[2]))
        publisher.publish(GamificationEvents.workoutCompleted(sessionId = 4L, timestamp = timestamps[3]))

        assertEquals(1, repository.getEventsOfType(GamificationEventType.WEEKLY_GOAL_COMPLETED).size)
    }

    @Test
    fun `quatro semanas consecutivas registram STREAK_MILESTONE_REACHED`() {
        val currentWeekMonday = LocalDate.of(2026, 3, 2)
        val timestamps = (0..3).map { timestampOf(currentWeekMonday.minusWeeks(it.toLong())) }

        val events = ConsistencyMilestoneEvaluator.evaluate(
            workoutTimestamps = timestamps,
            weeklyGoal = 0,
            referenceTimestamp = timestampOf(currentWeekMonday),
            goalSnapshots = listOf(WeeklyGoalSnapshot(timestamps.minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } ?: 0L, 0)),
            trackingStartedAtEpochDay = timestamps.minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } ?: 0L,
            zoneId = zone
        )

        val milestone = events.single { it.type == GamificationEventType.STREAK_MILESTONE_REACHED }
        assertEquals("4", milestone.metadata[GamificationEventMetadata.STREAK_WEEKS])
    }

    @Test
    fun `tres semanas consecutivas ainda nao sao um marco`() {
        val currentWeekMonday = LocalDate.of(2026, 3, 2)
        val timestamps = (0..2).map { timestampOf(currentWeekMonday.minusWeeks(it.toLong())) }

        val events = ConsistencyMilestoneEvaluator.evaluate(
            workoutTimestamps = timestamps,
            weeklyGoal = 0,
            referenceTimestamp = timestampOf(currentWeekMonday),
            goalSnapshots = listOf(WeeklyGoalSnapshot(timestamps.minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } ?: 0L, 0)),
            trackingStartedAtEpochDay = timestamps.minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } ?: 0L,
            zoneId = zone
        )

        assertTrue(events.none { it.type == GamificationEventType.STREAK_MILESTONE_REACHED })
    }

    @Test
    fun `semana sem treino interrompe a contagem do marco`() {
        val currentWeekMonday = LocalDate.of(2026, 3, 2)
        // Treinou nas semanas 0, 1, 2 e 4 — a semana 3 ficou vazia.
        val timestamps = listOf(0L, 1L, 2L, 4L).map { timestampOf(currentWeekMonday.minusWeeks(it)) }

        val events = ConsistencyMilestoneEvaluator.evaluate(
            workoutTimestamps = timestamps,
            weeklyGoal = 0,
            referenceTimestamp = timestampOf(currentWeekMonday),
            goalSnapshots = listOf(WeeklyGoalSnapshot(timestamps.minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } ?: 0L, 0)),
            trackingStartedAtEpochDay = timestamps.minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } ?: 0L,
            zoneId = zone
        )

        assertTrue(events.none { it.type == GamificationEventType.STREAK_MILESTONE_REACHED })
    }

    // -------------------------------------------------------------------------------------------
    // Teste 5 — Regressão: a gamificação nunca interfere no treino
    // -------------------------------------------------------------------------------------------

    @Test
    fun `publisher neutro nao registra nada e nao falha`() = runTest {
        assertFalse(
            GamificationEventPublisher.NoOp.publish(
                GamificationEvents.workoutCompleted(sessionId = 1L, timestamp = 1L)
            )
        )
    }

    @Test
    fun `falha ao persistir o evento nao propaga para o fluxo de treino`() = runTest {
        val repository = InMemoryGamificationEventRepository(failOnRecord = true)

        val stored = recorder(repository).publish(
            GamificationEvents.workoutCompleted(sessionId = 1L, timestamp = 1L)
        )

        assertFalse("A falha da gamificação deve ser contida", stored)
    }

    @Test
    fun `historico observavel expoe os eventos gravados`() = runTest {
        val repository = InMemoryGamificationEventRepository()
        recorder(repository).publish(GamificationEvents.workoutCompleted(sessionId = 1L, timestamp = 1L))

        assertEquals(1, repository.observeEvents().first().size)
    }
}
