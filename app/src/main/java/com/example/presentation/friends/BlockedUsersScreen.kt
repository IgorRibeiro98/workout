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
import androidx.compose.material3.AlertDialog
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
import com.example.domain.social.BlockedUser
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

const val BLOCKED_USERS_SCREEN_DESCRIPTION = "Tela de usuários bloqueados"

/**
 * Tela de usuários bloqueados (T17.6).
 *
 * Lista os usuários que o usuário autenticado bloqueou e permite o desbloqueio.
 * Desbloquear não restaura amizades ou desafios anteriores.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedUsersScreen(
    viewModel: BlockedUsersViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.open()
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Usuários bloqueados",
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
                .semantics { contentDescription = BLOCKED_USERS_SCREEN_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BlockedUsersBody(
                uiState = uiState,
                onRetry = viewModel::refresh,
                onUnblock = viewModel::unblockUser
            )
        }
    }
}

@Composable
private fun BlockedUsersBody(
    uiState: BlockedUsersUiState,
    onRetry: () -> Unit,
    onUnblock: (String) -> Unit
) {
    var userToUnblock by remember { mutableStateOf<BlockedUser?>(null) }

    when (val phase = uiState.phase) {
        BlockedUsersPhase.Idle, BlockedUsersPhase.Loading -> {
            Busy("Carregando...")
        }
        BlockedUsersPhase.Offline -> {
            Message(
                title = "Sem conexão",
                body = "Não foi possível carregar a lista de bloqueados. Tente novamente com internet."
            )
            SecondaryButton("Tentar de novo", onRetry)
        }
        is BlockedUsersPhase.Error -> {
            Message(
                title = "Erro ao carregar",
                body = "Não foi possível carregar a lista de usuários bloqueados."
            )
            SecondaryButton("Tentar de novo", onRetry)
        }
        is BlockedUsersPhase.Ready -> {
            uiState.notice?.let { Message(title = "Aviso", body = it) }

            if (phase.users.isEmpty()) {
                Message(
                    title = "Nenhum usuário bloqueado",
                    body = "Quando você bloqueia alguém, a pessoa aparece aqui e não pode interagir com você."
                )
            } else {
                phase.users.forEach { user ->
                    BlockedUserRow(
                        user = user,
                        isBusy = user.socialId in uiState.pendingUnblockSocialIds,
                        onRequestUnblock = { userToUnblock = user }
                    )
                }
            }
        }
    }

    userToUnblock?.let { user ->
        UnblockConfirmationDialog(
            user = user,
            onConfirm = {
                onUnblock(user.socialId)
                userToUnblock = null
            },
            onDismiss = { userToUnblock = null }
        )
    }
}

@Composable
private fun BlockedUserRow(
    user: BlockedUser,
    isBusy: Boolean,
    onRequestUnblock: () -> Unit
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.displayName,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Bloqueado",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            if (isBusy) {
                CircularProgressIndicator(
                    color = Lime400,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                TextButton(onClick = onRequestUnblock) {
                    Text("Desbloquear", color = Lime400)
                }
            }
        }
    }
}

@Composable
private fun UnblockConfirmationDialog(
    user: BlockedUser,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Desbloquear ${user.displayName}?", color = TextPrimary) },
        text = {
            Text(
                text = "O usuário poderá voltar a interagir ou enviar solicitações de amizade caso suas configurações permitam. Desbloquear não restaura amizades ou desafios anteriores.",
                color = TextSecondary,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Desbloquear", color = Lime400)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondary)
            }
        }
    )
}
