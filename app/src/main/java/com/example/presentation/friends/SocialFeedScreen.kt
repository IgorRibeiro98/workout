package com.example.presentation.friends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ImageBitmap
import com.example.domain.social.InteractionContext
import com.example.domain.social.ReactionType
import com.example.domain.social.interactionKey
import com.example.domain.social.WorkoutCheckIn
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

const val SOCIAL_FEED_SCREEN_DESCRIPTION = "Feed de check-ins dos amigos"

/** O texto de lista vazia. Ele fala do Feed, e nunca do que os amigos fizeram ou não (§90). */
const val SOCIAL_FEED_EMPTY_MESSAGE = "Nenhum check-in compartilhado recentemente."

/** O que um card de check-in diz sobre o **treino**. Nada além disto atravessa a fronteira. */
const val SOCIAL_FEED_CHECKIN_LABEL = "💪 Concluiu um treino"

/**
 * O emoji de cada reação (T17.9 §61).
 *
 * O protocolo carrega o **nome** (`FIRE`), e a tela escolhe o desenho. É o que permite trocar o
 * emoji sem tocar no servidor — e o que impede o cliente de enviar um caractere arbitrário (§62).
 */
internal val REACTION_EMOJI: Map<ReactionType, String> = mapOf(
    ReactionType.FIRE to "🔥",
    ReactionType.MUSCLE to "💪",
    ReactionType.CLAP to "👏"
)

/** O rótulo acessível de cada reação. O leitor de tela precisa de palavras, não de emoji. */
internal fun reactionDescription(type: ReactionType): String = when (type) {
    ReactionType.FIRE -> "Reagir com fogo"
    ReactionType.MUSCLE -> "Reagir com força"
    ReactionType.CLAP -> "Reagir com aplausos"
}

const val SOCIAL_FEED_COMMENTS_DESCRIPTION = "Ver comentários"
const val SOCIAL_FEED_PHOTO_DESCRIPTION = "Foto do check-in"

