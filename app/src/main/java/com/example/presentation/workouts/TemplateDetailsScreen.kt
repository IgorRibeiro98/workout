package com.example.presentation.workouts
import com.example.presentation.workouts.ResolvedTemplateExercise

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
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
import com.example.ui.theme.*
import com.example.domain.engine.ExerciseSearchEngine
import com.example.domain.engine.MuscleVisualResolver
import com.example.ui.components.SwipeAction
import com.example.ui.components.SwipeActionRow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.presentation.friends.ShareWorkoutDialog
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.components.ActionBottomSheet
import com.example.ui.components.ActionItemData
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDetailsScreen(
    viewModel: TemplateDetailsViewModel,
    onBack: () -> Unit,
    onAdaptWithCoach: () -> Unit = {},
    /**
     * O estado do diálogo de compartilhar (T17.7). `null` esconde a ação: um build sem backend
     * configurado monta esta tela inteira sem ela.
     */
    shareViewModel: com.example.presentation.friends.ShareWorkoutViewModel? = null
) {
    val template by viewModel.template.collectAsStateWithLifecycle()
    val exercises by viewModel.exercises.collectAsStateWithLifecycle()
    val rawExercises by viewModel.rawExercises.collectAsStateWithLifecycle()
    val allExercises by viewModel.allExercises.collectAsStateWithLifecycle()
    // A preferência de vibração vem da ViewModel: a tela não lê o `SettingsManager` do
    // `MainApplication` (§3).
    val hapticEnabled by viewModel.hapticEnabled.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    var showShareDialog by remember { mutableStateOf(false) }
    var showAddPickerSheet by remember { mutableStateOf(false) }
    var exerciseToEdit by remember { mutableStateOf<ResolvedTemplateExercise?>(null) }
    var activeExerciseActionSheet by remember { mutableStateOf<ResolvedTemplateExercise?>(null) }
    var exerciseToDelete by remember { mutableStateOf<ResolvedTemplateExercise?>(null) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(template?.name ?: "Treino", color = TextPrimary, fontWeight = FontWeight.Bold)
                        val dayOfWeek = template?.dayOfWeek
                        if (!dayOfWeek.isNullOrEmpty()) {
                            Text(dayOfWeek, color = Lime400, fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                actions = {
                    // Compartilhar treino com amigos (T17.7)
                    val canShare = exercises.isNotEmpty() && shareViewModel != null
                    IconButton(
                        onClick = { showShareDialog = true },
                        enabled = canShare
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartilhar treino",
                            tint = if (canShare) Lime400 else TextSecondary
                        )
                    }
                    // O Coach entra a partir do treino aberto; ele sugere, quem altera é o usuário.
                    IconButton(onClick = onAdaptWithCoach, enabled = exercises.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Adaptar com Coach IA",
                            tint = if (exercises.isNotEmpty()) Lime400 else TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddPickerSheet = true },
                containerColor = Lime400,
                contentColor = BackgroundDark
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Exercícios")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            
            if (exercises.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Nenhum exercício neste treino. Toque em + para adicionar.", color = TextSecondary)
                    }
                }
            }
            
            items(exercises, key = { it.templateExercise.id }) { templateExerciseDetails ->
                val endAction = SwipeAction(
                    icon = Icons.Default.Delete,
                    label = "Excluir",
                    backgroundColor = Color.Red.copy(alpha = 0.8f),
                    contentColor = Color.White,
                    onTrigger = { exerciseToDelete = templateExerciseDetails }
                )

                SwipeActionRow(
                    endAction = endAction,
                    hapticEnabled = hapticEnabled,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                ) {
                    val group = MuscleVisualResolver.resolveGroup(templateExerciseDetails.resolvedExercise.primaryMuscle)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .clickable { activeExerciseActionSheet = templateExerciseDetails }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BackgroundDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = group.icon,
                                contentDescription = null,
                                tint = group.color,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = templateExerciseDetails.resolvedExercise.displayName,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val machineText = templateExerciseDetails.templateExercise.machineLabel?.let { " • $it" } ?: ""
                            val weightText = templateExerciseDetails.templateExercise.plannedWeight?.let { " • ${it}kg" } ?: ""
                            val restText = " • ${templateExerciseDetails.templateExercise.restDurationSeconds}s descanso"
                            Text(
                                text = "${templateExerciseDetails.templateExercise.targetSets} séries · ${templateExerciseDetails.templateExercise.minReps}-${templateExerciseDetails.templateExercise.maxReps} reps$weightText$machineText$restText",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            if (!templateExerciseDetails.templateExercise.notes.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Obs: ${templateExerciseDetails.templateExercise.notes}",
                                    color = TextTertiary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        IconButton(onClick = { activeExerciseActionSheet = templateExerciseDetails }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Opções do exercício", tint = TextSecondary)
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    // Fast Multi-Select Exercise Picker
    if (showAddPickerSheet) {
        var searchQuery by remember { mutableStateOf("") }
        var selectedMuscleFilter by remember { mutableStateOf("Todos") }
        val selectedExerciseIds = remember { mutableStateListOf<Long>() }
        
        val muscleFilters = listOf("Todos", "Peitoral", "Costas", "Quadríceps", "Posterior", "Glúteos", "Panturrilhas", "Bíceps", "Tríceps", "Ombros", "Abdômen")

        val filteredCatalog = allExercises.filter { ex ->
            val matchesSearch = ExerciseSearchEngine.matches(
                query = searchQuery,
                name = ex.displayName,
                primaryMuscle = ex.primaryMuscle,
                equipment = ex.equipment
            )
            
            val matchesMuscle = selectedMuscleFilter == "Todos" || 
                MuscleVisualResolver.getDisplayName(ex.primaryMuscle) == selectedMuscleFilter ||
                (ex.primaryMuscle?.contains(selectedMuscleFilter, ignoreCase = true) == true)
            
            matchesSearch && matchesMuscle
        }

        ModalBottomSheet(
            onDismissRequest = { showAddPickerSheet = false },
            containerColor = SurfaceDark,
            dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .imePadding()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Selecionar Exercícios",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showAddPickerSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar exercício...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = BackgroundDark,
                        unfocusedContainerColor = BackgroundDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(muscleFilters, key = { it }) { filter ->
                        val isSelected = filter == selectedMuscleFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Lime400 else BackgroundDark)
                                .clickable { selectedMuscleFilter = filter }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = filter,
                                color = if (isSelected) BackgroundDark else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredCatalog, key = { it.id }) { ex ->
                        val isSelected = selectedExerciseIds.contains(ex.id)
                        val group = MuscleVisualResolver.resolveGroup(ex.primaryMuscle)

                        Surface(
                            color = if (isSelected) Lime400.copy(alpha = 0.15f) else BackgroundDark,
                            shape = RoundedCornerShape(12.dp),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Lime400) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelected) selectedExerciseIds.remove(ex.id)
                                    else selectedExerciseIds.add(ex.id)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SurfaceDark),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = group.icon,
                                        contentDescription = null,
                                        tint = group.color,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(ex.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Row {
                                        if (!ex.primaryMuscle.isNullOrBlank()) {
                                            Text(ex.primaryMuscle, color = TextSecondary, fontSize = 11.sp)
                                        }
                                        if (!ex.equipment.isNullOrBlank()) {
                                            Text(" • ${ex.equipment}", color = TextTertiary, fontSize = 11.sp)
                                        }
                                    }
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedExerciseIds.add(ex.id)
                                        else selectedExerciseIds.remove(ex.id)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Lime400,
                                        checkmarkColor = BackgroundDark
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (selectedExerciseIds.isNotEmpty()) {
                            viewModel.addExercisesToTemplate(selectedExerciseIds.toList())
                            showAddPickerSheet = false
                        }
                    },
                    enabled = selectedExerciseIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Lime400,
                        contentColor = BackgroundDark,
                        disabledContainerColor = SurfaceDark,
                        disabledContentColor = TextSecondary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = if (selectedExerciseIds.isEmpty()) "Selecione exercícios" else "ADICIONAR ${selectedExerciseIds.size} EXERCÍCIO${if (selectedExerciseIds.size > 1) "S" else ""}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Exercise Action Sheet
    val actionSheetItem = activeExerciseActionSheet
    if (actionSheetItem != null) {
        val selectedItem = actionSheetItem
        val currentIndex = exercises.indexOfFirst { it.templateExercise.id == selectedItem.templateExercise.id }
        val actions = buildList {
            if (currentIndex > 0) {
                add(
                    ActionItemData(
                        title = "Mover para cima",
                        icon = Icons.Default.ArrowUpward,
                        onClick = {
                            viewModel.moveExercise(currentIndex, currentIndex - 1)
                            activeExerciseActionSheet = null
                        }
                    )
                )
            }
            if (currentIndex >= 0 && currentIndex < exercises.size - 1) {
                add(
                    ActionItemData(
                        title = "Mover para baixo",
                        icon = Icons.Default.ArrowDownward,
                        onClick = {
                            viewModel.moveExercise(currentIndex, currentIndex + 1)
                            activeExerciseActionSheet = null
                        }
                    )
                )
            }
            add(
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_edit_template_exercise),
                    icon = Icons.Default.Edit,
                    onClick = {
                        exerciseToEdit = selectedItem
                        activeExerciseActionSheet = null
                    }
                )
            )
            add(
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_delete_template_exercise),
                    icon = Icons.Default.Delete,
                    destructive = true,
                    onClick = {
                        exerciseToDelete = selectedItem
                        activeExerciseActionSheet = null
                    }
                )
            )
        }
        ActionBottomSheet(
            onDismissRequest = { activeExerciseActionSheet = null },
            title = stringResource(id = R.string.sheet_exercise_options),
            subtitle = selectedItem.resolvedExercise.displayName,
            actions = actions
        )
    }

    val pendingRemoval = exerciseToDelete
    if (pendingRemoval != null) {
        val item = pendingRemoval
        AlertDialog(
            onDismissRequest = { exerciseToDelete = null },
            title = { Text("Remover Exercício", color = TextPrimary) },
            text = { Text("Deseja realmente remover o exercício '${item.resolvedExercise.displayName}' deste treino?", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeExercise(item.templateExercise)
                    exerciseToDelete = null
                }) { Text("Remover", color = Color.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { exerciseToDelete = null }) { Text("Cancelar", color = TextSecondary) }
            },
            containerColor = SurfaceDark
        )
    }

    // Exercise Configuration Bottom Sheet
    val editingExercise = exerciseToEdit
    if (editingExercise != null) {
        var sets by remember { mutableStateOf(editingExercise.templateExercise.targetSets.toString()) }
        var minReps by remember { mutableStateOf(editingExercise.templateExercise.minReps.toString()) }
        var maxReps by remember { mutableStateOf(editingExercise.templateExercise.maxReps.toString()) }
        var plannedWeight by remember { mutableStateOf(editingExercise.templateExercise.plannedWeight?.toString() ?: "") }
        var machine by remember { mutableStateOf(editingExercise.templateExercise.machineLabel ?: "") }
        var restSeconds by remember { mutableStateOf(editingExercise.templateExercise.restDurationSeconds.toString()) }
        var notes by remember { mutableStateOf(editingExercise.templateExercise.notes ?: "") }

        AppModalBottomSheet(
            onDismissRequest = { exerciseToEdit = null },
            title = "Configurações do Exercício",
            subtitle = editingExercise.resolvedExercise.displayName
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sets,
                        onValueChange = { sets = it },
                        label = { Text("Séries") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = restSeconds,
                        onValueChange = { restSeconds = it },
                        label = { Text("Descanso (s)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minReps,
                        onValueChange = { minReps = it },
                        label = { Text("Min Reps") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = maxReps,
                        onValueChange = { maxReps = it },
                        label = { Text("Max Reps") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = plannedWeight,
                    onValueChange = { plannedWeight = it },
                    label = { Text("Carga Planejada (kg - opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = machine,
                    onValueChange = { machine = it },
                    label = { Text("Máquina/Aparelho (ex: Máquina 21)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Observações do exercício") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val updated = editingExercise.templateExercise.copy(
                            targetSets = sets.toIntOrNull() ?: 3,
                            minReps = minReps.toIntOrNull() ?: 8,
                            maxReps = maxReps.toIntOrNull() ?: 12,
                            restDurationSeconds = restSeconds.toIntOrNull() ?: 90,
                            plannedWeight = plannedWeight.toFloatOrNull(),
                            machineLabel = machine.takeIf { it.isNotBlank() },
                            notes = notes.takeIf { it.isNotBlank() }
                        )
                        viewModel.updateExercise(updated)
                        exerciseToEdit = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("SALVAR CONFIGURAÇÕES", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }

    val templateToShare = template
    if (showShareDialog && templateToShare != null && shareViewModel != null) {
        // O snapshot é montado **aqui**, onde a entidade de treino legitimamente vive. O diálogo
        // social recebe só o resultado portável — ver `ShareWorkoutDialog` (T17.10 §105).
        val snapshotResult = remember(templateToShare, rawExercises) {
            com.example.data.repository.WorkoutShareSnapshotBuilder()
                .buildSnapshot(templateToShare, rawExercises)
        }
        ShareWorkoutDialog(
            buildResult = snapshotResult,
            viewModel = shareViewModel,
            onDismiss = {
                showShareDialog = false
                shareViewModel.reset()
            },
            // Snackbar, e não `Toast`: era o único `Toast` do app, e um aviso do sistema por cima
            // de uma tela que já tem Scaffold destoa de todo o resto (diálogo/snackbar).
            onShareSuccess = {
                showShareDialog = false
                shareViewModel.reset()
                snackbarScope.launch {
                    snackbarHostState.showSnackbar("Treino compartilhado com sucesso!")
                }
            }
        )
    }
}
