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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.social.GmsQrScanner
import com.example.domain.social.Friend
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

const val FRIENDS_TITLE = "Amigos"
const val FRIENDS_EMPTY_MESSAGE =
    "Você ainda não adicionou amigos. Compartilhe seu código ou adicione alguém."
const val ADD_FRIEND_BUTTON = "+ Adicionar amigo"
const val REMOVE_FRIEND_LABEL = "Remover"
const val FRIENDS_LIST_DESCRIPTION = "Lista de amigos"

/**
 * A lista de amigos, e a porta para adicionar alguém (T17.1 §66–§67).
 *
 * ## O que uma amizade dá, e o que ela não dá
 *
 * A lista mostra **nome social**, e nada mais. Não há nível, XP, sequência, frequência, último
 * treino nem medida — nenhum deles existe aqui porque nenhum deles é consequência de ser amigo.
 * Perfil social mais rico é T17.2, e vai passar por projeção autorizada e configuração de
 * privacidade; adiantá-lo com "só um número" é como essa fronteira se perde.
 *
 * ## Quem não aparece
 *
 * Amigo que desativou os recursos sociais não aparece — nem como "usuário indisponível". A
 * amizade continua gravada e volta inteira quando ele reativar; o que não existe, enquanto isso, é
 * perfil social para mostrar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    viewModel: FriendsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToRequests: () -> Unit,
    /**
     * T17.2 — abre o perfil social daquele amigo.
     *
     * O perfil é lido **neste toque**, e não ao abrir a lista: enriquecer cada linha com nível,
     * sequência ou frequência custaria uma requisição por amigo, e a lista voltaria a ser cara por
     * uma informação que quase sempre ninguém está olhando.
     */
    onOpenProfile: (Friend) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // O leitor é construído aqui porque é aqui que existe `Context` — e é instalado no ViewModel,
    // que não conhece Android. Criar o objeto **não** abre a câmera: isso só acontece no toque.
    val scanner = remember(context) { GmsQrScanner(context) }
    LaunchedEffect(scanner) { viewModel.attachScanner { scanner.scan() } }

    // Abrir a tela é o ato explícito que autoriza a leitura. `open()` é idempotente: voltar para
    // cá não refaz as requisições.
    LaunchedEffect(Unit) { viewModel.open() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = FRIENDS_TITLE,
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
                .semantics { contentDescription = FRIENDS_LIST_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FriendsBody(
                uiState = uiState,
                onAddFriend = viewModel::startAddFriend,
                onOpenRequests = onNavigateToRequests,
                onRemoveFriend = viewModel::startRemoveFriend,
                onOpenProfile = onOpenProfile,
                onRetry = viewModel::refresh
            )
        }
    }

    if (uiState.isAddFriendOpen) {
        AddFriendDialog(
            uiState = uiState,
            onCodeChange = viewModel::onCodeChanged,
            onLookup = viewModel::lookup,
            onScan = viewModel::scanQrCode,
            onSend = viewModel::sendRequest,
            onCancelRequest = viewModel::cancelRequest,
            onDismiss = viewModel::cancelAddFriend
        )
    }

    uiState.friendPendingRemoval?.let { friend ->
        RemoveFriendDialog(
            friend = friend,
            onConfirm = viewModel::confirmRemoveFriend,
            onDismiss = viewModel::cancelRemoveFriend
        )
    }
}

/**
 * O corpo da tela, sem ViewModel.
 *
 * Separado para ser renderizável em teste com um estado montado à mão — é a mesma divisão de
 * `MissionsScreen`. O que se prova aqui é o que a tela **mostra** e o que ela **não** mostra, e
 * isso não deveria exigir um servidor dublê.
 */
