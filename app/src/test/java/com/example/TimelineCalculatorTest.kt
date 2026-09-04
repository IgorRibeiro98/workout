package com.example

import com.example.domain.evolution.calculator.TimelineCalculator
import com.example.domain.evolution.model.EvolutionSnapshot
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.timeline.TimelineEventCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineCalculatorTest {

    /**
     * Teste 1 — Primeiro treino com data real
     */
    @Test
    fun testFirstWorkoutEventCreatedWithRealDate() {
        val firstWorkoutTime = 1690000000000L
        val summary = EvolutionSummary(
            currentWeight = 80f,
            initialWeight = 80f,
            weightChange = 0f,
            totalWorkoutSessions = 1,
            trainingDays = 1,
            averageWorkoutsPerWeek = 1.0f,
            totalExercisesPerformed = 4,
            generatedAt = System.currentTimeMillis()
        )

        val snapshot = EvolutionSnapshot(
            summary = summary,
            performanceSummary = null,
            consistencySummary = null,
            bodySummary = null,
            firstWorkoutDate = firstWorkoutTime
        )

        val events = TimelineCalculator.calculateTimelineEvents(snapshot)

        val firstWorkoutEvent = events.find { it.id == "event_first_workout" }
        assertNotNull(firstWorkoutEvent)
        assertEquals("Primeiro treino", firstWorkoutEvent?.title)
        assertEquals(firstWorkoutTime, firstWorkoutEvent?.date)
        assertEquals(TimelineEventCategory.TRAINING, firstWorkoutEvent?.category)
    }

    /**
     * Teste 2 — PR (Recorde Pessoal)
     */
    @Test
    fun testPersonalRecordEventCreated() {
        val pr = PersonalRecord(
            exerciseId = "ex_bench",
            exerciseName = "Supino Reto",
            maxWeight = 70f,
            repetitions = 10,
            achievedAt = 1700000000000L
        )

        val snapshot = EvolutionSnapshot(
            summary = null,
            performanceSummary = null,
            personalRecords = listOf(pr),
            consistencySummary = null,
            bodySummary = null
        )

        val events = TimelineCalculator.calculateTimelineEvents(snapshot)

        val prEvent = events.find { it.id.startsWith("event_pr_") }
        assertNotNull(prEvent)
        assertEquals("Primeiro recorde pessoal", prEvent?.title)
        assertTrue(prEvent?.description?.contains("Supino Reto") == true)
        assertTrue(prEvent?.description?.contains("70kg x 10") == true)
        assertEquals(TimelineEventCategory.PERFORMANCE, prEvent?.category)
    }

    /**
     * Teste 3 — Conquista desbloqueada
     */
    @Test
    fun testUnlockedAchievementEventCreated() {
        val achievement = Achievement(
            id = "first_workout",
            title = "Primeiro Treino",
            description = "Completou o primeiro treino",
            icon = "🏋️",
            category = AchievementCategory.TRAINING,
            unlockedAt = 1710000000000L,
            progress = 1.0f,
            currentProgress = 1,
            targetProgress = 1,
            tier = com.example.domain.evolution.model.achievement.AchievementTier.BRONZE
        )

        val snapshot = EvolutionSnapshot(
            summary = null,
            performanceSummary = null,
            achievements = listOf(achievement),
            consistencySummary = null,
            bodySummary = null
        )

        val events = TimelineCalculator.calculateTimelineEvents(snapshot)

        val achievementEvent = events.find { it.id == "event_achievement_first_workout" }
        assertNotNull(achievementEvent)
        assertEquals("Conquista desbloqueada", achievementEvent?.title)
        assertEquals("Primeiro Treino", achievementEvent?.description)
        assertEquals(1710000000000L, achievementEvent?.date)
        assertEquals(TimelineEventCategory.ACHIEVEMENT, achievementEvent?.category)
    }

    /**
     * Teste 4 — Ordenação decrescente
     */
    @Test
    fun testEventsOrderingDescending() {
        val t1 = 1700000000000L // 01/08
        val t2 = 1700002000000L // 15/08
        val t3 = 1700005000000L // 30/08

        val pr1 = PersonalRecord("ex1", "Exercício 1", 50f, 10, t1)
        val pr2 = PersonalRecord("ex2", "Exercício 2", 60f, 10, t2)
        val pr3 = PersonalRecord("ex3", "Exercício 3", 70f, 10, t3)

        val snapshot = EvolutionSnapshot(
            summary = null,
            performanceSummary = null,
            personalRecords = listOf(pr1, pr2, pr3),
            consistencySummary = null,
            bodySummary = null
        )

        val events = TimelineCalculator.calculateTimelineEvents(snapshot)

        assertEquals(3, events.size)
        assertEquals(t3, events[0].date)
        assertEquals(t2, events[1].date)
        assertEquals(t1, events[2].date)
    }

    /**
     * Teste 5 — Usuário novo (Sem dados)
     */
    @Test
    fun testNewUserEmptyTimeline() {
        val snapshot = EvolutionSnapshot(
            summary = null,
            performanceSummary = null,
            consistencySummary = null,
            bodySummary = null
        )

        val events = TimelineCalculator.calculateTimelineEvents(snapshot)

        assertTrue(events.isEmpty())
    }
}
