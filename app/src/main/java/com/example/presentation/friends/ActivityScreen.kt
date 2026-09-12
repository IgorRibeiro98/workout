package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.FriendActivityItem
import com.example.domain.social.FriendRankingEntry
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Tela de Atividade dos Amigos e Rankings Contextuais (T17.4).
 *
 * Apresenta:
 * 1. Ranking semanal móvel entre amigos (com opt-in contextual caso não participante).
 * 2. Feed recente de dias de treino dos amigos nos últimos 14 dias (0..13).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: SocialActivityViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Atividade e Ranking",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark
                )
            )
        },
        containerColor = BackgroundDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Seção 1: Ranking Semanal
            RankingSection(
                state = uiState.rankingState,
                isOptingIn = uiState.isOptingIn,
                optInError = uiState.optInErrorMessage,
                onOptIn = viewModel::optInToRanking,
                onRetry = viewModel::loadData
            )

            // Seção 2: Feed de Atividade dos Amigos
            ActivityFeedSection(
                state = uiState.activityState,
                onRetry = viewModel::loadData
            )
        }
    }
}

@Composable
private fun RankingSection(
    state: RankingUiState,
    isOptingIn: Boolean,
    optInError: String?,
    onOptIn: () -> Unit,
    onRetry: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Lime400,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Ranking semanal (últimos 7 dias)",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }

        when (state) {
            RankingUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Lime400, modifier = Modifier.size(28.dp))
                }
            }
            RankingUiState.OptedOut -> {
                OptInCard(
                    isOptingIn = isOptingIn,
                    errorMessage = optInError,
                    onOptIn = onOptIn
                )
            }
            is RankingUiState.Success -> {
                if (state.entries.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = SurfaceDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderLight)
                    ) {
                        Text(
                            text = "Nenhum participante no ranking esta semana.",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = SurfaceDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderLight)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            state.entries.forEach { entry ->
                                RankingEntryRow(entry = entry)
                            }
                        }
                    }
                }
            }
            is RankingUiState.Error -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = state.message, color = Red400, fontSize = 14.sp)
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                        ) {
                            Text("Tentar novamente", color = Lime400)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptInCard(
    isOptingIn: Boolean,
    errorMessage: String?,
    onOptIn: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Participe do ranking com seus amigos",
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
            Text(
                text = "O ranking compara a quantidade de treinos concluídos nos últimos 7 dias entre você e seus amigos diretos que também escolherem participar.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            if (errorMessage != null) {
                Text(text = errorMessage, color = Red400, fontSize = 13.sp)
            }

            Button(
                onClick = onOptIn,
                enabled = !isOptingIn,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime400,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                if (isOptingIn) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp))
                } else {
                    Text("Participar do ranking", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun RankingEntryRow(entry: FriendRankingEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "#${entry.rank}",
                color = if (entry.rank <= 3) Lime400 else TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.width(36.dp)
            )

            Text(
                text = entry.displayName,
                color = TextPrimary,
                fontWeight = if (entry.isCurrentUser) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp
            )

            if (entry.isCurrentUser) {
                Box(
                    modifier = Modifier
                        .background(Lime400.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Você",
                        color = Lime400,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Text(
            text = "${entry.score} ${if (entry.score == 1) "treino" else "treinos"}",
            color = TextSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun ActivityFeedSection(
    state: ActivityFeedUiState,
    onRetry: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FitnessCenter,
                contentDescription = null,
                tint = Lime400,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Atividade recente (últimos 14 dias)",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }

        when (state) {
            ActivityFeedUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Lime400, modifier = Modifier.size(28.dp))
                }
            }
            is ActivityFeedUiState.Success -> {
                if (state.items.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = SurfaceDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderLight)
                    ) {
                        Text(
                            text = "Nenhuma atividade recente dos seus amigos nos últimos 14 dias.",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.items.forEach { item ->
                            ActivityItemCard(item = item)
                        }
                    }
                }
            }
            is ActivityFeedUiState.Error -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = state.message, color = Red400, fontSize = 14.sp)
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                        ) {
                            Text("Tentar novamente", color = Lime400)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityItemCard(item: FriendActivityItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.displayName,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(
                    text = "Completou um treino",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }

            val relativeDay = when (item.daysAgo) {
                0 -> "Hoje"
                1 -> "Ontem"
                else -> "${item.daysAgo} dias atrás"
            }

            Text(
                text = relativeDay,
                color = if (item.daysAgo == 0) Lime400 else TextSecondary,
                fontWeight = if (item.daysAgo == 0) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp
            )
        }
    }
}
