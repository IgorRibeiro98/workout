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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.ai.model.EquipmentAvailability
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.GeneratedWorkoutDraftExercise
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.ai.model.WorkoutGoal
import com.example.domain.engine.MuscleGroup
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
internal data class GenerateWorkoutActions(
    val onGoalChange: (WorkoutGoal) -> Unit = {},
    val onDurationChange: (Int) -> Unit = {},
    val onToggleFocus: (MuscleGroup) -> Unit = {},
    val onToggleEquipment: (EquipmentAvailability) -> Unit = {},
    val onToggleExclusion: (String) -> Unit = {},
    val onNotesChange: (String) -> Unit = {},
    val onGenerate: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onRemoveExercise: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onOpenTemplate: (Long) -> Unit = {},
    val onNavigateBack: () -> Unit = {},
    /** Entrada contextual: explicar a proposta na tela. Não recebe texto, não recebe domínio. */
    val onExplainDraft: () -> Unit = {},
    val canExplain: Boolean = false
)

/**
 * Criar treino com IA: configuração estruturada, proposta e confirmação explícita.
 *
 * A tela não chama o provider ao abrir, ao girar ou ao recompor. Enquanto não houver toque em
 * "Salvar treino", nada foi persistido — sair daqui simplesmente descarta a proposta.
 */
