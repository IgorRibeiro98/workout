package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
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
import com.example.domain.social.FriendSocialProfile
import com.example.domain.social.ReportReason
import com.example.presentation.account.FriendProfilePhase
import com.example.presentation.account.SocialProfileUiState
import com.example.presentation.account.SocialProfileViewModel
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

const val FRIEND_PROFILE_DESCRIPTION = "Perfil social do amigo"
const val NO_SHARED_PROGRESS_MESSAGE =
    "Este amigo ainda não compartilha informações de progresso."

/**
 * O perfil social de um amigo (T17.2).
 *
 * ```text
 * IGOR
 *
 * Nível              14
 * 🔥 Consistência    4 semanas
 * 🏋️ Esta semana     3 treinos
 * ```
 *
 * ## Só o que está presente é desenhado
 *
 * Não há placeholder, não há "Streak: privado", não há traço no lugar do número. Um espaço
 * reservado contaria ao visitante o que a outra pessoa escolheu esconder — e a configuração de
 * privacidade de alguém é informação dessa pessoa, não de quem olha (§96).
 *
 * Pelo mesmo motivo, a tela **não** distingue "escondido" de "o servidor ainda não tem": os dois
 * chegam como campo ausente, de propósito.
 *
 * ## O que ela nunca mostra
 *
 * Nenhuma sessão individual, nenhum horário de treino, nenhum nome de treino, exercício, carga,
 * nota, medida corporal, PR, e-mail, Firebase UID ou `friendCode`. "3 treinos esta semana" é uma
 * contagem; "Segunda 19:30 — Peito" seria uma agenda, e atividade recente é T17.4, com contrato
 * próprio.
 *
 * Também não há "online agora" nem "última sincronização" (§70/§72): as duas revelariam hábito de
 * uso de quem está sendo olhado, e nenhuma delas é progresso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendSocialProfileScreen(
    socialId: String,
    displayNameHint: String?,
    viewModel: SocialProfileViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var feedbackNotice by remember { mutableStateOf<String?>(null) }

    // Abrir a tela é o ato explícito que autoriza a leitura — e é só aqui que ela acontece. A lista
    // de amigos continua não pedindo perfil nenhum (§64/§65).
    LaunchedEffect(socialId) { viewModel.openFriendProfile(socialId) }

    val currentDisplayName = (uiState.friendPhase.profileOrNull()?.displayName ?: displayNameHint) ?: "Perfil"

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        // O nome que a lista já mostrava serve de título enquanto a leitura corre;
                        // o servidor confirma (ou não) logo em seguida.
                        text = currentDisplayName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.closeFriendProfile()
                            onNavigateBack()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Mais opções",
                            tint = TextPrimary
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Denunciar") },
                            onClick = {
                                showMenu = false
                                showReportDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Bloquear", color = Red400) },
                            onClick = {
                                showMenu = false
                                showBlockDialog = true
                            }
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
                .semantics { contentDescription = FRIEND_PROFILE_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            feedbackNotice?.let { Message(title = "Aviso", body = it) }

            FriendSocialProfileBody(
                uiState = uiState,
                onRetry = viewModel::refreshFriendProfile
            )
        }
    }

    if (showBlockDialog) {
        BlockUserDialog(
            displayName = currentDisplayName,
            onConfirm = {
                showBlockDialog = false
                viewModel.blockUser(
                    socialId = socialId,
                    onSuccess = { onNavigateBack() },
                    onError = { err -> feedbackNotice = err }
                )
            },
            onDismiss = { showBlockDialog = false }
        )
    }

    if (showReportDialog) {
        ReportUserDialog(
            displayName = currentDisplayName,
            onConfirm = { reason ->
                showReportDialog = false
                viewModel.reportUser(
                    socialId = socialId,
                    reason = reason,
                    onSuccess = {
                        feedbackNotice = "Denúncia recebida. Obrigado por colaborar com a comunidade."
                    },
                    onError = { err -> feedbackNotice = err }
                )
            },
            onDismiss = { showReportDialog = false }
        )
    }
}

@Composable
private fun BlockUserDialog(
    displayName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Bloquear $displayName?", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = "Ao bloquear, vocês não poderão interagir, desafios em comum serão encerrados e a amizade será removida. O usuário não é notificado.",
                color = TextSecondary,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Bloquear", color = Red400)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun ReportUserDialog(
    displayName: String,
    onConfirm: (ReportReason) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedReason by remember { mutableStateOf(ReportReason.INAPPROPRIATE_BEHAVIOR) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Denunciar $displayName", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Escolha o motivo da denúncia. O usuário não é notificado.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                val options = listOf(
                    ReportReason.SPAM to "Spam ou atividade indesejada",
                    ReportReason.HARASSMENT to "Assédio ou ofensa",
                    ReportReason.INAPPROPRIATE_BEHAVIOR to "Comportamento inadequado",
                    ReportReason.OTHER to "Outro motivo"
                )

                options.forEach { (reason, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedReason == reason),
                            onClick = { selectedReason = reason }
                        )
                        Text(
                            text = label,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedReason) }) {
                Text("Enviar denúncia", color = Lime400)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondary)
            }
        }
    )
}

/**
 * O corpo da tela, sem ViewModel.
 *
 * Separado para ser renderizável em teste com um estado montado à mão — a mesma divisão de
 * `FriendsScreen`. O que se prova aqui é o que a tela **mostra** e, principalmente, o que ela
 * **não** mostra, e isso não deveria exigir um servidor dublê.
 */
