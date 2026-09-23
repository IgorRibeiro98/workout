package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.WorkoutShareContent
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareItem
import com.example.domain.social.WorkoutShareKind
import com.example.domain.social.WorkoutShareStatus
import com.example.domain.workout.template.WeekdaySchedule
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedWorkoutsScreen(
    viewModel: SharedWorkoutsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Treinos Compartilhados", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BackgroundDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = if (state.selectedTab == SharedWorkoutsTab.RECEIVED) 0 else 1,
                containerColor = BackgroundDark,
                contentColor = Lime400,
                indicator = { tabPositions ->
                    val index = if (state.selectedTab == SharedWorkoutsTab.RECEIVED) 0 else 1
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[index]),
                        color = Lime400
                    )
                }
            ) {
                Tab(
                    selected = state.selectedTab == SharedWorkoutsTab.RECEIVED,
                    onClick = { viewModel.selectTab(SharedWorkoutsTab.RECEIVED) },
                    text = {
                        Text(
                            "Recebidos (${state.receivedItems.size})",
                            color = if (state.selectedTab == SharedWorkoutsTab.RECEIVED) Lime400 else TextSecondary
                        )
                    }
                )
                Tab(
                    selected = state.selectedTab == SharedWorkoutsTab.SENT,
                    onClick = { viewModel.selectTab(SharedWorkoutsTab.SENT) },
                    text = {
                        Text(
                            "Enviados (${state.sentItems.size})",
                            color = if (state.selectedTab == SharedWorkoutsTab.SENT) Lime400 else TextSecondary
                        )
                    }
                )
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Lime400)
                }
            } else {
                val currentList = if (state.selectedTab == SharedWorkoutsTab.RECEIVED) {
                    state.receivedItems
                } else {
                    state.sentItems
                }

                if (currentList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (state.selectedTab == SharedWorkoutsTab.RECEIVED) {
                                "Nenhum treino ou programa compartilhado com você ainda."
                            } else {
                                "Você ainda não compartilhou treinos ou programas com amigos."
                            },
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            fontSize = 15.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(currentList, key = { it.shareId }) { item ->
                            WorkoutShareCard(
                                item = item,
                                isReceived = state.selectedTab == SharedWorkoutsTab.RECEIVED,
                                onClick = { viewModel.openDetail(item.shareId) },
                                onCancel = { viewModel.cancelShare(item.shareId) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal de Prévia / Adicionar
    // O detalhe é lido uma vez: `state` é um delegate, e cada `state.previewDetail` era uma
    // leitura nova que podia voltar nula entre a checagem e o uso.
    val previewDetail = state.previewDetail
    if (previewDetail != null) {
        WorkoutSharePreviewDialog(
            detail = previewDetail,
            isReceived = state.selectedTab == SharedWorkoutsTab.RECEIVED,
            isImporting = state.importingShareId == previewDetail.shareId,
            onDismiss = { viewModel.closeDetail() },
            // O conteúdo importado é o que o servidor devolver no aceite, não o da prévia.
            onImport = { viewModel.importShare(previewDetail.shareId) },
            onDecline = {
                viewModel.declineShare(previewDetail.shareId)
            }
        )
    }
}

@Composable
fun WorkoutShareCard(
    item: WorkoutShareItem,
    isReceived: Boolean,
    onClick: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = Lime400,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.templateName,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                StatusBadge(status = item.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isReceived) {
                    "De: ${item.otherUser.displayName}"
                } else {
                    "Para: ${item.otherUser.displayName}"
                },
                color = TextSecondary,
                fontSize = 14.sp
            )

            Text(
                text = if (item.kind == WorkoutShareKind.WORKOUT_PROGRAM) {
                    "Programa · ${item.templateCount} treinos · ${item.exerciseCount} exercícios"
                } else {
                    "${item.exerciseCount} exercícios"
                },
                color = TextSecondary,
                fontSize = 13.sp
            )

            if (!isReceived && item.status == WorkoutShareStatus.PENDING) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancelar", color = Red400, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: WorkoutShareStatus) {
    val (label, bg, fg) = when (status) {
        WorkoutShareStatus.PENDING -> Triple("Pendente", Color(0xFF332B00), Color(0xFFFFD54F))
        WorkoutShareStatus.ACCEPTED -> Triple("Aceito", Color(0xFF1B382B), Lime400)
        WorkoutShareStatus.IMPORTED -> Triple("Adicionado", Color(0xFF1B382B), Lime400)
        WorkoutShareStatus.DECLINED -> Triple("Recusado", Color(0xFF3B1D1D), Red400)
        WorkoutShareStatus.CANCELLED -> Triple("Cancelado", Color(0xFF2E2E2E), Color.LightGray)
        WorkoutShareStatus.EXPIRED -> Triple("Expirado", Color(0xFF2E2E2E), Color.Gray)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun WorkoutSharePreviewDialog(
    detail: WorkoutShareDetail,
    isReceived: Boolean,
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onDecline: () -> Unit
) {
    val content = detail.content
    val isProgram = detail.kind == WorkoutShareKind.WORKOUT_PROGRAM

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceDark,
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = content?.displayName ?: if (isProgram) "Programa Compartilhado" else "Treino Compartilhado",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isReceived) {
                        "Compartilhado por ${detail.sender.displayName}"
                    } else {
                        "Enviado para ${detail.recipient.displayName}"
                    },
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Aviso de privacidade e independência
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E2638),
                    border = BorderStroke(1.dp, Color(0xFF2C3E60)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF90CAF9),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isProgram) {
                                "Ao adicionar, uma cópia independente do programa inteiro é criada na sua conta, " +
                                    "sem virar o seu programa atual. Cargas planejadas, anotações e histórico do amigo " +
                                    "não são incluídos, e alterações que ele fizer depois não chegam até você."
                            } else {
                                "Ao adicionar, uma cópia independente é criada na sua conta. " +
                                    "Cargas planejadas, anotações e histórico do amigo não são incluídos."
                            },
                            color = Color(0xFFE3F2FD),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (content) {
                    is WorkoutShareContent.Workout -> {
                        Text(
                            text = "Exercícios (${content.exerciseCount})",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SharedExerciseList(
                            exercises = content.snapshot.exercises,
                            customNames = content.snapshot.customExercises.associate { it.ref to it.name }
                        )
                    }
                    is WorkoutShareContent.Program -> {
                        content.snapshot.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(text = description, color = TextSecondary, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Text(
                            text = "Treinos (${content.templateCount}) · ${content.exerciseCount} exercícios",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            content.snapshot.templates.sortedBy { it.orderInProgram }.forEach { template ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BackgroundDark, RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = template.shortIdentifier?.takeIf { it.isNotBlank() } ?: "•",
                                            color = Lime400,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = template.name,
                                            color = TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        WeekdaySchedule.formatShort(template.scheduledDays)?.let { days ->
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = days, color = Lime400, fontSize = 12.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    SharedExerciseList(
                                        exercises = template.exercises,
                                        compact = true,
                                        customNames = content.snapshot.customExercises.associate { it.ref to it.name }
                                    )
                                }
                            }
                        }
                    }
                    null -> {
                        Text(
                            text = "Esta oferta não pode ser adicionada nesta versão do Spark. Atualize o app para vê-la.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = BorderLight)
                Spacer(modifier = Modifier.height(16.dp))

                if (isReceived && (detail.status == WorkoutShareStatus.PENDING || detail.status == WorkoutShareStatus.ACCEPTED)) {
                    if (isImporting) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Lime400)
                        }
                    } else {
                        if (content != null) {
                            Button(
                                onClick = onImport,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Lime400,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (isProgram) "Adicionar aos meus programas" else "Adicionar aos meus treinos",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (detail.status == WorkoutShareStatus.PENDING) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onDecline,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Recusar", color = Red400)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Fechar", color = TextSecondary)
                }
            }
        }
    }
}

/**
 * A prévia dos exercícios de uma oferta.
 *
 * [customNames] traz o nome de cada `customExerciseRef` da oferta (T19.H2): um exercício CUSTOM não
 * tem id de catálogo para virar rótulo, e o nome que a prévia mostra é o que veio no snapshot — o
 * mesmo que o destinatário vai criar.
 *
 * Um treino sem exercícios não é erro desde a T19.H2: a prévia diz isso em vez de não desenhar nada.
 */
@Composable
private fun SharedExerciseList(
    exercises: List<SharedExerciseSnapshot>,
    compact: Boolean = false,
    customNames: Map<String, String> = emptyMap()
) {
    if (exercises.isEmpty()) {
        Text(
            text = "Sem exercícios — este treino chega vazio, pronto para você montar.",
            color = TextSecondary,
            fontSize = if (compact) 12.sp else 13.sp
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        exercises.sortedBy { it.sortOrder }.forEachIndexed { idx, ex ->
            Column(
                modifier = if (compact) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .background(BackgroundDark, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                }
            ) {
                val label = ex.canonicalExerciseId
                    ?.removePrefix("canonical:")
                    ?.replace("-", " ")
                    ?.capitalizeWords()
                    ?: ex.customExerciseRef?.let { customNames[it] }
                    ?: "Exercício"
                Text(
                    text = "${idx + 1}. $label",
                    color = TextPrimary,
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${ex.targetSets} séries × ${ex.minReps}–${ex.maxReps} reps • Descanso: ${ex.restDurationSeconds}s",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun String.capitalizeWords(): String =
    split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
