package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.FriendRequest
import com.example.presentation.account.FriendsPhase
import com.example.presentation.account.FriendsUiState
import com.example.presentation.account.FriendsViewModel
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

const val REQUESTS_TITLE = "Solicitações"
const val ACCEPT_LABEL = "Aceitar"
const val REJECT_LABEL = "Recusar"
const val CANCEL_REQUEST_LABEL = "Cancelar"
const val REQUESTS_EMPTY_MESSAGE = "Nenhuma solicitação pendente."
const val REQUESTS_LIST_DESCRIPTION = "Solicitações de amizade"

/**
 * As solicitações de amizade: as que eu recebi e as que eu enviei (T17.1 §71).
 *
 * ## Duas listas, porque são duas ações diferentes
 *
 * Recebida se aceita ou recusa; enviada se cancela. Misturá-las numa lista só obrigaria cada item
 * a explicar de que lado ele está — e o toque errado numa lista dessas tem consequência para outra
 * pessoa.
 *
 * ## Um toque, uma mutação
 *
 * Cada linha fica ocupada enquanto a ação dela está em voo, e o resto da tela continua vivo. O
 * servidor repete a proteção: aceitar duas vezes produz uma amizade, e cancelar duas vezes não
 * produz erro.
 *
 * ## Sem notificação
 *
 * Não há push nem badge fora do app: a T17.1 não introduz FCM. Quem abre a tela vê; quem não abre,
 * não é interrompido.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendRequestsScreen(
    viewModel: FriendsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.open() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = REQUESTS_TITLE,
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
                .padding(16.dp)
                .semantics { contentDescription = REQUESTS_LIST_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RequestsBody(
                uiState = uiState,
                onAccept = viewModel::acceptRequest,
                onReject = viewModel::rejectRequest,
                onCancel = viewModel::cancelRequest,
                onRetry = viewModel::refresh
            )
        }
    }
}

@Composable
private fun RequestsBody(
    uiState: FriendsUiState,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: () -> Unit
) {
    when (val phase = uiState.phase) {
        FriendsPhase.NotConfigured -> Message(
            title = "Indisponível",
            body = "Os recursos sociais não estão disponíveis nesta versão do app."
        )

        FriendsPhase.SignedOut -> Message(
            title = "Entre na Conta Spark",
            body = "Solicitações exigem conta. Treinar e ver histórico não."
        )

        FriendsPhase.Idle, FriendsPhase.Loading -> Busy("Carregando...")

        is FriendsPhase.SocialUnavailable -> Message(
            title = if (phase.disabled) "Social desativado" else "Ative os recursos sociais",
            body = if (phase.disabled) {
                "Reative no Perfil. Suas solicitações pendentes continuam guardadas."
            } else {
                "Ative os recursos sociais no Perfil para receber solicitações."
            }
        )

        FriendsPhase.Offline -> {
            Message(
                title = "Sem conexão",
                body = "Não foi possível falar com o servidor, então nada foi alterado."
            )
            SecondaryButton("Tentar de novo", onRetry)
        }

        is FriendsPhase.Error -> {
            Message(title = "Não foi possível carregar", body = messageFor(phase.reason))
            SecondaryButton("Tentar de novo", onRetry)
        }

        FriendsPhase.Ready -> {
            uiState.notice?.let { Message(title = "Aviso", body = messageFor(it)) }

            SectionTitle("Recebidas")
            if (uiState.incoming.isEmpty()) {
                Message(title = "Nada por aqui", body = REQUESTS_EMPTY_MESSAGE)
            } else {
                uiState.incoming.forEach { request ->
                    IncomingRow(
                        request = request,
                        isBusy = uiState.isRequestBusy(request.requestId),
                        onAccept = { onAccept(request.requestId) },
                        onReject = { onReject(request.requestId) }
                    )
                }
            }

            SectionTitle("Enviadas")
            if (uiState.outgoing.isEmpty()) {
                Message(
                    title = "Nada por aqui",
                    body = "Você não tem solicitações aguardando resposta."
                )
            } else {
                uiState.outgoing.forEach { request ->
                    OutgoingRow(
                        request = request,
                        isBusy = uiState.isRequestBusy(request.requestId),
                        onCancel = { onCancel(request.requestId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomingRow(
    request: FriendRequest,
    isBusy: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    RequestCard {
        Text(
            text = request.profile.displayName,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(text = "quer adicionar você", color = TextSecondary, fontSize = 13.sp)

        if (isBusy) {
            Busy("Respondendo...")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAccept) { Text(ACCEPT_LABEL, color = Lime400) }
                TextButton(onClick = onReject) { Text(REJECT_LABEL, color = Red400) }
            }
        }
    }
}

@Composable
private fun OutgoingRow(request: FriendRequest, isBusy: Boolean, onCancel: () -> Unit) {
    RequestCard {
        Text(
            text = request.profile.displayName,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(text = "Solicitação enviada", color = TextSecondary, fontSize = 13.sp)

        if (isBusy) {
            Busy("Cancelando...")
        } else {
            Row {
                TextButton(onClick = onCancel) { Text(CANCEL_REQUEST_LABEL, color = Red400) }
            }
        }
    }
}

@Composable
private fun RequestCard(content: @Composable () -> Unit) {
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
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}
