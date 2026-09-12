package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.social.SocialGroupContract
import com.example.domain.social.SocialGroup
import com.example.domain.social.SocialGroupInvitation
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle

const val SQUADS_SCREEN_DESCRIPTION = "Lista de squads"

/**
 * O texto de lista vazia.
 *
 * Ele explica **como** um Squad nasce, porque não existe outra forma: não há busca, não há link e
 * não há código de entrada (§4/§5). Sem essa frase, uma lista vazia pareceria uma falha.
 */
const val SQUADS_EMPTY_MESSAGE =
    "Você ainda não participa de nenhum squad. Crie um e convide seus amigos."

/** O rótulo do card. Ele nunca conta o que **as pessoas** do squad fizeram — só quantas são. */
internal fun squadMemberCountLabel(count: Int): String =
    if (count == 1) "1 membro" else "$count membros"

/**
 * A lista de Squads e os convites recebidos (T17.11 §131/§132/§133/§138).
 *
 * ## O card é deliberadamente pobre (§132)
 *
 * Nome e contagem de membros, e um botão para abrir. **Não** existe contador de "não lidos": ele
 * exigiria estado durável no aparelho para lembrar o que já foi visto, e §114 mantém o Room fora do
 * domínio de Squad. Um contador que reiniciasse a cada abertura seria pior do que não ter.
 *
 * ## Os convites vêm antes da lista
 *
 * Porque eles são a única coisa desta tela que **expira** e que pede uma decisão. A prévia é
 * mínima — nome do Squad, quantas pessoas, quem convidou (§139) — e não inclui a lista de membros:
 * quem ainda não entrou não é audiência do grupo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquadsScreen(
    viewModel: SquadsViewModel,
    onNavigateBack: () -> Unit,
    onOpenSquad: (groupId: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.open() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Squads",
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
        },
        floatingActionButton = {
            // §18 — o botão some quando o teto de squads criados foi atingido. Isso é conveniência,
            // e não autorização: o servidor recusa de qualquer forma.
            if (uiState.phase is SquadsPhase.Success && uiState.canCreateGroup) {
                FloatingActionButton(
                    onClick = { creating = true },
                    containerColor = Lime400
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Criar squad",
                        tint = BackgroundDark
                    )
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .semantics { contentDescription = SQUADS_SCREEN_DESCRIPTION }
        ) {
            SquadsBody(
                uiState = uiState,
                onRetry = viewModel::refresh,
                onOpenSquad = onOpenSquad,
                onAccept = viewModel::acceptInvitation,
                onDecline = viewModel::declineInvitation
            )
        }

        if (creating) {
            CreateSquadDialog(
                isCreating = uiState.isCreating,
                onDismiss = { creating = false },
                onConfirm = { name ->
                    viewModel.createGroup(name)
                    creating = false
                }
            )
        }

        uiState.notice?.let { notice ->
            SquadNoticeDialog(message = notice, onDismiss = viewModel::dismissNotice)
        }
    }
}

@Composable
private fun SquadsBody(
    uiState: SquadsUiState,
    onRetry: () -> Unit,
    onOpenSquad: (String) -> Unit,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit
) {
    when (val phase = uiState.phase) {
        SquadsPhase.Loading -> SquadCenteredBox { CircularProgressIndicator(color = Lime400) }

        SquadsPhase.SignedOut -> SquadCenteredMessage(
            title = "Entre na Conta Spark",
            body = "Os squads exigem uma conta para saber quem é você.",
            actionLabel = null,
            onAction = onRetry
        )

        SquadsPhase.SocialNotEnabled -> SquadCenteredMessage(
            title = "Ative seu perfil social",
            body = "Os squads fazem parte do social do Spark.",
            actionLabel = null,
            onAction = onRetry
        )

        SquadsPhase.NotConfigured -> SquadCenteredMessage(
            title = "Squads indisponíveis",
            // §116 — e o núcleo continua inteiro: essa é a frase que impede o usuário de achar que
            // o app quebrou.
            body = "Esta versão do Spark não tem servidor configurado. Seus treinos continuam " +
                "funcionando normalmente.",
            actionLabel = null,
            onAction = onRetry
        )

        SquadsPhase.Offline -> SquadCenteredMessage(
            title = "Sem conexão",
            body = "Os squads precisam de internet. Seus treinos continuam funcionando.",
            actionLabel = "Tentar de novo",
            onAction = onRetry
        )

        is SquadsPhase.Error -> SquadCenteredMessage(
            title = "Não foi possível carregar",
            body = phase.message,
            actionLabel = "Tentar de novo",
            onAction = onRetry
        )

        is SquadsPhase.Success -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (phase.invitations.isNotEmpty()) {
                item {
                    Text(
                        text = "CONVITES",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(phase.invitations, key = { it.invitationId }) { invitation ->
                    SquadInvitationCard(
                        invitation = invitation,
                        isBusy = uiState.respondingInvitationId == invitation.invitationId,
                        onAccept = { onAccept(invitation.invitationId) },
                        onDecline = { onDecline(invitation.invitationId) }
                    )
                }
            }

            if (phase.groups.isEmpty()) {
                item {
                    Text(
                        text = SQUADS_EMPTY_MESSAGE,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            } else {
                item {
                    Text(
                        text = "MEUS SQUADS",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(phase.groups, key = { it.groupId }) { group ->
                    SquadCard(group = group, onOpen = { onOpenSquad(group.groupId) })
                }
            }
        }
    }
}

@Composable
private fun SquadCard(group: SocialGroup, onOpen: () -> Unit) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = group.name.uppercase(),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = squadMemberCountLabel(group.memberCount),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            TextButton(onClick = onOpen) {
                Text(text = "Abrir", color = Lime400, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Um convite recebido (§138/§139).
 *
 * Mostra o **nome do Squad** porque sem ele a decisão é impossível — a pessoa precisa saber para
 * onde está sendo chamada. Mostra a contagem, que não é identidade de ninguém. E mostra quem
 * convidou, quando o servidor manda: se houver bloqueio, os campos vêm nulos e o card fica sem essa
 * linha, em vez de inventar um texto.
 */