/**
 * O Feed social (T17.8).
 *
 * ## O que um card mostra — e por que ele mostra tão pouco
 *
 * Nome social, "concluiu um treino", há quanto tempo **a publicação** foi feita e — desde a T17.9 —
 * a foto, a legenda, as reações e quantos comentários existem (§119). Não há nome do treino,
 * exercício, série, repetição, carga, duração, volume, recorde nem horário do treino, e a ausência
 * não é falta de tempo: é o contrato. O servidor também não os envia, então não há como esta tela
 * mostrá-los mesmo que alguém tentasse (§185).
 *
 * O tempo exibido é o da **publicação**, e não o do treino (§49). São coisas diferentes quando
 * alguém compartilha pelo Histórico horas depois, e é justamente o instante do treino que não pode
 * circular.
 *
 * ## A foto vem por `mediaId`, e nunca por URL
 *
 * O DTO carrega um identificador e as dimensões (§58/§59). Quem busca os bytes é o
 * `SocialMediaCache`, pelo endpoint autenticado, e o resultado vive **em memória** — não existe
 * `AsyncImage` com URL, não existe cache em disco e não existe caminho em que a foto de um amigo
 * encoste no armazenamento deste aparelho (§56/§57).
 *
 * As dimensões chegam antes da imagem, e o card reserva o espaço com elas: sem isso o Feed pularia
 * a cada foto que terminasse de carregar.
 *
 * ## As ações
 *
 * Reagir acontece **no card** (§120), porque é um toque e é reversível. Comentar abre o detalhe
 * (§118): conversa não cabe em uma lista.
 *
 * Na própria publicação, "Excluir publicação" (§87). Na de um amigo, "Ver perfil" — que leva à
 * tela da T17.2, onde **Bloquear** e **Denunciar** já existem (§88). Reusar aquela tela em vez de
 * repetir os diálogos aqui é o que mantém uma implementação só de bloqueio e denúncia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialFeedScreen(
    viewModel: SocialFeedViewModel,
    onNavigateBack: () -> Unit,
    onOpenFriendProfile: (socialId: String, displayName: String) -> Unit,
    onOpenCheckIn: (checkInId: String) -> Unit = {},
    now: Long = rememberRelativeNow()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.open() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Feed",
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
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .semantics { contentDescription = SOCIAL_FEED_SCREEN_DESCRIPTION }
        ) {
            SocialFeedBody(
                uiState = uiState,
                now = now,
                onRetry = viewModel::refresh,
                onDelete = viewModel::deleteCheckIn,
                onOpenFriendProfile = onOpenFriendProfile,
                onOpenCheckIn = onOpenCheckIn,
                onReact = viewModel::toggleReaction,
                onNeedPhoto = viewModel::loadPhoto
            )
        }

        uiState.notice?.let { notice ->
            NoticeDialog(message = notice, onDismiss = viewModel::dismissNotice)
        }
    }
}

@Composable
private fun SocialFeedBody(
    uiState: SocialFeedUiState,
    now: Long,
    onRetry: () -> Unit,
    onDelete: (String) -> Unit,
    onOpenFriendProfile: (String, String) -> Unit,
    onOpenCheckIn: (String) -> Unit,
    onReact: (String, ReactionType) -> Unit,
    onNeedPhoto: (String) -> Unit
) {
    when (val phase = uiState.phase) {
        SocialFeedPhase.Loading -> CenteredBox { CircularProgressIndicator(color = Lime400) }

        SocialFeedPhase.SignedOut -> CenteredMessage(
            title = "Entre na Conta Spark",
            detail = "O Feed mostra os check-ins que você e seus amigos compartilharam."
        )

        SocialFeedPhase.SocialNotEnabled -> CenteredMessage(
            title = "Recursos sociais desativados",
            detail = "Ative os recursos sociais no Perfil para ver e compartilhar check-ins."
        )

        SocialFeedPhase.Offline -> CenteredMessage(
            title = "Feed indisponível",
            detail = "Sem conexão agora. Seus treinos e seu histórico continuam funcionando normalmente.",
            actionLabel = "Tentar de novo",
            onAction = onRetry
        )

        is SocialFeedPhase.Error -> CenteredMessage(
            title = "Feed indisponível",
            detail = phase.message,
            actionLabel = "Tentar de novo",
            onAction = onRetry
        )

        is SocialFeedPhase.Success -> if (phase.items.isEmpty()) {
            CenteredMessage(
                title = SOCIAL_FEED_EMPTY_MESSAGE,
                detail = "Ao concluir um treino, você pode escolher compartilhar um check-in."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // `contentPadding`, e não `padding` no modifier: como padding do container, ele
                // recortava o conteúdo durante a rolagem em vez de deixá-lo passar sob a borda.
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(phase.items, key = { it.checkInId }) { checkIn ->
                    CheckInCard(
                        checkIn = checkIn,
                        now = now,
                        photo = checkIn.media?.let { uiState.photos[it.mediaId] },
                        isDeleting = uiState.deletingCheckInId == checkIn.checkInId,
                        // A ocupação é por `(audiência, publicação)` (T17.12 §61): esta tela é
                        // sempre o Feed de amigos, e a chave diz isso em vez de deixar implícito.
                        isReacting = interactionKey(
                            checkIn.checkInId,
                            InteractionContext.Friend
                        ) in uiState.pendingReactions,
                        onDelete = { onDelete(checkIn.checkInId) },
                        onOpenFriendProfile = {
                            onOpenFriendProfile(checkIn.author.socialId, checkIn.author.displayName)
                        },
                        onOpenCheckIn = { onOpenCheckIn(checkIn.checkInId) },
                        onReact = { type -> onReact(checkIn.checkInId, type) },
                        onNeedPhoto = onNeedPhoto
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckInCard(
    checkIn: WorkoutCheckIn,
    now: Long,
    photo: ImageBitmap?,
    isDeleting: Boolean,
    isReacting: Boolean,
    onDelete: () -> Unit,
    onOpenFriendProfile: () -> Unit,
    onOpenCheckIn: () -> Unit,
    onReact: (ReactionType) -> Unit,
    onNeedPhoto: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // O pedido da foto nasce quando o card entra em composição, e uma vez só por `mediaId`: o
    // cache descarta o pedido duplicado, e a ViewModel não abre uma segunda requisição.
    val mediaId = checkIn.media?.mediaId
    LaunchedEffect(mediaId) { if (mediaId != null) onNeedPhoto(mediaId) }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (checkIn.isCurrentUser) Lime400 else BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = checkIn.author.displayName.uppercase(),
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp
                        )
                        // O destaque discreto de §86. Sem avatar: o Social ainda não tem um (§85).
                        if (checkIn.isCurrentUser) {
                            Surface(color = SurfaceHighlight, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    text = "Você",
                                    color = Lime400,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(text = SOCIAL_FEED_CHECKIN_LABEL, color = TextPrimary, fontSize = 14.sp)
                    Text(
                        text = relativePublishedAt(checkIn.publishedAt, now),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                if (isDeleting) {
                    CircularProgressIndicator(color = Lime400, modifier = Modifier.size(20.dp))
                } else {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Ações da publicação",
                                tint = TextSecondary
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (checkIn.isCurrentUser) {
                                DropdownMenuItem(
                                    text = { Text("Excluir publicação", color = Red400) },
                                    onClick = {
                                        menuOpen = false
                                        confirmingDelete = true
                                    }
                                )
                            } else {
                                // Bloquear e denunciar o **autor** moram no perfil (T17.6/T17.2);
                                // denunciar a **publicação** mora no detalhe, onde ela é o assunto
                                // da tela (T17.9 §101/§104).
                                DropdownMenuItem(
                                    text = { Text("Ver perfil", color = TextPrimary) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenFriendProfile()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Abrir publicação", color = TextPrimary) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenCheckIn()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // A foto (§119). O espaço é reservado pelas dimensões que vieram no DTO, então a
            // chegada da imagem não empurra o resto do Feed para baixo.
            checkIn.media?.let { media ->
                CheckInPhoto(image = photo, aspectRatio = media.aspectRatio)
            }

            // A legenda. `Text` de Compose renderiza `String`: não há markup, não há link
            // clicável, não há menção (§9/§10/§79/§80). O que a pessoa escreveu é o que aparece.
            checkIn.caption?.let { caption ->
                Text(text = caption, color = TextPrimary, fontSize = 14.sp)
            }

            HorizontalDivider(color = BorderLight)

            ReactionBar(
                reactions = checkIn.reactions,
                currentUserReaction = checkIn.currentUserReaction,
                isBusy = isReacting,
                onReact = onReact,
                commentCount = checkIn.commentCount,
                onOpenComments = onOpenCheckIn
            )
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = SurfaceDark,
            title = { Text("Excluir publicação?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    // §69 da T17.8: a exclusão da publicação é sobre a publicação. O treino é outra
                    // coisa, e a tela precisa dizer isso para não prometer o que não faz.
                    //
                    // Desde a T17.9, o que some junto é dito por escrito: foto, legenda, reações e
                    // comentários pertencem à publicação (§99), e alguém que só quer tirar a foto
                    // precisa saber que vai levar a conversa junto.
                    text = "Seus amigos deixarão de ver este check-in — inclusive a foto, a " +
                        "legenda, as reações e os comentários. Seu treino continua salvo no " +
                        "histórico.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("Excluir", color = Red400) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }
}

/**
 * A foto de um card (T17.9 §119).
 *
 * Enquanto os bytes não chegam, o espaço fica reservado na proporção que o DTO informou. Se eles
 * nunca chegarem — sem rede, ou um restore em que o banco veio e a mídia não (§141) —, o card
 * continua completo e sem imagem: um Feed que não carrega por causa de uma foto seria pior do que
 * um card sem ela.
 */