@Composable
internal fun FriendsBody(
    uiState: FriendsUiState,
    onAddFriend: () -> Unit = {},
    onOpenRequests: () -> Unit = {},
    onRemoveFriend: (Friend) -> Unit = {},
    onOpenProfile: (Friend) -> Unit = {},
    onRetry: () -> Unit = {}
) {
    when (val phase = uiState.phase) {
        FriendsPhase.NotConfigured -> Message(
            title = "Indisponível",
            body = "Os recursos sociais não estão disponíveis nesta versão do app."
        )

        FriendsPhase.SignedOut -> Message(
            title = "Entre na Conta Spark",
            body = "Amigos exigem conta. Treinar, ver histórico e fazer backup não."
        )

        FriendsPhase.Idle, FriendsPhase.Loading -> Busy("Carregando...")

        is FriendsPhase.SocialUnavailable -> Message(
            title = if (phase.disabled) "Social desativado" else "Ative os recursos sociais",
            body = if (phase.disabled) {
                "Reative no Perfil para ver seus amigos. Nada foi apagado: suas amizades e seu " +
                    "código continuam guardados."
            } else {
                "Ative os recursos sociais no Perfil para adicionar amigos."
            }
        )

        FriendsPhase.Offline -> {
            Message(
                title = "Sem conexão",
                body = "Não foi possível falar com o servidor. Seus treinos, histórico e backup " +
                    "continuam normais."
            )
            SecondaryButton("Tentar de novo", onRetry)
        }

        is FriendsPhase.Error -> {
            Message(title = "Não foi possível carregar", body = messageFor(phase.reason))
            SecondaryButton("Tentar de novo", onRetry)
        }

        FriendsPhase.Ready -> {
            if (uiState.incomingCount > 0) {
                RequestsBanner(count = uiState.incomingCount, onClick = onOpenRequests)
            }

            if (uiState.friends.isEmpty()) {
                Message(title = "Nenhum amigo ainda", body = FRIENDS_EMPTY_MESSAGE)
            } else {
                uiState.friends.forEach { friend ->
                    FriendRow(
                        friend = friend,
                        isBusy = uiState.isFriendBusy(friend.socialId),
                        onOpen = { onOpenProfile(friend) },
                        onRemove = { onRemoveFriend(friend) }
                    )
                }
            }

            uiState.notice?.let { Message(title = "Aviso", body = messageFor(it)) }

            PrimaryButton(ADD_FRIEND_BUTTON, onAddFriend)
        }
    }
}

@Composable
private fun FriendRow(
    friend: Friend,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit
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
            // O nome abre o perfil social daquela pessoa (T17.2). A lista continua carregando só
            // nome — o que o perfil mostra depende do que ela escolheu compartilhar, e é lido lá.
            Text(
                text = friend.displayName,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onOpen)
            )
            // Enquanto a remoção está em voo, o botão daquele amigo para de responder — e os
            // outros continuam funcionando. Um `isLoading` da tela inteira travaria a lista toda.
            if (isBusy) {
                CircularProgressIndicator(
                    color = Lime400,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                TextButton(onClick = onRemove) {
                    Text(REMOVE_FRIEND_LABEL, color = Red400)
                }
            }
        }
    }
}

@Composable
private fun RequestsBanner(count: Int, onClick: () -> Unit) {
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
            Text(
                text = if (count == 1) "1 solicitação pendente" else "$count solicitações pendentes",
                color = TextPrimary,
                fontSize = 15.sp
            )
            TextButton(onClick = onClick) {
                Text("Ver", color = Lime400)
            }
        }
    }
}

/**
 * A confirmação de remover.
 *
 * O texto diz o que **não** acontece, porque é isso que a pessoa teme antes de tocar: nada de
 * treino, histórico ou backup é afetado, e remover não é bloquear.
 */
@Composable
private fun RemoveFriendDialog(friend: Friend, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Remover ${friend.displayName}?", color = TextPrimary) },
        text = {
            Text(
                text = "Vocês deixam de ser amigos. Nada dos seus treinos, histórico ou backup " +
                    "muda, e vocês podem se adicionar de novo depois.",
                color = TextSecondary,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(REMOVE_FRIEND_LABEL, color = Red400) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        }
    )
}

@Composable
internal fun Message(title: String, body: String) {
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
internal fun Busy(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            color = Lime400,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp)
        )
        Text(text = label, color = TextSecondary, fontSize = 14.sp)
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Lime400,
            contentColor = BackgroundDark
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onClick, enabled = enabled) {
            Text(text = text, color = Lime400)
        }
    }
}
