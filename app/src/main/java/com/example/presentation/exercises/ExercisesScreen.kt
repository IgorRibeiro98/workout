package com.example.presentation.exercises

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
import com.example.domain.engine.MuscleNormalizer
import com.example.domain.engine.MuscleVisualResolver
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(viewModel: ExercisesViewModel, onExerciseClick: (Long, String) -> Unit = { _, _ -> }) {
    val exercises by viewModel.exercises.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedMuscle by remember { mutableStateOf("Todos") }
    
    val allMuscles = listOf("Todos", "Peitoral", "Costas", "Quadríceps", "Posterior", "Glúteos", "Panturrilha", "Bíceps", "Tríceps", "Ombros", "Abdômen", "Antebraço", "Trapézio")

    val filteredExercises = exercises.filter {
        val search = searchQuery.trim().lowercase()
        val searchInEn = it.nameEn?.lowercase()?.contains(search) == true
        val searchInMuscle = it.primaryMuscle?.lowercase()?.contains(search) == true || it.secondaryMuscles?.lowercase()?.contains(search) == true
        val searchInEq = it.equipment?.lowercase()?.contains(search) == true
        val matchesSearch = search.isEmpty() || it.name.lowercase().contains(search) || (it.aliases?.lowercase()?.contains(search) == true) || searchInEn || searchInMuscle || searchInEq
        
        val matchesMuscle = selectedMuscle == "Todos" || MuscleNormalizer.normalize(it.primaryMuscle).contains(selectedMuscle, ignoreCase = true)
        matchesSearch && matchesMuscle
    }

    Scaffold(
        containerColor = BackgroundDark,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
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

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
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
            
            Spacer(modifier = Modifier.height(16.dp))
            
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
                    Text("Nenhum exercício encontrado.", color = TextSecondary)
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
                                .clickable { onExerciseClick(exercise.id, exercise.name) }
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
                                    text = exercise.name,
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

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var muscle by remember { mutableStateOf("") }
        var equipment by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Novo Exercício", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome do Exercício") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = muscle,
                        onValueChange = { muscle = it },
                        label = { Text("Músculo Principal (ex: Peitoral)") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = equipment,
                        onValueChange = { equipment = it },
                        label = { Text("Equipamento (ex: Halteres, Barra, Máquina)") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        viewModel.addExercise(name, muscle, equipment.takeIf { it.isNotBlank() })
                        showAddDialog = false
                    }
                }) { Text("Salvar", color = Lime400, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancelar", color = TextSecondary) }
            },
            containerColor = SurfaceDark
        )
    }
}
