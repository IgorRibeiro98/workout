package com.example.presentation.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.WorkoutAdaptationChange
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Orange400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/** As ações da tela, agrupadas para a assinatura do conteúdo continuar legível. */
internal data class AdaptWorkoutActions(
    val onAdapt: () -> Unit = {},
    val onToggleChange: (String) -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onApply: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onNavigateBack: () -> Unit = {}
)

/**
 * Adaptar um treino existente com o Coach: sugestões com evidência, aceitas uma a uma.
 *
 * A tela não chama o provider ao abrir, ao girar ou ao recompor. Enquanto não houver toque em
 * "Aplicar", o treino continua exatamente como está — sair daqui descarta a proposta.
 */
@Composable
fun AdaptWorkoutScreen(
    viewModel: AdaptWorkoutViewModel,
    templateId: Long,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Só informa qual treino está aberto. Nenhuma chamada ao modelo acontece aqui.
    LaunchedEffect(templateId) { viewModel.load(templateId) }

    AdaptWorkoutScreenContent(
        uiState = uiState,
        actions = AdaptWorkoutActions(
            onAdapt = viewModel::adapt,
            onToggleChange = viewModel::toggleChange,
            onDiscard = viewModel::discard,
            onApply = viewModel::apply,
            onReset = viewModel::reset,
            onNavigateBack = onNavigateBack
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdaptWorkoutScreenContent(
    uiState: AdaptWorkoutUiState,
    actions: AdaptWorkoutActions
) {
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Adaptar com Coach IA",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "O Coach compara o seu treino com o que você realmente executou e sugere " +
                    "ajustes. Você aceita uma a uma; nada muda sem a sua confirmação.",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Button(
                onClick = actions.onAdapt,
                enabled = uiState.canAdapt,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime400,
                    contentColor = BackgroundDark,
                    disabledContainerColor = SurfaceDark,
                    disabledContentColor = TextTertiary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (uiState.status is AdaptWorkoutStatus.Draft) {
                        "  Analisar novamente"
                    } else {
                        "  Adaptar meu treino"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            when (val status = uiState.status) {
                AdaptWorkoutStatus.Idle -> Unit

                AdaptWorkoutStatus.Generating -> LoadingBlock("Analisando seu treino...")

                is AdaptWorkoutStatus.Draft -> DraftSection(
                    draft = status.draft,
                    selectedIds = uiState.selectedChangeIds,
                    selectedCount = uiState.selectedCount,
                    canApply = uiState.canApply,
                    isApplying = false,
                    actions = actions
                )

                is AdaptWorkoutStatus.Applying -> DraftSection(
                    draft = status.draft,
                    selectedIds = uiState.selectedChangeIds,
                    selectedCount = uiState.selectedCount,
                    canApply = false,
                    isApplying = true,
                    actions = actions
                )

                is AdaptWorkoutStatus.Applied -> AppliedCard(status.appliedChanges, actions)

                is AdaptWorkoutStatus.NoChanges -> Card {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Nada a mudar por enquanto",
                            color = Lime400,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(text = status.summary, color = TextPrimary, fontSize = 14.sp)
                        DataQualityLine(status.dataQuality.level, status.dataQuality.description)
                    }
                }

                is AdaptWorkoutStatus.Message -> MessageCard(
                    message = if (status.canRetry) {
                        "${status.text} Você pode tentar novamente."
                    } else {
                        status.text
                    },
                    accent = if (status.isWarning) Orange400 else Red400,
                    isWarning = status.isWarning
                )
            }
        }
    }
}

@Composable
private fun DraftSection(
    draft: WorkoutAdaptationDraft,
    selectedIds: Set<String>,
    selectedCount: Int,
    canApply: Boolean,
    isApplying: Boolean,
    actions: AdaptWorkoutActions
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Sugestões para ${draft.templateName}")
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = draft.summary, color = TextPrimary, fontSize = 14.sp)
                DataQualityLine(draft.dataQuality.level, draft.dataQuality.description)
            }
        }
        Text(
            text = "Nada foi alterado ainda. Marque o que você quer aplicar.",
            color = TextTertiary,
            fontSize = 11.sp
        )

        draft.changes.forEach { change ->
            ChangeCard(
                change = change,
                selected = change.id in selectedIds,
                enabled = !isApplying,
                onToggle = actions.onToggleChange
            )
        }

        Text(
            text = when (selectedCount) {
                0 -> "Nenhuma alteração selecionada"
                1 -> "1 alteração selecionada"
                else -> "$selectedCount alterações selecionadas"
            },
            color = if (selectedCount == 0) TextTertiary else Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )

        if (isApplying) {
            LoadingBlock("Aplicando alterações...")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = actions.onDiscard,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark,
                        contentColor = TextSecondary
                    )
                ) {
                    Text(text = "Descartar", fontSize = 14.sp)
                }
                Button(
                    onClick = actions.onApply,
                    enabled = canApply,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lime400,
                        contentColor = BackgroundDark,
                        disabledContainerColor = SurfaceDark,
                        disabledContentColor = TextTertiary
                    )
                ) {
                    Text(
                        text = if (selectedCount > 0) "Aplicar $selectedCount" else "Aplicar",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeCard(
    change: WorkoutAdaptationChange,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(
                width = 1.dp,
                color = if (selected) Lime400 else BorderLight,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled) { onToggle(change.id) }
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                SelectionMark(selected)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = change.type.label,
                        color = Lime400,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = change.exerciseName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            // ANTES -> DEPOIS: o usuário precisa ver o que muda, não só que "vai melhorar".
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                ValueColumn("Atual", change.currentValue.label(), TextSecondary)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "vira",
                    tint = TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
                ValueColumn("Sugestão", change.suggestedValue.label(), Lime400)
            }

            if (change.type == WorkoutAdaptationType.REPLACE_EXERCISE) {
                Text(
                    text = "A carga planejada é limpa na troca: ela pertencia ao exercício anterior.",
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }

            Text(text = change.reason, color = TextPrimary, fontSize = 13.sp)
            Text(text = "Base: ${change.evidence}", color = TextSecondary, fontSize = 12.sp)
            Text(
                text = "Confiança: ${(change.confidence * 100).toInt().coerceIn(0, 100)}%",
                color = TextTertiary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ValueColumn(label: String, value: String, valueColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, color = TextTertiary, fontSize = 11.sp)
        Text(text = value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Lime400 else BackgroundDark)
            .border(
                width = 1.dp,
                color = if (selected) Lime400 else BorderLight,
                shape = RoundedCornerShape(6.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Text(text = "✓", color = BackgroundDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AppliedCard(appliedChanges: Int, actions: AdaptWorkoutActions) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Lime400,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = when (appliedChanges) {
                        1 -> "1 alteração aplicada ao treino."
                        else -> "$appliedChanges alterações aplicadas ao treino."
                    },
                    color = TextPrimary,
                    fontSize = 14.sp
                )
            }
            Text(
                text = "Vale para as próximas execuções. Os treinos que você já concluiu " +
                    "continuam registrados como foram feitos.",
                color = TextTertiary,
                fontSize = 11.sp
            )
            Button(
                onClick = actions.onNavigateBack,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime400,
                    contentColor = BackgroundDark
                )
            ) {
                Text(text = "Ver treino atualizado", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun DataQualityLine(level: AiDataQualityLevel, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = level.label(),
            color = level.accentColor(),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        if (description.isNotBlank()) {
            Text(text = description, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

private fun AiDataQualityLevel.label(): String = when (this) {
    AiDataQualityLevel.INSUFFICIENT -> "Dados insuficientes"
    AiDataQualityLevel.LIMITED -> "Dados limitados"
    AiDataQualityLevel.GOOD -> "Dados suficientes"
}

private fun AiDataQualityLevel.accentColor(): Color = when (this) {
    AiDataQualityLevel.INSUFFICIENT -> Red400
    AiDataQualityLevel.LIMITED -> Orange400
    AiDataQualityLevel.GOOD -> Lime400
}

/** ANTES e DEPOIS legíveis, no formato que o treino realmente usa. */
private fun WorkoutAdaptationValue.label(): String = when (this) {
    is WorkoutAdaptationValue.Load ->
        weightKg?.let { "${formatWeight(it)} kg" } ?: "sem carga definida"

    is WorkoutAdaptationValue.Reps ->
        if (minReps == maxReps) "$minReps reps" else "$minReps–$maxReps reps"

    is WorkoutAdaptationValue.Sets -> if (sets == 1) "1 série" else "$sets séries"
    is WorkoutAdaptationValue.Rest -> "$restSeconds s"
    is WorkoutAdaptationValue.Exercise -> name
}

private fun formatWeight(weightKg: Float): String =
    if (weightKg % 1f == 0f) weightKg.toInt().toString() else String.format("%.1f", weightKg)

@Composable
private fun LoadingBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = Lime400, strokeWidth = 3.dp)
        Text(text = message, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun MessageCard(message: String, accent: Color, isWarning: Boolean) {
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (isWarning) Icons.Default.CloudOff else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Text(text = message, color = TextSecondary, fontSize = 13.sp)
        }
    }
}
