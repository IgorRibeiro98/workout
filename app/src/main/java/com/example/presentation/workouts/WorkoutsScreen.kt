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
import com.example.ui.components.SwipeAction
import com.example.ui.components.SwipeActionRow

import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.components.ActionBottomSheet
import com.example.ui.components.ActionItemData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsScreen(viewModel: WorkoutsViewModel, onProgramClick: (Long) -> Unit) {
    val programs by viewModel.programs.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsManager = remember { (context.applicationContext as com.example.MainApplication).settingsManager }
    val hapticEnabled by settingsManager.hapticEnabledFlow.collectAsState(initial = true)

    var showAddProgramDialog by remember { mutableStateOf(false) }
    var programToDelete by remember { mutableStateOf<WorkoutProgramEntity?>(null) }
    var activeProgramForSheet by remember { mutableStateOf<WorkoutProgramEntity?>(null) }

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
                val endAction = SwipeAction(
                    icon = Icons.Default.Delete,
                    label = "Excluir",
                    backgroundColor = Color.Red.copy(alpha = 0.8f),
                    contentColor = Color.White,
                    onTrigger = { programToDelete = program }
                )

                SwipeActionRow(
                    endAction = endAction,
                    hapticEnabled = hapticEnabled,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { activeProgramForSheet = program }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Opções do programa", tint = TextSecondary)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = "Abrir", tint = TextSecondary)
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (activeProgramForSheet != null) {
        val program = activeProgramForSheet!!
        ActionBottomSheet(
            onDismissRequest = { activeProgramForSheet = null },
            title = stringResource(id = R.string.sheet_program_options),
            subtitle = program.name,
            actions = listOf(
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_open_program),
                    icon = Icons.Default.ChevronRight,
                    onClick = { onProgramClick(program.id) }
                ),
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_activate_program),
                    icon = Icons.Default.CheckCircle,
                    enabled = !program.isCurrent,
                    onClick = { viewModel.setCurrentProgram(program.id) }
                ),
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_delete_program),
                    icon = Icons.Default.Delete,
                    destructive = true,
                    onClick = { programToDelete = program }
                )
            )
        )
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
