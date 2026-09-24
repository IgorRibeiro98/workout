package com.example.presentation.friends

import android.os.Build
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.social.WorkoutSocialExercise
import com.example.domain.social.WorkoutSocialSet
import com.example.domain.social.WorkoutSocialSummary
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O resumo de treino no card (T19.H3 §59): formata o que veio, e só.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class CheckInWorkoutSummaryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val saoPaulo = ZoneId.of("America/Sao_Paulo")

    /** O exemplo da tarefa: "Superiores A", 19:57 → 20:52, Supino 3×10 @ 80, Remada 3×12 @ 60. */
    private val full = WorkoutSocialSummary(
        name = "Superiores A",
        startedAt = java.time.Instant.parse("2026-09-07T22:57:00Z").toEpochMilli(),
        durationSeconds = 55 * 60L,
        exerciseCount = 2,
        completedSetCount = 6,
        totalVolumeKg = 4560.0,
        exercises = listOf(
            WorkoutSocialExercise("Supino reto", "Peito", List(3) { WorkoutSocialSet(reps = 10, weightKg = 80.0) }),
            WorkoutSocialExercise("Remada", null, List(3) { WorkoutSocialSet(reps = 12, weightKg = 60.0) })
        )
    )

    // ------------------------------------------------------------------ formatação

    @Test
    fun `horario e duracao numa linha so, no fuso de quem le`() {
        assertEquals("19:57 · 55 min", CheckInSummaryFormat.whenLine(full, saoPaulo))
        assertNull(CheckInSummaryFormat.whenLine(WorkoutSocialSummary(name = "X"), saoPaulo))
    }

    @Test
    fun `contagens e volume em pt-BR`() {
        assertEquals("2 exercícios · 6 séries · Volume 4.560 kg", CheckInSummaryFormat.countsLine(full))
        assertEquals("1 série", CheckInSummaryFormat.countsLine(WorkoutSocialSummary(completedSetCount = 1)))
    }

    @Test
    fun `carga com casas decimais e serie por tempo`() {
        assertEquals("22,25 kg × 10", CheckInSummaryFormat.set(WorkoutSocialSet(reps = 10, weightKg = 22.25)))
        assertEquals("10 reps", CheckInSummaryFormat.set(WorkoutSocialSet(reps = 10)))
        assertEquals("60 s", CheckInSummaryFormat.set(WorkoutSocialSet(durationSeconds = 60)))
    }

    @Test
    fun `duracao longa e curta`() {
        assertEquals("1 h 05 min", CheckInSummaryFormat.duration(65 * 60L))
        assertEquals("2 h", CheckInSummaryFormat.duration(120 * 60L))
        assertEquals("1 min", CheckInSummaryFormat.duration(20L))
    }

    // ------------------------------------------------------------------ tela

    @Test
    fun `o card mostra o resumo completo do exemplo`() {
        composeRule.setContent {
            CheckInWorkoutSummaryView(summary = full, compact = true, zone = saoPaulo)
        }

        composeRule.onNodeWithText("Superiores A").assertExists()
        composeRule.onNodeWithText("19:57 · 55 min").assertExists()
        composeRule.onNodeWithText("2 exercícios · 6 séries · Volume 4.560 kg").assertExists()
        composeRule.onNodeWithText("80 kg × 10 · 80 kg × 10 · 80 kg × 10").assertExists()
    }

    @Test
    fun `sem cargas compartilhadas nenhum peso aparece (H3 30)`() {
        val noWeights = full.copy(
            exercises = listOf(WorkoutSocialExercise("Supino reto", null, List(3) { WorkoutSocialSet(reps = 10) }))
        )
        composeRule.setContent {
            CheckInWorkoutSummaryView(summary = noWeights, compact = false, zone = saoPaulo)
        }

        composeRule.onNodeWithText("Série 1 · 10 reps").assertExists()
        composeRule.onNodeWithText("kg ×", substring = true).assertDoesNotExist()
    }

    @Test
    fun `o card compacto mostra ate tres exercicios e aponta para o resto`() {
        val many = WorkoutSocialSummary(
            exercises = (1..5).map { WorkoutSocialExercise("Exercício $it") }
        )
        composeRule.setContent {
            CheckInWorkoutSummaryView(summary = many, compact = true, zone = saoPaulo)
        }

        composeRule.onNodeWithText("Exercício 3").assertExists()
        composeRule.onNodeWithText("Exercício 4").assertDoesNotExist()
        composeRule.onNodeWithText("+2 exercícios", substring = true).assertExists()
    }
}
