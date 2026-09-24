package com.example.presentation.friends

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Lime400
import com.example.ui.theme.TextPrimary

/** O rótulo acessível do botão parado. */
const val REFRESH_ACTION_LABEL = "Atualizar"

/** O rótulo acessível enquanto a releitura está em voo. */
const val REFRESHING_ACTION_LABEL = "Atualizando"

/** A tag de teste do botão — uma só para todas as telas sociais. */
const val SOCIAL_REFRESH_ACTION_TAG = "social_refresh_action"

/**
 * O "↻" das telas sociais (T19.H3 §3–§5).
 *
 * ```text
 * ← Título                         ↻
 * ```
 *
 * ## Por que um botão, se já havia gesto
 *
 * O pull-to-refresh existia em quatro telas e não era descobrível: quem não sabia que devia puxar
 * a tela só via um pedido novo reiniciando o app. O gesto continua onde existia; este botão é a
 * forma **visível** da mesma ação. Os dois chamam o **mesmo** método da ViewModel — não há dois
 * mecanismos de atualização, e não há leitura extra escondida aqui.
 *
 * ## O que ele não é
 *
 * Não é polling, não é timer, não é "atualizar ao voltar para a tela" (§10). Só um toque pede ao
 * servidor, e só uma leitura: nada é publicado, sincronizado ou alterado.
 *
 * ## Estados
 *
 * Parado, ícone de atualizar. Atualizando, um indicador de progresso **no lugar** do ícone e o
 * botão desabilitado — um segundo toque durante a releitura não sai (a ViewModel também ignora).
 * O alvo de toque tem 48dp (§5).
 */
@Composable
fun SocialRefreshAction(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    enabled: Boolean = true
) {
    val label = if (isRefreshing) REFRESHING_ACTION_LABEL else REFRESH_ACTION_LABEL
    IconButton(
        onClick = onRefresh,
        enabled = enabled && !isRefreshing,
        // 48dp **explícitos** (§5): o `IconButton` do Material3 desenha 40dp e só expande o alvo
        // de toque por um modificador que não aparece nos limites do nó — e o requisito é o alvo,
        // não o desenho. Fixar o tamanho aqui torna a garantia verificável.
        modifier = Modifier
            .size(48.dp)
            .testTag(SOCIAL_REFRESH_ACTION_TAG)
            .semantics { contentDescription = label }
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                color = Lime400,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else {
            // A descrição mora no botão (acima): repeti-la no ícone faria o leitor de tela dizer
            // "Atualizar" duas vezes.
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, tint = TextPrimary)
        }
    }
}
