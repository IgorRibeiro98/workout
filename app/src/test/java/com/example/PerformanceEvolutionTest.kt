package com.example

import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.domain.evolution.calculator.PerformanceCalculator
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import com.example.domain.evolution.repository.PerformanceRepository
import com.example.feature.evolution.state.PerformanceUiState
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceEvolutionTest {

    /**
     * Teste 1 — Volume (Mandatory Test 1)
     * Entrada: 70kg, 3 séries, 10 reps
     * Esperado: 2100kg
     */
    @Test
    fun testMandatory1_CalculateVolume() {
        val volume = PerformanceCalculator.calculateVolume(
            weightKg = 70f,
            sets = 3,
            reps = 10
        )
        assertEquals(2100f, volume, 0.01f)

        val zeroVolume = PerformanceCalculator.calculateVolume(
            weightKg = 0f,
            sets = 3,
            reps = 10
        )
        assertEquals(0f, zeroVolume, 0.01f)
    }

    /**
     * Teste 2 — Evolução de carga (Mandatory Test 2)
     * Entrada: 40kg, 70kg
     * Esperado: +30kg
     */
    @Test
    fun testMandatory2_CalculateWeightEvolution() {
        val firstWeight = 40f
        val currentWeight = 70f
        val variation = PerformanceCalculator.calculateWeightEvolution(firstWeight, currentWeight)

        assertNotNull(variation)
        assertEquals(30f, variation ?: 0f, 0.01f)

        // Percentage growth
        val percentage = PerformanceCalculator.calculatePercentageGrowth(firstWeight, currentWeight)
        assertEquals(75f, percentage, 0.01f)
    }

    /**
     * Teste 3 — PR (Personal Record) (Mandatory Test 3)
     * Entrada: Supino, 70kg, 10 reps
     * Esperado: Criar recorde
     */
    @Test
    fun testMandatory3_CalculatePersonalRecord() {
        val sessionTime = 1756598400000L // 30/08/2026
        val session = SessionCalendarSummary(
            session = WorkoutSessionEntity(
                id = 1L,
                templateId = 1L,
                startedAt = sessionTime,
                finishedAt = sessionTime + 3600000L,
                status = "COMPLETED"
            ),
            checkIn = null,
            exercises = listOf(
                ExerciseSessionWithSets(
                    exerciseSession = ExerciseSessionEntity(
                        id = 10L,
                        sessionId = 1L,
                        plannedExerciseId = 100L,
                        actualExerciseId = 100L,
                        exerciseNameSnapshot = "Supino reto"
                    ),
                    sets = listOf(
                        SetLogEntity(id = 1L, exerciseSessionId = 10L, setNumber = 1, weight = 60f, repetitions = 10, completed = true),
                        SetLogEntity(id = 2L, exerciseSessionId = 10L, setNumber = 2, weight = 70f, repetitions = 10, completed = true),
                        SetLogEntity(id = 3L, exerciseSessionId = 10L, setNumber = 3, weight = 65f, repetitions = 8, completed = true)
                    )
                )
            )
        )

        val prs = PerformanceCalculator.calculatePersonalRecords(listOf(session))

        assertEquals(1, prs.size)
        val pr = prs.first()
        assertEquals("Supino reto", pr.exerciseName)
        assertEquals(70f, pr.maxWeight, 0.01f)
        assertEquals(10, pr.repetitions)
        assertEquals(sessionTime, pr.achievedAt)
    }

    /**
     * Teste 4 — Exercício sem histórico (Mandatory Test 4)
     * Entrada: novo exercício
     * Esperado: Não quebrar
     */
    @Test
    fun testMandatory4_ExerciseWithoutHistory_DoesNotCrash() {
        // Exercise with no completed sets
        val sessionWithEmptySets = SessionCalendarSummary(
            session = WorkoutSessionEntity(
                id = 1L,
                templateId = 1L,
                startedAt = 1000L,
                finishedAt = 2000L,
                status = "COMPLETED"
            ),
            checkIn = null,
            exercises = listOf(
                ExerciseSessionWithSets(
                    exerciseSession = ExerciseSessionEntity(
                        id = 10L,
                        sessionId = 1L,
                        plannedExerciseId = 999L,
                        actualExerciseId = 999L,
                        exerciseNameSnapshot = "Novo Exercício Desconhecido"
                    ),
                    sets = emptyList()
                )
            )
        )

        val evolutions = PerformanceCalculator.calculateExerciseEvolutions(listOf(sessionWithEmptySets))
        assertEquals(1, evolutions.size)
        val evolution = evolutions.first()
        assertEquals("Novo Exercício Desconhecido", evolution.exerciseName)
        assertNull(evolution.firstWeight)
        assertNull(evolution.currentWeight)
        assertNull(evolution.bestWeight)
        assertNull(evolution.weightVariation)
        assertEquals(1, evolution.totalExecutions)
        assertEquals(0f, evolution.totalVolume, 0.01f)

        // Empty session list
        val emptyEvolutions = PerformanceCalculator.calculateExerciseEvolutions(emptyList())
        assertTrue(emptyEvolutions.isEmpty())

        val emptyPrs = PerformanceCalculator.calculatePersonalRecords(emptyList())
        assertTrue(emptyPrs.isEmpty())

        val emptyVolumeHistory = PerformanceCalculator.calculateVolumeHistory(emptyList())
        assertTrue(emptyVolumeHistory.isEmpty())
    }

    /**
     * Teste 5 — Usuário sem treinos (Mandatory Test 5)
     * Esperado: Estado vazio com mensagem / summary zerado sem quebrar
     */
    @Test
    fun testMandatory5_EmptyUserWorkouts_EmptyState() {
        val emptySummary = PerformanceCalculator.calculateWorkoutPerformanceSummary(emptyList())

        assertEquals(0, emptySummary.totalSessions)
        assertEquals(0, emptySummary.totalExercises)
        assertEquals(0, emptySummary.totalSets)
        assertEquals(0, emptySummary.totalRepetitions)
        assertEquals(0f, emptySummary.totalVolume, 0.01f)
        assertNull(emptySummary.averageSessionDuration)

        val uiState = PerformanceUiState(
            isLoading = false,
            summary = emptySummary,
            exercises = emptyList(),
            records = emptyList()
        )

        assertTrue(uiState.isEmpty)
    }

    /**
     * Teste adicional — Ordenação por evolução percentual
     * Exercício A: 10kg → 20kg (+100%)
     * Exercício B: 80kg → 90kg (+12.5%)
     * Esperado: A aparece primeiro que B
     */
    @Test
    fun testExerciseEvolution_PercentageSorting() {
        val session1 = SessionCalendarSummary(
            session = WorkoutSessionEntity(id = 1L, templateId = 1L, startedAt = 1000L, status = "COMPLETED"),
            checkIn = null,
            exercises = listOf(
                ExerciseSessionWithSets(
                    exerciseSession = ExerciseSessionEntity(id = 1L, sessionId = 1L, plannedExerciseId = 1L, actualExerciseId = 1L, exerciseNameSnapshot = "Exercício B"),
                    sets = listOf(SetLogEntity(id = 1L, exerciseSessionId = 1L, setNumber = 1, weight = 80f, repetitions = 10, completed = true))
                ),
                ExerciseSessionWithSets(
                    exerciseSession = ExerciseSessionEntity(id = 2L, sessionId = 1L, plannedExerciseId = 2L, actualExerciseId = 2L, exerciseNameSnapshot = "Exercício A"),
                    sets = listOf(SetLogEntity(id = 2L, exerciseSessionId = 2L, setNumber = 1, weight = 10f, repetitions = 10, completed = true))
                )
            )
        )

        val session2 = SessionCalendarSummary(
            session = WorkoutSessionEntity(id = 2L, templateId = 1L, startedAt = 2000L, status = "COMPLETED"),
            checkIn = null,
            exercises = listOf(
                ExerciseSessionWithSets(
                    exerciseSession = ExerciseSessionEntity(id = 3L, sessionId = 2L, plannedExerciseId = 1L, actualExerciseId = 1L, exerciseNameSnapshot = "Exercício B"),
                    sets = listOf(SetLogEntity(id = 3L, exerciseSessionId = 3L, setNumber = 1, weight = 90f, repetitions = 10, completed = true))
                ),
                ExerciseSessionWithSets(
                    exerciseSession = ExerciseSessionEntity(id = 4L, sessionId = 2L, plannedExerciseId = 2L, actualExerciseId = 2L, exerciseNameSnapshot = "Exercício A"),
                    sets = listOf(SetLogEntity(id = 4L, exerciseSessionId = 4L, setNumber = 1, weight = 20f, repetitions = 10, completed = true))
                )
            )
        )

        val evolutions = PerformanceCalculator.calculateExerciseEvolutions(listOf(session1, session2))

        assertEquals(2, evolutions.size)
        // Exercício A (+100%) must appear before Exercício B (+12.5%)
        assertEquals("Exercício A", evolutions[0].exerciseName)
        assertEquals(10f, evolutions[0].firstWeight ?: 0f, 0.01f)
        assertEquals(20f, evolutions[0].currentWeight ?: 0f, 0.01f)
        assertEquals(10f, evolutions[0].weightVariation ?: 0f, 0.01f)

        assertEquals("Exercício B", evolutions[1].exerciseName)
        assertEquals(80f, evolutions[1].firstWeight ?: 0f, 0.01f)
        assertEquals(90f, evolutions[1].currentWeight ?: 0f, 0.01f)
        assertEquals(10f, evolutions[1].weightVariation ?: 0f, 0.01f)
    }

    /**
     * Teste Repository Mock
     */
    @Test
    fun testPerformanceRepositoryFlow() = runBlocking {
        val fakeRepo = object : PerformanceRepository {
            override suspend fun getPerformanceSummary() = WorkoutPerformanceSummary(
                totalSessions = 48,
                totalExercises = 120,
                totalSets = 420,
                totalRepetitions = 4200,
                totalVolume = 125400f,
                averageSessionDuration = 52
            )

            override suspend fun getExerciseEvolution(exerciseId: String): ExercisePerformanceEvolution? = null
            override suspend fun getAllExercisesEvolution(): List<ExercisePerformanceEvolution> = emptyList()
            override suspend fun getPersonalRecords(): List<PersonalRecord> = emptyList()
            override suspend fun getVolumeHistory() = emptyList<com.example.domain.evolution.model.performance.VolumePoint>()

            override fun getPerformanceSummaryFlow() = flow { emit(getPerformanceSummary()) }
            override fun getAllExercisesEvolutionFlow() = flowOf(emptyList<ExercisePerformanceEvolution>())
            override fun getPersonalRecordsFlow() = flowOf(emptyList<PersonalRecord>())
            override fun getVolumeHistoryFlow() = flowOf(emptyList<com.example.domain.evolution.model.performance.VolumePoint>())
        }

        val summary = fakeRepo.getPerformanceSummary()
        assertEquals(48, summary.totalSessions)
        assertEquals(420, summary.totalSets)
        assertEquals(125400f, summary.totalVolume, 0.01f)
        assertEquals(52, summary.averageSessionDuration)
    }
}
