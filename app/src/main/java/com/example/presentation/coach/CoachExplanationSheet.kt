package com.example.presentation.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Orange400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * A entrada contextual do Coach: um botão pequeno, não um CTA invasivo.
 *
 * Ela não dispara nada sozinha — quem chama passa [onClick], e só o toque leva à explicação.
 */
@Composable
internal fun CoachExplanationTrigger(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp,
            vertical = 2.dp
        )
    ) {
        Icon(
            imageVector = Icons.Outlined.HelpOutline,
            contentDescription = null,
            tint = if (enabled) Lime400 else TextTertiary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "  $text",
            color = if (enabled) Lime400 else TextTertiary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

/**
 * A apresentação leve de uma explicação: texto, dados considerados e limitações.
 *
 * A folha é a mesma nas quatro origens para o usuário reconhecer o formato: o que muda é o
 * conteúdo, não a estrutura. Quando o texto veio do modelo, isso é dito discretamente — sem
 * expor provider, modelo ou detalhe técnico.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoachExplanationSheet(
    state: CoachExplanationUiState,
    onDismiss: () -> Unit
) {
    if (state is CoachExplanationUiState.Hidden) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundDark,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (state) {
                CoachExplanationUiState.Hidden -> Unit

                CoachExplanationUiState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 24.dp)
                ) {
                    CircularProgressIndicator(
                        color = Lime400,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    // Uma etapa só, porque é isso que está acontecendo de verdade.
                    Text(text = "Explicando...", color = TextSecondary, fontSize = 14.sp)
                }

                is CoachExplanationUiState.Message -> Text(
                    text = state.text,
                    color = TextSecondary,
                    fontSize = 14.sp
                )

                is CoachExplanationUiState.Ready -> ExplanationBody(state)
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceDark,
                    contentColor = TextPrimary
                )
            ) {
                // Fechar sempre funciona, inclusive durante o carregamento: nenhuma folha fica
                // presa esperando o provider.
                Text(text = if (state is CoachExplanationUiState.Loading) "Cancelar" else "Fechar", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ExplanationBody(state: CoachExplanationUiState.Ready) {
    Text(
        text = state.title,
        color = TextPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp
    )

    Text(text = state.explanation, color = TextSecondary, fontSize = 14.sp)

    if (state.evidenceItems.isNotEmpty()) {
        SheetSectionTitle("Dados considerados")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
                .background(SurfaceDark, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            state.evidenceItems.forEach { item ->
                Text(text = "• $item", color = TextSecondary, fontSize = 13.sp)
            }
        }
    }

    // Limitação não é rodapé escondido: quando existe, ela aparece com o mesmo peso do resto.
    if (state.limitations.isNotEmpty()) {
        SheetSectionTitle("Limitações", color = Orange400)
        state.limitations.forEach { limitation ->
            Text(text = "• $limitation", color = TextSecondary, fontSize = 13.sp)
        }
    }

    if (state.fromModel) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(12.dp)
            )
            Text(text = "Explicação do Coach IA", color = TextTertiary, fontSize = 11.sp)
        }
    } else {
        Text(
            text = "Explicação montada pelo Spark com os dados do seu app.",
            color = TextTertiary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SheetSectionTitle(text: String, color: androidx.compose.ui.graphics.Color = Lime400) {
    Text(text = text, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
}
