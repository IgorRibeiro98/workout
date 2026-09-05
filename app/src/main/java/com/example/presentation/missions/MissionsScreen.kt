package com.example.presentation.missions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val PtBr = Locale("pt", "BR")
private val DayMonthFormatter = DateTimeFormatter.ofPattern("dd/MM", PtBr)

/**
 * Missões: os objetivos que o Spark acompanha a partir do treino real.
 *
 * A tela apenas renderiza [MissionUiState]. Progresso, alvo, prazo e recompensa chegam prontos da
 * autoridade de missões — nenhuma regra é decidida aqui.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionsScreen(
    viewModel: MissionViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    MissionsScreenContent(uiState = uiState, onNavigateBack = onNavigateBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionsScreenContent(
    uiState: MissionUiState,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Missões",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
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
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Lime400, strokeWidth = 3.dp)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            MissionsSummary(uiState = uiState)

            if (uiState.activeMissions.isNotEmpty()) {
                MissionSection(title = "Missões ativas") {
                    uiState.activeMissions.forEach { mission ->
                        ActiveMissionCard(mission = mission)
                    }
                }
            } else if (!uiState.hasMissions) {
                EmptyMissionsCard()
            }

            if (uiState.completedMissions.isNotEmpty()) {
                MissionSection(title = "Concluídas recentemente") {
                    uiState.completedMissions.forEach { mission ->
                        CompletedMissionRow(mission = mission)
                    }
                }
            }

            if (uiState.expiredMissions.isNotEmpty()) {
                MissionSection(title = "Expiradas") {
                    uiState.expiredMissions.forEach { mission ->
                        ExpiredMissionRow(mission = mission)
                    }
                }
            }
        }
    }
}

@Composable
private fun MissionsSummary(uiState: MissionUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            value = "${uiState.activeMissions.size}",
            label = "Ativas",
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            value = "${uiState.completedMissions.size}",
            label = "Concluídas",
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            value = "+${uiState.availableRewardXp}",
            label = "XP em aberto",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier.fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp
            )
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun MissionSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        content()
    }
}

@Composable
private fun ActiveMissionCard(mission: MissionUiItem) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = mission.title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = mission.description,
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${mission.progress} / ${mission.target}",
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp
            )

            LinearProgressIndicator(
                progress = { mission.progressPercentage },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = Lime400,
                trackColor = SurfaceHighlight
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "+${mission.rewardXp} XP",
                    color = Lime400,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                val deadline = mission.expiresAt?.let { deadlineLabel(it) }
                if (deadline != null) {
                    Text(
                        text = deadline,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletedMissionRow(mission: MissionUiItem) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Lime400.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Lime400,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mission.title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                val completedLabel = mission.completedAt?.let { formatDate(it) }
                if (completedLabel != null) {
                    Text(
                        text = "Concluída em $completedLabel",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Text(
                text = "+${mission.rewardXp} XP",
                color = Lime400,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun ExpiredMissionRow(mission: MissionUiItem) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mission.title,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "${mission.progress} / ${mission.target} — período encerrado",
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyMissionsCard() {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Nenhuma missão por aqui ainda.",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = "Conclua um treino para começar a acompanhar seus objetivos.",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

/** Prazo em linguagem do usuário; a data em si continua vindo do domínio. */
private fun deadlineLabel(
    expiresAt: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId)
): String {
    val endDate = Instant.ofEpochMilli(expiresAt).atZone(zoneId).toLocalDate()
    return when (val days = ChronoUnit.DAYS.between(today, endDate)) {
        0L -> "Termina hoje"
        1L -> "Termina amanhã"
        in 2L..6L -> "Termina ${endDate.dayOfWeek.getDisplayName(TextStyle.FULL, PtBr)}"
        else -> if (days < 0) "Período encerrado" else "Termina em $days dias"
    }
}

private fun formatDate(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().format(DayMonthFormatter)
