package com.example.feature.evolution.achievements.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.feature.evolution.achievements.AchievementsUiState
import com.example.feature.evolution.achievements.AchievementsViewModel
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AchievementSection(
    viewModel: AchievementsViewModel,
    modifier: Modifier = Modifier,
    testTag: String = "achievement_section"
) {
    val uiState by viewModel.uiState.collectAsState()
    AchievementSection(
        uiState = uiState,
        onCategorySelect = { viewModel.selectCategory(it) },
        onAchievementClick = { viewModel.selectAchievementForDetail(it) },
        onDismissDetail = { viewModel.selectAchievementForDetail(null) },
        modifier = modifier,
        testTag = testTag
    )
}

@Composable
fun AchievementSection(
    uiState: AchievementsUiState,
    modifier: Modifier = Modifier,
    onCategorySelect: (AchievementCategory?) -> Unit = {},
    onAchievementClick: (Achievement) -> Unit = {},
    onDismissDetail: () -> Unit = {},
    testTag: String = "achievement_section"
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag(testTag)
    ) {
        // 9.1 Cabeçalho
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🏆",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Conquistas",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                if (!uiState.isLoading && uiState.totalCount > 0) {
                    Text(
                        text = "${uiState.unlockedCount} de ${uiState.totalCount} desbloqueadas",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Lime400
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Barra de progresso geral
            LinearProgressIndicator(
                progress = { uiState.overallProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Lime400,
                trackColor = Color.White.copy(alpha = 0.1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 9.2 Filtros de categoria
        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val categories = listOf(
                null to "Todos",
                AchievementCategory.TRAINING to "Treino",
                AchievementCategory.CONSISTENCY to "Consistência",
                AchievementCategory.PERFORMANCE to "Performance",
                AchievementCategory.BODY to "Corpo"
            )

            categories.forEach { (cat, label) ->
                val isSelected = uiState.selectedCategory == cat
                FilterChip(
                    selected = isSelected,
                    onClick = { onCategorySelect(cat) },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = SurfaceDark,
                        labelColor = TextSecondary,
                        selectedContainerColor = Lime400.copy(alpha = 0.2f),
                        selectedLabelColor = Lime400
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = Color.White.copy(alpha = 0.1f),
                        selectedBorderColor = Lime400.copy(alpha = 0.6f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Lime400,
                        strokeWidth = 3.dp
                    )
                }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    uiState.displayedAchievements.forEach { achievement ->
                        AchievementCard(
                            achievement = achievement,
                            onClick = { onAchievementClick(achievement) }
                        )
                    }
                }
            }
        }
    }

    // 11. Tela de detalhe (ModalBottomSheet)
    val selectedDetail = uiState.selectedAchievementForDetail
    if (selectedDetail != null) {
        AchievementDetailBottomSheet(
            achievement = selectedDetail,
            onDismiss = onDismissDetail
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AchievementDetailBottomSheet(
    achievement: Achievement,
    onDismiss: () -> Unit
) {
    val isUnlocked = achievement.unlockedAt != null
    val tierColor = getTierColor(achievement.tier)
    val categoryName = when (achievement.category) {
        AchievementCategory.TRAINING -> "Treino"
        AchievementCategory.CONSISTENCY -> "Consistência"
        AchievementCategory.PERFORMANCE -> "Performance"
        AchievementCategory.BODY -> "Corpo"
    }

    val unitLabel = when (achievement.category) {
        AchievementCategory.TRAINING -> if (achievement.targetProgress == 1) "treino" else "treinos"
        AchievementCategory.CONSISTENCY -> if (achievement.targetProgress == 1) "semana" else "semanas"
        AchievementCategory.PERFORMANCE -> if (achievement.targetProgress == 1) "recorde" else "recordes"
        AchievementCategory.BODY -> if (achievement.targetProgress == 1) "medição" else "medições"
    }

    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Detalhes da Conquista"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        if (isUnlocked) tierColor.copy(alpha = 0.2f)
                        else Color.White.copy(alpha = 0.05f)
                    )
                    .border(2.dp, if (isUnlocked) tierColor else Color.White.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isUnlocked) achievement.icon else "🔒",
                    fontSize = 36.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Title
            Text(
                text = achievement.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Tier & Category Badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tierColor.copy(alpha = 0.2f))
                        .border(1.dp, tierColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Tier ${getTierName(achievement.tier)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tierColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceHighlight)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = categoryName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Description
            Text(
                text = achievement.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Progress & Status Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceHighlight)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Status",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                        Text(
                            text = if (isUnlocked) "Desbloqueada" else "Em progresso",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isUnlocked) Lime400 else TextSecondary
                        )
                    }

                    if (isUnlocked && achievement.unlockedAt != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val dateFormatted = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
                            .format(Date(achievement.unlockedAt))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Data",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                            Text(
                                text = dateFormatted,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Progresso",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                        Text(
                            text = "${achievement.currentProgress}/${achievement.targetProgress} $unitLabel",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { achievement.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = tierColor,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
