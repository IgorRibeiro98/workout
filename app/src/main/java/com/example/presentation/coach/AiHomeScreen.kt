package com.example.presentation.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.ai.model.AiCapability
import com.example.ui.components.HubEntryCard
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * AiHome (T19.1): o hub que organiza as capacidades do Coach IA por capacidade, em vez de uma
 * única entrada "Coach IA" acumulando tudo dentro do Perfil.
 *
 * ```text
 * AiHome
 * ↓
 * capabilities da conta (T19.0)
 * ↓
 * ação selecionada
 * ↓
 * fluxo existente de Coach
 * ```
 *
 * Este hub não decide autorização sozinha: ele só lê o mesmo [AiCapabilitiesViewModel] que já
 * observava as telas do Coach (compartilhado desde `MainScreen`), para não convidar uma ação que o
 * backend recusaria — o backend continua sendo quem valida de verdade, a cada chamada real, dentro
 * de cada tela de destino. Abrir este hub não fala com o provider de IA: só navega.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHomeScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAnalyze: () -> Unit,
    onNavigateToGenerate: () -> Unit,
    onNavigateToAdaptEntry: () -> Unit,
    /** Capabilities de IA (T19.0). `null` faz o hub tratar tudo como indeterminado. */
    capabilitiesViewModel: AiCapabilitiesViewModel? = null
) {
    val capabilitiesState by (capabilitiesViewModel?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(AiCapabilitiesUiState.LoadFailed))
        .collectAsStateWithLifecycle()

    LaunchedEffect(capabilitiesViewModel) { capabilitiesViewModel?.ensureLoaded() }

    AiHomeScreenContent(
        capabilitiesState = capabilitiesState,
        onNavigateBack = onNavigateBack,
        onNavigateToAnalyze = onNavigateToAnalyze,
        onNavigateToGenerate = onNavigateToGenerate,
        onNavigateToAdaptEntry = onNavigateToAdaptEntry,
        onRetryCapabilities = { capabilitiesViewModel?.retry() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiHomeScreenContent(
    capabilitiesState: AiCapabilitiesUiState,
    onNavigateBack: () -> Unit,
    onNavigateToAnalyze: () -> Unit,
    onNavigateToGenerate: () -> Unit,
    onNavigateToAdaptEntry: () -> Unit,
    onRetryCapabilities: () -> Unit = {}
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "O Coach analisa o que você já treinou e sugere ajustes. " +
                    "Ele nunca altera seus treinos sozinho.",
                color = TextSecondary,
                fontSize = 13.sp
            )

            CapabilityEntry(
                icon = Icons.Default.AutoAwesome,
                title = "Analisar meu treino",
                subtitle = "Uma leitura do seu treino a partir do histórico real",
                capability = AiCapability.AI_ANALYZE_WORKOUT,
                capabilitiesState = capabilitiesState,
                deniedMessage = "A análise de treino não está disponível para esta conta.",
                onClick = onNavigateToAnalyze,
                onRetry = onRetryCapabilities
            )

            CapabilityEntry(
                icon = Icons.Default.AddCircleOutline,
                title = "Gerar treino",
                subtitle = "Um treino novo, a partir dos seus objetivos e equipamentos",
                capability = AiCapability.AI_GENERATE_WORKOUT,
                capabilitiesState = capabilitiesState,
                deniedMessage = "A geração de treino não está disponível para esta conta.",
                onClick = onNavigateToGenerate,
                onRetry = onRetryCapabilities
            )

            CapabilityEntry(
                icon = Icons.Default.Tune,
                title = "Adaptar treino",
                subtitle = "Escolha um treino em Treinos para pedir um ajuste ao Coach",
                capability = AiCapability.AI_ADAPT_WORKOUT,
                capabilitiesState = capabilitiesState,
                deniedMessage = "A adaptação de treino não está disponível para esta conta.",
                onClick = onNavigateToAdaptEntry,
                onRetry = onRetryCapabilities
            )
        }
    }
}

/**
 * Um card do hub mais o aviso de capability logo abaixo dele (T19.0/T19.1).
 *
 * `DENIED` não navega: o card fica visualmente inerte, e o aviso explica por quê — a mesma regra
 * de "capability negada não inicia ação de IA" que cada tela de destino já aplica sozinha, só que
 * antes do toque chegar lá. `DETERMINING` (carregando, ou sem conta — o convite de login continua
 * sendo responsabilidade da tela de destino) e `UNKNOWN` continuam navegáveis: a tela de destino é
 * quem decide o que mostrar quando a disponibilidade não pôde ser confirmada.
 */
@Composable
private fun CapabilityEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    capability: AiCapability,
    capabilitiesState: AiCapabilitiesUiState,
    deniedMessage: String,
    onClick: () -> Unit,
    onRetry: () -> Unit
) {
    val availability = capabilitiesState.availabilityOf(capability)
    val isDenied = availability == CoachActionAvailability.DENIED

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HubEntryCard(
            icon = icon,
            title = title,
            subtitle = subtitle,
            onClick = onClick,
            enabled = !isDenied
        )
        CoachCapabilityNotice(
            availability = availability,
            deniedMessage = deniedMessage,
            onRetry = onRetry
        )
    }
}
