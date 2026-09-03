package com.example.presentation.exercises

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.engine.MuscleNormalizer
import com.example.domain.engine.MuscleVisualResolver
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(viewModel: ExercisesViewModel, onExerciseClick: (Long, String) -> Unit = { _, _ -> }) {
    val exercises by viewModel.exercises.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedRegion by remember { mutableStateOf("Todas") }
    var selectedMuscle by remember { mutableStateOf("Todos") }
    var selectedEquipment by remember { mutableStateOf("Todos") }
    var selectedMode by remember { mutableStateOf("Todos") }
    
    val allRegions = listOf("Todas", "Superiores", "Inferiores", "Core")
    val allMuscles = listOf("Todos", "Peitoral", "Costas", "Quadríceps", "Posterior", "Glúteos", "Panturrilha", "Bíceps", "Tríceps", "Ombros", "Abdômen", "Antebraço", "Trapézio")
    val allEquipments = listOf("Todos", "Barra", "Halteres", "Máquina", "Anilha", "Cabo", "Peso Corporal", "Kettlebell")
    val allModes = listOf("Todos", "Repetições", "Tempo / Isometria")

    fun getMuscleRegion(muscle: String?): String {
        val norm = MuscleNormalizer.normalize(muscle).lowercase()
        return when {
            norm.contains("peitoral") || norm.contains("costas") || norm.contains("bíceps") ||
            norm.contains("tríceps") || norm.contains("ombros") || norm.contains("antebraço") ||
            norm.contains("trapézio") -> "Superiores"
            norm.contains("quadríceps") || norm.contains("posterior") || norm.contains("glúteo") ||
            norm.contains("panturrilha") -> "Inferiores"
            norm.contains("abdômen") || norm.contains("core") || norm.contains("lombar") -> "Core"
            else -> "Superiores"
        }
    }

    val activeFiltersCount = (if (selectedRegion != "Todas") 1 else 0) +
            (if (selectedMuscle != "Todos") 1 else 0) +
            (if (selectedEquipment != "Todos") 1 else 0) +
            (if (selectedMode != "Todos") 1 else 0)

    val filteredExercises = exercises.filter {
        val search = searchQuery.trim().lowercase()
        val searchInEn = it.nameEn?.lowercase()?.contains(search) == true
        val searchInMuscle = it.primaryMuscle?.lowercase()?.contains(search) == true || it.secondaryMuscles.any { m -> m.lowercase().contains(search) }
        val searchInEq = it.equipment?.lowercase()?.contains(search) == true
        val searchInPattern = it.movementPattern?.lowercase()?.contains(search) == true
        val searchInNotes = it.notes?.lowercase()?.contains(search) == true
        val matchesSearch = search.isEmpty() ||
                it.displayName.lowercase().contains(search) ||
                (it.rawExercise.aliases?.lowercase()?.contains(search) == true) ||
                searchInEn || searchInMuscle || searchInEq || searchInPattern || searchInNotes
        
        val matchesRegion = selectedRegion == "Todas" || getMuscleRegion(it.primaryMuscle) == selectedRegion
        val matchesMuscle = selectedMuscle == "Todos" || MuscleNormalizer.normalize(it.primaryMuscle).contains(selectedMuscle, ignoreCase = true)
        val matchesEquipment = selectedEquipment == "Todos" || it.equipment?.contains(selectedEquipment, ignoreCase = true) == true
        val isDuration = it.executionMode == com.example.domain.model.ExerciseExecutionMode.DURATION
        val matchesMode = when (selectedMode) {
            "Repetições" -> !isDuration
            "Tempo / Isometria" -> isDuration
            else -> true
        }
        
        matchesSearch && matchesRegion && matchesMuscle && matchesEquipment && matchesMode
    }

    if (showFilterSheet || showAddSheet) {
        BackHandler {
            showFilterSheet = false
            showAddSheet = false
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = Lime400,
                contentColor = BackgroundDark
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Exercício")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Catálogo de Exercícios",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Buscar exercício...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Surface(
                    onClick = { showFilterSheet = true },
                    color = if (activeFiltersCount > 0) Lime400 else SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = stringResource(id = R.string.sheet_filter_exercises_title),
                            tint = if (activeFiltersCount > 0) BackgroundDark else TextPrimary
                        )
                        if (activeFiltersCount > 0) {
                            Text(
                                text = "($activeFiltersCount)",
                                color = BackgroundDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Quick Muscle Chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(allMuscles) { muscle ->
                    val isSelected = muscle == selectedMuscle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Lime400 else SurfaceDark)
                            .clickable { selectedMuscle = muscle }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = muscle,
                            color = if (isSelected) BackgroundDark else TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            if (filteredExercises.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Nenhum exercício encontrado.", color = TextSecondary, fontSize = 15.sp)
                        if (activeFiltersCount > 0 || searchQuery.isNotEmpty()) {
                            TextButton(onClick = {
                                searchQuery = ""
                                selectedRegion = "Todas"
                                selectedMuscle = "Todos"
                                selectedEquipment = "Todos"
                                selectedMode = "Todos"
                            }) {
                                Text(stringResource(id = R.string.sheet_action_clear_filters), color = Lime400, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredExercises, key = { it.id }) { exercise ->
                        val group = MuscleVisualResolver.resolveGroup(exercise.primaryMuscle)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceDark)
                                .clickable { onExerciseClick(exercise.id, exercise.displayName) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(group.color.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = group.icon,
                                    contentDescription = null,
                                    tint = group.color,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = exercise.displayName,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!exercise.primaryMuscle.isNullOrEmpty()) {
                                        Text(
                                            text = exercise.primaryMuscle,
                                            color = group.color,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    if (!exercise.equipment.isNullOrEmpty()) {
                                        Text(
                                            text = " • ${exercise.equipment}",
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                    if (exercise.isUserCreated) {
                                        Text(
                                            text = " • Criado por você",
                                            color = Lime400,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = "Ver Detalhes", tint = TextSecondary)
                        }
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    // Filter Exercises Bottom Sheet
    if (showFilterSheet) {
        var tempRegion by remember { mutableStateOf(selectedRegion) }
        var tempMuscle by remember { mutableStateOf(selectedMuscle) }
        var tempEquipment by remember { mutableStateOf(selectedEquipment) }
        var tempMode by remember { mutableStateOf(selectedMode) }

        val hasAnyFilter = tempRegion != "Todas" || tempMuscle != "Todos" || tempEquipment != "Todos" || tempMode != "Todos"

        AppModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            title = stringResource(id = R.string.sheet_filter_exercises_title),
            headerRightContent = {
                if (hasAnyFilter) {
                    TextButton(onClick = {
                        tempRegion = "Todas"
                        tempMuscle = "Todos"
                        tempEquipment = "Todos"
                        tempMode = "Todos"
                    }) {
                        Text(stringResource(id = R.string.sheet_action_clear_filters), color = Red500, fontSize = 13.sp)
                    }
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 1. Região Corporal
                Text("Região Corporal", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(allRegions) { region ->
                        val isSelected = region == tempRegion
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Lime400 else SurfaceHighlight)
                                .clickable { tempRegion = region }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = region,
                                color = if (isSelected) BackgroundDark else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 2. Grupo Muscular
                Text("Grupo Muscular", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(allMuscles) { muscle ->
                        val isSelected = muscle == tempMuscle
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Lime400 else SurfaceHighlight)
                                .clickable { tempMuscle = muscle }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = muscle,
                                color = if (isSelected) BackgroundDark else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 3. Equipamento
                Text("Equipamento", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(allEquipments) { equipment ->
                        val isSelected = equipment == tempEquipment
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Lime400 else SurfaceHighlight)
                                .clickable { tempEquipment = equipment }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = equipment,
                                color = if (isSelected) BackgroundDark else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 4. Modo de Execução
                Text("Modo de Execução", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(allModes) { mode ->
                        val isSelected = mode == tempMode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Lime400 else SurfaceHighlight)
                                .clickable { tempMode = mode }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = mode,
                                color = if (isSelected) BackgroundDark else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        selectedRegion = tempRegion
                        selectedMuscle = tempMuscle
                        selectedEquipment = tempEquipment
                        selectedMode = tempMode
                        showFilterSheet = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(stringResource(id = R.string.sheet_action_apply_filters), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }

    // Add New Exercise Bottom Sheet
    if (showAddSheet) {
        var name by remember { mutableStateOf("") }
        var muscle by remember { mutableStateOf("") }
        var equipment by remember { mutableStateOf("") }

        AppModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            title = "Novo Exercício",
            subtitle = "Cadastrar exercício personalizado"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do Exercício") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lime400,
                        unfocusedBorderColor = BorderLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                OutlinedTextField(
                    value = muscle,
                    onValueChange = { muscle = it },
                    label = { Text("Músculo Principal (ex: Peitoral)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lime400,
                        unfocusedBorderColor = BorderLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                OutlinedTextField(
                    value = equipment,
                    onValueChange = { equipment = it },
                    label = { Text("Equipamento (ex: Halteres, Barra, Máquina)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lime400,
                        unfocusedBorderColor = BorderLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            viewModel.addExercise(name, muscle, equipment.takeIf { it.isNotBlank() })
                            showAddSheet = false
                        }
                    },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("SALVAR EXERCÍCIO", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

