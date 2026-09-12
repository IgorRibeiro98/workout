package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.Challenge
import com.example.domain.social.ChallengeDetail
import com.example.domain.social.ChallengeParticipantScore
import com.example.domain.social.ChallengeStatus
import com.example.presentation.account.ChallengeDetailPhase
import com.example.presentation.account.ChallengeUiState
import com.example.presentation.account.ChallengeViewModel
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle

const val CHALLENGE_DETAIL_DESCRIPTION = "Detalhe do desafio"
const val LEADERBOARD_DESCRIPTION = "Placar do desafio"

/**
 * O desafio aberto: regras, placar e o que dá para fazer (T17.3 §169–§179).
 *
 * ```text
 * 12 TREINOS
 * 10/09 — 09/10 · Em andamento
 *
 * 1. Igor       8 / 12     ← a própria linha, destacada discretamente
 * 2. João       7 / 12
 * 3. Jonathas   5 / 12
 *
 * [ Sair do desafio ]
 * ```
 *
 * ## O que esta tela nunca mostra
 *
 * Treino, exercício, carga, repetição, nota, horário e medida de ninguém — nem os do próprio
 * usuário. **Participar de um desafio dá acesso ao placar, e não aos dados que o produziram.** Ela
 * também não mostra linha do tempo de eventos ("Igor treinou às 19:32"): isso não existe nesta
 * fase, e o servidor nem devolve os instantes.
 *
 * ## A barra limita, o número não
 *
 * Uma pontuação acima da meta — 15 de 12 — aparece como **15**, e a barra é que para em 100%.
 * Truncar o número apagaria um fato para caber num desenho.
 *
 * ## Quando o resultado ainda pode mudar, a tela diz
 *
 * Em desafio encerrado, [RESULT_MAY_CHANGE_NOTICE] aparece enquanto o servidor não puder afirmar
 * que todos já sincronizaram — que é sempre, nesta fase. Prometer um resultado irrevogável seria
 * mentir sobre a única coisa que o desafio afirma.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeDetailScreen(
    challengeId: String,
    nameHint: String? = null,
    viewModel: ChallengeViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(challengeId) { viewModel.openChallenge(challengeId) }
    // Sair da tela descarta o que foi lido: ele é cache, e a próxima abertura relê. Sem isto, o
    // placar de ontem apareceria por um instante ao reabrir.
    DisposableEffect(challengeId) { onDispose { viewModel.closeChallenge() } }

    var confirming by remember { mutableStateOf<PendingAction?>(null) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = titleOf(uiState, nameHint),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = BACK_LABEL,
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
                .padding(16.dp)
                .semantics { contentDescription = CHALLENGE_DETAIL_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChallengeDetailBody(
                uiState = uiState,
                challengeId = challengeId,
                onRetry = viewModel::refreshChallenge,
                onRequestLeave = { confirming = PendingAction.Leave },
                onRequestCancel = { confirming = PendingAction.Cancel }
            )
        }
    }

    // A confirmação é obrigatória nas duas ações porque as duas são irreversíveis: não há rejoin,
    // e um desafio cancelado não volta.
    confirming?.let { action ->
        ConfirmDialog(
            action = action,
            onConfirm = {
                when (action) {
                    PendingAction.Leave -> viewModel.leaveChallenge(challengeId)
                    PendingAction.Cancel -> viewModel.cancelChallenge(challengeId)
                }
                confirming = null
            },
            onDismiss = { confirming = null }
        )
    }
}

private enum class PendingAction { Leave, Cancel }

@Composable
private fun ConfirmDialog(
    action: PendingAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                text = if (action == PendingAction.Leave) LEAVE_CONFIRM_TITLE
                else CANCEL_CONFIRM_TITLE,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = if (action == PendingAction.Leave) LEAVE_CONFIRM_BODY
                else CANCEL_CONFIRM_BODY,
                color = TextSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = if (action == PendingAction.Leave) LEAVE_CHALLENGE_LABEL
                    else CANCEL_CHALLENGE_LABEL,
                    color = Red400
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(BACK_LABEL, color = TextSecondary) }
        }
    )
}

/**
 * O corpo do detalhe, separado da `Scaffold` para poder ser renderizado em teste.
 *
 * Mesma decisão da T17.2 (`FriendSocialProfileBody`): o que precisa de prova é o **conteúdo** —
 * que o placar aparece na ordem certa, que a barra limita sem truncar o número, e que o aviso de
 * convergência aparece quando é verdade. Montar `ViewModel` + navegação num teste de Compose
 * provaria menos e quebraria por mais motivos.
 */
