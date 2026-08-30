package com.example.presentation.workouts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.WorkoutProgramEntity
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsScreen(viewModel: WorkoutsViewModel, onProgramClick: (Long) -> Unit) {
    val programs by viewModel.programs.collectAsState()
    var showAddProgramDialog by remember { mutableStateOf(false) }
    var programToDelete by remember { mutableStateOf<WorkoutProgramEntity?>(null) }

    Scaffold(
        containerColor = BackgroundDark,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddProgramDialog = true },
                containerColor = Lime400,
                contentColor = BackgroundDark
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Programa")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item {
                Text(
                    text = "Meus Programas",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            
            if (programs.isEmpty()) {
                item {
                    Text("Nenhum programa criado.", color = TextSecondary)
                }
            }
            
            items(programs, key = { it.id }) { program ->
                val isCurrent = program.isCurrent
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { dismissValue ->
                        if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                            programToDelete = program
                            false
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
                                .clip(RoundedCornerShape(24.dp))
                                .background(color)
                                .padding(end = 24.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = Color.White)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(SurfaceDark)
                            .border(
                                1.dp,
                                if (isCurrent) Lime400.copy(alpha = 0.5f) else BorderLight,
                                RoundedCornerShape(24.dp)
                            )
                            .clickable { onProgramClick(program.id) }
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = program.name,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            if (isCurrent) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "PROGRAMA ATUAL",
                                    color = Emerald500,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = "Abrir", tint = TextSecondary)
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (programToDelete != null) {
        AlertDialog(
            onDismissRequest = { programToDelete = null },
            title = { Text("Excluir Programa", color = TextPrimary) },
            text = { Text("Deseja realmente excluir o programa '${programToDelete?.name}' e todos os seus treinos?", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    programToDelete?.let { viewModel.deleteProgram(it) }
                    programToDelete = null
                }) { Text("Excluir", color = Color.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { programToDelete = null }) { Text("Cancelar", color = TextSecondary) }
            },
            containerColor = SurfaceDark
        )
    }

    if (showAddProgramDialog) {
        var name by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showAddProgramDialog = false },
            title = { Text("Novo Programa", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do Programa") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = BackgroundDark,
                        unfocusedContainerColor = BackgroundDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        viewModel.createProgram(name)
                        showAddProgramDialog = false
                    }
                }) { Text("Criar", color = Lime400, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddProgramDialog = false }) { Text("Cancelar", color = TextSecondary) }
            },
            containerColor = SurfaceDark
        )
    }
}