@Composable
private fun SquadInvitationCard(
    invitation: SocialGroupInvitation,
    isBusy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        color = SurfaceHighlight,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Lime400),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "Convite para Squad", color = TextSecondary, fontSize = 12.sp)
            Text(
                text = invitation.groupName.uppercase(),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = squadMemberCountLabel(invitation.memberCount),
                color = TextSecondary,
                fontSize = 13.sp
            )
            invitation.inviterDisplayName?.let { name ->
                Text(text = "Convite de $name", color = TextSecondary, fontSize = 13.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDecline, enabled = !isBusy) {
                    Text(text = "Recusar", color = TextSecondary)
                }
                TextButton(onClick = onAccept, enabled = !isBusy) {
                    Text(text = "Participar", color = Lime400, fontWeight = FontWeight.Bold)
                }
                if (isBusy) {
                    CircularProgressIndicator(
                        color = Lime400,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * A criação (§134).
 *
 * Um campo, e nada mais: sem descrição, sem regras, sem links e sem imagem (§9/§10). Cada um deles
 * traria conteúdo gerado por usuário e a moderação que vem junto — e nenhum é necessário para um
 * grupo de treino entre amigos existir.
 */
@Composable
private fun CreateSquadDialog(
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val valid = trimmed.length >= SocialGroupContract.Limits.MIN_NAME_LENGTH &&
        trimmed.length <= SocialGroupContract.Limits.MAX_NAME_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(text = "Novo Squad", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        if (it.length <= SocialGroupContract.Limits.MAX_NAME_LENGTH) name = it
                    },
                    singleLine = true,
                    label = { Text("Nome") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${trimmed.length}/${SocialGroupContract.Limits.MAX_NAME_LENGTH}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = valid && !isCreating) {
                Text(text = "Criar Squad", color = Lime400, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Cancelar", color = TextSecondary) }
        }
    )
}

@Composable
internal fun SquadNoticeDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        text = { Text(text = message, color = TextPrimary) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "OK", color = Lime400) }
        }
    )
}

@Composable
internal fun SquadCenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun SquadCenteredMessage(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    SquadCenteredBox {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(text = body, color = TextSecondary, fontSize = 14.sp)
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(text = actionLabel, color = Lime400) }
            }
        }
    }
}
