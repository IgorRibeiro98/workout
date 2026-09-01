package com.example

import com.example.domain.evolution.calculator.TimelineCalculator
import com.example.domain.evolution.model.BodyEvolutionSummary
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
     * Teste 1 — Primeiro treino
     * Entrada: totalSessions = 1
     * Esperado: Evento "Primeiro treino" criado
     */
    @Test
    fun testFirstWorkoutEventCreated() {
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

        val events = TimelineCalculator.calculateTimelineEvents(
            summary = summary,
            performanceSummary = null,
            consistencySummary = null,
            bodySummary = null
        )

        val firstWorkoutEvent = events.find { it.id == "event_first_workout" }
        assertNotNull(firstWorkoutEvent)
        assertEquals("Primeiro treino", firstWorkoutEvent?.title)
        assertEquals(TimelineEventCategory.TRAINING, firstWorkoutEvent?.category)
    }

    /**
     * Teste 2 — PR (Recorde Pessoal)
     * Entrada: PersonalRecord Supino 70kg
     * Esperado: Evento de PR criado
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

        val events = TimelineCalculator.calculateTimelineEvents(
            summary = null,
            performanceSummary = null,
            personalRecords = listOf(pr),
            consistencySummary = null,
            bodySummary = null
        )

        val prEvent = events.find { it.id.startsWith("event_pr_") }
        assertNotNull(prEvent)
        assertEquals("Primeiro recorde pessoal", prEvent?.title)
        assertTrue(prEvent?.description?.contains("Supino Reto") == true)
        assertTrue(prEvent?.description?.contains("70kg x 10") == true)
        assertEquals(TimelineEventCategory.PERFORMANCE, prEvent?.category)
    }

    /**
     * Teste 3 — Conquista desbloqueada
     * Entrada: Achievement com unlockedAt != null
     * Esperado: Evento correspondente criado
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
            targetProgress = 1
        )

        val events = TimelineCalculator.calculateTimelineEvents(
            summary = null,
            performanceSummary = null,
            achievements = listOf(achievement),
            consistencySummary = null,
            bodySummary = null
        )

        val achievementEvent = events.find { it.id == "event_achievement_first_workout" }
        assertNotNull(achievementEvent)
        assertEquals("Conquista desbloqueada", achievementEvent?.title)
        assertEquals("Primeiro Treino", achievementEvent?.description)
        assertEquals(1710000000000L, achievementEvent?.date)
        assertEquals(TimelineEventCategory.ACHIEVEMENT, achievementEvent?.category)
    }

    /**
     * Teste 4 — Ordenação
     * Entrada: Eventos com datas 01/08, 30/08, 15/08
     * Esperado: Ordenação decrescente (30/08, 15/08, 01/08)
     */
    @Test
    fun testEventsOrderingDescending() {
        val t1 = 1700000000000L // Ex: 01/08
        val t2 = 1700002000000L // Ex: 15/08
        val t3 = 1700005000000L // Ex: 30/08

        val pr1 = PersonalRecord("ex1", "Exercício 1", 50f, 10, t1)
        val pr2 = PersonalRecord("ex2", "Exercício 2", 60f, 10, t2)
        val pr3 = PersonalRecord("ex3", "Exercício 3", 70f, 10, t3)

        val events = TimelineCalculator.calculateTimelineEvents(
            summary = null,
            performanceSummary = null,
            personalRecords = listOf(pr1, pr2, pr3),
            consistencySummary = null,
            bodySummary = null
        )

        assertEquals(3, events.size)
        assertEquals(t3, events[0].date)
        assertEquals(t2, events[1].date)
        assertEquals(t1, events[2].date)
    }

    /**
     * Teste 5 — Usuário novo
     * Entrada: Sem dados
     * Esperado: Lista de eventos vazia
     */
    @Test
    fun testNewUserEmptyTimeline() {
        val events = TimelineCalculator.calculateTimelineEvents(
            summary = null,
            performanceSummary = null,
            consistencySummary = null,
            bodySummary = null
        )

        assertTrue(events.isEmpty())
    }
}
