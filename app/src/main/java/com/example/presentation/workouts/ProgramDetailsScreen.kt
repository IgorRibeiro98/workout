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
import com.example.data.local.WorkoutTemplateEntity
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramDetailsScreen(
    viewModel: ProgramDetailsViewModel,
    onNavigateBack: () -> Unit,
    onTemplateClick: (Long) -> Unit
) {
    val program by viewModel.program.collectAsState()
    val templates by viewModel.templates.collectAsState()
    
    var showAddTemplateDialog by remember { mutableStateOf(false) }
    var templateToDelete by remember { mutableStateOf<WorkoutTemplateEntity?>(null) }

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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddTemplateDialog = true },
                containerColor = Lime400,
                contentColor = BackgroundDark
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Treino")
            }
        }
    ) { innerPadding ->
        if (program != null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    val p = program!!
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
                    items(templates, key = { it.id }) { template ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { dismissValue ->
                                if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                    templateToDelete = template
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
                                        .clip(RoundedCornerShape(16.dp))
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
                                    if (!template.dayOfWeek.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(text = template.dayOfWeek!!, color = Lime400, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = "Abrir", tint = TextSecondary)
                            }
                        }
                    }
                }
                
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
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

    if (showAddTemplateDialog) {
        var name by remember { mutableStateOf("") }
        var shortId by remember { mutableStateOf("") }
        var selectedDay by remember { mutableStateOf("Nenhum") }
        val days = listOf("Nenhum", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
        
        AlertDialog(
            onDismissRequest = { showAddTemplateDialog = false },
            title = { Text("Novo Treino", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = shortId,
                        onValueChange = { shortId = it },
                        label = { Text("Sigla (ex: A, B)") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome (ex: Peito e Tríceps)") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Text("Dia da semana sugerido:", color = TextSecondary, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(days) { day ->
                            val isSelected = day == selectedDay
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Lime400 else BackgroundDark)
                                    .clickable { selectedDay = day }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = day,
                                    color = if (isSelected) BackgroundDark else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        val dayVal = if (selectedDay != "Nenhum") selectedDay else null
                        viewModel.createTemplate(name, shortId, dayVal)
                        showAddTemplateDialog = false
                    }
                }) { Text("Criar", color = Lime400, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddTemplateDialog = false }) { Text("Cancelar", color = TextSecondary) }
            },
            containerColor = SurfaceDark
        )
    }
}
