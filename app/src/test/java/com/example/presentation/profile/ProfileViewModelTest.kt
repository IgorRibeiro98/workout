package com.example.presentation.profile

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.BodyMeasurementEntity
import com.example.data.local.ExerciseEntity
import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutSessionEntity
import com.example.data.repository.AchievementRepositoryImpl
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.ConsistencyRepositoryImpl
import com.example.data.repository.WorkoutRepository
import com.example.data.repository.XpTransactionRepositoryImpl
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.model.achievement.AchievementTier
import com.example.domain.evolution.model.achievement.AchievementUnlock
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WeeklyConsistencyStatus
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import com.example.domain.evolution.repository.AchievementEvaluationOrigin
import com.example.domain.evolution.repository.AchievementRepository
import com.example.domain.evolution.repository.ConsistencyRepository
import com.example.domain.gamification.model.UserProgress
import com.example.domain.gamification.model.XpTransaction
import com.example.domain.gamification.repository.XpTransactionOrigin
import com.example.domain.gamification.repository.XpTransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
 * O Perfil do Atleta é uma projeção: cada número exibido tem de chegar da autoridade que já o
 * calcula. Estes testes usam as fontes reais (Room + repositórios) sempre que possível e recorrem a
 * dublês apenas para provar que o ViewModel *copia* o valor da autoridade em vez de recalculá-lo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ProfileViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var settingsManager: SettingsManager
    private lateinit var workoutRepository: WorkoutRepository
    private lateinit var bodyMeasurementRepository: BodyMeasurementRepository
    private lateinit var consistencyRepository: ConsistencyRepositoryImpl
    private lateinit var achievementRepository: AchievementRepositoryImpl
    private lateinit var xpTransactionRepository: XpTransactionRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsManager = SettingsManager(context)
        workoutRepository = WorkoutRepository(database.workoutDao(), settingsManager = settingsManager)
        bodyMeasurementRepository = BodyMeasurementRepository(database.bodyMeasurementDao())
        consistencyRepository = ConsistencyRepositoryImpl(
            workoutDao = database.workoutDao(),
            weeklyGoalDao = database.weeklyGoalDao(),
            settingsManager = settingsManager
        )
        achievementRepository = AchievementRepositoryImpl(
            achievementDao = database.achievementDao(),
            workoutDao = database.workoutDao(),
            gamificationEventDao = database.gamificationEventDao(),
            consistencyRepository = consistencyRepository,
            bodyMeasurementRepository = bodyMeasurementRepository
        )
        xpTransactionRepository = XpTransactionRepositoryImpl(database.xpTransactionDao())
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        xpRepository: XpTransactionRepository = xpTransactionRepository,
        consistency: ConsistencyRepository = consistencyRepository,
        achievements: AchievementRepository = achievementRepository
    ) = ProfileViewModel(
        xpTransactionRepository = xpRepository,
        consistencyRepository = consistency,
        achievementRepository = achievements,
        workoutRepository = workoutRepository,
        bodyMeasurementRepository = bodyMeasurementRepository,
        settingsManager = settingsManager
    )

    private suspend fun ProfileViewModel.awaitState(
        predicate: (ProfileUiState) -> Boolean = { true }
    ): ProfileUiState = uiState.first { !it.isLoading && predicate(it) }

    // --- Usuário novo ---------------------------------------------------------------------------

    @Test
    fun newUser_showsRealZeroesWithoutInventingHistory() = runTest {
        val state = createViewModel().awaitState()

        assertEquals(1, state.level)
        assertEquals(0, state.totalXp)
        assertEquals(0, state.currentLevelXp)
        assertEquals(0, state.completedWorkouts)
        assertEquals(0, state.personalRecordsCount)
        assertEquals(0, state.unlockedAchievements)
        assertEquals(0, state.streakWeeks)
        assertTrue("Nenhuma conquista desbloqueada deve produzir prévia vazia", state.recentAchievements.isEmpty())
        assertNull("Sem medição não pode existir peso", state.latestWeightKg)
        // O catálogo canônico continua sendo o denominador, mesmo sem nada desbloqueado.
        assertEquals(
            com.example.domain.evolution.model.achievement.AchievementCatalog.DEFINITIONS.size,
            state.totalAchievements
        )
    }

    // --- XP e nível -----------------------------------------------------------------------------

    @Test
    fun levelAndXp_areProjectedFromUserProgressWithoutRecalculation() = runTest {
        // Valores propositalmente incoerentes com a curva real: se o Perfil recalculasse o nível,
        // ele não conseguiria reproduzir exatamente este UserProgress.
        val authority = FakeXpTransactionRepository(
            UserProgress(currentLevel = 4, totalXp = 7_777, currentLevelXp = 250, xpForNextLevel = 2_000)
        )

        val state = createViewModel(xpRepository = authority).awaitState()

        assertEquals(4, state.level)
        assertEquals(7_777, state.totalXp)
        assertEquals(250, state.currentLevelXp)
        assertEquals(2_000, state.xpForNextLevel)
        assertEquals(1_750, state.xpToNextLevel)
        assertEquals(0.125f, state.levelProgress, 0.0001f)
    }

    @Test
    fun newXpTransaction_updatesProfileWithoutReopeningTheScreen() = runTest {
        val viewModel = createViewModel()
        assertEquals(0, viewModel.awaitState().totalXp)

        xpTransactionRepository.saveTransaction(
            XpTransaction(
                eventId = "event-1",
                amount = 600,
                reason = "workout_completed",
                createdAt = System.currentTimeMillis()
            ),
            XpTransactionOrigin.LIVE
        )

        val updated = viewModel.awaitState { it.totalXp == 600 }
        // Curva canônica: 500 XP fecham o nível 1, e o nível 2 exige 1000.
        assertEquals(2, updated.level)
        assertEquals(100, updated.currentLevelXp)
        assertEquals(1_000, updated.xpForNextLevel)
    }

    // --- Sequência ------------------------------------------------------------------------------

    @Test
    fun streak_isProjectedFromConsistencyAuthority() = runTest {
        val authority = FakeConsistencyRepository(
            ConsistencyProgress(
                currentStreakWeeks = 8,
                longestStreakWeeks = 12,
                currentWeekCompleted = 3,
                currentWeekGoal = 4,
                currentWeekStatus = WeeklyConsistencyStatus.IN_PROGRESS
            )
        )

        val state = createViewModel(consistency = authority).awaitState()

        assertEquals(8, state.streakWeeks)
        assertEquals(3, state.weeklyCompleted)
        assertEquals(4, state.weeklyGoal)
    }

    // --- Treinos --------------------------------------------------------------------------------

    @Test
    fun completedWorkouts_countOnlyCompletedSessions() = runTest {
        repeat(10) { insertSession(SessionStatus.COMPLETED) }
        repeat(2) { insertSession(SessionStatus.CANCELLED) }
        insertSession(SessionStatus.IN_PROGRESS)
        insertSession(SessionStatus.PLANNED)

        val state = createViewModel().awaitState { it.completedWorkouts > 0 }

        assertEquals(10, state.completedWorkouts)
    }

    // --- Recordes -------------------------------------------------------------------------------

    @Test
    fun personalRecords_countPersistedRecordsOnly() = runTest {
        val exerciseId = database.workoutDao().insertExercise(ExerciseEntity(name = "Supino"))
        repeat(12) { index ->
            database.workoutDao().insertPersonalRecord(
                PersonalRecordEntity(
                    exerciseId = exerciseId,
                    date = System.currentTimeMillis() + index,
                    prType = PRType.MAX_WEIGHT,
                    value = 100f + index
                )
            )
        }

        val state = createViewModel().awaitState { it.personalRecordsCount > 0 }

        assertEquals(12, state.personalRecordsCount)
    }

    // --- Conquistas -----------------------------------------------------------------------------

    @Test
    fun achievements_showUnlockedOverTotalAndPreviewTheThreeMostRecent() = runTest {
        val authority = FakeAchievementRepository(
            buildAchievements(total = 19, unlocked = 7)
        )

        val state = createViewModel(achievements = authority).awaitState()

        assertEquals(7, state.unlockedAchievements)
        assertEquals(19, state.totalAchievements)
        assertEquals(3, state.recentAchievements.size)
        // Mais recentes primeiro: as conquistas 6, 5 e 4 têm os maiores unlockedAt.
        assertEquals(listOf("ach_6", "ach_5", "ach_4"), state.recentAchievements.map { it.id })
    }

    @Test
    fun achievementUnlockedWhileProfileIsOpen_updatesCounterAndPreview() = runTest {
        val authority = FakeAchievementRepository(buildAchievements(total = 19, unlocked = 0))
        val viewModel = createViewModel(achievements = authority)

        assertEquals(0, viewModel.awaitState().unlockedAchievements)

        authority.emit(buildAchievements(total = 19, unlocked = 1))

        val updated = viewModel.awaitState { it.unlockedAchievements == 1 }
        assertEquals(19, updated.totalAchievements)
        assertEquals(listOf("ach_0"), updated.recentAchievements.map { it.id })
    }

    // --- Corpo ----------------------------------------------------------------------------------

    @Test
    fun latestWeight_isShownWhenAMeasurementExists() = runTest {
        bodyMeasurementRepository.insertMeasurement(
            BodyMeasurementEntity(date = System.currentTimeMillis(), weightKg = 89.4f)
        )

        val state = createViewModel().awaitState { it.latestWeightKg != null }

        assertEquals(89.4f, state.latestWeightKg!!, 0.001f)
    }

    // --- Meta semanal ---------------------------------------------------------------------------

    @Test
    fun changingWeeklyGoal_keepsCurrentWeekAndAppliesFromNextWeek() = runTest {
        consistencyRepository.initialize()
        val viewModel = createViewModel()
        val goalBefore = viewModel.awaitState().weeklyGoal

        viewModel.setWeeklyGoal(goalBefore + 1)

        val updated = viewModel.awaitState { it.nextWeekGoal == goalBefore + 1 }
        assertEquals("A semana corrente mantém a meta vigente", goalBefore, updated.weeklyGoal)
        assertTrue(updated.hasPendingGoalChange)

        // O histórico de metas não é reescrito: a semana atual continua registrada com o valor antigo
        // e a nova meta passa a valer só a partir da próxima segunda-feira.
        val snapshots = consistencyRepository.getGoalSnapshots()
        val currentMonday = LocalDate.now(ZoneId.systemDefault()).with(DayOfWeek.MONDAY).toEpochDay()
        val nextMonday = currentMonday + 7
        assertEquals(goalBefore, snapshots.first { it.effectiveFromWeek == currentMonday }.goal)
        assertEquals(goalBefore + 1, snapshots.first { it.effectiveFromWeek == nextMonday }.goal)
    }

    // --- Helpers --------------------------------------------------------------------------------

    private suspend fun insertSession(status: SessionStatus) {
        val now = System.currentTimeMillis()
        database.workoutDao().insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = now - 3_600_000,
                finishedAt = if (status == SessionStatus.COMPLETED) now else null,
                status = status.name,
                notes = null,
                templateNameSnapshot = "Treino A"
            )
        )
    }

    private fun buildAchievements(total: Int, unlocked: Int): List<Achievement> =
        (0 until total).map { index ->
            Achievement(
                id = "ach_$index",
                title = "Conquista $index",
                description = "Descrição $index",
                icon = "🏆",
                tier = AchievementTier.BRONZE,
                category = AchievementCategory.TRAINING,
                unlockedAt = if (index < unlocked) 1_000L + index else null,
                progress = if (index < unlocked) 1f else 0f,
                currentProgress = if (index < unlocked) 1 else 0,
                targetProgress = 1
            )
        }

    private class FakeXpTransactionRepository(
        progress: UserProgress
    ) : XpTransactionRepository {
        private val progressFlow = MutableStateFlow(progress)
        override val newTransactions: SharedFlow<XpTransaction> =
            MutableSharedFlow<XpTransaction>().asSharedFlow()

        override suspend fun saveTransaction(transaction: XpTransaction, origin: XpTransactionOrigin) = false
        override suspend fun hasTransactionForEvent(eventId: String) = false
        override fun getTransactions(): Flow<List<XpTransaction>> = flowOf(emptyList())
        override fun getUserProgress(): Flow<UserProgress> = progressFlow
        override suspend fun replaceAllTransactions(transactions: List<XpTransaction>) = Unit
    }

    private class FakeConsistencyRepository(
        private val progress: ConsistencyProgress
    ) : ConsistencyRepository {
        override suspend fun initialize() = Unit
        override suspend fun getConsistencySummary() =
            WorkoutConsistencySummary(0, 0, 0, 0f, null)
        override suspend fun getFrequencyHistory(): List<WorkoutFrequencyPoint> = emptyList()
        override suspend fun getConsistencyProgress(): ConsistencyProgress = progress
        override suspend fun getWeeklyConsistencies(): List<WeeklyConsistency> = emptyList()
        override suspend fun getGoalSnapshots(): List<WeeklyGoalSnapshot> = emptyList()
        override suspend fun setWeeklyGoal(newGoal: Int) = Unit
        override fun getConsistencySummaryFlow(): Flow<WorkoutConsistencySummary> =
            flowOf(WorkoutConsistencySummary(0, 0, 0, 0f, null))
        override fun getFrequencyHistoryFlow(): Flow<List<WorkoutFrequencyPoint>> = flowOf(emptyList())
        override fun getWorkoutTimestampsFlow(): Flow<List<Long>> = flowOf(emptyList())
        override fun getConsistencyProgressFlow(): Flow<ConsistencyProgress> = flowOf(progress)
        override fun getWeeklyConsistenciesFlow(): Flow<List<WeeklyConsistency>> = flowOf(emptyList())
        override fun getGoalSnapshotsFlow(): Flow<List<WeeklyGoalSnapshot>> = flowOf(emptyList())
    }

    private class FakeAchievementRepository(
        initial: List<Achievement>
    ) : AchievementRepository {
        private val achievements = MutableStateFlow(initial)
        override val liveUnlocks: SharedFlow<AchievementUnlock> =
            MutableSharedFlow<AchievementUnlock>().asSharedFlow()

        fun emit(list: List<Achievement>) {
            achievements.value = list
        }

        override fun getAchievementsFlow(): Flow<List<Achievement>> = achievements
        override suspend fun getAchievements(): List<Achievement> = achievements.value
        override suspend fun evaluateAndUnlock(origin: AchievementEvaluationOrigin): List<AchievementUnlock> =
            emptyList()
    }
}