@Composable
internal fun FriendSocialProfileBody(
    uiState: SocialProfileUiState,
    onRetry: () -> Unit = {}
) {
    when (val phase = uiState.friendPhase) {
        FriendProfilePhase.Idle, FriendProfilePhase.Loading -> Busy("Carregando...")

        FriendProfilePhase.NotAvailable -> Message(
            title = "Perfil indisponível",
            // Uma frase para as quatro situações. Dizer "vocês não são mais amigos" quando pode
            // ser "ele desativou o social" seria afirmar o que o servidor não afirmou.
            body = "Não foi possível abrir este perfil. Ele pode ter deixado de estar disponível."
        )

        FriendProfilePhase.Offline -> {
            Message(
                title = "Sem conexão",
                body = "Não foi possível falar com o servidor. Seus treinos, histórico e backup " +
                    "continuam normais."
            )
            SecondaryButton("Tentar de novo", onRetry)
        }

        is FriendProfilePhase.Error -> {
            Message(title = "Não foi possível carregar", body = messageFor(phase.reason))
            SecondaryButton("Tentar de novo", onRetry)
        }

        is FriendProfilePhase.NoSharedProgress -> Message(
            title = phase.profile.displayName,
            // Nunca "não treina": o app não sabe disso, e o servidor não disse isso. O que ele
            // disse é que não há nada compartilhado agora.
            body = NO_SHARED_PROGRESS_MESSAGE
        )

        is FriendProfilePhase.Ready -> SharedProgressCard(phase.profile)
    }
}

/**
 * Os blocos compartilhados, e só eles.
 *
 * Cada linha existe apenas se o campo veio. Nenhum default numérico entra aqui: um `?: 0` seria o
 * app inventando um fato sobre a vida de outra pessoa — exatamente o que "ausência não é zero"
 * proíbe.
 */
@Composable
private fun SharedProgressCard(profile: FriendSocialProfile) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = profile.displayName,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            profile.sharedProgress.level?.let { level ->
                ProgressRow(label = "Nível", value = "$level")
            }
            profile.sharedProgress.consistencyStreak?.let { weeks ->
                // "semanas", e nunca "dias": a consistência do Spark é **semanal**, e traduzir o
                // conceito na UI social faria o mesmo número significar outra coisa (§20/§21).
                ProgressRow(
                    label = "🔥 Consistência",
                    value = if (weeks == 1) "1 semana" else "$weeks semanas"
                )
            }
            profile.sharedProgress.weeklyWorkoutCount?.let { count ->
                ProgressRow(
                    label = "🏋️ Esta semana",
                    value = if (count == 1) "1 treino" else "$count treinos"
                )
            }
            // `highlightedAchievementIds` existe no contrato e **nunca** chega preenchido nesta
            // versão: o servidor não consegue validar que uma conquista foi obtida, então a
            // seleção de destaques não foi implementada (ver `social-profile-contract.md`). Não
            // há renderização aqui porque não haveria o que renderizar — e desenhar uma seção
            // vazia prometeria uma capacidade que não existe.
        }
    }
}

@Composable
private fun ProgressRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextSecondary, fontSize = 14.sp)
        Text(text = value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** O perfil já lido, quando existe — para o título continuar estável entre as fases. */
private fun FriendProfilePhase.profileOrNull(): FriendSocialProfile? = when (this) {
    is FriendProfilePhase.Ready -> profile
    is FriendProfilePhase.NoSharedProgress -> profile
    else -> null
}
