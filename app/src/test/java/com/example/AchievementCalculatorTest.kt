package com.example

import com.example.domain.evolution.calculator.AchievementCalculator
import com.example.domain.evolution.model.BodyEvolutionSummary
import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementCalculatorTest {

    /**
     * Teste 1 — Primeiro treino
     * Entrada: totalSessions = 1
     * Esperado: Primeiro treino desbloqueado
     */
    @Test
    fun testFirstWorkoutAchievementUnlocked() {
        val summary = EvolutionSummary(
            currentWeight = 80f,
            initialWeight = 80f,
            weightChange = 0f,
            totalWorkoutSessions = 1,
            trainingDays = 1,
            averageWorkoutsPerWeek = 1.0f,
            totalExercisesPerformed = 5,
            generatedAt = System.currentTimeMillis()
        )

        val achievements = AchievementCalculator.calculateAchievements(
            summary = summary,
            performanceSummary = null,
            consistencySummary = null,
            bodySummary = null
        )

        val firstWorkout = achievements.find { it.id == "first_workout" }
        assertNotNull(firstWorkout)
        assertNotNull(firstWorkout?.unlockedAt)
        assertEquals(1.0f, firstWorkout?.progress ?: 0f, 0.01f)
        assertEquals(1, firstWorkout?.currentProgress)
        assertEquals(1, firstWorkout?.targetProgress)
    }

    /**
     * Teste 2 — Dez treinos
     * Entrada: totalSessions = 10
     * Esperado: 10 treinos desbloqueado
     */
    @Test
    fun testTenWorkoutsAchievementUnlocked() {
        val summary = EvolutionSummary(
            currentWeight = 80f,
            initialWeight = 80f,
            weightChange = 0f,
            totalWorkoutSessions = 10,
            trainingDays = 8,
            averageWorkoutsPerWeek = 3.0f,
            totalExercisesPerformed = 50,
            generatedAt = System.currentTimeMillis()
        )

        val achievements = AchievementCalculator.calculateAchievements(
            summary = summary,
            performanceSummary = null,
            consistencySummary = null,
            bodySummary = null
        )

        val tenWorkouts = achievements.find { it.id == "10_workouts" }
        assertNotNull(tenWorkouts)
        assertNotNull(tenWorkouts?.unlockedAt)
        assertEquals(1.0f, tenWorkouts?.progress ?: 0f, 0.01f)
        assertEquals(10, tenWorkouts?.currentProgress)
        assertEquals(10, tenWorkouts?.targetProgress)

        val fiftyWorkouts = achievements.find { it.id == "50_workouts" }
        assertNotNull(fiftyWorkouts)
        assertNull(fiftyWorkouts?.unlockedAt)
        assertEquals(0.2f, fiftyWorkouts?.progress ?: 0f, 0.01f)
        assertEquals(10, fiftyWorkouts?.currentProgress)
        assertEquals(50, fiftyWorkouts?.targetProgress)
    }

    /**
     * Teste 3 — Primeiro PR
     * Entrada: personalRecords = 1
     * Esperado: Primeiro PR desbloqueado
     */
    @Test
    fun testFirstPRAchievementUnlocked() {
        val pr = PersonalRecord(
            exerciseId = "ex_1",
            exerciseName = "Supino Reto",
            maxWeight = 100f,
            repetitions = 8,
            achievedAt = System.currentTimeMillis()
        )

        val achievements = AchievementCalculator.calculateAchievements(
            summary = null,
            performanceSummary = null,
            personalRecords = listOf(pr),
            consistencySummary = null,
            bodySummary = null
        )

        val firstPR = achievements.find { it.id == "first_pr" }
        assertNotNull(firstPR)
        assertNotNull(firstPR?.unlockedAt)
        assertEquals(1.0f, firstPR?.progress ?: 0f, 0.01f)
        assertEquals(AchievementCategory.PERFORMANCE, firstPR?.category)
    }

    /**
     * Teste 4 — Usuário novo (sem dados)
     * Esperado: Mostrar todas as conquistas bloqueadas
     */
    @Test
    fun testNewUserAllAchievementsLocked() {
        val achievements = AchievementCalculator.calculateAchievements(
            summary = null,
            performanceSummary = null,
            consistencySummary = null,
            bodySummary = null
        )

        assertTrue(achievements.isNotEmpty())
        achievements.forEach { achievement ->
            assertNull("Conquista ${achievement.id} deveria estar bloqueada", achievement.unlockedAt)
            assertEquals(0, achievement.currentProgress)
        }
    }

    /**
     * Teste 5 — Evolução corporal (-5kg) e Primeira medição
     */
    @Test
    fun testBodyEvolutionAchievements() {
        val measurement = BodyMeasurement(
            id = 1,
            date = System.currentTimeMillis(),
            weightKg = 80f,
            heightCm = 175f,
            waistCm = null,
            abdomenCm = null,
            chestCm = null,
            leftArmCm = null,
            rightArmCm = null,
            leftThighCm = null,
            rightThighCm = null,
            leftCalfCm = null,
            rightCalfCm = null,
            hipCm = null,
            bodyFatPercentage = null
        )

        val bodySummary = BodyEvolutionSummary(
            currentWeight = 80f,
            initialWeight = 86f,
            weightVariation = -6f,
            currentHeight = 175f,
            bmi = 26.1f,
            bmiCategory = null
        )

        val achievements = AchievementCalculator.calculateAchievements(
            summary = null,
            performanceSummary = null,
            consistencySummary = null,
            bodySummary = bodySummary,
            measurements = listOf(measurement)
        )

        val firstMeasurement = achievements.find { it.id == "first_measurement" }
        assertNotNull(firstMeasurement?.unlockedAt)

        val bodyEvolution = achievements.find { it.id == "body_evolution_5kg" }
        assertNotNull(bodyEvolution?.unlockedAt)
        assertEquals(1.0f, bodyEvolution?.progress ?: 0f, 0.01f)
        assertEquals(AchievementCategory.BODY, bodyEvolution?.category)
    }

    /**
     * Teste 6 — Evolução de carga (+10kg)
     */
    @Test
    fun testLoadEvolutionAchievement() {
        val exerciseEvolution = ExercisePerformanceEvolution(
            exerciseId = "ex_squat",
            exerciseName = "Agachamento Livre",
            firstWeight = 60f,
            currentWeight = 72f,
            bestWeight = 72f,
            weightVariation = 12f,
            totalExecutions = 20,
            totalVolume = 5000f
        )

        val achievements = AchievementCalculator.calculateAchievements(
            summary = null,
            performanceSummary = null,
            exerciseEvolutions = listOf(exerciseEvolution),
            consistencySummary = null,
            bodySummary = null
        )

        val loadAchievement = achievements.find { it.id == "load_evolution_10kg" }
        assertNotNull(loadAchievement?.unlockedAt)
        assertEquals(1.0f, loadAchievement?.progress ?: 0f, 0.01f)
    }
}
