package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.Challenge
import com.example.domain.social.ChallengeInvite
import com.example.presentation.account.ChallengeListPhase
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

/**
 * A tela de desafios: o que está em andamento, o que vem, o que passou — e os convites (T17.3).
 *
 * ```text
 * Desafios
 *
 * Convites
 * ────────────────
 * Igor convidou você · 12 treinos
 * [ Aceitar ]  [ Recusar ]
 *
 * Ativos
 * ────────────────
 * 12 treinos · 3 participantes
 *
 * Próximos
 * ────────────────
 * 10 dias ativos · Começa em 12/09
 *
 * Encerrados
 * ────────────────
 * Treinos de Agosto
 *
 * [ Criar desafio ]
 * ```
 *
 * ## Sem placar na lista
 *
 * Cada cartão mostra as **regras** e o estado, e não a pontuação. Pontuar uma lista custaria uma
 * consulta de treino por participante de cada desafio, e a decisão de ler o placar só ao abrir é
 * a mesma da T17.2 para o perfil de amigo: um perfil é lido no toque, nunca em lote.
 *
 * ## Sem polling e sem tempo real
 *
 * O placar atualiza ao abrir o desafio ou ao puxar a lista. Não há `WebSocket`, `SSE`, FCM nem
 * atualização periódica — e abrir um desafio **não** dispara sincronização de treino.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengesScreen(
    viewModel: ChallengeViewModel,
    onNavigateBack: () -> Unit,
    onOpenChallenge: (String) -> Unit,
    onCreateChallenge: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.open() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = CHALLENGES_TITLE,
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
                .semantics { contentDescription = CHALLENGES_LIST_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChallengesBody(
                uiState = uiState,
                onOpenChallenge = onOpenChallenge,
                onCreateChallenge = onCreateChallenge,
                onAcceptInvite = viewModel::acceptInvite,
                onDeclineInvite = viewModel::declineInvite,
                onRetry = viewModel::refresh
            )
        }
    }
}

/** O corpo da lista, separado da `Scaffold` para poder ser renderizado em teste. */
@Composable
internal fun ChallengesBody(
    uiState: ChallengeUiState,
    onOpenChallenge: (String) -> Unit,
    onCreateChallenge: () -> Unit,
    onAcceptInvite: (String) -> Unit,
    onDeclineInvite: (String) -> Unit,
    onRetry: () -> Unit
) {
    when (val phase = uiState.listPhase) {
        ChallengeListPhase.NotConfigured -> ChallengeMessage(
            title = "Indisponível",
            body = "Os recursos sociais não estão disponíveis nesta versão do app."
        )

        ChallengeListPhase.SignedOut -> ChallengeMessage(
            title = "Entre na Conta Spark",
            body = "Desafios exigem conta. Treinar e ver histórico não."
        )

        ChallengeListPhase.Idle, ChallengeListPhase.Loading -> ChallengeBusy("Carregando...")

        is ChallengeListPhase.SocialUnavailable -> ChallengeMessage(
            title = if (phase.disabled) "Social desativado" else "Ative os recursos sociais",
            body = if (phase.disabled) {
                "Reative no Perfil para voltar a participar de desafios. Nada foi apagado."
            } else {
                "Ative os recursos sociais no Perfil para criar e participar de desafios."
            }
        )

        ChallengeListPhase.Offline -> {
            // A segunda frase é a que evita o pânico: desafio é server-authoritative, treino não.
            ChallengeMessage(
                title = "Sem conexão",
                body = "Não foi possível falar com o servidor, então nada foi alterado. " +
                    "Seus treinos e seu histórico continuam normais."
            )
            SecondaryAction("Tentar de novo", onRetry)
        }

        is ChallengeListPhase.Error -> {
            ChallengeMessage(title = "Não foi possível carregar", body = messageFor(phase.error))
            SecondaryAction("Tentar de novo", onRetry)
        }

        is ChallengeListPhase.Ready -> {
            uiState.notice?.let { ChallengeMessage(title = "Aviso", body = messageFor(it)) }

            if (uiState.invites.isNotEmpty()) {
                ChallengeSectionTitle(CHALLENGE_INVITES_TITLE)
                uiState.invites.forEach { invite ->
                    InviteRow(
                        invite = invite,
                        isBusy = invite.invitationId in uiState.pendingInvitationIds,
                        onAccept = { onAcceptInvite(invite.invitationId) },
                        onDecline = { onDeclineInvite(invite.invitationId) }
                    )
                }
            }

            if (phase.challenges.isEmpty() && uiState.invites.isEmpty()) {
                ChallengeMessage(title = "Nada por aqui", body = CHALLENGES_EMPTY_MESSAGE)
            }

            ChallengeGroup(SECTION_ACTIVE, uiState.activeChallenges, onOpenChallenge)
            ChallengeGroup(SECTION_UPCOMING, uiState.upcomingChallenges, onOpenChallenge)
            ChallengeGroup(SECTION_FINISHED, uiState.finishedChallenges, onOpenChallenge)

            Button(
                onClick = onCreateChallenge,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Lime400)
            ) {
                Text(CREATE_CHALLENGE_LABEL, color = BackgroundDark, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChallengeGroup(
    title: String,
    challenges: List<Challenge>,
    onOpen: (String) -> Unit
) {
    if (challenges.isEmpty()) return
    ChallengeSectionTitle(title)
    challenges.forEach { challenge ->
        ChallengeCard(challenge = challenge, onClick = { onOpen(challenge.challengeId) })
    }
}

@Composable
private fun ChallengeCard(challenge: Challenge, onClick: () -> Unit) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = challenge.name,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = "${labelFor(challenge.type)} · ${targetLabelFor(challenge)}",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Text(
                // Um desafio que ainda não começou diz **quando** começa; os outros dizem o
                // período. A informação que falta é diferente em cada fase.
                text = if (challenge.status == com.example.domain.social.ChallengeStatus.UPCOMING) {
                    startsAtLabelFor(challenge)
                } else {
                    periodLabelFor(challenge)
                },
                color = TextSecondary,
                fontSize = 13.sp
            )
            Text(
                text = "${participantsLabelFor(challenge.participantCount)} · " +
                    labelFor(challenge.status),
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Um convite, na lista.
 *
 * Ele mostra as regras e quem convidou — e **não** mostra placar nem participantes: ver o
 * progresso dos outros antes de consentir em mostrar o próprio é a assimetria que o consentimento
 * existe para impedir. O aviso de consentimento aparece na tela do convite, antes do aceite.
 */
@Composable
private fun InviteRow(
    invite: ChallengeInvite,
    isBusy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Lime400),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${invite.challenge.creator.displayName} convidou você",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = invite.challenge.name,
                color = TextPrimary,
                fontSize = 15.sp
            )
            Text(
                text = "${targetLabelFor(invite.challenge)} · " +
                    periodLabelFor(invite.challenge),
                color = TextSecondary,
                fontSize = 13.sp
            )
            Text(text = CHALLENGE_CONSENT_NOTICE, color = TextSecondary, fontSize = 12.sp)

            if (isBusy) {
                ChallengeBusy("Respondendo...")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAccept) { Text(ACCEPT_CHALLENGE_LABEL, color = Lime400) }
                    TextButton(onClick = onDecline) {
                        Text(DECLINE_CHALLENGE_LABEL, color = Red400)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- compartilhados

@Composable
internal fun ChallengeSectionTitle(text: String) {
    Text(text = text, color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
internal fun ChallengeMessage(title: String, body: String) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(text = body, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun ChallengeBusy(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Lime400)
        Text(text = label, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
internal fun SecondaryAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = Lime400) }
}