@Composable
private fun CheckInPhoto(image: ImageBitmap?, aspectRatio: Float) {
    Surface(
        color = SurfaceHighlight,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio.coerceIn(0.5f, 2f)),
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
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

/**
 * A barra de reações e o atalho para os comentários (T17.9 §119/§120).
 *
 * Um toque reage; tocar de novo na reação atual remove (§120). A que está ativa fica destacada, e
 * o número ao lado é o que **este** viewer pode ver — contagem filtrada no servidor (§69), nunca
 * recalculada aqui.
 *
 * `internal` desde a T17.12 (§12/§133): o card do Squad passou a oferecer reação e comentário, e
 * ele desenha **esta** barra. Uma segunda cópia divergiria no dia em que um quarto tipo de reação
 * entrasse — e a divergência apareceria como um botão que existe em uma tela e não na outra.
 * Quem muda entre as duas telas é a audiência das ações, e ela não é assunto deste desenho.
 */
@Composable
internal fun ReactionBar(
    reactions: Map<ReactionType, Int>,
    currentUserReaction: ReactionType?,
    isBusy: Boolean,
    onReact: (ReactionType) -> Unit,
    commentCount: Int,
    onOpenComments: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReactionType.entries.forEach { type ->
            val count = reactions[type] ?: 0
            val selected = currentUserReaction == type
            Surface(
                color = if (selected) SurfaceHighlight else SurfaceDark,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (selected) Lime400 else BorderLight),
                modifier = Modifier
                    .clickable(enabled = !isBusy) { onReact(type) }
                    .semantics { contentDescription = reactionDescription(type) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = REACTION_EMOJI.getValue(type), fontSize = 14.sp)
                    Text(
                        text = "$count",
                        color = if (selected) Lime400 else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .clickable { onOpenComments() }
                .semantics { contentDescription = SOCIAL_FEED_COMMENTS_DESCRIPTION }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "💬", fontSize = 14.sp)
            Text(text = "$commentCount", color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NoticeDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        text = { Text(message, color = TextPrimary) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = Lime400) } }
    )
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CenteredMessage(
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(text = detail, color = TextSecondary, fontSize = 13.sp)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = Lime400) }
            }
        }
    }
}

/**
 * Há quanto tempo a **publicação** foi feita.
 *
 * Arredondado de propósito, e nunca uma data com hora: "há 18 min" é o que o Feed precisa dizer, e
 * um horário exato de publicação feito logo após o treino se aproximaria de revelar o horário do
 * treino — que não pode circular (§48).
 */
internal fun relativePublishedAt(publishedAt: Long, now: Long): String {
    val elapsedMinutes = ((now - publishedAt).coerceAtLeast(0L)) / 60_000L
    return when {
        elapsedMinutes < 1L -> "agora mesmo"
        elapsedMinutes < 60L -> "há $elapsedMinutes min"
        elapsedMinutes < 60L * 24L -> {
            val hours = elapsedMinutes / 60L
            if (hours == 1L) "há 1 hora" else "há $hours horas"
        }
        else -> {
            val days = elapsedMinutes / (60L * 24L)
            if (days == 1L) "há 1 dia" else "há $days dias"
        }
    }
}
