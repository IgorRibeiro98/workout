package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.CheckInComment
import com.example.domain.social.ReactionType
import com.example.domain.social.SocialReportTarget
import com.example.domain.social.WorkoutCheckIn
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

const val CHECKIN_DETAIL_SCREEN_DESCRIPTION = "Detalhe do check-in"
const val CHECKIN_DETAIL_COMMENT_FIELD_DESCRIPTION = "Escrever comentário"
const val CHECKIN_DETAIL_SEND_COMMENT_DESCRIPTION = "Enviar comentário"
const val CHECKIN_DETAIL_EMPTY_COMMENTS = "Nenhum comentário ainda."

/** Os motivos de denúncia. A taxonomia é a da T17.6, reusada sem paralelo (T17.9 §107). */
private val REPORT_REASONS = listOf(
    "SPAM" to "Spam",
    "HARASSMENT" to "Assédio",
    "INAPPROPRIATE_BEHAVIOR" to "Conteúdo impróprio",
    "OTHER" to "Outro"
)

/**
 * O detalhe de um check-in (T17.9 §118).
 *
 * ```text
 * ┌──────────────────────────────┐
 * │ IGOR                         │
 * │ 💪 Concluiu um treino        │
 * │ [ foto ]                     │
 * │ "Hoje rendeu demais"         │
 * │ 🔥 4   💪 3   👏 2           │
 * ├──────────────────────────────┤
 * │ JOÃO   Boa!                  │
 * │ ANA    Arrasou               │
 * ├──────────────────────────────┤
 * │ [ escrever comentário… ] ▶   │
 * └──────────────────────────────┘
 * ```
 *
 * ## Sem bottom navigation nova (§118)
 *
 * Ela é uma tela empilhada, alcançada pelo card do Feed. A barra inferior continua sendo do núcleo
 * do produto — treinar, histórico, evolução —, e o social continua morando dentro do Perfil.
 *
 * ## O que ela mostra do treino: nada
 *
 * Exatamente como o card (§185): nome social, "concluiu um treino", o instante da **publicação**,
 * a foto, a legenda e a conversa. Exercício, carga, série, duração, horário do treino e nome do
 * template não chegam a esta tela porque não existem no DTO.
 *
 * ## Reação e comentário se comportam diferente, de propósito
 *
 * A reação é otimista e reversível (§121). O comentário espera o servidor (§122) — ele carrega
 * texto que a pessoa escreveu, e um que aparece e some faz quem escreveu acreditar que a outra
 * pessoa leu. Quando o envio falha, o rascunho **permanece** no campo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInDetailScreen(
    viewModel: CheckInDetailViewModel,
    checkInId: String,
    onNavigateBack: () -> Unit,
    onOpenFriendProfile: (socialId: String, displayName: String) -> Unit,
    now: Long = System.currentTimeMillis(),
    /**
     * O seletor de Squad (T17.11 §140).
     *
     * Opcional: um build sem Spark Backend não tem Squads (§116), e a tela continua completa sem
     * ele — o item de menu simplesmente não aparece.
     */
    shareToSquadViewModel: ShareToSquadViewModel? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    var sharingToSquad by remember { mutableStateOf(false) }

    LaunchedEffect(checkInId) { viewModel.open(checkInId) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Publicação",
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .semantics { contentDescription = CHECKIN_DETAIL_SCREEN_DESCRIPTION }
        ) {
            when (val phase = uiState.phase) {
                CheckInDetailPhase.Loading ->
                    CenteredContent { CircularProgressIndicator(color = Lime400) }

                CheckInDetailPhase.SignedOut -> CenteredContent {
                    Text("Entre na Conta Spark para ver publicações.", color = TextSecondary)
                }

                CheckInDetailPhase.Offline -> CenteredContent {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sem conexão agora.", color = TextPrimary)
                        TextButton(onClick = viewModel::refresh) {
                            Text("Tentar de novo", color = Lime400)
                        }
                    }
                }

                CheckInDetailPhase.Unavailable -> CenteredContent {
                    // Uma resposta só para "foi excluída", "deixaram de ser amigos", "há bloqueio"
                    // e "o autor desativou o Social": o servidor não distingue os quatro (§51), e a
                    // tela não inventa uma distinção que ela não tem como saber.
                    Text("Esta publicação não está mais disponível.", color = TextSecondary)
                }

                is CheckInDetailPhase.Error -> CenteredContent {
                    Text(phase.message, color = TextSecondary)
                }

                is CheckInDetailPhase.Success -> DetailBody(
                    checkIn = phase.checkIn,
                    comments = phase.comments,
                    uiState = uiState,
                    now = now,
                    onReact = viewModel::toggleReaction,
                    onDraftChanged = viewModel::onDraftChanged,
                    onSendComment = viewModel::sendComment,
                    onDeleteComment = viewModel::deleteComment,
                    onReport = viewModel::report,
                    onOpenFriendProfile = onOpenFriendProfile,
                    onShareToSquad = shareToSquadViewModel?.let { { sharingToSquad = true } }
                )
            }
        }

        if (sharingToSquad && shareToSquadViewModel != null) {
            ShareToSquadDialog(
                viewModel = shareToSquadViewModel,
                checkInId = checkInId,
                onDismiss = { sharingToSquad = false }
            )
        }

        uiState.notice?.let { notice ->
            AlertDialog(
                onDismissRequest = viewModel::dismissNotice,
                containerColor = SurfaceDark,
                text = { Text(notice, color = TextPrimary, fontSize = 14.sp) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissNotice) {
                        Text("OK", color = Lime400)
                    }
                }
            )
        }
    }
}

