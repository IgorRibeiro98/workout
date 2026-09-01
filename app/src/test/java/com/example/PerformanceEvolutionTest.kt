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
import com.example.feature.evolution.performance.PerformanceUiState
import com.example.feature.evolution.performance.components.formatVolumeSummary
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
     * Teste 1 — Usuário sem treino (PARTE 13 / Teste 1)
     * Entrada: []
     * Esperado: Estado vazio
     */
    @Test
    fun testMandatory_UserWithoutWorkouts_ReturnsEmptyState() {
        val emptySessions = emptyList<SessionCalendarSummary>()
        val summary = PerformanceCalculator.calculateWorkoutPerformanceSummary(emptySessions)
        val exercises = PerformanceCalculator.calculateExerciseEvolutions(emptySessions)
        val records = PerformanceCalculator.calculatePersonalRecords(emptySessions)

        val uiState = PerformanceUiState(
            isLoading = false,
            summary = summary,
            topExercises = exercises,
            personalRecords = records
        )

        assertEquals(0, summary.totalSessions)
        assertEquals(0, summary.totalSets)
        assertEquals(0f, summary.totalVolume, 0.01f)
        assertTrue(exercises.isEmpty())
        assertTrue(records.isEmpty())
        assertTrue(uiState.isEmpty)
    }

    /**
     * Teste 2 — Resumo (PARTE 13 / Teste 2)
     * Entrada: 50 sessões, 400 séries, 100000 volume
     * Esperado: Mostrar: 50 treinos, 400 séries, 100 mil kg
     */
    @Test
    fun testMandatory_SummaryFormatting() {
        val summary = WorkoutPerformanceSummary(
            totalSessions = 50,
            totalExercises = 150,
            totalSets = 400,
            totalRepetitions = 4000,
            totalVolume = 100000f,
            averageSessionDuration = 60
        )

        assertEquals(50, summary.totalSessions)
        assertEquals(400, summary.totalSets)
        assertEquals(100000f, summary.totalVolume, 0.01f)

        val formattedVolume = formatVolumeSummary(summary.totalVolume)
        assertEquals("100 mil kg", formattedVolume)

        // Também testando 125.400 -> "125,4 mil kg"
        val formattedVolumeDecimal = formatVolumeSummary(125400f)
        assertEquals("125,4 mil kg", formattedVolumeDecimal)
    }

    /**
     * Teste 3 — Exercício evoluído (PARTE 13 / Teste 3)
     * Entrada: Supino, 40kg -> 70kg
     * Esperado: Mostrar: +30kg (+75%)
     */
    @Test
    fun testMandatory_ExerciseEvolved_WeightEvolution() {
        val firstWeight = 40f
        val currentWeight = 70f

        val variation = PerformanceCalculator.calculateWeightEvolution(firstWeight, currentWeight)
        val percentage = PerformanceCalculator.calculatePercentageGrowth(firstWeight, currentWeight)

        assertNotNull(variation)
        assertEquals(30f, variation ?: 0f, 0.01f)
        assertEquals(75f, percentage, 0.01f)
    }

    /**
     * Teste 4 — PR (PARTE 13 / Teste 4)
     * Entrada: Supino, 70kg x 10
     * Esperado: Mostrar recorde
     */
    @Test
    fun testMandatory_PersonalRecord() {
        val sessionTime = 1756598400000L
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
                        SetLogEntity(id = 2L, exerciseSessionId = 10L, setNumber = 2, weight = 70f, repetitions = 10, completed = true)
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
     * Teste Volume Calculation (70kg * 3 * 10 = 2100kg)
     */
    @Test
    fun testCalculateVolume() {
        val volume = PerformanceCalculator.calculateVolume(
            weightKg = 70f,
            sets = 3,
            reps = 10
        )
        assertEquals(2100f, volume, 0.01f)
    }

    /**
     * Teste Exercício sem histórico não quebra
     */
    @Test
    fun testExerciseWithoutHistory_DoesNotCrash() {
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
    }

    /**
     * Teste ordenação dos exercícios que mais evoluíram
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
        // Exercício A (+100%) deve vir antes de Exercício B (+12.5%)
        assertEquals("Exercício A", evolutions[0].exerciseName)
        assertEquals(10f, evolutions[0].firstWeight ?: 0f, 0.01f)
        assertEquals(20f, evolutions[0].currentWeight ?: 0f, 0.01f)
        assertEquals(10f, evolutions[0].weightVariation ?: 0f, 0.01f)

        assertEquals("Exercício B", evolutions[1].exerciseName)
        assertEquals(80f, evolutions[1].firstWeight ?: 0f, 0.01f)
        assertEquals(90f, evolutions[1].currentWeight ?: 0f, 0.01f)
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
    }
}
