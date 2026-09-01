package com.example.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.audit.ExerciseAuditDetail
import com.example.domain.audit.PremiumAuditReport
import com.example.domain.audit.PremiumLibraryAudit
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumLibraryAuditSheet(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val auditEngine = remember { PremiumLibraryAudit() }
    val report = remember { auditEngine.auditAsset(context) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Todos") }
    var selectedStatus by remember { mutableStateOf("Todos") } // "Todos", "Aprovados", "Com Alerta"
    var expandedExerciseId by remember { mutableStateOf<String?>(null) }

    val categories = listOf("Todos", "Peito", "Costas", "Pernas", "Ombros", "Braços", "Abdômen")

    val filteredExercises = remember(searchQuery, selectedCategory, selectedStatus, report) {
        report.exerciseDetails.filter { ex ->
            val matchesSearch = searchQuery.isBlank() ||
                    ex.namePtBr.contains(searchQuery, ignoreCase = true) ||
                    ex.nameEn.contains(searchQuery, ignoreCase = true) ||
                    ex.id.contains(searchQuery, ignoreCase = true)

            val matchesCategory = selectedCategory == "Todos" || ex.category.equals(selectedCategory, ignoreCase = true)

            val matchesStatus = when (selectedStatus) {
                "Aprovados" -> ex.isApproved
                "Com Alerta" -> !ex.isApproved || ex.mediaType == "Sem mídia" || !ex.hasAlternatives
                else -> true
            }

            matchesSearch && matchesCategory && matchesStatus
        }
    }

    AppModalBottomSheet(
        onDismissRequest = onDismissRequest,
        title = "Auditoria da Biblioteca Premium",
        subtitle = "Diagnóstico completo dos 144 exercícios"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. KPI SUMMARY CARDS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricSummaryCard(
                    title = "Total",
                    value = "${report.totalExercises}",
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                MetricSummaryCard(
                    title = "Aprovados",
                    value = "${report.approved}",
                    color = Lime400,
                    modifier = Modifier.weight(1f)
                )
                MetricSummaryCard(
                    title = "Revisão",
                    value = "${report.needsReview}",
                    color = if (report.needsReview > 0) Color(0xFFFFB74D) else TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                MetricSummaryCard(
                    title = "Sem Mídia",
                    value = "${report.missingMedia}",
                    color = if (report.missingMedia > 0) Color(0xFFEF5350) else TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }

            // 2. SEARCH FIELD
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar por nome ou ID...", color = TextSecondary, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpar", tint = TextSecondary)
                        }
                    }
                } else null,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lime400,
                    unfocusedBorderColor = BorderLight,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // 3. CATEGORY CHIPS
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Lime400,
                            selectedLabelColor = BackgroundDark,
                            containerColor = SurfaceDark,
                            labelColor = TextSecondary
                        ),
                        border = null
                    )
                }
            }

            // 4. EXERCISE LIST
            Text(
                text = "Exibindo ${filteredExercises.size} de ${report.totalExercises} exercícios",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(filteredExercises, key = { it.id }) { item ->
                        ExerciseAuditCard(
                            item = item,
                            isExpanded = expandedExerciseId == item.id,
                            onToggleExpand = {
                                expandedExerciseId = if (expandedExerciseId == item.id) null else item.id
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricSummaryCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExerciseAuditCard(
    item: ExerciseAuditDetail,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = item.namePtBr,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${item.id} • ${item.nameEn}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Category Badge
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(item.category, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // Media Badge
                    val mediaColor = when (item.mediaType) {
                        "YouTube" -> Color(0xFFFF5252)
                        "ExerciseDB" -> Lime400
                        "Local GIF" -> Color(0xFF4FC3F7)
                        else -> Color(0xFFEF5350)
                    }
                    Box(
                        modifier = Modifier
                            .background(mediaColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(item.mediaType, color = mediaColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    // Status Badge
                    if (item.isApproved) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Aprovado", tint = Lime400, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Default.Warning, contentDescription = "Atenção", tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp))
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(BackgroundDark, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("• Execução (Passos): ${item.stepsCount}", color = TextPrimary, fontSize = 12.sp)
                        Text("• Dicas: ${item.tipsCount}", color = TextPrimary, fontSize = 12.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("• Erros Comuns: ${item.mistakesCount}", color = TextPrimary, fontSize = 12.sp)
                        Text("• Segurança: ${item.attentionPointsCount}", color = TextPrimary, fontSize = 12.sp)
                    }
                    Text(
                        text = "• Alternativas cadastradas: ${if (item.hasAlternatives) "SIM" else "NÃO"}",
                        color = if (item.hasAlternatives) Lime400 else Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (item.issues.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Inconsistências Encontradas:", color = Color(0xFFEF5350), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        item.issues.forEach { issue ->
                            Text("• [${issue.field}] ${issue.message}", color = Color(0xFFEF5350), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