@Composable
private fun DetailBody(
    checkIn: WorkoutCheckIn,
    comments: List<CheckInComment>,
    uiState: CheckInDetailUiState,
    now: Long,
    onReact: (ReactionType) -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendComment: () -> Unit,
    onDeleteComment: (String) -> Unit,
    onReport: (SocialReportTarget, String, String) -> Unit,
    onOpenFriendProfile: (String, String) -> Unit,
    /** `null` quando o compartilhamento em Squad não está disponível (T17.11 §116/§140). */
    onShareToSquad: (() -> Unit)?
) {
    var reportingTarget by remember { mutableStateOf<Pair<SocialReportTarget, String>?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                DetailHeader(
                    checkIn = checkIn,
                    photo = uiState.photo,
                    now = now,
                    isReacting = uiState.isReacting,
                    onReact = onReact,
                    onOpenFriendProfile = {
                        onOpenFriendProfile(checkIn.author.socialId, checkIn.author.displayName)
                    },
                    onReport = { reportingTarget = SocialReportTarget.CHECKIN to checkIn.checkInId },
                    onShareToSquad = onShareToSquad
                )
            }

            item { HorizontalDivider(color = BorderLight) }

            if (comments.isEmpty()) {
                item {
                    Text(
                        text = CHECKIN_DETAIL_EMPTY_COMMENTS,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(comments, key = { it.commentId }) { comment ->
                    CommentRow(
                        comment = comment,
                        isDeleting = uiState.deletingCommentId == comment.commentId,
                        onDelete = { onDeleteComment(comment.commentId) },
                        onReport = {
                            reportingTarget = SocialReportTarget.COMMENT to comment.commentId
                        }
                    )
                }
            }
        }

        CommentComposer(
            draft = uiState.draft,
            remaining = uiState.draftRemaining,
            canSend = uiState.canSendComment,
            isSending = uiState.isSendingComment,
            onDraftChanged = onDraftChanged,
            onSend = onSendComment
        )
    }

    reportingTarget?.let { (target, id) ->
        ReportReasonDialog(
            onPick = { reason ->
                reportingTarget = null
                onReport(target, id, reason)
            },
            onDismiss = { reportingTarget = null }
        )
    }
}

