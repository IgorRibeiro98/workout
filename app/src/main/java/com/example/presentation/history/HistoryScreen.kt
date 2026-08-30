package com.example.presentation.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import com.example.domain.engine.MuscleVisualResolver
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsState()
    
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentMonth by remember { mutableStateOf(Calendar.getInstance()) }
    var selectedSummaryForDetail by remember { mutableStateOf<SessionCalendarSummary?>(null) }

    Scaffold(
        containerColor = BackgroundDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Histórico de Treinos",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Segmented Control Tabs
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

            when (selectedTab) {
                0 -> {
                    // Calendário Tab
                    CalendarView(
                        currentMonth = currentMonth,
                        onMonthChange = { currentMonth = it },
                        selectedDate = state.selectedDate,
                        onDateSelected = { viewModel.selectDate(it) },
                        summaries = state.calendarSummaries
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(state.selectedDate),
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (state.sessionsForSelectedDate.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Nenhum treino realizado nesta data.",
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.sessionsForSelectedDate) { summary ->
                                SessionDetailsCard(
                                    summary = summary,
                                    onDelete = { viewModel.deleteSession(summary.session) },
                                    onCardClick = { selectedSummaryForDetail = summary },
                                    onUpdateCheckIn = { checkIn, cin, cout, gym ->
                                        viewModel.updateCheckInTime(checkIn, cin, cout, gym)
                                    }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    }
                }
                1 -> {
                    // Todos os Treinos Tab
                    if (state.allCompletedSessions.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Nenhum treino registrado ainda.",
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.allCompletedSessions) { summary ->
                                SessionDetailsCard(
                                    summary = summary,
                                    onDelete = { viewModel.deleteSession(summary.session) },
                                    onCardClick = { selectedSummaryForDetail = summary },
                                    onUpdateCheckIn = { checkIn, cin, cout, gym ->
                                        viewModel.updateCheckInTime(checkIn, cin, cout, gym)
                                    }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    }
                }
                2 -> {
                    // Volume Muscular Tab
                    if (state.muscleSetsDistribution.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Sem dados de volume suficientes.",
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        val maxSets = (state.muscleSetsDistribution.values.maxOrNull() ?: 1).coerceAtLeast(1)
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Text(
                                    text = "Distribuição de Séries por Grupo Muscular",
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
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
                            }
                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }
    }

    // Detail BottomSheet for workout session
    if (selectedSummaryForDetail != null) {
        val summary = selectedSummaryForDetail!!
        ModalBottomSheet(
            onDismissRequest = { selectedSummaryForDetail = null },
            containerColor = SurfaceDark,
            dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = summary.session.templateNameSnapshot ?: "Treino",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.getDefault()).format(Date(summary.session.startedAt)),
                            color = Lime400,
                            fontSize = 13.sp
                        )
                    }
                    IconButton(onClick = { selectedSummaryForDetail = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(summary.exercises) { exWithSets ->
                        val group = MuscleVisualResolver.resolveGroup(exWithSets.exerciseSession.primaryMuscleSnapshot)
                        Surface(
                            color = BackgroundDark,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = group.icon,
                                        contentDescription = null,
                                        tint = group.color,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = exWithSets.exerciseSession.exerciseNameSnapshot,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    if (!exWithSets.exerciseSession.machineLabelSnapshot.isNullOrBlank()) {
                                        Text(
                                            text = " (${exWithSets.exerciseSession.machineLabelSnapshot})",
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                exWithSets.sets.forEach { s ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Série ${s.setNumber} (${s.type})", color = TextSecondary, fontSize = 13.sp)
                                        Text("${s.weight}kg × ${s.repetitions} reps", color = if (s.completed) Lime400 else TextTertiary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun CalendarView(
    currentMonth: Calendar,
    onMonthChange: (Calendar) -> Unit,
    selectedDate: Date,
    onDateSelected: (Date) -> Unit,
    summaries: List<SessionCalendarSummary>
) {
    val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceDark)
            .padding(16.dp)
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
                Text(text = day, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        val cal = currentMonth.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        
        val totalCells = firstDayOfWeek + daysInMonth
        val rows = Math.ceil(totalCells / 7.0).toInt()
        
        var dayCounter = 1
        
        for (i in 0 until rows) {
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
}

@Composable
fun SessionDetailsCard(
    summary: SessionCalendarSummary,
    onDelete: () -> Unit,
    onCardClick: () -> Unit,
    onUpdateCheckIn: (com.example.data.local.CheckInEntity, Long, Long?, String?) -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val started = timeFormat.format(Date(summary.session.startedAt))
    val finished = summary.session.finishedAt?.let { timeFormat.format(Date(it)) } ?: "--:--"
    
    val durationMs = if (summary.session.finishedAt != null) summary.session.finishedAt - summary.session.startedAt else 0L
    val durationMin = durationMs / 60000
    val durationStr = if (durationMin > 60) "${durationMin/60}h${durationMin%60}m" else "${durationMin}m"
    
    val totalSets = summary.exercises.sumOf { ex -> ex.sets.count { it.completed } }
    val totalVolume = summary.exercises.sumOf { ex -> ex.sets.filter{it.completed}.sumOf { (it.weight * it.repetitions).toDouble() } }.toInt()
    
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    
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
            Row {
                IconButton(onClick = { showEditDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar", tint = TextSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = TextSecondary)
                }
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
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Excluir Treino", color = TextPrimary) },
            text = { Text("Tem certeza que deseja remover este treino do seu histórico? Esta ação não pode ser desfeita.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text("Excluir", color = Red500, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    if (showEditDialog) {
        var gymName by remember { mutableStateOf(summary.checkIn?.gymName ?: "") }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Editar Check-in", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = gymName,
                        onValueChange = { gymName = it },
                        label = { Text("Nome da Academia (opcional)") },
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
                    summary.checkIn?.let { ci ->
                        onUpdateCheckIn(ci, ci.checkInTime, ci.checkOutTime, gymName.takeIf { it.isNotBlank() })
                    }
                    showEditDialog = false
                }) {
                    Text("Salvar", color = Lime400, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
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
