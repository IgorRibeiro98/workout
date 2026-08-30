package com.example.presentation.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(viewModel: TodayViewModel, onNavigateToExecution: () -> Unit) {
    val state by viewModel.state.collectAsState()
    
    var showSwapSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Header
        val today = SimpleDateFormat("EEEE, d MMM", Locale("pt", "BR")).format(Date())
        Text(
            text = today.uppercase(),
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Resumo",
            color = TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Active session card or Next workout card
        if (state.activeSession != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Lime400, Lime500)
                        )
                    )
                    .padding(32.dp)
            ) {
                Column {
                    Text(
                        text = "SESSÃO EM ANDAMENTO",
                        color = BackgroundDark.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.activeSession?.templateNameSnapshot ?: "Treino Livre",
                        color = BackgroundDark,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 32.sp
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = onNavigateToExecution,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BackgroundDark,
                            contentColor = Lime400
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("CONTINUAR TREINO", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (state.nextTemplate != null) {
            val isCustomSwap = state.sequence.find { it.isCurrent }?.template?.id != state.nextTemplate?.id
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(SurfaceDark)
                    .padding(24.dp)
            ) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isCustomSwap) "TREINO SELECIONADO" else "PRÓXIMO DA SEQUÊNCIA",
                            color = Lime400,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        TextButton(
                            onClick = { showSwapSheet = true },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("TROCAR", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = state.nextTemplate?.name ?: "Treino",
                        color = TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 32.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val estTime = if (state.nextTemplateExerciseCount > 0) state.nextTemplateExerciseCount * 8 + 10 else 45
                    val dayLabel = state.nextTemplate?.dayOfWeek?.let { "$it · " } ?: ""
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Lime400.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = state.nextTemplate?.shortIdentifier ?: "A",
                                color = Lime400,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$dayLabel${state.nextTemplateExerciseCount} exercícios · ~$estTime min",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }

                    if (state.predominantMuscles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            state.predominantMuscles.forEach { muscle ->
                                val group = com.example.domain.engine.MuscleVisualResolver.resolveGroup(muscle)
                                Surface(
                                    color = SurfaceHighlight,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = group.icon,
                                            contentDescription = null,
                                            tint = group.color,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = muscle,
                                            color = TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            viewModel.startWorkout(state.nextTemplate!!.id)
                            onNavigateToExecution()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime400,
                            contentColor = BackgroundDark
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("INICIAR TREINO", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(SurfaceDark)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nenhum programa ativo.",
                    color = TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Check-in card
        if (state.activeCheckIn == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, BorderLight, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Não fez check-in ainda",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Toque para registrar entrada",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
                Button(
                    onClick = { viewModel.manualCheckIn() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Check-in")
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Lime400.copy(alpha = 0.1f))
                    .border(1.dp, Lime400.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Treinando agora",
                        color = Lime400,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Check-in realizado",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
                Button(
                    onClick = { viewModel.manualCheckOut() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Check-out")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Meta Semanal", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (i in 1..state.weeklyGoal) {
                val isCompleted = i <= state.weeklyCompleted
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(if (isCompleted) Lime400 else SurfaceDark)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${state.weeklyCompleted} de ${state.weeklyGoal} treinos concluídos",
            color = TextSecondary,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(32.dp))
        
        if (state.lastSession != null) {
            Text("Último Treino", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(SurfaceDark)
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = state.lastSession?.templateNameSnapshot ?: "Treino Livre",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val dateStr = SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(Date(state.lastSession!!.startedAt))
                    Text(
                        text = dateStr,
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
    
    if (showSwapSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSwapSheet = false },
            containerColor = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Escolher outro treino para hoje", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn {
                    items(state.allTemplates) { tpl ->
                        val isSuggested = state.sequence.find { it.isCurrent }?.template?.id == tpl.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.overrideTodayTemplate(tpl.id)
                                    showSwapSheet = false
                                }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BackgroundDark),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(tpl.shortIdentifier ?: "X", color = Lime400, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tpl.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                if (isSuggested) {
                                    Text("Sugerido (próximo da sequência)", color = Lime400, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
