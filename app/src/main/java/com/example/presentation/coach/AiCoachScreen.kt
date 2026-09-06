package com.example.presentation.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.domain.ai.model.AiDataQualityLevel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Orange400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * Prova de integração do Coach IA: um botão explícito, um resumo e as sugestões.
 *
 * A tela não chama o provider ao abrir, ao girar ou ao recompor — só o toque em
 * "Analisar meu treino" fala com o modelo. Nada aqui aplica sugestão: o que aparece é conselho.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCoachScreen(
    viewModel: AiCoachViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGenerateWorkout: () -> Unit = {},
    /** Entrar na Conta Spark, pela mesma infraestrutura da T16.1. Nunca disparado sozinho. */
    onSignIn: (android.content.Context) -> Unit = {},
    isSignInAvailable: Boolean = true,
    isSigningIn: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val explanationState by viewModel.explanationState.collectAsState()

    AiCoachScreenContent(
        uiState = uiState,
        onAnalyze = viewModel::analyze,
        onNavigateBack = onNavigateBack,
        onNavigateToGenerateWorkout = onNavigateToGenerateWorkout,
        canExplain = viewModel.canExplain,
        onExplain = viewModel::explain,
        onSignIn = onSignIn,
        isSignInAvailable = isSignInAvailable,
        isSigningIn = isSigningIn
    )

    CoachExplanationSheet(
        state = explanationState,
        onDismiss = viewModel::dismissExplanation
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiCoachScreenContent(
    uiState: AiCoachUiState,
    onAnalyze: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToGenerateWorkout: () -> Unit = {},
    /** `false` esconde as entradas contextuais: sem Coach, nenhum botão promete o que não há. */
    canExplain: Boolean = false,
    /** Recebe o id do alvo, nunca o texto exibido. */
    onExplain: (String) -> Unit = {},
    onSignIn: (android.content.Context) -> Unit = {},
    isSignInAvailable: Boolean = true,
    isSigningIn: Boolean = false
) {
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Coach IA",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "O Coach analisa o que você já treinou e sugere ajustes. " +
                    "Ele nunca altera seus treinos sozinho.",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Button(
                onClick = onAnalyze,
                enabled = uiState !is AiCoachUiState.Loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime400,
                    contentColor = BackgroundDark,
                    disabledContainerColor = SurfaceDark,
                    disabledContentColor = TextTertiary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "  Analisar meu treino",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            // Segunda capacidade do mesmo Coach, não uma segunda área de IA.
            Button(
                onClick = onNavigateToGenerateWorkout,
                enabled = uiState !is AiCoachUiState.Loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceDark,
                    contentColor = TextPrimary,
                    disabledContainerColor = SurfaceDark,
                    disabledContentColor = TextTertiary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.AddCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "  Criar treino com IA",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            when (uiState) {
                AiCoachUiState.Idle -> Unit

                AiCoachUiState.Loading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = Lime400, strokeWidth = 3.dp)
                    // Uma etapa só, porque é isso que está acontecendo de verdade.
                    Text(
                        text = "Analisando seu treino...",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                is AiCoachUiState.Success -> AdviceSection(uiState, canExplain, onExplain)

                is AiCoachUiState.AuthRequired -> CoachAccountRequiredCard(
                    message = uiState.message,
                    isSignInAvailable = isSignInAvailable,
                    isSigningIn = isSigningIn,
                    onSignIn = onSignIn
                )

                is AiCoachUiState.Unavailable -> MessageCard(
                    message = uiState.message,
                    accent = Orange400,
                    isWarning = true
                )

                is AiCoachUiState.Error -> MessageCard(
                    message = if (uiState.canRetry) {
                        "${uiState.message} Você pode tentar novamente."
                    } else {
                        uiState.message
                    },
                    accent = Red400,
                    isWarning = false
                )
            }
        }
    }
}

@Composable
private fun AdviceSection(
    state: AiCoachUiState.Success,
    canExplain: Boolean,
    onExplain: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Resumo")
        Card {
            Text(text = state.summary, color = TextPrimary, fontSize = 14.sp)
        }

        if (state.positiveSignals.isNotEmpty()) {
            SectionTitle("Pontos positivos")
            state.positiveSignals.forEach { observation ->
                ObservationCard(
                    observation = observation,
                    marker = "✓",
                    markerColor = Lime400,
                    canExplain = canExplain,
                    onExplain = onExplain
                )
            }
        }

        if (state.attentionPoints.isNotEmpty()) {
            SectionTitle("Pontos de atenção")
            state.attentionPoints.forEach { observation ->
                ObservationCard(
                    observation = observation,
                    marker = "!",
                    markerColor = Orange400,
                    canExplain = canExplain,
                    onExplain = onExplain
                )
            }
        }

        if (state.recommendations.isNotEmpty()) {
            SectionTitle("Recomendações")
            state.recommendations.forEach { recommendation ->
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = recommendation.label,
                            color = Lime400,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        recommendation.exerciseName?.let { name ->
                            Text(text = name, color = TextSecondary, fontSize = 12.sp)
                        }
                        Text(text = recommendation.reason, color = TextPrimary, fontSize = 14.sp)
                        // A evidência fica visível: o usuário vê de onde a sugestão saiu.
                        recommendation.evidence?.let { evidence ->
                            Text(
                                text = "Base: $evidence",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "Confiança: ${recommendation.confidencePercent}%",
                            color = TextTertiary,
                            fontSize = 11.sp
                        )
                        // Entrada contextual: pequena, ao lado da decisão que ela explica.
                        if (canExplain) {
                            CoachExplanationTrigger(text = "Por quê?") {
                                onExplain(recommendation.id)
                            }
                        }
                    }
                }
            }
        }

        SectionTitle("Base da análise")
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = state.dataQuality.label,
                    color = state.dataQuality.level.accentColor(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = state.dataQuality.description,
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = sessionsLabel(state.dataQuality.sessionsAnalyzed),
                    color = TextTertiary,
                    fontSize = 11.sp
                )
                Text(
                    text = "O Coach apenas recomenda. Nada aqui foi aplicado ao seu treino.",
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun sessionsLabel(sessionsAnalyzed: Int): String = when (sessionsAnalyzed) {
    0 -> "Nenhuma sessão concluída utilizada"
    1 -> "1 sessão concluída utilizada"
    else -> "$sessionsAnalyzed sessões concluídas utilizadas"
}

@Composable
private fun AiDataQualityLevel.accentColor(): Color = when (this) {
    AiDataQualityLevel.INSUFFICIENT -> Red400
    AiDataQualityLevel.LIMITED -> Orange400
    AiDataQualityLevel.GOOD -> Lime400
}

@Composable
private fun ObservationCard(
    observation: AiObservationUi,
    marker: String,
    markerColor: Color,
    canExplain: Boolean,
    onExplain: (String) -> Unit
) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(text = marker, color = markerColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = observation.title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            observation.exerciseName?.let { name ->
                Text(text = name, color = TextSecondary, fontSize = 12.sp)
            }
            Text(text = observation.description, color = TextSecondary, fontSize = 13.sp)
            if (canExplain) {
                CoachExplanationTrigger(text = "Por quê?") { onExplain(observation.id) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun MessageCard(
    message: String,
    accent: Color,
    isWarning: Boolean
) {
    Card {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (isWarning) Icons.Default.CloudOff else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Text(text = message, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

