package com.example.presentation.workouts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
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
import com.example.data.local.TemplateExerciseWithDetails
import com.example.domain.engine.MuscleVisualResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDetailsScreen(
    viewModel: TemplateDetailsViewModel,
    onBack: () -> Unit
) {
    val template by viewModel.template.collectAsState()
    val exercises by viewModel.exercises.collectAsState()
    val allExercises by viewModel.allExercises.collectAsState()
    
    var showAddPickerSheet by remember { mutableStateOf(false) }
    var exerciseToEdit by remember { mutableStateOf<TemplateExerciseWithDetails?>(null) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(template?.name ?: "Treino", color = TextPrimary, fontWeight = FontWeight.Bold)
                        if (!template?.dayOfWeek.isNullOrEmpty()) {
                            Text(template?.dayOfWeek!!, color = Lime400, fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
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
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { dismissValue ->
                        if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                            viewModel.removeExercise(templateExerciseDetails.templateExercise)
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        val color by animateColorAsState(
                            when (dismissState.targetValue) {
                                SwipeToDismissBoxValue.EndToStart -> Color.Red.copy(alpha = 0.8f)
                                else -> Color.Transparent
                            }
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .background(color)
                                .padding(end = 24.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remover", tint = Color.White)
                        }
                    }
                ) {
                    val group = MuscleVisualResolver.resolveGroup(templateExerciseDetails.exercise.primaryMuscle)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .clickable { exerciseToEdit = templateExerciseDetails }
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
                                text = templateExerciseDetails.exercise.name,
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
                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = TextSecondary)
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
            val matchesSearch = searchQuery.isBlank() || 
                ex.name.contains(searchQuery, ignoreCase = true) ||
                (ex.nameEn?.contains(searchQuery, ignoreCase = true) == true) ||
                (ex.equipment?.contains(searchQuery, ignoreCase = true) == true)
            
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
                    items(muscleFilters) { filter ->
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
                                    Text(ex.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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

    // Exercise Configuration Dialog
    if (exerciseToEdit != null) {
        var sets by remember { mutableStateOf(exerciseToEdit!!.templateExercise.targetSets.toString()) }
        var minReps by remember { mutableStateOf(exerciseToEdit!!.templateExercise.minReps.toString()) }
        var maxReps by remember { mutableStateOf(exerciseToEdit!!.templateExercise.maxReps.toString()) }
        var plannedWeight by remember { mutableStateOf(exerciseToEdit!!.templateExercise.plannedWeight?.toString() ?: "") }
        var machine by remember { mutableStateOf(exerciseToEdit!!.templateExercise.machineLabel ?: "") }
        var restSeconds by remember { mutableStateOf(exerciseToEdit!!.templateExercise.restDurationSeconds.toString()) }
        var notes by remember { mutableStateOf(exerciseToEdit!!.templateExercise.notes ?: "") }
        
        AlertDialog(
            onDismissRequest = { exerciseToEdit = null },
            title = { Text(exerciseToEdit!!.exercise.name, color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = machine, 
                        onValueChange = { machine = it }, 
                        label = { Text("Máquina/Aparelho (ex: Máquina 21)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = notes, 
                        onValueChange = { notes = it }, 
                        label = { Text("Observações do exercício") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = exerciseToEdit!!.templateExercise.copy(
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
                }) { Text("Salvar", color = Lime400, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { exerciseToEdit = null }) { Text("Cancelar", color = TextSecondary) }
            },
            containerColor = SurfaceDark
        )
    }
}
