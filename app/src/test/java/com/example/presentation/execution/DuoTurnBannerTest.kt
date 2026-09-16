package com.example.presentation.execution

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutGuestSetLogEntity
import com.example.data.local.WorkoutSessionParticipantEntity
import com.example.domain.workout.execution.DuoExecution
import com.example.presentation.execution.components.DuoTurnBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O que a tela diz sobre a vez (T19.4 §6.6): o participante atual é explícito, o próximo é
 * previsível, e o descanso de quem espera é visível e derivado do timestamp.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class DuoTurnBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val owner = WorkoutSessionParticipantEntity(id = 1, sessionId = 10, role = "OWNER", position = 0)
    private val guest = WorkoutSessionParticipantEntity(id = 2, sessionId = 10, role = "GUEST", displayName = "João", position = 1)

    private fun exercise(vararg ownerCompleted: Boolean) = ExerciseSessionWithSets(
        exerciseSession = ExerciseSessionEntity(id = 100, sessionId = 10, plannedExerciseId = 1, actualExerciseId = 1, exerciseNameSnapshot = "Supino"),
        sets = ownerCompleted.mapIndexed { i, done -> SetLogEntity(id = 1000L + i, exerciseSessionId = 100, setNumber = i + 1, completed = done) }
    )

    private fun guestSets(vararg completed: Boolean) = completed.mapIndexed { i, done ->
        WorkoutGuestSetLogEntity(id = 2000L + i, participantId = 2, exerciseSessionId = 100, setNumber = i + 1, completed = done)
    }

    @Test
    fun `vez do dono mostra Voce, depois Joao, e o convidado pronto`() {
        val duo = DuoExecution.from(listOf(owner, guest), guestSets(false, false))!!
        val exercise = exercise(false, false)

        composeRule.setContent {
            DuoTurnBanner(duo = duo, turn = duo.turnFor(exercise), ownerRestTarget = null, isExerciseCompleted = false)
        }

        composeRule.onNodeWithText("VEZ DE").assertIsDisplayed()
        composeRule.onNodeWithTag("duo_current_participant").assertTextContains("Você")
        composeRule.onNodeWithTag("duo_next_participant").assertTextContains("João")
        composeRule.onNodeWithTag("duo_waiting_status").assertTextContains("João: pronto")
    }

    @Test
    fun `vez do convidado mostra o descanso do dono correndo`() {
        val duo = DuoExecution.from(listOf(owner, guest), guestSets(false, false))!!
        val exercise = exercise(true, false)
        val ownerRestTarget = System.currentTimeMillis() + 61_000L

        composeRule.setContent {
            DuoTurnBanner(duo = duo, turn = duo.turnFor(exercise), ownerRestTarget = ownerRestTarget, isExerciseCompleted = false)
        }

        composeRule.onNodeWithTag("duo_current_participant").assertTextContains("João")
        composeRule.onNodeWithTag("duo_next_participant").assertTextContains("Você")
        composeRule.onNodeWithTag("duo_waiting_status").assertTextContains("Você: descansando", substring = true)
    }

    @Test
    fun `vez do dono na serie 2 mostra o descanso do convidado correndo`() {
        val restingGuest = guest.copy(restEndsAt = System.currentTimeMillis() + 45_000L)
        val duo = DuoExecution.from(listOf(owner, restingGuest), guestSets(true, false))!!
        val exercise = exercise(true, false)

        composeRule.setContent {
            DuoTurnBanner(duo = duo, turn = duo.turnFor(exercise), ownerRestTarget = null, isExerciseCompleted = false)
        }

        composeRule.onNodeWithTag("duo_current_participant").assertTextContains("Você")
        composeRule.onNodeWithTag("duo_waiting_status").assertTextContains("João: descansando", substring = true)
    }

    @Test
    fun `exercicio concluido pelos dois nao tem vez`() {
        val duo = DuoExecution.from(listOf(owner, guest), guestSets(true, true))!!
        val exercise = exercise(true, true)

        composeRule.setContent {
            DuoTurnBanner(duo = duo, turn = duo.turnFor(exercise), ownerRestTarget = null, isExerciseCompleted = true)
        }

        composeRule.onNodeWithText("DUPLA").assertIsDisplayed()
        composeRule.onNodeWithTag("duo_current_participant").assertTextContains("Exercício concluído pelos dois")
    }
}