@Composable
private fun DetailHeader(
    checkIn: WorkoutCheckIn,
    photo: ImageBitmap?,
    now: Long,
    isReacting: Boolean,
    onReact: (ReactionType) -> Unit,
    onOpenFriendProfile: () -> Unit,
    onReport: () -> Unit,
    onShareToSquad: (() -> Unit)?
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = checkIn.author.displayName.uppercase(),
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
                Text(text = SOCIAL_FEED_CHECKIN_LABEL, color = TextPrimary, fontSize = 14.sp)
                Text(
                    text = relativePublishedAt(checkIn.publishedAt, now),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            // T17.11 §140 — a publicação **própria** ganha "Compartilhar no Squad". Ela não tem
            // "Ver perfil" nem "Denunciar": os dois são sobre outra pessoa.
            if (checkIn.isCurrentUser && onShareToSquad != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Ações da publicação",
                            tint = TextSecondary
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Compartilhar no Squad", color = TextPrimary) },
                            onClick = {
                                menuOpen = false
                                onShareToSquad()
                            }
                        )
                    }
                }
            }

            if (!checkIn.isCurrentUser) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Ações da publicação",
                            tint = TextSecondary
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Ver perfil", color = TextPrimary) },
                            onClick = {
                                menuOpen = false
                                onOpenFriendProfile()
                            }
                        )
                        // §101/§102 — denunciar **a publicação**. A foto e a legenda pertencem a
                        // ela: não existe "denunciar a foto" como alvo separado.
                        DropdownMenuItem(
                            text = { Text("Denunciar publicação", color = Red400) },
                            onClick = {
                                menuOpen = false
                                onReport()
                            }
                        )
                    }
                }
            }
        }

        checkIn.media?.let { media ->
            Surface(
                color = SurfaceHighlight,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(media.aspectRatio.coerceIn(0.5f, 2f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (photo != null) {
                        Image(
                            bitmap = photo,
                            contentDescription = SOCIAL_FEED_PHOTO_DESCRIPTION,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = Lime400, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        checkIn.caption?.let { caption ->
            Text(text = caption, color = TextPrimary, fontSize = 15.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReactionType.entries.forEach { type ->
                val count = checkIn.reactions[type] ?: 0
                val selected = checkIn.currentUserReaction == type
                Surface(
                    color = if (selected) SurfaceHighlight else SurfaceDark,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (selected) Lime400 else BorderLight),
                    modifier = Modifier
                        .clickable(enabled = !isReacting) { onReact(type) }
                        .semantics { contentDescription = reactionDescription(type) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = REACTION_EMOJI.getValue(type), fontSize = 16.sp)
                        Text(
                            text = "$count",
                            color = if (selected) Lime400 else TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: CheckInComment,
    isDeleting: Boolean,
    onDelete: () -> Unit,
    onReport: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = comment.author.displayName.uppercase(),
                color = if (comment.isCurrentUser) Lime400 else TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            // Texto puro. Sem markup, sem link clicável, sem menção (§9/§10/§79/§80).
            Text(text = comment.body, color = TextPrimary, fontSize = 14.sp)
        }

        if (isDeleting) {
            CircularProgressIndicator(color = Lime400, modifier = Modifier.size(16.dp))
        } else {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Ações do comentário",
                        tint = TextSecondary
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // `canDelete` vem do **servidor** (§93/§94/§95): o autor do comentário e o
                    // autor da publicação podem apagar. Esconder o item não é controle de acesso —
                    // o servidor recusa de qualquer forma —, mas oferecer uma ação que vai falhar
                    // é pior do que não oferecê-la.
                    if (comment.canDelete) {
                        DropdownMenuItem(
                            text = { Text("Apagar comentário", color = Red400) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                    if (!comment.isCurrentUser) {
                        DropdownMenuItem(
                            text = { Text("Denunciar comentário", color = Red400) },
                            onClick = {
                                menuOpen = false
                                onReport()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentComposer(
    draft: String,
    remaining: Int,
    canSend: Boolean,
    isSending: Boolean,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(color = SurfaceDark, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = CHECKIN_DETAIL_COMMENT_FIELD_DESCRIPTION },
                placeholder = { Text("Escreva um comentário…", color = TextSecondary) },
                supportingText = {
                    if (remaining < 20) {
                        Text(
                            text = "$remaining",
                            color = if (remaining < 0) Red400 else TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                },
                maxLines = 4
            )

            if (isSending) {
                CircularProgressIndicator(color = Lime400, modifier = Modifier.size(20.dp))
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.semantics {
                        contentDescription = CHECKIN_DETAIL_SEND_COMMENT_DESCRIPTION
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = if (canSend) Lime400 else TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * A escolha do motivo (§107).
 *
 * A taxonomia é a da T17.6, reusada sem nenhuma paralela. Não há campo de texto livre: uma
 * denúncia com descrição seria conteúdo do usuário chegando ao servidor por uma porta que não tem
 * sanitização — e §107 pede exatamente o contrário.
 */
@Composable
private fun ReportReasonDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Denunciar", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "Nossa equipe vai revisar. Denunciar não bloqueia ninguém, e a pessoa " +
                        "denunciada não é avisada.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                REPORT_REASONS.forEach { (code, label) ->
                    TextButton(
                        onClick = { onPick(code) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, color = TextPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        }
    )
}

@Composable
private fun CenteredContent(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}
