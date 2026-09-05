package com.example.domain.gamification

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutSessionEntity
import com.example.data.repository.ConsistencyRepositoryImpl
import com.example.data.repository.GamificationEventRepositoryImpl
import com.example.data.repository.MissionRepositoryImpl
import com.example.data.repository.XpTransactionRepositoryImpl
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.model.mission.MissionCompletion
import com.example.domain.gamification.model.mission.MissionProgress
import com.example.domain.gamification.model.mission.MissionStatus
import com.example.domain.gamification.repository.MissionEvaluationOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * T13.5 — comportamento das missões sobre as autoridades reais.
 *
 * O teste usa o banco de verdade (sessões, consistência, eventos e XP) porque os riscos da tarefa
 * são de integração: recompensa duplicada, progresso perdido no reinício e reconciliação que
 * comemora de novo não aparecem em um teste com dublês.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MissionIntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var settingsManager: SettingsManager
    private lateinit var consistencyRepository: ConsistencyRepositoryImpl
    private lateinit var eventRepository: GamificationEventRepositoryImpl
    private lateinit var xpTransactionRepository: XpTransactionRepositoryImpl
    private lateinit var xpCalculatorService: XpCalculatorService
    private lateinit var missionRepository: MissionRepositoryImpl

    private val zone: ZoneId = ZoneId.systemDefault()
    private val currentMonday: LocalDate = LocalDate.now(zone).with(DayOfWeek.MONDAY)
    private val previousMonday: LocalDate = currentMonday.minusWeeks(1)

    /** A meta semanal fica em 4 para não se confundir com o alvo 3 da missão de treinos. */
    private val weeklyGoal = 4

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsManager = SettingsManager(context)
        settingsManager.setWeeklyGoal(weeklyGoal)
        // Duas semanas de acompanhamento: a semana anterior existe para a autoridade de consistência.
        settingsManager.setTrackingStartedAt(currentMonday.minusWeeks(2).toEpochDay())

        consistencyRepository = ConsistencyRepositoryImpl(
            workoutDao = database.workoutDao(),
            weeklyGoalDao = database.weeklyGoalDao(),
            settingsManager = settingsManager
        )
        consistencyRepository.initialize()

        eventRepository = GamificationEventRepositoryImpl(database.gamificationEventDao())
        xpTransactionRepository = XpTransactionRepositoryImpl(database.xpTransactionDao())
        xpCalculatorService = XpCalculatorService(xpTransactionRepository)
        missionRepository = newMissionRepository()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------------------------------------
    // Progresso
    // ---------------------------------------------------------------------------------------

    @Test
    fun `progresso da missao semanal acompanha sessoes concluidas`() = runTest {
        assertEquals(0, missionRepository.progressOf(WEEKLY_WORKOUTS))

        insertSession(currentMonday, hour = 7)
        assertEquals(1, missionRepository.progressOf(WEEKLY_WORKOUTS))

        insertSession(currentMonday, hour = 12)
        assertEquals(2, missionRepository.progressOf(WEEKLY_WORKOUTS))
        assertEquals(MissionStatus.ACTIVE, missionRepository.missionOf(WEEKLY_WORKOUTS).status)

        insertSession(currentMonday, hour = 19)
        assertEquals(MissionStatus.COMPLETED, missionRepository.missionOf(WEEKLY_WORKOUTS).status)
    }

    @Test
    fun `sessoes nao concluidas nao movem a missao`() = runTest {
        insertSession(currentMonday, hour = 7, status = SessionStatus.CANCELLED)
        insertSession(currentMonday, hour = 9, status = SessionStatus.CANCELLED)
        insertSession(currentMonday, hour = 11, status = SessionStatus.IN_PROGRESS)
        insertSession(currentMonday, hour = 13, status = SessionStatus.PLANNED)

        assertEquals(0, missionRepository.progressOf(WEEKLY_WORKOUTS))
        assertEquals(0, missionRepository.progressOf(TOTAL_WORKOUTS))
        assertEquals(MissionStatus.ACTIVE, missionRepository.missionOf(WEEKLY_WORKOUTS).status)
        assertTrue(missionRepository.evaluateAndComplete().isEmpty())
    }

    @Test
    fun `dois treinos no mesmo dia contam um unico dia ativo`() = runTest {
        insertSession(currentMonday, hour = 7)
        insertSession(currentMonday, hour = 19)
        insertSession(currentMonday.plusDays(1), hour = 8)

        assertEquals("2 dias, não 3", 2, missionRepository.progressOf(TRAINING_DAYS))
        assertEquals(3, missionRepository.progressOf(WEEKLY_WORKOUTS))
    }

    @Test
    fun `missao de meta semanal observa a autoridade de consistencia`() = runTest {
        repeat(3) { insertSession(currentMonday, hour = 6 + it) }

        val partial = missionRepository.missionOf(WEEKLY_GOAL)
        val consistency = consistencyRepository.getConsistencyProgress()
        assertEquals("O alvo é a meta da consistência", consistency.currentWeekGoal, partial.target)
        assertEquals(consistency.currentWeekCompleted, partial.progress)
        assertEquals(MissionStatus.ACTIVE, partial.status)

        insertSession(currentMonday, hour = 20)
        assertEquals(MissionStatus.COMPLETED, missionRepository.missionOf(WEEKLY_GOAL).status)
        assertEquals(weeklyGoal, missionRepository.missionOf(WEEKLY_GOAL).target)
    }

    @Test
    fun `marco acumulado conclui no decimo treino`() = runTest {
        repeat(9) { insertSession(currentMonday.minusWeeks(it.toLong()), hour = 8) }

        val almost = missionRepository.missionOf(TOTAL_WORKOUTS)
        assertEquals(9, almost.progress)
        assertEquals(10, almost.target)
        assertEquals(MissionStatus.ACTIVE, almost.status)

        insertSession(currentMonday.minusWeeks(9), hour = 9)
        assertEquals(MissionStatus.COMPLETED, missionRepository.missionOf(TOTAL_WORKOUTS).status)
    }

    // ---------------------------------------------------------------------------------------
    // Recompensa e idempotência
    // ---------------------------------------------------------------------------------------

    @Test
    fun `conclusao gera exatamente uma transacao canonica de xp`() = runTest {
        repeat(3) { insertSession(currentMonday, hour = 6 + it) }

        val completions = missionRepository.evaluateAndComplete(MissionEvaluationOrigin.LIVE)
        assertEquals(listOf(WEEKLY_WORKOUTS), completions.map { it.missionId })

        val transactions = xpTransactionRepository.getTransactions().first()
        assertEquals(1, transactions.size)
        assertEquals(150, transactions.single().amount)
        assertEquals(1, eventRepository.getEventsOfType(GamificationEventType.MISSION_COMPLETED).size)
    }

    @Test
    fun `processar a mesma conclusao duas vezes nao duplica recompensa`() = runTest {
        repeat(3) { insertSession(currentMonday, hour = 6 + it) }

        val first = missionRepository.evaluateAndComplete(MissionEvaluationOrigin.LIVE)
        val second = missionRepository.evaluateAndComplete(MissionEvaluationOrigin.LIVE)

        assertEquals(1, first.size)
        assertTrue("A segunda avaliação não conclui nada de novo", second.isEmpty())
        assertEquals(1, eventRepository.getEventsOfType(GamificationEventType.MISSION_COMPLETED).size)
        assertEquals(1, xpTransactionRepository.getTransactions().first().size)
        assertEquals(
            1,
            missionRepository.getMissions().count {
                it.missionId == WEEKLY_WORKOUTS && it.status == MissionStatus.COMPLETED
            }
        )
    }

    @Test
    fun `progresso e conclusao sobrevivem ao reinicio do aplicativo`() = runTest {
        repeat(3) { insertSession(currentMonday, hour = 6 + it) }
        missionRepository.evaluateAndComplete(MissionEvaluationOrigin.LIVE)

        // Nova instância sobre o mesmo banco: é o que acontece ao reabrir o app.
        val afterRestart = newMissionRepository()
        val mission = afterRestart.missionOf(WEEKLY_WORKOUTS)

        assertEquals(MissionStatus.COMPLETED, mission.status)
        assertNotNull("A conclusão registrada volta com data", mission.completedAt)
        assertTrue(afterRestart.evaluateAndComplete(MissionEvaluationOrigin.RECONCILIATION).isEmpty())
        assertEquals(1, xpTransactionRepository.getTransactions().first().size)
    }

    @Test
    fun `reconciliacao completa o que ficou pendente sem comemorar`() = runTest {
        // Treino concluído sem que a missão fosse avaliada (app encerrado antes).
        repeat(3) { insertSession(currentMonday, hour = 6 + it) }

        val emitted = mutableListOf<MissionCompletion>()
        val job = launch { missionRepository.liveCompletions.collect { emitted.add(it) } }

        val completions = missionRepository.evaluateAndComplete(MissionEvaluationOrigin.RECONCILIATION)

        assertEquals(1, completions.size)
        kotlinx.coroutines.delay(50)
        assertTrue("Reconciliação não emite comemoração ao vivo", emitted.isEmpty())
        assertEquals(1, xpTransactionRepository.getTransactions().first().size)

        // Rodar de novo depois de concluída: nada muda.
        val again = missionRepository.evaluateAndComplete(MissionEvaluationOrigin.RECONCILIATION)
        assertTrue(again.isEmpty())
        assertEquals(1, xpTransactionRepository.getTransactions().first().size)
        assertEquals(MissionStatus.COMPLETED, missionRepository.missionOf(WEEKLY_WORKOUTS).status)

        job.cancel()
    }

    @Test
    fun `conclusao ao vivo emite comemoracao`() = runTest {
        repeat(3) { insertSession(currentMonday, hour = 6 + it) }

        val emitted = mutableListOf<MissionCompletion>()
        val job = launch { missionRepository.liveCompletions.collect { emitted.add(it) } }

        missionRepository.evaluateAndComplete(MissionEvaluationOrigin.LIVE)
        kotlinx.coroutines.delay(50)

        assertEquals(1, emitted.size)
        assertEquals(WEEKLY_WORKOUTS, emitted.single().missionId)
        job.cancel()
    }

    // ---------------------------------------------------------------------------------------
    // Períodos
    // ---------------------------------------------------------------------------------------

    @Test
    fun `virada de semana abre nova instancia e preserva o historico`() = runTest {
        repeat(3) { insertSession(previousMonday, hour = 6 + it) }

        // Avaliação feita "durante" a semana passada.
        val pastReference = previousMonday.plusDays(3).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val pastRepository = newMissionRepository(now = { pastReference })
        val completions = pastRepository.evaluateAndComplete(MissionEvaluationOrigin.LIVE)
        assertEquals(1, completions.size)
        assertEquals(previousMonday.toEpochDay().toString(), completions.single().periodKey)

        // Já na semana corrente: instância nova, histórico intacto.
        val missions = missionRepository.getMissions()
        val current = missions.first {
            it.missionId == WEEKLY_WORKOUTS && it.periodKey == currentMonday.toEpochDay().toString()
        }
        assertEquals(0, current.progress)
        assertEquals(MissionStatus.ACTIVE, current.status)
        assertNull(current.completedAt)

        val history = missions.first {
            it.missionId == WEEKLY_WORKOUTS && it.periodKey == previousMonday.toEpochDay().toString()
        }
        assertEquals(MissionStatus.COMPLETED, history.status)
        assertNotNull(history.completedAt)

        // E nada é recompensado de novo por ter virado a semana.
        assertTrue(missionRepository.evaluateAndComplete(MissionEvaluationOrigin.LIVE).isEmpty())
        assertEquals(1, xpTransactionRepository.getTransactions().first().size)
    }

    @Test
    fun `missao semanal nao concluida expira ao fim do periodo`() = runTest {
        insertSession(previousMonday, hour = 8)

        val expired = missionRepository.getMissions().first {
            it.missionId == WEEKLY_WORKOUTS && it.periodKey == previousMonday.toEpochDay().toString()
        }
        assertEquals(MissionStatus.EXPIRED, expired.status)
        assertEquals(1, expired.progress)

        val current = missionRepository.missionOf(WEEKLY_WORKOUTS)
        assertEquals(MissionStatus.ACTIVE, current.status)
    }

    // ---------------------------------------------------------------------------------------
    // Reatividade
    // ---------------------------------------------------------------------------------------

    @Test
    fun `tela aberta acompanha o treino concluido sem recarregar`() = runBlocking {
        val flow = missionRepository.getMissionsFlow()
        assertEquals(0, flow.first().progressOf(WEEKLY_WORKOUTS))

        insertSession(currentMonday, hour = 7)

        val updated = withTimeout(10_000) {
            flow.first { missions -> missions.progressOf(WEEKLY_WORKOUTS) == 1 }
        }
        assertEquals(1, updated.progressOf(WEEKLY_WORKOUTS))
    }

    // ---------------------------------------------------------------------------------------
    // Apoio
    // ---------------------------------------------------------------------------------------

    private fun newMissionRepository(
        now: () -> Long = { System.currentTimeMillis() }
    ) = MissionRepositoryImpl(
        consistencyRepository = consistencyRepository,
        gamificationEventRepository = eventRepository,
        xpCalculatorService = xpCalculatorService,
        zoneId = zone,
        now = now
    )

    private suspend fun insertSession(
        date: LocalDate,
        hour: Int,
        status: SessionStatus = SessionStatus.COMPLETED
    ): Long {
        val startedAt = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return database.workoutDao().insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = startedAt,
                finishedAt = if (status == SessionStatus.COMPLETED) startedAt + 3_600_000 else null,
                status = status.name,
                notes = null,
                templateNameSnapshot = "Treino"
            )
        )
    }

    private suspend fun MissionRepositoryImpl.missionOf(missionId: String): MissionProgress =
        getMissions().first {
            it.missionId == missionId && it.periodKey != previousMonday.toEpochDay().toString()
        }

    private suspend fun MissionRepositoryImpl.progressOf(missionId: String): Int =
        missionOf(missionId).progress

    private fun List<MissionProgress>.progressOf(missionId: String): Int =
        first {
            it.missionId == missionId && it.periodKey != previousMonday.toEpochDay().toString()
        }.progress

    private companion object {
        const val WEEKLY_WORKOUTS = "weekly_workouts_3"
        const val TRAINING_DAYS = "weekly_training_days_3"
        const val WEEKLY_GOAL = "weekly_goal"
        const val TOTAL_WORKOUTS = "total_workouts_10"
    }
}