@Composable
fun GenerateWorkoutScreen(
    viewModel: GenerateWorkoutViewModel,
    onNavigateBack: () -> Unit,
    onOpenTemplate: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val explanationState by viewModel.explanationState.collectAsState()

    // Só carrega o recorte local do catálogo (Room). Nenhuma chamada ao modelo acontece aqui.
    LaunchedEffect(Unit) { viewModel.refreshCandidates() }

    GenerateWorkoutScreenContent(
        uiState = uiState,
        actions = GenerateWorkoutActions(
            onGoalChange = viewModel::setGoal,
            onDurationChange = viewModel::setDuration,
            onToggleFocus = viewModel::toggleFocus,
            onToggleEquipment = viewModel::toggleEquipment,
            onToggleExclusion = viewModel::toggleExclusion,
            onNotesChange = viewModel::setNotes,
            onGenerate = viewModel::generate,
            onDiscard = viewModel::discardDraft,
            onRemoveExercise = viewModel::removeExerciseFromDraft,
            onSave = viewModel::save,
            onReset = viewModel::reset,
            onOpenTemplate = onOpenTemplate,
            onNavigateBack = onNavigateBack,
            onExplainDraft = viewModel::explainDraft,
            canExplain = viewModel.canExplain
        )
    )

    CoachExplanationSheet(
        state = explanationState,
        onDismiss = viewModel::dismissExplanation
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerateWorkoutScreenContent(
    uiState: GenerateWorkoutUiState,
    actions: GenerateWorkoutActions
) {
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Criar treino com IA",
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
                text = "O Coach monta uma proposta com exercícios do seu catálogo. " +
                    "Nada é salvo até você confirmar.",
                color = TextSecondary,
                fontSize = 13.sp
            )

            ConfigurationSection(uiState, actions)

            Button(
                onClick = actions.onGenerate,
                enabled = uiState.canGenerate,
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
                    text = if (uiState.status is GenerateWorkoutStatus.Draft) {
                        "  Gerar novamente"
                    } else {
                        "  Gerar treino"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            when (val status = uiState.status) {
                GenerateWorkoutStatus.Idle -> Unit

                GenerateWorkoutStatus.Generating -> LoadingBlock("Montando seu treino...")

                is GenerateWorkoutStatus.Draft -> DraftSection(
                    draft = status.draft,
                    isSaving = false,
                    actions = actions
                )

                is GenerateWorkoutStatus.Saving -> DraftSection(
                    draft = status.draft,
                    isSaving = true,
                    actions = actions
                )

                is GenerateWorkoutStatus.Saved -> SavedCard(status, actions)

                is GenerateWorkoutStatus.Message -> MessageCard(
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
private fun ConfigurationSection(
    uiState: GenerateWorkoutUiState,
    actions: GenerateWorkoutActions
) {
    SectionTitle("Objetivo")
    ChipRow {
        WorkoutGoal.entries.forEach { goal ->
            SelectableChip(
                label = goal.label,
                selected = goal == uiState.goal,
                enabled = !uiState.isBusy,
                onClick = { actions.onGoalChange(goal) }
            )
        }
    }

    SectionTitle("Duração")
    ChipRow {
        WorkoutGenerationPreferences.DURATION_OPTIONS.forEach { minutes ->
            SelectableChip(
                label = "$minutes min",
                selected = minutes == uiState.durationMinutes,
                enabled = !uiState.isBusy,
                onClick = { actions.onDurationChange(minutes) }
            )
        }
    }

    SectionTitle("Foco")
    Text(
        text = "Escolha de 1 a ${com.example.domain.ai.AiModelConfig.MAX_FOCUS_MUSCLE_GROUPS} grupos musculares.",
        color = TextTertiary,
        fontSize = 11.sp
    )
    ChipRow {
        WorkoutGenerationPreferences.SELECTABLE_FOCUS_GROUPS.forEach { group ->
            SelectableChip(
                label = group.displayName,
                selected = group in uiState.focusMuscleGroups,
                enabled = !uiState.isBusy,
                onClick = { actions.onToggleFocus(group) }
            )
        }
    }

    SectionTitle("Equipamentos")
    ChipRow {
        EquipmentAvailability.entries.forEach { equipment ->
            SelectableChip(
                label = equipment.label,
                selected = equipment in uiState.availableEquipment,
                enabled = !uiState.isBusy,
                onClick = { actions.onToggleEquipment(equipment) }
            )
        }
    }

    CandidateSection(uiState, actions)

    SectionTitle("Observações (opcional)")
    OutlinedTextField(
        value = uiState.notes,
        onValueChange = actions.onNotesChange,
        enabled = !uiState.isBusy,
        placeholder = {
            Text(text = "Ex.: prefiro terminar com abdômen", fontSize = 12.sp, color = TextTertiary)
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Lime400,
            unfocusedBorderColor = BorderLight,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * O que o pedido alcança no catálogo, e a exclusão explícita.
 *
 * Tudo aqui é leitura local: abrir a lista, marcar ou desmarcar não custa nenhuma chamada.
 */
@Composable
private fun CandidateSection(
    uiState: GenerateWorkoutUiState,
    actions: GenerateWorkoutActions
) {
    var expanded by remember { mutableStateOf(false) }

    SectionTitle("Exercícios candidatos")
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when {
                    uiState.focusMuscleGroups.isEmpty() -> "Escolha o foco para ver os exercícios disponíveis."
                    uiState.candidateCount == 0 ->
                        "Nenhum exercício do catálogo atende a esse foco com esses equipamentos."
                    uiState.candidateCount == 1 -> "1 exercício será enviado ao Coach."
                    else -> "${uiState.candidateCount} exercícios serão enviados ao Coach."
                },
                color = if (uiState.candidateCount == 0) Orange400 else TextSecondary,
                fontSize = 13.sp
            )

            if (uiState.candidates.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) "Ocultar lista" else "Excluir exercícios",
                        color = Lime400,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (expanded) {
                    // Sem scroll próprio: a lista tem no máximo
                    // AiModelConfig.MAX_CANDIDATE_EXERCISES itens e rola junto com a tela.
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        uiState.candidates.forEach { candidate ->
                            val excluded = candidate.exerciseId in uiState.excludedExerciseIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !uiState.isBusy) {
                                        actions.onToggleExclusion(candidate.exerciseId)
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (excluded) "✕" else "•",
                                    color = if (excluded) Red400 else Lime400,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.name,
                                        color = if (excluded) TextTertiary else TextPrimary,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = listOfNotNull(candidate.muscleGroup, candidate.equipment)
                                            .joinToString(" • "),
                                        color = TextTertiary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftSection(
    draft: GeneratedWorkoutDraft,
    isSaving: Boolean,
    actions: GenerateWorkoutActions
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Treino sugerido")
        Text(
            text = draft.name,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Text(
            text = "Proposta ainda não salva. Nada foi criado nos seus treinos.",
            color = TextTertiary,
            fontSize = 11.sp
        )

        draft.exercises.forEach { exercise ->
            DraftExerciseCard(exercise, enabled = !isSaving, onRemove = actions.onRemoveExercise)
        }

        if (draft.explanation.isNotBlank()) {
            SectionTitle("Por que esse treino?")
            Card {
                Text(text = draft.explanation, color = TextSecondary, fontSize = 13.sp)
            }
        }

        if (actions.canExplain) {
            CoachExplanationTrigger(
                text = "Como isso foi decidido?",
                enabled = !isSaving,
                onClick = actions.onExplainDraft
            )
        }

        if (isSaving) {
            LoadingBlock("Salvando treino...")
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
                    onClick = actions.onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lime400,
                        contentColor = BackgroundDark
                    )
                ) {
                    Text(text = "Salvar treino", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun DraftExerciseCard(
    exercise: GeneratedWorkoutDraftExercise,
    enabled: Boolean,
    onRemove: (String) -> Unit
) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "${exercise.sortOrder + 1}.",
                    color = Lime400,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = exercise.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = buildString {
                    append("${exercise.sets} × ${repsLabel(exercise.minReps, exercise.maxReps)}")
                    exercise.weightKg?.let { append(" • ${formatWeight(it)} kg") }
                    append(" • descanso ${exercise.restSeconds} s")
                },
                color = TextSecondary,
                fontSize = 13.sp
            )
            if (exercise.reason.isNotBlank()) {
                Text(text = exercise.reason, color = TextTertiary, fontSize = 12.sp)
            }
            TextButton(
                onClick = { onRemove(exercise.exerciseId) },
                enabled = enabled
            ) {
                Text(text = "Remover do rascunho", color = Red400, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SavedCard(status: GenerateWorkoutStatus.Saved, actions: GenerateWorkoutActions) {
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
                    text = "\"${status.name}\" foi criado nos seus treinos.",
                    color = TextPrimary,
                    fontSize = 14.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = actions.onReset,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark,
                        contentColor = TextSecondary
                    )
                ) {
                    Text(text = "Gerar outro", fontSize = 14.sp)
                }
                Button(
                    onClick = { actions.onOpenTemplate(status.templateId) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lime400,
                        contentColor = BackgroundDark
                    )
                ) {
                    Text(text = "Editar treino", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

private fun repsLabel(minReps: Int, maxReps: Int): String =
    if (minReps == maxReps) "$minReps" else "$minReps–$maxReps"

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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        content()
    }
}

@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Lime400 else SurfaceDark)
            .border(
                width = 1.dp,
                color = if (selected) Lime400 else BorderLight,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) BackgroundDark else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
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
