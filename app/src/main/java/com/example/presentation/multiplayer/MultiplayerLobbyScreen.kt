package com.example.presentation.multiplayer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.multiplayer.MultiplayerInvitation
import com.example.domain.social.Friend
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * "Treinar em dupla à distância" (T19.5).
 *
 * Duas metades, sem abas: os convites recebidos (aceitar cria a cópia do treino e inicia a sessão
 * deste aparelho) e os amigos (convidar cria a sala com o treino de hoje). A tela não executa
 * nada: quem inicia a sessão é o `MultiplayerWorkoutStarter`, e a execução é a tela de sempre.
 *
 * @param templateId o treino de hoje deste aparelho — o que o host leva para a sala. `null` quando
 * não há treino para hoje: dá para aceitar um convite, mas não para convidar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerLobbyScreen(
    viewModel: MultiplayerLobbyViewModel,
    templateId: Long?,
    onNavigateBack: () -> Unit,
    onWorkoutStarted: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedFriendId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(templateId) { viewModel.loadTemplate(templateId) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                MultiplayerLobbyEvent.WorkoutStarted -> onWorkoutStarted()
            }
        }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dupla à distância", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BackgroundDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Cada um treina no próprio celular, com a própria sessão. Você vê as séries que o outro " +
                    "concluiu; pesos, repetições e recordes ficam só no aparelho de cada um.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when {
                !state.isConfigured -> Notice("Treino em dupla à distância indisponível neste build.")
                !state.isSignedIn -> Notice("Entre na sua conta, no Perfil, para treinar em dupla à distância.")
                else -> {
                    val created = state.createdRoom
                    if (created != null) {
                        CreatedRoomCard(
                            peerName = created.peer?.displayName ?: "convidado",
                            peerJoined = state.peerHasJoined,
                            workoutName = created.workout.name,
                            isStarting = state.isStarting,
                            onStart = { templateId?.let(viewModel::startAsHost) },
                            onCancel = viewModel::cancelCreatedRoom
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    SectionTitle("CONVITES RECEBIDOS")
                    when {
                        state.isLoadingInvitations -> Loading()
                        state.invitationsError != null -> Notice(state.invitationsError!!, onRetry = viewModel::refresh)
                        state.invitations.isEmpty() -> Notice("Nenhum convite no momento. Peça a um amigo para convidar você.")
                        else -> state.invitations.forEach { invitation ->
                            InvitationCard(
                                invitation = invitation,
                                isStarting = state.isStarting,
                                onJoin = { viewModel.joinAndStart(invitation.roomId) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    SectionTitle("CONVIDAR UM AMIGO")
                    if (templateId == null) {
                        Notice("Não há treino de hoje para levar para a sala. Escolha um programa com treino para hoje.")
                    } else if (state.templateBlockedReason != null) {
                        Notice(state.templateBlockedReason!!)
                    } else {
                        Text(
                            text = "Treino: ${state.templateName ?: "Treino de hoje"}",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        when {
                            state.isLoadingFriends -> Loading()
                            state.friendsError != null -> Notice(state.friendsError!!, onRetry = viewModel::refresh)
                            state.friends.isEmpty() -> Notice("Você ainda não tem amigos adicionados.")
                            else -> {
                                state.friends.forEach { friend ->
                                    FriendRow(
                                        friend = friend,
                                        selected = friend.socialId == selectedFriendId,
                                        onClick = { selectedFriendId = friend.socialId }
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { selectedFriendId?.let { viewModel.createRoom(templateId, it) } },
                                    enabled = selectedFriendId != null && !state.isCreating && created == null,
                                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("multiplayer_create_room_button"),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(if (state.isCreating) "CRIANDO SALA…" else "CONVIDAR", fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Lime400,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun Loading() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Lime400, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Notice(text: String, onRetry: (() -> Unit)? = null) {
    Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, BorderLight), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = text, color = TextSecondary, fontSize = 13.sp)
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Tentar de novo", color = Lime400) }
            }
        }
    }
}

@Composable
private fun CreatedRoomCard(
    peerName: String,
    peerJoined: Boolean,
    workoutName: String,
    isStarting: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Lime400),
        modifier = Modifier.fillMaxWidth().testTag("multiplayer_created_room")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (peerJoined) "$peerName ENTROU" else "AGUARDANDO $peerName".uppercase(),
                color = Lime400,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                modifier = Modifier.testTag("multiplayer_room_status")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = workoutName, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (peerJoined) "Podem começar. Cada um vê as séries do outro conforme conclui." else "Você pode começar agora; quando $peerName entrar, vocês passam a se ver.",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, enabled = !isStarting, modifier = Modifier.weight(1f)) {
                    Text("CANCELAR", color = TextSecondary, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onStart,
                    enabled = !isStarting,
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                    modifier = Modifier.weight(1f).testTag("multiplayer_start_host_button")
                ) {
                    Text("INICIAR TREINO", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun InvitationCard(invitation: MultiplayerInvitation, isStarting: Boolean, onJoin: () -> Unit) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth().testTag("multiplayer_invitation_${invitation.roomId}")
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = invitation.hostDisplayName, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(
                    text = "${invitation.workoutName} · ${invitation.exerciseCount} exercício(s)",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onJoin,
                enabled = !isStarting,
                colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                modifier = Modifier.testTag("multiplayer_join_button")
            ) {
                Text("ENTRAR", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun FriendRow(friend: Friend, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) Lime400 else BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
            .testTag("multiplayer_friend_${friend.socialId}")
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = friend.displayName,
                color = if (selected) Lime400 else TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (selected) Text("✓", color = Lime400, fontWeight = FontWeight.Black)
        }
    }
}
