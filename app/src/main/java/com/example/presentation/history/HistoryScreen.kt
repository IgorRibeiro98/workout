package com.example.presentation.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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

    val groupedAllSessions = remember(state.allCompletedSessions) {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        state.allCompletedSessions.groupBy { summary ->
            dateFormat.format(Date(summary.session.startedAt))
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
                    val tabs = listOf("Calendário", "Todos os Treinos", "Volume")
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
                2 -> {
                    // Volume Tab
                    item {
                        var expandedFilter by remember { mutableStateOf(false) }
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                            Box {
                                TextButton(onClick = { expandedFilter = true }) {
                                    Text(state.volumeTimeRange, color = Lime400, fontWeight = FontWeight.Bold)
                                }
                                DropdownMenu(
                                    expanded = expandedFilter,
                                    onDismissRequest = { expandedFilter = false },
                                    modifier = Modifier.background(SurfaceDark)
                                ) {
                                    listOf("Esta semana", "Este mês", "Tudo").forEach { range ->
                                        DropdownMenuItem(
                                            text = { Text(range, color = TextPrimary) },
                                            onClick = {
                                                viewModel.setVolumeTimeRange(range)
                                                expandedFilter = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.muscleSetsDistribution.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Sem dados de volume suficientes.",
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        val maxSets = (state.muscleSetsDistribution.values.maxOrNull() ?: 1).coerceAtLeast(1)
                        item {
                            Text(
                                text = "Distribuição de Séries por Grupo Muscular",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        
                        items(state.muscleSetsDistribution.entries.sortedByDescending { it.value }.toList()) { entry ->
                            val group = MuscleVisualResolver.resolveGroup(entry.key)
                            val fraction = (entry.value.toFloat() / maxSets.toFloat()).coerceIn(0.05f, 1f)
                            val kgVol = state.muscleVolumeDistribution[entry.key]?.toInt() ?: 0
                            
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
                                                text = entry.key,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                        }
                                        Text(
                                            text = "${entry.value} séries • ${kgVol}kg",
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
                                                .fillMaxWidth(fraction)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(group.color)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
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
                items(summary.exercises) { exSummary ->
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
                                    Text(
                                        text = "${set.weight}kg x ${set.repetitions}",
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
    val totalVolume = summary.exercises.sumOf { ex -> ex.sets.filter{it.completed}.sumOf { (it.weight * it.repetitions).toDouble() } }.toInt()
    
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