@Composable
internal fun ChallengeDetailBody(
    uiState: ChallengeUiState,
    challengeId: String,
    onRetry: () -> Unit,
    onRequestLeave: () -> Unit,
    onRequestCancel: () -> Unit
) {
    when (val phase = uiState.detailPhase) {
        ChallengeDetailPhase.Idle, ChallengeDetailPhase.Loading -> ChallengeBusy("Carregando...")

        ChallengeDetailPhase.NotAvailable -> ChallengeMessage(
            title = "Indisponível",
            body = "Este desafio não está mais disponível para você."
        )

        ChallengeDetailPhase.Offline -> {
            ChallengeMessage(
                title = "Sem conexão",
                body = "Não foi possível falar com o servidor, então nada foi alterado. " +
                    "Seus treinos e seu histórico continuam normais."
            )
            SecondaryAction("Tentar de novo", onRetry)
        }

        is ChallengeDetailPhase.Error -> {
            ChallengeMessage(title = "Não foi possível carregar", body = messageFor(phase.error))
            SecondaryAction("Tentar de novo", onRetry)
        }

        is ChallengeDetailPhase.Ready -> {
            val detail = phase.detail
            uiState.notice?.let { ChallengeMessage(title = "Aviso", body = messageFor(it)) }

            Header(detail.challenge)

            when (detail.challenge.status) {
                ChallengeStatus.UPCOMING -> UpcomingBody(detail)
                ChallengeStatus.CANCELLED -> ChallengeMessage(
                    title = "Desafio cancelado",
                    body = "Ele foi encerrado para todos e não tem resultado."
                )
                ChallengeStatus.VOID -> ChallengeMessage(
                    title = "Sem competição",
                    body = "O período começou sem participantes suficientes, " +
                        "então este desafio não tem resultado."
                )
                ChallengeStatus.ACTIVE, ChallengeStatus.ENDED -> Leaderboard(detail)
            }

            detail.pendingInvitationCount
                ?.takeIf { it > 0 }
                ?.let {
                    // Só o criador recebe este número, e ele nunca vem com nomes: os outros
                    // participantes não precisam saber quem ainda não respondeu.
                    Text(text = pendingInvitesLabelFor(it), color = TextSecondary, fontSize = 12.sp)
                }

            if (detail.withdrawnCount > 0) {
                Text(
                    text = withdrawnLabelFor(detail.withdrawnCount),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            val isBusy = challengeId in uiState.pendingChallengeIds
            if (isBusy) {
                ChallengeBusy("Enviando...")
            } else {
                // Quem pode o quê é decisão do **servidor** (§174/§175). A tela não deduz o botão
                // a partir do papel: um app desatualizado ofereceria o errado.
                if (detail.viewer.canCancel) {
                    TextButton(onClick = onRequestCancel) {
                        Text(CANCEL_CHALLENGE_LABEL, color = Red400)
                    }
                }
                if (detail.viewer.canLeave) {
                    TextButton(onClick = onRequestLeave) {
                        Text(LEAVE_CHALLENGE_LABEL, color = Red400)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(challenge: Challenge) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = challenge.name,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
        Text(
            text = "${labelFor(challenge.type)} · ${targetLabelFor(challenge)}",
            color = TextSecondary,
            fontSize = 13.sp
        )
        Text(
            text = "${periodLabelFor(challenge)} · ${labelFor(challenge.status)}",
            color = TextSecondary,
            fontSize = 13.sp
        )
        Text(text = explanationFor(challenge.type), color = TextSecondary, fontSize = 12.sp)
    }
}

/** Um desafio que ainda não começou: quem já está dentro, e quando começa. Sem placar. */
@Composable
private fun UpcomingBody(detail: ChallengeDetail) {
    ChallengeMessage(
        title = startsAtLabelFor(detail.challenge),
        body = "A pontuação começa a contar no primeiro dia do período."
    )
    ChallengeSectionTitle("Participantes")
    detail.participants.forEach { participant ->
        Text(
            text = participant.displayName +
                if (participant.isViewer) " (você)" else "",
            color = if (participant.isViewer) Lime400 else TextPrimary,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun Leaderboard(detail: ChallengeDetail) {
    ChallengeSectionTitle("Placar")

    Column(
        modifier = Modifier.semantics { contentDescription = LEADERBOARD_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        detail.participants.forEach { participant ->
            LeaderboardRow(participant = participant, target = detail.challenge.target)
        }
    }

    if (detail.participants.all { it.goalReached } && detail.participants.isNotEmpty()) {
        Text(text = "Todos atingiram a meta.", color = Lime400, fontSize = 13.sp)
    }

    if (detail.resultMayStillChange) {
        Text(text = RESULT_MAY_CHANGE_NOTICE, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun LeaderboardRow(participant: ChallengeParticipantScore, target: Int) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        // O destaque da própria linha é a borda, e não uma cor de fundo berrante (§170).
        border = BorderStroke(1.dp, if (participant.isViewer) Lime400 else BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${participant.rank}. ${participant.displayName}" +
                        if (participant.isViewer) " (você)" else "",
                    color = if (participant.isViewer) Lime400 else TextPrimary,
                    fontWeight = if (participant.isViewer) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 15.sp
                )
                Text(
                    // O número real, mesmo acima da meta: 15 / 12 é o que aconteceu.
                    text = "${participant.score} / $target",
                    color = if (participant.goalReached) Lime400 else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            LinearProgressIndicator(
                // A **barra** é que se limita a 100%, e não a pontuação.
                progress = {
                    if (target <= 0) 0f
                    else (participant.score.toFloat() / target).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = Lime400,
                trackColor = BorderLight
            )
        }
    }
}

private fun titleOf(uiState: ChallengeUiState, nameHint: String?): String =
    (uiState.detailPhase as? ChallengeDetailPhase.Ready)?.detail?.challenge?.name
        ?: nameHint
        ?: CHALLENGES_TITLE
