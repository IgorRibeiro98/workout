package com.example.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertTrue

/**
 * As afirmações que a matriz visual da T19.H2 exige, escritas como teste.
 *
 * ## Por que não bastam os limites do nó
 *
 * Compose **corta** o texto que não cabe: o nó continua dentro do pai, e uma asserção de limites
 * passa enquanto o usuário lê "Todos os...". O que descreve o defeito é
 * [TextLayoutResult.hasVisualOverflow] — "o que eu desenhei não coube no espaço que me deram" — e é
 * essa a pergunta que [assertTextFits] faz.
 *
 * ## Font scale
 *
 * A escala vem de [LocalDensity], e não de `@Config`: é o único jeito de rodar a mesma tela em 1.0,
 * 1.3 e 1.5 dentro da mesma classe de teste, e é exatamente o que o sistema faz quando o usuário
 * aumenta a fonte.
 */
fun ComposeContentTestRule.setContentWithFontScale(
    fontScale: Float,
    content: @Composable () -> Unit
) {
    setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density = base.density, fontScale = fontScale)
        ) {
            content()
        }
    }
}

/**
 * Este rótulo de uma linha coube inteiro — nada foi cortado.
 *
 * ## Por que não `TextLayoutResult.hasVisualOverflow`
 *
 * Seria a pergunta certa, mas sob Robolectric ela responde `true` para **todo** texto: o campo
 * compara a altura do parágrafo (float) com o tamanho do nó (inteiro arredondado), e a diferença de
 * fração já basta. Um "sempre vermelho" não descreve defeito nenhum.
 *
 * O que este helper faz é remedir o **mesmo** texto, com o **mesmo** estilo, numa linha só e sem
 * limite de largura, e comparar com a largura que o layout de fato deu a ele. É literalmente a
 * pergunta do defeito: "cabe, ou é cortado?".
 */
fun SemanticsNodeInteraction.assertTextFits(label: String = "") {
    forEachTextLayout(label) { result, suffix ->
        val input = result.layoutInput
        val natural = TextMeasurer(
            defaultFontFamilyResolver = input.fontFamilyResolver,
            defaultDensity = input.density,
            defaultLayoutDirection = input.layoutDirection
        ).measure(
            text = input.text,
            style = input.style,
            overflow = TextOverflow.Clip,
            softWrap = false,
            maxLines = 1
        )
        assertTrue(
            "texto cortado$suffix: \"${input.text}\" precisa de ${natural.size.width}px e recebeu " +
                "${input.constraints.maxWidth}px",
            natural.size.width <= input.constraints.maxWidth
        )
    }
}

/** O texto deste nó não passou de [maxLines] linhas — nada de uma palavra por linha sem necessidade. */
fun SemanticsNodeInteraction.assertTextWithinLines(maxLines: Int, label: String = "") {
    forEachTextLayout(label) { result, suffix ->
        assertTrue(
            "texto em ${result.lineCount} linhas (máximo $maxLines)$suffix: \"${result.layoutInput.text}\"",
            result.lineCount <= maxLines
        )
    }
}

private fun SemanticsNodeInteraction.forEachTextLayout(
    label: String,
    block: (TextLayoutResult, String) -> Unit
) {
    val suffix = if (label.isEmpty()) "" else " ($label)"
    val node = fetchSemanticsNode()
    val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        ?: throw AssertionError("nó sem TextLayoutResult$suffix")
    val results = mutableListOf<TextLayoutResult>()
    action.action?.invoke(results)
    assertTrue("nenhum TextLayoutResult$suffix", results.isNotEmpty())
    results.forEach { block(it, suffix) }
}

/**
 * O nó está inteiro dentro da largura visível — nenhuma borda cai fora da viewport.
 *
 * É a asserção do chip "parcialmente visível na lateral" que o smoke test encontrou.
 */
fun SemanticsNodeInteraction.assertWithinViewportWidth(viewportWidthDp: Int, label: String = "") {
    val bounds = getBoundsInRoot()
    val suffix = if (label.isEmpty()) "" else " ($label)"
    assertTrue("borda esquerda fora da tela$suffix: ${bounds.left}", bounds.left.value >= -0.5f)
    assertTrue(
        "borda direita fora da tela$suffix: ${bounds.right} > ${viewportWidthDp}dp",
        bounds.right.value <= viewportWidthDp + 0.5f
    )
}

/** O nó está inteiro dentro da altura visível: um CTA fora dela é um CTA que não existe. */
fun SemanticsNodeInteraction.assertWithinViewportHeight(viewportHeightDp: Int, label: String = "") {
    val bounds = getBoundsInRoot()
    val suffix = if (label.isEmpty()) "" else " ($label)"
    assertTrue("topo fora da tela$suffix: ${bounds.top}", bounds.top.value >= -0.5f)
    assertTrue(
        "base fora da tela$suffix: ${bounds.bottom} > ${viewportHeightDp}dp",
        bounds.bottom.value <= viewportHeightDp + 0.5f
    )
}

/** Um alvo de toque utilizável. 48dp é o mínimo do Material, e o que a T19.H2 exige. */
fun SemanticsNodeInteraction.assertTouchTargetAtLeast(minDp: Int = 48, label: String = "") {
    val bounds = getBoundsInRoot()
    val suffix = if (label.isEmpty()) "" else " ($label)"
    assertTrue(
        "alvo de toque menor que ${minDp}dp$suffix: ${bounds.height}",
        bounds.height >= minDp.dp - 0.5.dp
    )
}
