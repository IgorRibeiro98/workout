package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.Friend
import com.example.domain.social.InteractionContext
import com.example.domain.social.ReactionType
import com.example.domain.social.SocialGroupFeedItem
import com.example.domain.social.SocialGroupMember
import com.example.domain.social.interactionKey
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.rememberRelativeNow

const val SQUAD_DETAIL_SCREEN_DESCRIPTION = "Detalhe do squad"

/** O texto de feed vazio. Ele fala do **compartilhamento**, porque nada entra sozinho (§52). */
const val SQUAD_FEED_EMPTY_MESSAGE =
    "Nenhum check-in compartilhado neste squad ainda. Compartilhe um dos seus."

/**
 * O rótulo de um participante que o viewer bloqueou, ou que o bloqueou (§34/§136).
 *
 * A entrada continua na lista — a contagem não muda (§35) — e a identidade não aparece.
 */
const val SQUAD_UNAVAILABLE_MEMBER_LABEL = "Participante indisponível"

/** Quando um check-in entrou **neste squad**. Nunca o horário do treino (§85). */
internal fun relativeSharedAt(sharedToGroupAt: Long, now: Long): String =
    "Compartilhado no Squad ${relativePublishedAt(sharedToGroupAt, now)}"

/**
 * O detalhe de um Squad: feed, membros e administração (T17.11 §135/§136/§143/§144).
 *
 * ## Duas abas, e não duas telas
 *
 * O feed e os membros são o mesmo grupo visto de dois jeitos, e separá-los em rotas faria a volta
 * do detalhe de um check-in cair sempre na aba errada.
 *
 * ## As ações que aparecem dependem do papel, e o papel vem do servidor
 *
 * O dono vê convidar, remover, transferir e excluir; o participante vê sair. Isso é conveniência de
 * tela (§72): cada uma dessas rotas revalida o papel, e uma UI desatualizada não amplia nada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquadDetailScreen(
    viewModel: SquadDetailViewModel,
    onNavigateBack: () -> Unit,
    /**
     * Abre o detalhe da publicação **na conversa deste Squad** (T17.12 §35).
     *
     * O nome do squad viaja junto para a tela de detalhe poder dizer onde a conversa acontece
     * (§63). Ele é texto, e não autorização: quem decide o que este usuário alcança é o servidor.
     */
    onOpenCheckIn: (checkInId: String, groupName: String) -> Unit = { _, _ -> },
    now: Long = rememberRelativeNow()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var inviting by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.open() }

    // Sair e excluir fecham a tela: continuar nela mostraria um Squad que já não responde.
    LaunchedEffect(uiState.closed) { if (uiState.closed) onNavigateBack() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.detail?.name.orEmpty(),
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
                actions = {
                    if (uiState.phase is SquadDetailPhase.Success) {
                        SquadOverflowMenu(
                            isOwner = uiState.isOwner,
                            onInvite = {
                                viewModel.loadInvitableFriends()
                                inviting = true
                            },
                            onLeave = { confirmingLeave = true },
                            onDelete = { confirmingDelete = true }
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
                .semantics { contentDescription = SQUAD_DETAIL_SCREEN_DESCRIPTION }
        ) {
            uiState.detail?.let { detail ->
                Text(
                    text = squadMemberCountLabel(detail.memberCount),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                TabRow(
                    selectedTabIndex = if (uiState.tab == SquadDetailTab.FEED) 0 else 1,
                    containerColor = BackgroundDark,
                    contentColor = Lime400
                ) {
                    Tab(
                        selected = uiState.tab == SquadDetailTab.FEED,
                        onClick = { viewModel.selectTab(SquadDetailTab.FEED) },
                        text = { Text("Feed") }
                    )
                    Tab(
                        selected = uiState.tab == SquadDetailTab.MEMBERS,
                        onClick = { viewModel.selectTab(SquadDetailTab.MEMBERS) },
                        text = { Text("Membros") }
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                SquadDetailBody(
                    uiState = uiState,
                    now = now,
                    onRetry = viewModel::refresh,
                    onOpenCheckIn = { checkInId ->
                        onOpenCheckIn(checkInId, uiState.detail?.name.orEmpty())
                    },
                    onReact = viewModel::toggleReaction,
                    onUnshare = viewModel::unshare,
                    onNeedPhoto = viewModel::loadPhoto,
                    onRemoveMember = viewModel::removeMember,
                    onTransferOwnership = viewModel::transferOwnership
                )
            }
        }

        if (inviting) {
            InviteToSquadDialog(
                friends = uiState.invitableFriends,
                busySocialId = uiState.busyTargetId,
                onDismiss = { inviting = false },
                onInvite = { socialId ->
                    viewModel.invite(socialId)
                    inviting = false
                }
            )
        }

        if (confirmingLeave) {
            SquadConfirmDialog(
                title = "Sair do squad?",
                body = "Seus check-ins compartilhados aqui deixam de aparecer para os outros " +
                    "participantes. As publicações continuam no seu feed.",
                confirmLabel = "Sair",
                onDismiss = { confirmingLeave = false },
                onConfirm = {
                    confirmingLeave = false
                    viewModel.leave()
                }
            )
        }

        if (confirmingDelete) {
            SquadConfirmDialog(
                title = "Excluir squad?",
                // §47/§49 — a frase existe porque "excluir" perto de publicações costuma soar como
                // apagar conteúdo, e aqui ele não apaga nada de ninguém.
                body = "O squad e o feed dele desaparecem para todos os participantes. " +
                    "Nenhum treino e nenhum check-in é apagado.",
                confirmLabel = "Excluir",
                onDismiss = { confirmingDelete = false },
                onConfirm = {
                    confirmingDelete = false
                    viewModel.deleteGroup()
                }
            )
        }

        uiState.notice?.let { notice ->
            SquadNoticeDialog(message = notice, onDismiss = viewModel::dismissNotice)
        }
    }
}

@Composable
private fun SquadDetailBody(
    uiState: SquadDetailUiState,
    now: Long,
    onRetry: () -> Unit,
    onOpenCheckIn: (String) -> Unit,
    onReact: (String, ReactionType) -> Unit,
    onUnshare: (String) -> Unit,
    onNeedPhoto: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onTransferOwnership: (String) -> Unit
) {
    when (val phase = uiState.phase) {
        SquadDetailPhase.Loading -> SquadCenteredBox { CircularProgressIndicator(color = Lime400) }

        SquadDetailPhase.SignedOut -> SquadCenteredMessage(
            title = "Entre na Conta Spark",
            body = "Os squads exigem uma conta para saber quem é você.",
            actionLabel = null,
            onAction = onRetry
        )

        SquadDetailPhase.SocialNotEnabled -> SquadCenteredMessage(
            title = "Ative seu perfil social",
            body = "Os squads fazem parte do social do Spark.",
            actionLabel = null,
            onAction = onRetry
        )

        SquadDetailPhase.Unavailable -> SquadCenteredMessage(
            title = "Squad indisponível",
            // §59/§60 — inexistente, excluído e "você não é mais membro" recebem a mesma resposta
            // do servidor, e a tela diz a mesma coisa para os três.
            body = "Este squad não está mais disponível para você.",
            actionLabel = null,
            onAction = onRetry
        )

        SquadDetailPhase.Offline -> SquadCenteredMessage(
            title = "Sem conexão",
            body = "Os squads precisam de internet. Seus treinos continuam funcionando.",
            actionLabel = "Tentar de novo",
            onAction = onRetry
        )

        is SquadDetailPhase.Error -> SquadCenteredMessage(
            title = "Não foi possível carregar",
            body = phase.message,
            actionLabel = "Tentar de novo",
            onAction = onRetry
        )

        is SquadDetailPhase.Success -> when (uiState.tab) {
            SquadDetailTab.FEED -> SquadFeedList(
                items = phase.feed,
                photos = uiState.photos,
                busyTargetId = uiState.busyTargetId,
                pendingReactions = uiState.pendingReactions,
                groupId = phase.detail.groupId,
                now = now,
                onOpenCheckIn = onOpenCheckIn,
                onReact = onReact,
                onUnshare = onUnshare,
                onNeedPhoto = onNeedPhoto
            )

            SquadDetailTab.MEMBERS -> SquadMemberList(
                members = phase.members,
                isOwner = uiState.isOwner,
                busyTargetId = uiState.busyTargetId,
                onRemove = onRemoveMember,
                onTransfer = onTransferOwnership
            )
        }
    }
}

@Composable
private fun SquadFeedList(
    items: List<SocialGroupFeedItem>,
    photos: Map<String, ImageBitmap>,
    busyTargetId: String?,
    /** As reações em voo, com chave `(audiência, publicação)` (T17.12 §61). */
    pendingReactions: Set<String>,
    groupId: String,
    now: Long,
    onOpenCheckIn: (String) -> Unit,
    onReact: (String, ReactionType) -> Unit,
    onUnshare: (String) -> Unit,
    onNeedPhoto: (String) -> Unit
) {
    if (items.isEmpty()) {
        SquadCenteredMessage(
            title = "Feed vazio",
            body = SQUAD_FEED_EMPTY_MESSAGE,
            actionLabel = null,
            onAction = {}
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.checkIn.checkInId }) { item ->
            SquadFeedCard(
                item = item,
                photo = item.checkIn.media?.let { photos[it.mediaId] },
                isBusy = busyTargetId == item.checkIn.checkInId,
                isReacting = interactionKey(
                    item.checkIn.checkInId,
                    InteractionContext.Group(groupId)
                ) in pendingReactions,
                now = now,
                onOpen = { onOpenCheckIn(item.checkIn.checkInId) },
                onReact = { type -> onReact(item.checkIn.checkInId, type) },
                onUnshare = { onUnshare(item.checkIn.checkInId) },
                onNeedPhoto = onNeedPhoto
            )
        }
    }
}

/**
 * Um card do feed do Squad (§143/§144).
 *
 * ## O que ele mostra
 *
 * Autor, "concluiu um treino", foto, legenda e **quando foi compartilhado aqui** (§85). Nada de
 * treino: nem exercício, nem carga, nem duração, nem horário da sessão (§75).
 *
 * ## Reagir e comentar aqui é fazê-lo **neste Squad** (T17.12 §12/§14)
 *
 * Até a T17.11 este card não oferecia nada disso: a interação era do check-in, e não do lugar onde
 * a conversa acontece, então um comentário escrito aqui apareceria no Feed de amigos e no outro
 * Squad. Aquela fase escolheu leitura em vez de vazamento e deixou o acesso só-por-Squad como
 * somente leitura (T17.11 §70/§71).
 *
 * A T17.12 resolveu a causa — a interação pertence a uma audiência —, e o card volta a oferecer as
 * ações: elas nascem em `GROUP(groupId)` e não tocam a reação que a pessoa deixou no Feed de
 * amigos nem em outro Squad. O servidor passa a mandar `canInteract = true` para todo membro
 * ativo (§39).
 *
 * `canInteract` continua vindo do servidor e continua sendo respeitado (§144/§72): esconder um
 * botão nunca foi controle de acesso, mas oferecer uma ação que sempre falha é pior do que não
 * oferecê-la. O toque no corpo do card abre o detalhe — a conversa deste Squad, e não outra.
 */
@Composable
private fun SquadFeedCard(
    item: SocialGroupFeedItem,
    photo: ImageBitmap?,
    isBusy: Boolean,
    isReacting: Boolean,
    now: Long,
    onOpen: () -> Unit,
    onReact: (ReactionType) -> Unit,
    onUnshare: () -> Unit,
    onNeedPhoto: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val checkIn = item.checkIn

    val mediaId = checkIn.media?.mediaId
    LaunchedEffect(mediaId) { if (mediaId != null) onNeedPhoto(mediaId) }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (checkIn.isCurrentUser) Lime400 else BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = checkIn.author.displayName.uppercase(),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = SOCIAL_FEED_CHECKIN_LABEL,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                // §130 — só o autor pode desfazer o compartilhamento, e é isso que o menu oferece.
                if (checkIn.isCurrentUser) {
                    IconButton(onClick = { menuOpen = true }, enabled = !isBusy) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Opções",
                            tint = TextSecondary
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            // O texto diz o que acontece de verdade (§128): o vínculo some, a
                            // publicação fica.
                            text = { Text("Remover deste squad", color = Red400) },
                            onClick = {
                                menuOpen = false
                                onUnshare()
                            }
                        )
                    }
                }
            }

            if (photo != null) {
                Image(
                    bitmap = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(checkIn.media?.aspectRatio ?: 1f)
                        .clip(RoundedCornerShape(12.dp))
                )
            }

            checkIn.caption?.let { caption ->
                Text(text = caption, color = TextPrimary, fontSize = 14.sp)
            }

            Text(
                text = relativeSharedAt(item.sharedToGroupAt, now),
                color = TextSecondary,
                fontSize = 12.sp
            )

            if (checkIn.canInteract) {
                // A **mesma** barra do Feed de amigos (T17.12 §133): o que muda entre as duas
                // telas é a audiência das ações, e não o desenho delas. Duas cópias divergiriam no
                // dia em que um tipo de reação entrasse em uma e não na outra.
                ReactionBar(
                    reactions = checkIn.reactions,
                    currentUserReaction = checkIn.currentUserReaction,
                    isBusy = isReacting,
                    onReact = onReact,
                    commentCount = checkIn.commentCount,
                    // A conversa mora no detalhe, como no Feed (T17.9 §118) — e o detalhe aberto
                    // daqui é a conversa **deste** Squad.
                    onOpenComments = onOpen
                )
            }
        }
    }
}

