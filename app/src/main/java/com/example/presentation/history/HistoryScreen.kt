package com.example.presentation.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.CheckInEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.domain.engine.MuscleVisualResolver
import com.example.ui.components.SwipeAction
import com.example.ui.components.SwipeActionRow
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.components.ActionBottomSheet
import com.example.ui.components.ActionItemData
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsManager = remember { (context.applicationContext as com.example.MainApplication).settingsManager }
    val hapticEnabled by settingsManager.hapticEnabledFlow.collectAsState(initial = true)
    
    var selectedTab by remember { mutableStateOf(0) }
    var currentMonth by remember { mutableStateOf(Calendar.getInstance()) }
    var selectedSummaryForDetail by remember { mutableStateOf<SessionCalendarSummary?>(null) }
    var isCalendarExpanded by remember { mutableStateOf(false) }
    
    // Bottom Sheets & Dialogs State
    var activeSessionForSheet by remember { mutableStateOf<SessionCalendarSummary?>(null) }
    var sessionToDelete by remember { mutableStateOf<WorkoutSessionEntity?>(null) }
    var sessionToEditCheckIn by remember { mutableStateOf<SessionCalendarSummary?>(null) }

    var historyTypeFilter by remember { mutableStateOf("Todos") }
    var historyGrouping by remember { mutableStateOf("Semana") }

    // The period comes from the ViewModel so the workout list and the analysis tab always
    // describe the same slice of time.
    val filteredAllSessions = remember(state.sessionsInPeriod, historyTypeFilter) {
        state.sessionsInPeriod.filter { summary ->
            val totalSets = summary.exercises.sumOf { it.sets.size }
            val completedSets = summary.exercises.sumOf { e -> e.sets.count { it.completed } }
            val isPartial = totalSets > 0 && completedSets < totalSets
            when (historyTypeFilter) {
                "Concluídos" -> !isPartial
                "Parciais" -> isPartial
                else -> true
            }
        }
    }

    val groupedAllSessions = remember(filteredAllSessions, historyGrouping) {
        when (historyGrouping) {
            "Mês" -> {
                val format = SimpleDateFormat("MMMM yyyy", Locale("pt", "BR"))
                filteredAllSessions.groupBy { format.format(Date(it.session.startedAt)).replaceFirstChar { c -> c.uppercase() } }
            }
            "Semana" -> {
                val cal = Calendar.getInstance()
                filteredAllSessions.groupBy { summary ->
                    cal.timeInMillis = summary.session.startedAt
                    val week = cal.get(Calendar.WEEK_OF_YEAR)
                    val year = cal.get(Calendar.YEAR)
                    "Semana $week de $year"
                }
            }
            else -> {
                val format = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                filteredAllSessions.groupBy { format.format(Date(it.session.startedAt)) }
            }
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        contentWindowInsets = WindowInsets.navigationBars
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Histórico de Treinos",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
                
                if (state.calendarSummaries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val totalWorkouts = state.calendarSummaries.size
                    val totalDurationMs = state.calendarSummaries.sumOf { (it.session.finishedAt ?: it.session.startedAt) - it.session.startedAt }
                    val totalSets = state.calendarSummaries.sumOf { s -> s.exercises.sumOf { e -> e.sets.count { it.completed } } }
                    val durationMin = totalDurationMs / 60000
                    val durationStr = if (durationMin > 60) "${durationMin/60}h${durationMin%60}m" else "${durationMin}m"
                    Text(
                        text = "$totalWorkouts treinos · $durationStr · $totalSets séries",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                // Segmented Control Tabs (T3.3)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDark)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val tabs = listOf("Calendário", "Todos os Treinos", "Análise")
                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Lime400 else Color.Transparent)
                                .clickable { selectedTab = index }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                color = if (isSelected) BackgroundDark else TextSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (selectedTab != 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("history_period_filter"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Período:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        HistoryPeriod.entries.forEach { period ->
                            HistoryFilterChip(
                                label = period.label,
                                isSelected = period == state.period,
                                onClick = { viewModel.setPeriod(period) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            when (selectedTab) {
                0 -> {
                    // Calendário Tab
                    item {
                        CalendarView(
                            currentMonth = currentMonth,
                            onMonthChange = { currentMonth = it },
                            selectedDate = state.selectedDate,
                            onDateSelected = { viewModel.selectDate(it) },
                            summaries = state.calendarSummaries,
                            isExpanded = isCalendarExpanded,
                            onExpandedChange = { isCalendarExpanded = it }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(state.selectedDate),
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    
                    if (state.sessionsForSelectedDate.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Nenhum treino neste dia.",
                                    color = TextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        items(state.sessionsForSelectedDate, key = { "cal_${it.session.id}" }) { summary ->
                            val deleteAction = SwipeAction(
                                icon = Icons.Default.Delete,
                                label = "Excluir",
                                backgroundColor = Red500,
                                contentColor = Color.White,
                                onTrigger = { sessionToDelete = summary.session }
                            )
                            val editAction = SwipeAction(
                                icon = Icons.Default.Edit,
                                label = "Editar",
                                backgroundColor = SurfaceDark,
                                contentColor = Lime400,
                                onTrigger = { sessionToEditCheckIn = summary }
                            )

                            SwipeActionRow(
                                startAction = editAction,
                                endAction = deleteAction,
                                hapticEnabled = hapticEnabled,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                SessionDetailsCard(
                                    summary = summary,
                                    onOptionsClick = { activeSessionForSheet = summary },
                                    onCardClick = { selectedSummaryForDetail = summary }
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
                1 -> {
                    // Todos os Treinos
                    if (state.allCompletedSessions.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Nenhum treino registrado.",
                                    color = TextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Tipo & Agrupamento
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Tipo:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        listOf("Todos", "Concluídos", "Parciais").forEach { type ->
                                            val isSelected = type == historyTypeFilter
                                            Surface(
                                                color = if (isSelected) Lime400 else SurfaceDark,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.clickable { historyTypeFilter = type }
                                            ) {
                                                Text(
                                                    text = type,
                                                    color = if (isSelected) BackgroundDark else TextSecondary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf("Semana", "Mês", "Dia").forEach { grp ->
                                            val isSelected = grp == historyGrouping
                                            Surface(
                                                color = if (isSelected) Lime400.copy(alpha = 0.2f) else SurfaceDark,
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, if (isSelected) Lime400 else BorderLight),
                                                modifier = Modifier.clickable { historyGrouping = grp }
                                            ) {
                                                Text(
                                                    text = grp,
                                                    color = if (isSelected) Lime400 else TextSecondary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (filteredAllSessions.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Nenhum treino com os filtros selecionados.",
                                        color = TextSecondary,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        } else {
                            groupedAllSessions.forEach { (dateStr, sessionsInGroup) ->
                                item(key = "header_$dateStr") {
                                    Text(
                                        text = dateStr,
                                        color = TextSecondary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                                items(sessionsInGroup, key = { "all_${it.session.id}" }) { summary ->
                                    val deleteAction = SwipeAction(
                                        icon = Icons.Default.Delete,
                                        label = "Excluir",
                                        backgroundColor = Red500,
                                        contentColor = Color.White,
                                        onTrigger = { sessionToDelete = summary.session }
                                    )
                                    val editAction = SwipeAction(
                                        icon = Icons.Default.Edit,
                                        label = "Editar",
                                        backgroundColor = SurfaceDark,
                                        contentColor = Lime400,
                                        onTrigger = { sessionToEditCheckIn = summary }
                                    )

                                    SwipeActionRow(
                                        startAction = editAction,
                                        endAction = deleteAction,
                                        hapticEnabled = hapticEnabled,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        SessionDetailsCard(
                                            summary = summary,
                                            onOptionsClick = { activeSessionForSheet = summary },
                                            onCardClick = { selectedSummaryForDetail = summary }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // Análise: one period, three lenses. Kept to simple bars and rows on purpose —
                    // the goal is to spot evolution, not to operate a reporting tool.
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .testTag("history_analysis_filter"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Análise:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            HistoryAnalysis.entries.forEach { analysis ->
                                HistoryFilterChip(
                                    label = analysis.label,
                                    isSelected = analysis == state.analysis,
                                    onClick = { viewModel.setAnalysis(analysis) }
                                )
                            }
                        }
                    }

                    if (state.totals.sessions == 0) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Nenhum treino em ${state.period.label.lowercase(Locale("pt", "BR"))}.",
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        item {
                            PeriodTotalsCard(totals = state.totals, period = state.period)
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        val showStrength = state.analysis == HistoryAnalysis.ALL || state.analysis == HistoryAnalysis.STRENGTH
                        val showVolume = state.analysis == HistoryAnalysis.ALL || state.analysis == HistoryAnalysis.VOLUME
                        val showGroups = state.analysis == HistoryAnalysis.ALL || state.analysis == HistoryAnalysis.MUSCLE_GROUPS

                        if (showStrength) {
                            item {
                                AnalysisSectionTitle("Cargas mais pesadas do período")
                            }
                            if (state.strengthHighlights.isEmpty()) {
                                item { AnalysisEmptyRow("Sem séries com carga registrada.") }
                            } else {
                                items(state.strengthHighlights, key = { "strength_${it.exerciseName}" }) { highlight ->
                                    Surface(
                                        color = SurfaceDark,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = highlight.exerciseName,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                maxLines = 2,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = "${formatKg(highlight.maxWeight.toDouble())} × ${highlight.repsAtMaxWeight}",
                                                color = Lime400,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }

                        if (showVolume) {
                            item {
                                AnalysisSectionTitle("Volume por grupo muscular")
                            }
                            val volumeEntries = state.muscleVolumeDistribution.entries
                                .filter { it.value > 0.0 }
                                .sortedByDescending { it.value }
                            if (volumeEntries.isEmpty()) {
                                item { AnalysisEmptyRow("Sem volume registrado no período.") }
                            } else {
                                val maxVolume = volumeEntries.first().value
                                items(volumeEntries.toList(), key = { "volume_${it.key}" }) { entry ->
                                    MuscleDistributionRow(
                                        muscleName = entry.key,
                                        valueLabel = formatKg(entry.value),
                                        fraction = (entry.value / maxVolume).toFloat()
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }

                        if (showGroups) {
                            item {
                                AnalysisSectionTitle("Séries por grupo muscular")
                            }
                            val setEntries = state.muscleSetsDistribution.entries.sortedByDescending { it.value }
                            if (setEntries.isEmpty()) {
                                item { AnalysisEmptyRow("Sem séries concluídas no período.") }
                            } else {
                                val maxSets = setEntries.first().value.coerceAtLeast(1)
                                items(setEntries.toList(), key = { "sets_${it.key}" }) { entry ->
                                    MuscleDistributionRow(
                                        muscleName = entry.key,
                                        valueLabel = "${entry.value} séries",
                                        fraction = entry.value.toFloat() / maxSets.toFloat()
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet: Session Details (T8B Standard AppModalBottomSheet)
    if (selectedSummaryForDetail != null) {
        val summary = selectedSummaryForDetail!!
        AppModalBottomSheet(
            onDismissRequest = { selectedSummaryForDetail = null },
            title = summary.session.templateNameSnapshot ?: "Treino",
            subtitle = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()).format(Date(summary.session.startedAt))
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(summary.sortedExercises) { exSummary ->
                    Surface(
                        color = BackgroundDark,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = exSummary.exerciseSession.exerciseNameSnapshot,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            exSummary.sets.filter { it.completed }.forEachIndexed { i, set ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "Série ${i + 1}", color = TextSecondary, fontSize = 14.sp)
                                    val valStr = if (set.isDurationMode) {
                                        val dur = set.durationSeconds ?: set.repetitions
                                        if (set.weight > 0f) "${set.weight}kg × ${dur}s" else "${dur}s"
                                    } else {
                                        "${set.weight}kg × ${set.repetitions} reps"
                                    }
                                    Text(
                                        text = valStr,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ActionBottomSheet: Options for historical session (T8B)
    if (activeSessionForSheet != null) {
        val summary = activeSessionForSheet!!
        ActionBottomSheet(
            onDismissRequest = { activeSessionForSheet = null },
            title = stringResource(id = R.string.sheet_session_options),
            subtitle = summary.session.templateNameSnapshot ?: "Treino",
            actions = listOf(
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_view_session_details),
                    icon = Icons.Default.ExpandMore,
                    onClick = { selectedSummaryForDetail = summary }
                ),
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_edit_checkin),
                    icon = Icons.Default.Edit,
                    onClick = { sessionToEditCheckIn = summary }
                ),
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_delete_session),
                    icon = Icons.Default.Delete,
                    destructive = true,
                    onClick = { sessionToDelete = summary.session }
                )
            )
        )
    }

    // Safety delete confirmation dialog (T9)
    if (sessionToDelete != null) {
        val session = sessionToDelete!!
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Excluir do Histórico", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Deseja realmente remover esta sessão do seu histórico permanentemente? Esta ação não pode ser desfeita.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(session)
                    sessionToDelete = null
                }) {
                    Text("Excluir", color = Red500, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    // Edit Check-in dialog
    if (sessionToEditCheckIn != null) {
        val summary = sessionToEditCheckIn!!
        var gymName by remember { mutableStateOf(summary.checkIn?.gymName ?: "") }
        AlertDialog(
            onDismissRequest = { sessionToEditCheckIn = null },
            title = { Text("Editar Check-in", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = gymName,
                        onValueChange = { gymName = it },
                        label = { Text("Nome da Academia (opcional)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Lime400,
                            unfocusedBorderColor = BorderLight
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ci = summary.checkIn ?: CheckInEntity(
                        sessionId = summary.session.id,
                        checkInTime = summary.session.startedAt,
                        checkOutTime = summary.session.finishedAt,
                        gymName = ""
                    )
                    viewModel.updateCheckInTime(
                        checkIn = ci,
                        newCheckInTime = ci.checkInTime,
                        newCheckOutTime = ci.checkOutTime ?: summary.session.finishedAt,
                        gym = gymName.takeIf { it.isNotBlank() }
                    )
                    sessionToEditCheckIn = null
                }) {
                    Text("Salvar", color = Lime400, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToEditCheckIn = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

@Composable
fun CalendarView(
    currentMonth: Calendar,
    onMonthChange: (Calendar) -> Unit,
    selectedDate: Date,
    onDateSelected: (Date) -> Unit,
    summaries: List<SessionCalendarSummary>,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceDark)
            .padding(16.dp)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val prev = currentMonth.clone() as Calendar
                prev.add(Calendar.MONTH, -1)
                onMonthChange(prev)
            }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Anterior", tint = TextPrimary)
            }
            Text(
                text = monthFormat.format(currentMonth.time).replaceFirstChar { it.uppercase() },
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = {
                val next = currentMonth.clone() as Calendar
                next.add(Calendar.MONTH, 1)
                onMonthChange(next)
            }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Próximo", tint = TextPrimary)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        val daysOfWeek = listOf("D", "S", "T", "Q", "Q", "S", "S")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            daysOfWeek.forEach { day ->
                Text(
                    text = day,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        val cal = currentMonth.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        
        val totalCells = firstDayOfWeek + daysInMonth
        val rows = Math.ceil(totalCells / 7.0).toInt()
        
        val selectedCal = Calendar.getInstance().apply { time = selectedDate }
        val isSelectedMonth = selectedCal.get(Calendar.YEAR) == currentMonth.get(Calendar.YEAR) && selectedCal.get(Calendar.MONTH) == currentMonth.get(Calendar.MONTH)
        val selectedDay = selectedCal.get(Calendar.DAY_OF_MONTH)
        
        val selectedRowIndex = if (isSelectedMonth) {
            (firstDayOfWeek + selectedDay - 1) / 7
        } else {
            0
        }
        
        var dayCounter = 1
        
        Column {
            for (i in 0 until rows) {
                if (!isExpanded && i != selectedRowIndex) {
                    for (j in 0 until 7) {
                        val cellIndex = i * 7 + j
                        if (cellIndex >= firstDayOfWeek && dayCounter <= daysInMonth) {
                            dayCounter++
                        }
                    }
                    continue
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    for (j in 0 until 7) {
                        val cellIndex = i * 7 + j
                        if (cellIndex < firstDayOfWeek || dayCounter > daysInMonth) {
                            Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                        } else {
                            val dateForCell = (currentMonth.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dayCounter) }.time
                            val isSelected = isSameDay(dateForCell, selectedDate)
                            val hasWorkout = summaries.any { isSameDay(Date(it.session.startedAt), dateForCell) }
                            
                            val bgColor = if (isSelected) Lime400 else Color.Transparent
                            val textColor = if (isSelected) BackgroundDark else TextPrimary
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(4.dp)
                                    .clip(CircleShape)
                                    .background(bgColor)
                                    .clickable { onDateSelected(dateForCell) },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = dayCounter.toString(),
                                        color = textColor,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (hasWorkout && !isSelected) {
                                        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Lime400))
                                    }
                                }
                            }
                            dayCounter++
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onExpandedChange(!isExpanded) }
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            val icon = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore
            Icon(
                icon, 
                contentDescription = if (isExpanded) "Recolher calendário" else "Expandir calendário", 
                tint = TextSecondary
            )
        }
    }
}

@Composable
fun SessionDetailsCard(
    summary: SessionCalendarSummary,
    onOptionsClick: () -> Unit,
    onCardClick: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val started = timeFormat.format(Date(summary.session.startedAt))
    val finished = summary.session.finishedAt?.let { timeFormat.format(Date(it)) } ?: "--:--"
    
    val durationMs = if (summary.session.finishedAt != null) summary.session.finishedAt - summary.session.startedAt else 0L
    val durationMin = durationMs / 60000
    val durationStr = if (durationMin > 60) "${durationMin/60}h${durationMin%60}m" else "${durationMin}m"
    
    val totalSets = summary.exercises.sumOf { ex -> ex.sets.count { it.completed } }
    val totalVolume = summary.exercises.sumOf { ex -> ex.sets.filter { it.completed && !it.isDurationMode }.sumOf { (it.weight * it.repetitions).toDouble() } }.toInt()
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderLight, RoundedCornerShape(24.dp))
            .clickable { onCardClick() }
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.session.templateNameSnapshot ?: "Treino Customizado",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                if (!summary.checkIn?.gymName.isNullOrBlank()) {
                    Text(
                        text = "📍 ${summary.checkIn!!.gymName}",
                        color = Lime400,
                        fontSize = 12.sp
                    )
                }
            }
            IconButton(onClick = onOptionsClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Opções do treino", tint = TextSecondary)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DetailItem(label = "Entrada", value = summary.checkIn?.let { timeFormat.format(Date(it.checkInTime)) } ?: started)
            DetailItem(label = "Saída", value = summary.checkIn?.checkOutTime?.let { timeFormat.format(Date(it)) } ?: finished)
            DetailItem(label = "Duração", value = durationStr)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = BorderLight)
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DetailItem(label = "Exercícios", value = "${summary.exercises.size}")
            DetailItem(label = "Séries Feitas", value = "$totalSets")
            DetailItem(label = "Volume Total", value = "${totalVolume}kg")
        }
    }
}

@Composable
fun DetailItem(label: String, value: String) {
    Column {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private fun isSameDay(date1: Date, date2: Date): Boolean {
    val cal1 = Calendar.getInstance().apply { time = date1 }
    val cal2 = Calendar.getInstance().apply { time = date2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

/** Rounded selectable chip used by the history period and analysis filters. */
@Composable
private fun HistoryFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) Lime400 else SurfaceDark,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (isSelected) BackgroundDark else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun AnalysisSectionTitle(title: String) {
    Text(
        text = title,
        color = TextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun AnalysisEmptyRow(message: String) {
    Text(
        text = message,
        color = TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 16.dp)
    )
}

/** Headline numbers for the selected period, shared by every analysis lens. */
@Composable
private fun PeriodTotalsCard(totals: PeriodTotals, period: HistoryPeriod) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("history_period_totals")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = period.label.uppercase(Locale("pt", "BR")),
                color = Lime400,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TotalsItem(value = "${totals.sessions}", label = if (totals.sessions == 1) "treino" else "treinos")
                TotalsItem(value = "${totals.completedSets}", label = "séries")
                TotalsItem(value = formatKg(totals.volumeKg), label = "volume")
                TotalsItem(value = formatDuration(totals.durationMinutes), label = "tempo")
            }
        }
    }
}

@Composable
private fun TotalsItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(text = label, color = TextSecondary, fontSize = 11.sp)
    }
}

/** One muscle group with a proportional bar, used for both volume and set distribution. */
@Composable
private fun MuscleDistributionRow(
    muscleName: String,
    valueLabel: String,
    fraction: Float
) {
    val group = MuscleVisualResolver.resolveGroup(muscleName)
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = group.icon,
                        contentDescription = null,
                        tint = group.color,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = muscleName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Text(
                    text = valueLabel,
                    color = Lime400,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BackgroundDark)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0.05f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(group.color)
                )
            }
        }
    }
}

private fun formatKg(value: Double): String {
    return when {
        value >= 1000 -> String.format(Locale("pt", "BR"), "%.1ft", value / 1000)
        value % 1.0 == 0.0 -> "${value.toInt()}kg"
        else -> String.format(Locale("pt", "BR"), "%.1fkg", value)
    }
}

private fun formatDuration(minutes: Long): String {
    return if (minutes >= 60) "${minutes / 60}h${minutes % 60}m" else "${minutes}m"
}
