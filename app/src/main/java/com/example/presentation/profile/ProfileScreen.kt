package com.example.presentation.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.achievement.Achievement
import com.example.feature.evolution.achievements.components.getTierColor
import com.example.feature.evolution.achievements.components.getTierName
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.theme.*
import java.util.Locale

private val PtBr = Locale("pt", "BR")

private fun formatInt(value: Int): String = String.format(PtBr, "%,d", value)

/**
 * Perfil do Atleta: a identidade de progressão do usuário dentro do Spark.
 *
 * A tela apenas renderiza [ProfileUiState] e emite intenções. Nível, XP, sequência, conquistas,
 * treinos e recordes são calculados pelas autoridades do domínio e chegam aqui prontos — nenhuma
 * regra de gamificação vive nesta Composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToBodyEvolution: () -> Unit,
    onNavigateToAchievements: () -> Unit,
    onNavigateToMissions: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    ProfileScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToBodyEvolution = onNavigateToBodyEvolution,
        onNavigateToAchievements = onNavigateToAchievements,
        onNavigateToMissions = onNavigateToMissions,
        onWeeklyGoalChange = viewModel::setWeeklyGoal
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreenContent(
    uiState: ProfileUiState,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToBodyEvolution: () -> Unit,
    onNavigateToAchievements: () -> Unit,
    onNavigateToMissions: () -> Unit,
    onWeeklyGoalChange: (Int) -> Unit
) {
    var showGoalBottomSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Perfil do Atleta",
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
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configurações",
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
            AthleteHeaderCard(uiState = uiState)

            StatsGrid(uiState = uiState)

            MissionsSection(onClick = onNavigateToMissions)

            AchievementsPreviewSection(
                achievements = uiState.recentAchievements,
                onSeeAll = onNavigateToAchievements
            )

            WeeklyGoalSection(
                uiState = uiState,
                onEdit = { showGoalBottomSheet = true }
            )

            BodyEvolutionSection(
                latestWeightKg = uiState.latestWeightKg,
                onClick = onNavigateToBodyEvolution
            )

            SettingsSection(onClick = onNavigateToSettings)
        }
    }

    if (showGoalBottomSheet) {
        WeeklyGoalBottomSheet(
            currentWeeklyGoal = uiState.weeklyGoal,
            nextWeeklyGoal = uiState.nextWeekGoal,
            onDismiss = { showGoalBottomSheet = false },
            onConfirm = { goal ->
                onWeeklyGoalChange(goal)
                showGoalBottomSheet = false
            }
        )
    }
}

/**
 * Cabeçalho: quem sou eu no Spark hoje.
 *
 * O nome permanece "Atleta" enquanto não existir identidade configurável.
 */
