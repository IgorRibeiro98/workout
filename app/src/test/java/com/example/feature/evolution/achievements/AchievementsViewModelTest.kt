package com.example.feature.evolution.achievements

import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.model.achievement.AchievementTier
import com.example.domain.evolution.model.achievement.AchievementUnlock
import com.example.domain.evolution.repository.AchievementEvaluationOrigin
import com.example.domain.evolution.repository.AchievementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeAchievementRepository(
        private val achievementsFlow: Flow<List<Achievement>>
    ) : AchievementRepository {
        override fun getAchievementsFlow(): Flow<List<Achievement>> = achievementsFlow
        override suspend fun getAchievements(): List<Achievement> = achievementsFlow.first()
        override suspend fun evaluateAndUnlock(origin: AchievementEvaluationOrigin): List<AchievementUnlock> = emptyList()
        override val liveUnlocks: SharedFlow<AchievementUnlock> = MutableSharedFlow<AchievementUnlock>().asSharedFlow()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSampleAchievements(): List<Achievement> {
        return listOf(
            Achievement(
                id = "workout_1",
                title = "Primeiro Treino",
                description = "Complete 1 treino",
                icon = "fitness_center",
                tier = AchievementTier.BRONZE,
                category = AchievementCategory.TRAINING,
                unlockedAt = 1000L,
                progress = 1.0f,
                currentProgress = 1,
                targetProgress = 1
            ),
            Achievement(
                id = "workout_10",
                title = "10 Treinos",
                description = "Complete 10 treinos",
                icon = "fitness_center",
                tier = AchievementTier.SILVER,
                category = AchievementCategory.TRAINING,
                unlockedAt = null,
                progress = 0.5f,
                currentProgress = 5,
                targetProgress = 10
            ),
            Achievement(
                id = "body_1",
                title = "Primeira Medição",
                description = "Registre medidas",
                icon = "straighten",
                tier = AchievementTier.BRONZE,
                category = AchievementCategory.BODY,
                unlockedAt = 2000L,
                progress = 1.0f,
                currentProgress = 1,
                targetProgress = 1
            ),
            Achievement(
                id = "streak_4",
                title = "4 Semanas",
                description = "4 semanas consecutivas",
                icon = "local_fire_department",
                tier = AchievementTier.SILVER,
                category = AchievementCategory.CONSISTENCY,
                unlockedAt = null,
                progress = 0.25f,
                currentProgress = 1,
                targetProgress = 4
            )
        )
    }

    @Test
    fun testInitialStateAndSorting() = runTest {
        val flow = MutableStateFlow(createSampleAchievements())
        val repository = FakeAchievementRepository(flow)
        val viewModel = AchievementsViewModel(repository)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(4, state.totalCount)
        assertEquals(2, state.unlockedCount)
        assertEquals(0.5f, state.overallProgress, 0.01f)
        assertNull(state.selectedCategory)

        // Sorting check (INV / Section 9.3):
        // Unlocked first, sorted descending by unlockedAt:
        // body_1 (2000L) > workout_1 (1000L)
        // Then in-progress sorted descending by progress:
        // workout_10 (0.5f) > streak_4 (0.25f)
        val ids = state.displayedAchievements.map { it.id }
        assertEquals(listOf("body_1", "workout_1", "workout_10", "streak_4"), ids)
    }

    @Test
    fun testCategoryFiltering() = runTest {
        val flow = MutableStateFlow(createSampleAchievements())
        val repository = FakeAchievementRepository(flow)
        val viewModel = AchievementsViewModel(repository)

        testDispatcher.scheduler.advanceUntilIdle()

        // Filter by TRAINING
        viewModel.selectCategory(AchievementCategory.TRAINING)
        testDispatcher.scheduler.advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(AchievementCategory.TRAINING, state.selectedCategory)
        assertEquals(2, state.displayedAchievements.size)
        assertTrue(state.displayedAchievements.all { it.category == AchievementCategory.TRAINING })

        // Filter by BODY
        viewModel.selectCategory(AchievementCategory.BODY)
        testDispatcher.scheduler.advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals(AchievementCategory.BODY, state.selectedCategory)
        assertEquals(1, state.displayedAchievements.size)
        assertEquals("body_1", state.displayedAchievements.first().id)

        // Reset filter
        viewModel.selectCategory(null)
        testDispatcher.scheduler.advanceUntilIdle()

        state = viewModel.uiState.value
        assertNull(state.selectedCategory)
        assertEquals(4, state.displayedAchievements.size)
    }

    @Test
    fun testDetailSelection() = runTest {
        val sampleList = createSampleAchievements()
        val flow = MutableStateFlow(sampleList)
        val repository = FakeAchievementRepository(flow)
        val viewModel = AchievementsViewModel(repository)

        testDispatcher.scheduler.advanceUntilIdle()

        val target = sampleList[1] // workout_10
        viewModel.selectAchievementForDetail(target)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(target.id, viewModel.uiState.value.selectedAchievementForDetail?.id)

        // Dismiss detail
        viewModel.selectAchievementForDetail(null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedAchievementForDetail)
    }
}
