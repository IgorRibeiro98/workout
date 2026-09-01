package com.example.presentation.execution

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SessionCalendarSummary
import com.example.domain.engine.ProgressionEngine
import com.example.domain.engine.ProgressionResult
import com.example.domain.engine.VolumeCalculator
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SummaryScreen(
    summary: SessionCalendarSummary,
    onClose: () -> Unit
) {
    val durationMs = if (summary.session.finishedAt != null) summary.session.finishedAt - summary.session.startedAt else 0L
    val durationMin = durationMs / 60000
    val durationStr = if (durationMin > 60) "${durationMin/60}h${durationMin%60}m" else "${durationMin}m"
    
    val totalSets = summary.exercises.sumOf { ex -> ex.sets.count { it.completed } }
    val totalVolume = summary.exercises.sumOf { ex -> VolumeCalculator.calculateVolume(ex.sets) }.toInt()
    val isOrderAdapted = summary.exercises.any { it.exerciseSession.executionOrder != it.exerciseSession.plannedOrder }

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
            ) {
                Text("CONCLUÍR", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Text("Treino concluído", color = Lime400, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(summary.session.templateNameSnapshot ?: "Treino", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(32.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SummaryStat("Duração", durationStr)
                    SummaryStat("Exercícios", "${summary.exercises.size}")
                    SummaryStat("Séries", "$totalSets")
                    SummaryStat("Volume", "${totalVolume}kg")
                }

                if (isOrderAdapted) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Surface(
                        color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚡ Você adaptou a ordem dos exercícios neste treino.",
                                color = Color(0xFFF59E0B),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                HorizontalDivider(color = BorderLight)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Progressão Sugerida", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            items(summary.exercises.size) { index ->
                val ex = summary.exercises[index]
                if (ex.sets.isNotEmpty()) {
                    val result = ProgressionEngine.evaluate(
                        targetSets = ex.sets.size,
                        targetRepsMax = 12, // Default fallback if not available
                        actualSets = ex.sets,
                        currentLoad = ex.sets.firstOrNull()?.weight ?: 0f
                    )
                    
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceDark).padding(16.dp)) {
                        Text(ex.exerciseSession.exerciseNameSnapshot, color = TextPrimary, fontWeight = FontWeight.Bold)
                        when (result) {
                            is ProgressionResult.Increase -> {
                                Text("Progressão disponível!", color = Lime400, fontSize = 14.sp)
                                Text("Sugerido: ${result.suggestedLoad}kg no próximo treino.", color = TextSecondary, fontSize = 14.sp)
                            }
                            is ProgressionResult.Maintain -> {
                                Text("Manter ${result.currentLoad}kg.", color = TextSecondary, fontSize = 14.sp)
                            }
                            is ProgressionResult.Decrease -> {
                                Text("Considere reduzir carga.", color = Red500, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}