@Composable
private fun AthleteHeaderCard(uiState: ProfileUiState) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Lime400.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Avatar",
                        tint = Lime400,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Column {
                    Text(
                        text = "Atleta",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Nível ${uiState.level}",
                        color = Lime400,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${formatInt(uiState.totalXp)} XP total",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            LinearProgressIndicator(
                progress = { uiState.levelProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = Lime400,
                trackColor = SurfaceHighlight
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatInt(uiState.currentLevelXp)} / ${formatInt(uiState.xpForNextLevel)} XP",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "${formatInt(uiState.xpToNextLevel)} XP para o nível ${uiState.level + 1}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun StatsGrid(uiState: ProfileUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                emoji = "🔥",
                value = "${uiState.streakWeeks}",
                unit = if (uiState.streakWeeks == 1) "semana" else "semanas",
                label = "Sequência",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                emoji = "🏋️",
                value = formatInt(uiState.completedWorkouts),
                unit = null,
                label = "Treinos",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                emoji = "🏆",
                value = "${uiState.unlockedAchievements} / ${uiState.totalAchievements}",
                unit = null,
                label = "Conquistas",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                emoji = "⚡",
                value = formatInt(uiState.personalRecordsCount),
                unit = null,
                label = "Recordes",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatCard(
    emoji: String,
    value: String,
    unit: String?,
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = emoji, fontSize = 18.sp)
                Text(
                    text = value,
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Prévia das conquistas. A coleção completa continua sendo responsabilidade da área de Evolução.
 */
@Composable
private fun AchievementsPreviewSection(
    achievements: List<Achievement>,
    onSeeAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Conquistas",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onSeeAll() }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (achievements.isEmpty()) {
                    Text(
                        text = "Sua primeira conquista está a caminho.",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Continue treinando para desbloqueá-la.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                } else {
                    achievements.forEach { achievement ->
                        AchievementPreviewRow(achievement = achievement)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ver todas",
                        color = Lime400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Lime400,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AchievementPreviewRow(achievement: Achievement) {
    val tierColor = getTierColor(achievement.tier)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tierColor.copy(alpha = 0.15f))
                .border(1.dp, tierColor.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = achievement.icon, fontSize = 18.sp)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = achievement.title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = getTierName(achievement.tier),
                color = tierColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun WeeklyGoalSection(
    uiState: ProfileUiState,
    onEdit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Meta Semanal",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onEdit() }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LimeTransparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarToday,
                                contentDescription = null,
                                tint = Lime400,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "${uiState.weeklyCompleted} / ${uiState.weeklyGoal} treinos",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            if (uiState.hasPendingGoalChange) {
                                Text(
                                    text = "Próxima semana: ${uiState.nextWeekGoal} treinos",
                                    color = Lime400,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Editar",
                            color = Lime400,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val goalProgress = if (uiState.weeklyGoal > 0) {
                    (uiState.weeklyCompleted.toFloat() / uiState.weeklyGoal).coerceIn(0f, 1f)
                } else {
                    0f
                }

                LinearProgressIndicator(
                    progress = { goalProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Lime400,
                    trackColor = SurfaceHighlight
                )
            }
        }
    }
}

/**
 * Porta de entrada para as missões. O Perfil continua sendo o hub de progressão: as missões vivem
 * na própria área, com sua autoridade de avaliação.
 */
@Composable
private fun MissionsSection(onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Missões",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        ProfileNavigationCard(
            icon = Icons.Default.Flag,
            title = "Missões e Desafios",
            subtitle = "Objetivos da semana e marcos do seu treino",
            onClick = onClick
        )
    }
}

@Composable
private fun BodyEvolutionSection(
    latestWeightKg: Float?,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Evolução Corporal",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        ProfileNavigationCard(
            icon = Icons.Default.Straighten,
            title = "Evolução e Medidas Corporais",
            subtitle = if (latestWeightKg != null) {
                "Último peso: ${String.format(PtBr, "%.1f kg", latestWeightKg)}"
            } else {
                "Nenhuma medição registrada"
            },
            onClick = onClick
        )
    }
}

@Composable
private fun SettingsSection(onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Configurações",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        ProfileNavigationCard(
            icon = Icons.Default.Settings,
            title = "Configurações do Aplicativo",
            subtitle = "Preferências do aplicativo",
            onClick = onClick
        )
    }
}

@Composable
private fun ProfileNavigationCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(LimeTransparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Lime400,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Edição da meta semanal.
 *
 * A vigência continua sendo decidida pelo repositório de consistência: a semana atual mantém a meta
 * antiga e o novo valor passa a valer na próxima. Aqui só explicamos isso ao usuário.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeeklyGoalBottomSheet(
    currentWeeklyGoal: Int,
    nextWeeklyGoal: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedValue by remember { mutableIntStateOf(nextWeeklyGoal) }

    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Meta Semanal de Treinos",
        subtitle = "Quantos dias por semana você pretende treinar?"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            (1..7).forEach { days ->
                val isSelected = days == selectedValue
                Surface(
                    color = if (isSelected) Lime400.copy(alpha = 0.15f) else SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) Lime400 else BorderLight
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selectedValue = days }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$days ${if (days == 1) "treino" else "treinos"} por semana",
                                color = if (isSelected) Lime400 else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            val freqDesc = when (days) {
                                1 -> "Manutenção básica"
                                2 -> "Ritmo leve"
                                3 -> "Frequência recomendada para iniciantes"
                                4 -> "Equilíbrio ideal para hipertrofia"
                                5 -> "Frequência avançada"
                                6 -> "Rotina intensa"
                                else -> "Atividade diária"
                            }
                            Text(
                                text = freqDesc,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selecionado",
                                tint = Lime400,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            if (selectedValue != currentWeeklyGoal) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = SurfaceHighlight,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Sua nova meta começará na próxima semana.",
                            color = Lime400,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Esta semana continua com meta de $currentWeeklyGoal ${if (currentWeeklyGoal == 1) "treino" else "treinos"}.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { onConfirm(selectedValue) },
                colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = if (selectedValue != nextWeeklyGoal) "SALVAR NOVA META" else "Salvar Meta",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
