package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * A folha padrão do Spark.
 *
 * ## Conteúdo alto (H2.4)
 *
 * Por omissão o conteúdo não rola: a folha cresce com ele, e `ModalBottomSheet` a limita à altura
 * da tela — o que passar disso fica **fora do alcance**, sem barra de rolagem e sem aviso. Foi
 * exatamente o que aconteceu com a Meta Semanal: sete opções, o aviso de vigência e o botão de
 * salvar passavam de 800dp, e em aparelhos de tela menor o botão simplesmente não existia para o
 * usuário.
 *
 * [scrollableContent] liga a rolagem **do miolo**, e [footer] fica fixo abaixo dele. A combinação
 * é o que garante que o CTA esteja sempre alcançável, em qualquer altura de tela e em qualquer
 * `fontScale`. Continua sendo opt-in: uma folha cujo conteúdo já é uma lista rolável (`LazyColumn`)
 * não pode ser embrulhada num `verticalScroll`, e todas as folhas existentes seguem como estavam.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollableContent: Boolean = false,
    /**
     * A folha de conteúdo alto abre **inteira**.
     *
     * `ModalBottomSheet` abre no estado "meio aberto" quando o conteúdo passa de metade da tela, e
     * é justamente aí que o rodapé — o CTA — fica abaixo da borda. Não é rolagem que resolve: o
     * que está fora é a folha, não o conteúdo dela. Uma folha curta não chega a usar esse estado,
     * então nada muda para as demais.
     */
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = scrollableContent),
    title: String? = null,
    subtitle: String? = null,
    headerRightContent: @Composable (RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            if (title != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                color = Lime400,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (headerRightContent != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            headerRightContent()
                        }
                    }
                }
            }
            if (scrollableContent) {
                // `fill = false`: o miolo ocupa o que precisa e só cede ao chegar no teto da
                // folha — uma folha curta continua curta, e não passa a ocupar a tela inteira.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                }
            } else {
                content()
            }
            footer?.invoke(this)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun BottomSheetActionItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    selected: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val contentColor = when {
        !enabled -> TextSecondary.copy(alpha = 0.4f)
        destructive -> Red500
        selected -> Lime400
        else -> TextPrimary
    }
    val iconColor = when {
        !enabled -> TextSecondary.copy(alpha = 0.4f)
        destructive -> Red500
        selected -> Lime400
        else -> Lime400
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$title${if (subtitle != null) ", $subtitle" else ""}${if (selected) ", selecionado" else ""}"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = contentColor,
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.4f),
                        fontSize = 12.sp
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selecionado",
                    tint = Lime400,
                    modifier = Modifier.size(20.dp)
                )
            } else if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

data class ActionItemData(
    val title: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val selected: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionBottomSheet(
    onDismissRequest: () -> Unit,
    title: String,
    actions: List<ActionItemData>,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    AppModalBottomSheet(
        onDismissRequest = onDismissRequest,
        title = title,
        subtitle = subtitle,
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            actions.forEach { action ->
                BottomSheetActionItem(
                    title = action.title,
                    subtitle = action.subtitle,
                    icon = action.icon,
                    enabled = action.enabled,
                    destructive = action.destructive,
                    selected = action.selected,
                    onClick = {
                        onDismissRequest()
                        action.onClick()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectionBottomSheet(
    title: String,
    options: List<T>,
    selectedOption: T?,
    optionTitle: (T) -> String,
    onOptionSelected: (T) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    optionSubtitle: ((T) -> String?)? = null,
    optionIcon: ((T) -> ImageVector?)? = null
) {
    AppModalBottomSheet(
        onDismissRequest = onDismissRequest,
        title = title,
        subtitle = subtitle,
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                BottomSheetActionItem(
                    title = optionTitle(option),
                    subtitle = optionSubtitle?.invoke(option),
                    icon = optionIcon?.invoke(option),
                    selected = isSelected,
                    onClick = {
                        onDismissRequest()
                        onOptionSelected(option)
                    }
                )
            }
        }
    }
}
