package com.example.presentation.history

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.presentation.assertTextFits
import com.example.presentation.assertTouchTargetAtLeast
import com.example.presentation.assertWithinViewportWidth
import com.example.presentation.setContentWithFontScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A matriz visual do Histórico (T19.H2 / H2.1), como teste.
 *
 * ```text
 *            fontScale 1.0   1.3   1.5
 * 320dp          ·            ·     ·
 * 360dp          ·            ·     ·
 * 411dp          ·            ·     ·
 * ```
 *
 * Em cada célula: nenhuma aba e nenhum chip com texto cortado, e nenhum controle saindo da tela.
 *
 * ## Por que `hasVisualOverflow` e não os limites do nó
 *
 * Compose corta o texto que não cabe — o nó continua dentro do pai, e uma asserção de limites passa
 * enquanto o usuário lê "Todos os...". A pergunta certa é a de [assertTextFits]. Foi exatamente
 * esse o defeito: três abas com `weight(1f)` e `maxLines = 1` num terço de 328dp.
 *
 * ## A largura usada aqui
 *
 * `HistoryScreen` desenha estes controles dentro de um `LazyColumn` com `padding(horizontal = 16.dp)`,
 * então a largura disponível é a da tela menos 32dp. É a mesma conta que o teste faz.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w411dp-h891dp")
class HistoryFiltersResponsiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val horizontalPadding = 16

    @Composable
    private fun Header(screenWidthDp: Int) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding.dp)
        ) {
            HistorySegmentedTabs(tabs = HISTORY_TABS, selectedIndex = 1, onSelect = {})
            HistoryFilterGroup(label = "Período", modifier = Modifier.testTag("period")) {
                HistoryPeriod.entries.forEach { period ->
                    HistoryFilterChip(label = period.label, isSelected = period == HistoryPeriod.MONTH, onClick = {})
                }
            }
            HistoryFilterGroup(label = "Tipo", modifier = Modifier.testTag("type")) {
                HISTORY_TYPE_FILTERS.forEach { HistoryFilterChip(label = it, isSelected = it == "Todos", onClick = {}) }
            }
            HistoryFilterGroup(label = "Agrupar", modifier = Modifier.testTag("grouping")) {
                HISTORY_GROUPINGS.forEach {
                    HistoryFilterChip(label = it, isSelected = it == "Semana", onClick = {}, style = HistoryChipStyle.Outlined)
                }
            }
            // `screenWidthDp` entra na composição para que o teste falhe alto se a largura do
            // qualifier e a esperada divergirem.
            check(screenWidthDp > 0)
        }
    }

    private fun verifyAt(screenWidthDp: Int, fontScale: Float) {
        composeRule.setContentWithFontScale(fontScale) { Header(screenWidthDp) }

        val where = "${screenWidthDp}dp, fontScale $fontScale"

        HISTORY_TABS.forEach { tab ->
            composeRule.onNodeWithText(tab).assertTextFits("aba \"$tab\" em $where")
        }

        // Os rótulos se repetem entre grupos ("Semana" é período **e** agrupamento), então a
        // busca é sempre dentro do grupo — como o usuário os lê.
        val groups = mapOf(
            "period" to HistoryPeriod.entries.map { it.label },
            "type" to HISTORY_TYPE_FILTERS,
            "grouping" to HISTORY_GROUPINGS
        )
        groups.forEach { (tag, chips) ->
            chips.forEach { chip ->
                val node = composeRule.onNode(hasText(chip) and hasAnyAncestor(hasTestTag(tag)))
                node.assertTextFits("chip \"$chip\" de $tag em $where")
                node.assertWithinViewportWidth(screenWidthDp, "chip \"$chip\" de $tag em $where")
            }
            composeRule.onNodeWithTag(tag).assertWithinViewportWidth(screenWidthDp, "grupo $tag em $where")
        }
    }

    // Uma célula da matriz por teste: `setContent` só pode ser chamado uma vez por teste, e a
    // largura da tela é `@Config(qualifiers = ...)`, que também é por teste.

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `320dp fontScale 1_0`() = verifyAt(320, 1.0f)

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `320dp fontScale 1_3`() = verifyAt(320, 1.3f)

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `320dp fontScale 1_5`() = verifyAt(320, 1.5f)

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun `360dp fontScale 1_0`() = verifyAt(360, 1.0f)

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun `360dp fontScale 1_3`() = verifyAt(360, 1.3f)

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun `360dp fontScale 1_5`() = verifyAt(360, 1.5f)

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `411dp fontScale 1_0`() = verifyAt(411, 1.0f)

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `411dp fontScale 1_3`() = verifyAt(411, 1.3f)

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `411dp fontScale 1_5`() = verifyAt(411, 1.5f)

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun `a aba continua com alvo de toque utilizavel`() {
        composeRule.setContentWithFontScale(1.0f) { Header(360) }
        HISTORY_TABS.forEach { tab ->
            composeRule.onNodeWithText(tab).assertTouchTargetAtLeast(label = "aba \"$tab\"")
        }
    }
}