@Composable
private fun SquadMemberList(
    members: List<SocialGroupMember>,
    isOwner: Boolean,
    busyTargetId: String?,
    onRemove: (String) -> Unit,
    onTransfer: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(members, key = { it.membershipId }) { member ->
            SquadMemberRow(
                member = member,
                canManage = isOwner && !member.isCurrentUser,
                isBusy = busyTargetId == member.membershipId,
                onRemove = { onRemove(member.membershipId) },
                onTransfer = { onTransfer(member.membershipId) }
            )
        }
    }
}

/**
 * Uma linha da lista de membros (§136).
 *
 * Um participante bloqueado — em qualquer direção — vira uma entrada opaca: sem nome, sem
 * `socialId`, sem navegação de perfil. O dono ainda consegue administrá-la pelo `membershipId`
 * (§36), que é o que preserva a integridade do grupo sem revelar quem é a pessoa.
 */
@Composable
private fun SquadMemberRow(
    member: SocialGroupMember,
    canManage: Boolean,
    isBusy: Boolean,
    onRemove: () -> Unit,
    onTransfer: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        color = if (member.available) SurfaceDark else SurfaceHighlight,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.displayName ?: SQUAD_UNAVAILABLE_MEMBER_LABEL,
                    color = if (member.available) TextPrimary else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                if (member.isOwner) {
                    Text(text = "OWNER", color = Lime400, fontSize = 11.sp)
                }
            }

            if (canManage) {
                IconButton(onClick = { menuOpen = true }, enabled = !isBusy) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Gerenciar participante",
                        tint = TextSecondary
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Transferir posse", color = TextPrimary) },
                        onClick = {
                            menuOpen = false
                            onTransfer()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Remover do squad", color = Red400) },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        }
                    )
                }
            }
            if (isBusy) {
                CircularProgressIndicator(color = Lime400, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SquadOverflowMenu(
    isOwner: Boolean,
    onInvite: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit
) {
    var open by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "Opções do squad",
            tint = TextPrimary
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (isOwner) {
            // §22 — só o dono convida, e por isso só ele vê este item.
            DropdownMenuItem(
                text = { Text("Convidar amigo", color = TextPrimary) },
                onClick = {
                    open = false
                    onInvite()
                }
            )
            DropdownMenuItem(
                text = { Text("Excluir squad", color = Red400) },
                onClick = {
                    open = false
                    onDelete()
                }
            )
        } else {
            // §39 — o dono não tem "Sair": ele transfere a posse ou exclui.
            DropdownMenuItem(
                text = { Text("Sair do squad", color = Red400) },
                onClick = {
                    open = false
                    onLeave()
                }
            )
        }
    }
}

/**
 * O seletor de convite (§137).
 *
 * Ele mostra **amigos atuais**, e só eles: um Squad não é caminho para conhecer gente nova (§24). A
 * lista sai da amizade local por conveniência — o servidor revalida a amizade e o bloqueio no envio
 * e de novo no aceite (§25/§29), e recusa um `socialId` que este seletor não deveria ter oferecido.
 */
@Composable
private fun InviteToSquadDialog(
    friends: List<Friend>,
    busySocialId: String?,
    onDismiss: () -> Unit,
    onInvite: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(text = "Convidar amigo", color = TextPrimary) },
        text = {
            if (friends.isEmpty()) {
                Text(
                    text = "Você ainda não tem amigos disponíveis para convidar.",
                    color = TextSecondary
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(friends, key = { it.socialId }) { friend ->
                        TextButton(
                            onClick = { onInvite(friend.socialId) },
                            enabled = busySocialId == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = friend.displayName,
                                color = TextPrimary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "Fechar", color = Lime400) }
        }
    )
}

@Composable
internal fun SquadConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(text = title, color = TextPrimary) },
        text = { Text(text = body, color = TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmLabel, color = Red400, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Cancelar", color = TextSecondary) }
        }
    )
}
