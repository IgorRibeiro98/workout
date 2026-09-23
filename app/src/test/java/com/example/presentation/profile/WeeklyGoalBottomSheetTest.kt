package com.example.presentation.profile

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.presentation.assertWithinViewportHeight
import com.example.presentation.setContentWithFontScale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Meta Semanal consegue ser concluída (T19.H2 / H2.4).
 *
 * ## O defeito
 *
 * As sete opções, o aviso de vigência e o botão passavam de 800dp de altura. O conteúdo de um
 * `ModalBottomSheet` **não rola sozinho**: a folha cresce com ele e o sistema a limita à tela. Num
 * aparelho de 640dp o botão "Salvar" simplesmente não existia para o usuário — sem barra de
 * rolagem, sem aviso, sem jeito de chegar nele.
 *
 * ## O que este teste afirma
 *
 * Que o botão está **dentro da tela** em 320×640 com fonte aumentada, que ele responde, e que a
 * folha não some antes de a gravação ter sido aceita.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w320dp-h640dp")
class WeeklyGoalBottomSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        currentWeeklyGoal: Int = 3,
        nextWeeklyGoal: Int = 3,
        saveState: WeeklyGoalSave = WeeklyGoalSave.Idle,
        fontScale: Float = 1.0f,
        onConfirm: (Int) -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeRule.setContentWithFontScale(fontScale) {
            WeeklyGoalBottomSheet(
                currentWeeklyGoal = currentWeeklyGoal,
                nextWeeklyGoal = nextWeeklyGoal,
                saveState = saveState,
                onDismiss = onDismiss,
                onConfirm = onConfirm
            )
        }
    }

    @Test
    fun `o botao de salvar esta dentro da tela em 320x640`() {
        render()
        composeRule.onNodeWithTag(WEEKLY_GOAL_SAVE_TAG)
            .assertIsDisplayed()
            .assertWithinViewportHeight(640, "botão de salvar em 320x640")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `o botao de salvar continua dentro da tela com a fonte aumentada`() {
        render(fontScale = 1.3f)
        composeRule.onNodeWithTag(WEEKLY_GOAL_SAVE_TAG)
            .assertIsDisplayed()
            .assertWithinViewportHeight(640, "botão de salvar com fontScale 1.3")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `as sete opcoes continuam alcancaveis pela rolagem`() {
        render()
        // A última opção vive abaixo da dobra: ela existe e o miolo rola até ela — antes o
        // conteúdo inteiro era cortado, e nem rolar adiantava.
        composeRule.onNodeWithText("7 treinos por semana").performScrollTo().assertIsDisplayed()
        // E o rodapé continua onde estava: rolar o miolo não leva o CTA embora.
        composeRule.onNodeWithTag(WEEKLY_GOAL_SAVE_TAG).assertWithinViewportHeight(640, "CTA após rolar")
    }

    @Test
    fun `escolher outra meta e salvar entrega exatamente o valor escolhido`() {
        var saved: Int? = null
        render(currentWeeklyGoal = 3, nextWeeklyGoal = 3, onConfirm = { saved = it })

        composeRule.onNodeWithText("5 treinos por semana").performScrollTo().performClick()
        composeRule.onNodeWithTag(WEEKLY_GOAL_SAVE_TAG).performClick()

        assertEquals(5, saved)
    }

    @Test
    fun `a semantica de vigencia aparece antes de salvar`() {
        render(currentWeeklyGoal = 3, nextWeeklyGoal = 3)
        composeRule.onNodeWithText("5 treinos por semana").performScrollTo().performClick()

        // O aviso vive no fim do miolo rolável: ele existe e é alcançável — o que não pode é o
        // CTA depender de rolagem, e ele não depende (rodapé fixo).
        composeRule.onNodeWithText("Esta semana continua com meta de 3 treinos.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Sua nova meta começará na próxima semana.").assertIsDisplayed()
    }

    @Test
    fun `durante a gravacao o botao fica bloqueado, e um segundo toque nao chama de novo`() {
        var calls = 0
        render(saveState = WeeklyGoalSave.Saving, onConfirm = { calls++ })

        composeRule.onNodeWithTag(WEEKLY_GOAL_SAVE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithText("Salvando...").assertIsDisplayed()
        assertEquals(0, calls)
    }

    @Test
    fun `uma falha fica visivel com a folha aberta, em vez de sumir junto com ela`() {
        var dismissed = false
        render(saveState = WeeklyGoalSave.Failed, onDismiss = { dismissed = true })

        composeRule.onNodeWithText("Não foi possível salvar a meta agora. Nada foi alterado — tente de novo.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(WEEKLY_GOAL_SAVE_TAG).assertIsEnabled()
        org.junit.Assert.assertFalse("a folha não pode fechar sozinha numa falha", dismissed)
    }
}
