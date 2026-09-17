package com.example.presentation.workouts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateWithSchedule
import com.example.domain.workout.template.WeekdaySchedule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import java.time.DayOfWeek
import com.example.ui.theme.*
import com.example.ui.components.SwipeAction
import com.example.ui.components.SwipeActionRow

import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.components.ActionBottomSheet
import com.example.ui.components.ActionItemData
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.presentation.friends.ShareWorkoutDialog
import com.example.presentation.friends.ShareWorkoutViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramDetailsScreen(
    viewModel: ProgramDetailsViewModel,
    onNavigateBack: () -> Unit,
    onTemplateClick: (Long) -> Unit,
    /**
     * O estado do diálogo de compartilhar programa (T19.3). `null` esconde a ação: um build sem
     * backend configurado monta esta tela inteira sem ela — como na tela do treino (T17.7).
     */
    shareViewModel: ShareWorkoutViewModel? = null
) {
    val program by viewModel.program.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    // A preferência de vibração vem da ViewModel: a tela não lê o `SettingsManager` do
    // `MainApplication` (§3).
    val hapticEnabled by viewModel.hapticEnabled.collectAsStateWithLifecycle()
    val shareBuildResult by viewModel.shareBuildResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    
    // O formulário de criar/editar treino vive na ViewModel (T19.8): o rascunho sobrevive à
    // recomposição e a validação é a do domínio.
    val templateForm by viewModel.templateForm.collectAsStateWithLifecycle()
    var templateToDelete by remember { mutableStateOf<WorkoutTemplateEntity?>(null) }
    var activeTemplateForSheet by remember { mutableStateOf<WorkoutTemplateWithSchedule?>(null) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(program?.name ?: "Detalhes do Programa", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                actions = {
                    // Compartilhar o programa inteiro com um amigo (T19.3). O snapshot é montado na
                    // ViewModel, a partir do Room; a política de exercício CUSTOM decide antes de
                    // existir oferta.
                    if (shareViewModel != null) {
                        val canShare = templates.isNotEmpty()
                        IconButton(
                            onClick = { viewModel.prepareShare() },
                            enabled = canShare
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Compartilhar programa",
                                tint = if (canShare) Lime400 else TextSecondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.openCreateTemplateForm() },
                containerColor = Lime400,
                contentColor = BackgroundDark
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Treino")
            }
        }
    ) { innerPadding ->
        val currentProgram = program
        if (currentProgram != null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    val p = currentProgram
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .padding(16.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = p.name, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            if (p.isCurrent) {
                                Text("PROGRAMA ATUAL", color = Emerald500, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Treinos", color = TextSecondary, fontSize = 12.sp)
                                Text("${templates.size}", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            if (!p.isCurrent) {
                                Button(
                                    onClick = { viewModel.setCurrentProgram() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("ATIVAR PROGRAMA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Treinos da Divisão", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                
                if (templates.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Nenhum treino cadastrado. Toque em + para adicionar.", color = TextSecondary)
                        }
                    }
                } else {
                    items(templates, key = { it.template.id }) { item ->
                        val template = item.template
                        val endAction = SwipeAction(
                            icon = Icons.Default.Delete,
                            label = "Excluir",
                            backgroundColor = Color.Red.copy(alpha = 0.8f),
                            contentColor = Color.White,
                            onTrigger = { templateToDelete = template }
                        )

                        SwipeActionRow(
                            endAction = endAction,
                            hapticEnabled = hapticEnabled,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(SurfaceDark)
                                    .clickable { onTemplateClick(template.id) }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(BackgroundDark),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = template.shortIdentifier ?: "A",
                                        color = Lime400,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = template.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    // Os dias do treino (T19.8): `Seg · Qui` para um treino que
                                    // acontece duas vezes na semana; nada quando não tem dia fixo.
                                    WeekdaySchedule.formatShort(item.scheduledDays)?.let { days ->
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(text = days, color = Lime400, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { activeTemplateForSheet = item }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Opções do treino", tint = TextSecondary)
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = "Abrir", tint = TextSecondary)
                                }
                            }
                        }
                    }
                }
                
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    val buildResult = shareBuildResult
    if (buildResult != null && shareViewModel != null) {
        ShareWorkoutDialog(
            buildResult = buildResult,
            viewModel = shareViewModel,
            onDismiss = {
                viewModel.dismissShare()
                shareViewModel.reset()
            },
            onShareSuccess = {
                viewModel.dismissShare()
                shareViewModel.reset()
                snackbarScope.launch {
                    snackbarHostState.showSnackbar("Programa compartilhado com sucesso!")
                }
            }
        )
    }

    val sheetTemplate = activeTemplateForSheet
    if (sheetTemplate != null) {
        val template = sheetTemplate.template
        ActionBottomSheet(
            onDismissRequest = { activeTemplateForSheet = null },
            title = stringResource(id = R.string.sheet_template_options),
            subtitle = template.name,
            actions = listOf(
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_open_template),
                    icon = Icons.Default.ChevronRight,
                    onClick = { onTemplateClick(template.id) }
                ),
                // Editar nome, sigla e dias (T19.8) — o mesmo template, em todos os dias dele.
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_edit_template),
                    icon = Icons.Default.Edit,
                    onClick = { viewModel.openEditTemplateForm(sheetTemplate) }
                ),
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_delete_template),
                    icon = Icons.Default.Delete,
                    destructive = true,
                    onClick = { templateToDelete = template }
                )
            )
        )
    }

    if (templateToDelete != null) {
        AlertDialog(
            onDismissRequest = { templateToDelete = null },
            title = { Text("Excluir Treino", color = TextPrimary) },
            text = { Text("Deseja realmente remover o treino '${templateToDelete?.name}'?", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    templateToDelete?.let { viewModel.deleteTemplate(it) }
                    templateToDelete = null
                }) { Text("Excluir", color = Color.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { templateToDelete = null }) { Text("Cancelar", color = TextSecondary) }
            },
            containerColor = SurfaceDark
        )
    }

    val form = templateForm
    if (form != null) {
        TemplateFormDialog(
            form = form,
            onNameChanged = viewModel::onTemplateNameChanged,
            onShortIdChanged = viewModel::onTemplateShortIdChanged,
            onToggleDay = viewModel::toggleTemplateDay,
            onSubmit = viewModel::submitTemplateForm,
            onDismiss = viewModel::dismissTemplateForm
        )
    }
}

/**
 * O formulário de treino (T19.8): criar e editar são o **mesmo** diálogo.
 *
 * Obrigatório é o que o domínio exige (`WorkoutTemplateFields`): só o nome, marcado com `*`. Sigla
 * e dias são opcionais e não parecem obrigatórios. Os dias são chips de seleção múltipla, sem um
 * "Nenhum" concorrente — nenhum chip ligado **é** "sem dia fixo", e a legenda diz isso.
 */
@Composable
private fun TemplateFormDialog(
    form: TemplateFormState,
    onNameChanged: (String) -> Unit,
    onShortIdChanged: (String) -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = BackgroundDark,
        unfocusedContainerColor = BackgroundDark,
        errorContainerColor = BackgroundDark,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        errorTextColor = TextPrimary
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.isEditing) "Editar Treino" else "Novo Treino", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = onNameChanged,
                    label = { Text("Nome *") },
                    placeholder = { Text("ex: Peito e Tríceps") },
                    singleLine = true,
                    isError = form.nameError,
                    supportingText = if (form.nameError) {
                        { Text("Informe o nome do treino.") }
                    } else null,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().testTag("template_form_name")
                )
                OutlinedTextField(
                    value = form.shortId,
                    onValueChange = onShortIdChanged,
                    label = { Text("Sigla") },
                    placeholder = { Text("ex: A, B") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth().testTag("template_form_short_id")
                )
                Text("Dias da semana", color = TextSecondary, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DayOfWeek.entries.forEach { day ->
                        val isSelected = day in form.scheduledDays
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Lime400 else BackgroundDark)
                                .toggleable(
                                    value = isSelected,
                                    role = Role.Checkbox,
                                    onValueChange = { onToggleDay(day) }
                                )
                                .padding(vertical = 8.dp)
                                .testTag("template_form_day_${day.name}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = WeekdaySchedule.shortLabel(day),
                                color = if (isSelected) BackgroundDark else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = if (form.scheduledDays.isEmpty()) "Nenhum dia selecionado: treino sem dia fixo."
                    else "Campos com * são obrigatórios.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, modifier = Modifier.testTag("template_form_submit")) {
                Text(if (form.isEditing) "Salvar" else "Criar", color = Lime400, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        },
        containerColor = SurfaceDark
    )
}
