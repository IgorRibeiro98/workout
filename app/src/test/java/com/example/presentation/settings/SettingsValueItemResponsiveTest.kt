package com.example.presentation.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.presentation.assertTextFits
import com.example.presentation.assertTextWithinLines
import com.example.presentation.assertWithinViewportWidth
import com.example.presentation.setContentWithFontScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Ao terminar o descanso" continua legível ao lado do seu valor (T19.H2 / H2.3).
 *
 * O defeito: a `Row` media o valor **primeiro**, com a linha inteira à disposição. "Avançar
 * automaticamente" ficava com quase toda a largura e o título, mesmo com `weight(1f)`, virava
 *
 * ```text
 * Ao terminar        Avançar
 * o descanso         automaticamente  >
 *
 * O que fazer
 * quando o
 * tempo de...
 * ```
 *
 * A regra nova é explícita: o valor só fica ao lado quando cabe em metade da linha; acima disso ele
 * desce e o título recupera a largura inteira. Este teste afirma as duas metades da regra — o
 * título nunca é picado, e o valor nunca é cortado — nas três larguras e nas três escalas de fonte.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w360dp-h740dp")
class SettingsValueItemResponsiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val title = "Ao terminar o descanso"
    private val subtitle = "O que fazer quando o tempo de descanso chega a zero"
    private val longValue = "Continuar contando até eu avançar"
    private val shortValue = "Avançar automaticamente"

    private fun render(fontScale: Float, value: String) {
        composeRule.setContentWithFontScale(fontScale) {
            // A tela desenha os itens numa `Column` com `padding(16.dp)`.
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                SettingsValueItem(title = title, valueText = value, subtitle = subtitle, onClick = {})
            }
        }
    }

    private fun verify(screenWidthDp: Int, fontScale: Float, value: String) {
        render(fontScale, value)
        val where = "${screenWidthDp}dp, fontScale $fontScale, valor \"$value\""

        // O título numa linha só: ele cabe na largura inteira em qualquer um dos casos, porque o
        // valor desce quando disputa espaço com ele.
        composeRule.onNodeWithText(title).assertTextFits("título em $where")
        composeRule.onNodeWithText(title).assertWithinViewportWidth(screenWidthDp, "título em $where")
        // O subtítulo pode quebrar — ele é uma frase —, mas não em uma palavra por linha.
        composeRule.onNodeWithText(subtitle).assertTextWithinLines(3, "subtítulo em $where")
        composeRule.onNodeWithText(value).assertTextFits("valor em $where")
        composeRule.onNodeWithText(value).assertWithinViewportWidth(screenWidthDp, "valor em $where")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `320dp fontScale 1_0`() = verify(320, 1.0f, shortValue)

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `320dp fontScale 1_5 com o valor mais longo`() = verify(320, 1.5f, longValue)

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun `360dp fontScale 1_0`() = verify(360, 1.0f, shortValue)

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun `360dp fontScale 1_3 com o valor mais longo`() = verify(360, 1.3f, longValue)

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `411dp fontScale 1_0`() = verify(411, 1.0f, shortValue)

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `411dp fontScale 1_3 com o valor mais longo`() = verify(411, 1.3f, longValue)

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `um valor curto continua ao lado do titulo, e nao embaixo`() {
        composeRule.setContentWithFontScale(1.0f) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                SettingsValueItem(title = "Descanso entre séries", valueText = "90s", onClick = {})
            }
        }
        // Lado a lado: a mesma faixa vertical. Empilhar tudo seria resolver o corte inventando
        // uma tela mais longa.
        val titleBounds = composeRule.onNodeWithText("Descanso entre séries").getBoundsInRoot()
        val valueBounds = composeRule.onNodeWithText("90s").getBoundsInRoot()
        org.junit.Assert.assertTrue(
            "o valor curto desceu para outra linha: $titleBounds / $valueBounds",
            valueBounds.top < titleBounds.bottom
        )
    }
}
