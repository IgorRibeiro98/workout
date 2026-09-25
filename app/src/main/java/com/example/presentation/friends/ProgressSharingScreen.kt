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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.FriendSocialProfile
import com.example.domain.social.ProgressSharingField
import com.example.domain.social.ProgressSharingGroup
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialFieldAvailabilityDetail
import com.example.domain.social.SocialSyncResult
import com.example.domain.social.isShared
import com.example.presentation.account.ProgressSharingPhase
import com.example.presentation.account.SharingDataSync
import com.example.presentation.account.SocialProfileUiState
import com.example.presentation.account.SocialProfileViewModel
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.FitnessCenter
import com.example.ui.components.IconLabel
import com.example.ui.components.semanticIcon
import androidx.compose.ui.graphics.vector.ImageVector

const val PROGRESS_SHARING_TITLE = "Compartilhar progresso"
const val PROGRESS_SHARING_DESCRIPTION = "Configurações de compartilhamento de progresso"
const val PREVIEW_BUTTON = "Pré-visualizar meu perfil"
const val PREVIEW_EMPTY_MESSAGE =
    "Seus amigos ainda não veem nenhuma informação de progresso no seu perfil."

/**
 * O que **eu** compartilho com meus amigos (T17.2 §97).
 *
 * ```text
 * Compartilhar progresso
 *
 * [ Seus treinos no servidor · Sincronizar dados ]   ← T19.H5: só quando falta treino no servidor
 *
 * PROGRESSO GERAL
 * Nível · Consistência semanal · Treinos da semana · Conquistas em destaque
 *
 * ESTATÍSTICAS DE TREINO                       ← T19.H3: no perfil, somadas da semana
 * Tempo treinado · Séries · Volume da semana · Treinos totais
 *
 * DETALHES DOS CHECK-INS                       ← T19.H3: em cada publicação, também nas antigas
 * Nome · Horário · Duração · Exercícios · Séries e repetições · Cargas · Volume total
 *
 * [ Pré-visualizar meu perfil ]
 * ```
 *
 * Todos começam desligados (a migration do servidor criou os onze novos desligados para quem já
 * existia). "Cargas utilizadas" depende de "Exercícios" e "Séries e repetições" — ver
 * [dependencyMet].
 *
 * Um servidor que não declara `contractVersion` 2 (T19.H5) não conhece os dois últimos grupos: no
 * lugar deles aparece um aviso só, e nenhum interruptor que ele recusaria.
 *
 * ## Interruptor e disponibilidade são coisas diferentes
 *
 * O interruptor diz o que eu **permito**; a etiqueta ao lado diz o que o servidor **consegue**
 * mostrar. As duas juntas produzem o único estado que uma tela de privacidade não pode esconder:
 * "ligado, e ainda assim não aparece" (§38/§74).
 *
 * Ligar um campo `UNAVAILABLE` é permitido de propósito: a preferência fica guardada e o campo
 * aparece sozinho no dia em que o dado existir. O que **não** acontece é o servidor gravar um
 * valor falso para o interruptor ter efeito.
 *
 * `UNSUPPORTED` (T19.H0) é diferente: sincronizar nunca resolve, então o interruptor fica
 * desabilitado — ligá-lo não teria efeito observável hoje nem depois. Uma preferência antiga
 * persistida como `true` continua chegando como `true` (nada é apagado), só sem controle
 * interativo enquanto a métrica não tiver autoridade remota (ver [SharingToggle]).
 *
 * ## Sem atualização otimista
 *
 * O interruptor só se move depois que o servidor confirmou (§106). Offline, ele não se move e a
 * tela diz que nada foi alterado (§107) — numa tela de privacidade, um "salvo" que não salvou é o
 * pior desfecho possível.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressSharingScreen(
    viewModel: SocialProfileViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.openProgressSharing() }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = PROGRESS_SHARING_TITLE,
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
                    // T19.H3: uma escolha feita em outro aparelho, e a disponibilidade de cada
                    // campo (que muda depois de sincronizar), só aparecem relendo.
                    SocialRefreshAction(
                        isRefreshing = uiState.isSharingRefreshing ||
                            uiState.sharingPhase is ProgressSharingPhase.Loading,
                        onRefresh = viewModel::refreshProgressSharing,
                        enabled = uiState.isConfigured &&
                            uiState.sharingPhase !is ProgressSharingPhase.Saving
                    )
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
                .semantics { contentDescription = PROGRESS_SHARING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProgressSharingBody(
                uiState = uiState,
                onToggle = viewModel::setShare,
                onPreview = viewModel::loadPreview,
                onRetry = viewModel::refreshProgressSharing,
                onSyncData = viewModel::syncData
            )
        }
    }
}

/** O corpo da tela, sem ViewModel — renderizável em teste com um estado montado à mão. */
@Composable
internal fun ProgressSharingBody(
    uiState: SocialProfileUiState,
    onToggle: (ProgressSharingField, Boolean) -> Unit = { _, _ -> },
    onPreview: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSyncData: () -> Unit = {}
) {
    if (!uiState.isConfigured) {
        Message(
            title = "Indisponível",
            body = "Os recursos sociais não estão disponíveis nesta versão do app."
        )
        return
    }

    when (val phase = uiState.sharingPhase) {
        ProgressSharingPhase.Idle, ProgressSharingPhase.Loading -> Busy("Carregando...")

        is ProgressSharingPhase.SocialUnavailable -> Message(
            title = if (phase.disabled) "Social desativado" else "Ative os recursos sociais",
            body = if (phase.disabled) {
                "Reative no Perfil para voltar a compartilhar. Nada foi apagado."
            } else {
                "Ative os recursos sociais no Perfil para compartilhar seu progresso."
            }
        )

        ProgressSharingPhase.Offline -> {
            Message(
                title = "Sem conexão",
                body = "Não foi possível falar com o servidor. Nada foi alterado."
            )
            SecondaryButton("Tentar de novo", onRetry)
        }

        is ProgressSharingPhase.Error -> {
            Message(title = "Não foi possível carregar", body = messageFor(phase.reason))
            SecondaryButton("Tentar de novo", onRetry)
        }

        ProgressSharingPhase.Ready, ProgressSharingPhase.Saving -> {
            // Durante "Sincronizar dados" os interruptores esperam: a releitura depois do ciclo e
            // um `PATCH` cruzados poderiam deixar na tela o interruptor anterior ao toque.
            val enabled = phase == ProgressSharingPhase.Ready &&
                uiState.dataSync !is SharingDataSync.Running

            Text(
                text = "Escolha o que seus amigos podem ver. Tudo começa desligado, e nada é " +
                    "publicado sem você ligar.",
                color = TextSecondary,
                fontSize = 13.sp
            )

            // T19.H5 §13: quando o que falta é treino no servidor — e só então, ou para mostrar o
            // resultado de uma sincronização desta tela —, a ação que resolve fica à mão.
            if (uiState.canSyncData &&
                (uiState.availability.needsSync || uiState.dataSync !is SharingDataSync.Idle)
            ) {
                SyncDataCard(
                    dataSync = uiState.dataSync,
                    stillMissing = uiState.availability.needsSync,
                    enabled = phase == ProgressSharingPhase.Ready,
                    onSyncData = onSyncData
                )
            }

            // A releitura depois de um "Sincronizar dados" que o servidor confirmou: aí "sem treino
            // no servidor" deixa de ser "sincronize" e passa a ser "não há treino".
            val syncConfirmed = (uiState.dataSync as? SharingDataSync.Finished)
                ?.let { it.result == SocialSyncResult.SYNCED && it.reread } == true

            // Os três grupos da T19.H3 (§23), na ordem do enum. Um grupo é um título, uma frase
            // sobre onde aquilo aparece e para quem, e os interruptores dele. Um grupo que o
            // servidor não declarou conhecer (T19.H5) não oferece interruptor nenhum: o `PATCH`
            // seria recusado. Os grupos assim viram **um** aviso só.
            val (supported, unknownToServer) = ProgressSharingGroup.entries
                .partition { uiState.contractVersion >= it.sinceContractVersion }
            supported.forEach { group ->
                GroupHeader(
                    title = progressSharingGroupTitle(group),
                    description = progressSharingGroupDescription(group)
                )
                ProgressSharingField.entries.filter { it.group == group }.forEach { field ->
                    SharingToggle(
                        label = progressSharingLabel(field),
                        checked = uiState.settings.isShared(field),
                        detail = uiState.availability.detailOf(field),
                        enabled = enabled && dependencyMet(field, uiState.settings),
                        onCheckedChange = { onToggle(field, it) },
                        note = progressSharingNote(field, uiState.settings),
                        syncConfirmed = syncConfirmed
                    )
                }
            }
            if (unknownToServer.isNotEmpty()) {
                LegacyBackendNotice()
            }

            if (phase == ProgressSharingPhase.Saving) {
                Busy("Salvando...")
            }

            uiState.notice?.let { Message(title = "Aviso", body = messageFor(it)) }

            SecondaryButton(PREVIEW_BUTTON, onPreview, enabled = enabled)
            if (uiState.isPreviewLoading) {
                Busy("Montando a prévia...")
            }
            uiState.preview?.let { PreviewCard(it) }
        }
    }
}

/** O título de um grupo e, quando há, onde aquilo aparece e para quem (T19.H3 §23/§37). */
@Composable
private fun GroupHeader(title: String, description: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title.uppercase(),
            color = Lime400,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            modifier = Modifier.semantics { heading() }
        )
        description?.let { Text(text = it, color = TextSecondary, fontSize = 12.sp) }
    }
}

/**
 * "Cargas utilizadas" só tem onde aparecer dentro das séries de cada exercício (§31).
 *
 * Desligado e sem as duas dependências, o interruptor fica desabilitado — ligá-lo não teria efeito
 * observável. **Ligado**, ele continua clicável mesmo sem elas: é o único jeito de desligar uma
 * escolha que sobrou de antes, e o servidor já não publica carga nenhuma nesse estado.
 */
internal fun dependencyMet(field: ProgressSharingField, settings: ProgressSharingSettings): Boolean =
    when (field) {
        ProgressSharingField.WORKOUT_WEIGHTS ->
            settings.shareWorkoutWeights ||
                (settings.shareWorkoutExercises && settings.shareWorkoutSets)
        else -> true
    }

/**
 * Um interruptor e o que o servidor consegue mostrar daquele campo.
 *
 * A etiqueta de disponibilidade fica **fora** do interruptor porque ela não é o estado dele: é o
 * estado do dado. Juntar as duas coisas num único controle produziria o interruptor desabilitado
 * que a pessoa não entende — e ela precisa poder ligar agora o que vai aparecer depois.
 *
 * A exceção é [SocialFieldAvailability.UNSUPPORTED] (T19.H0): sincronizar nunca resolve esse
 * campo, então ligá-lo agora não é "vai aparecer depois" — é um interruptor sem efeito observável.
 * O switch fica desabilitado, mas o valor de [checked] não é apagado: uma preferência antiga
 * persistida como `true` continua vindo do servidor como `true`, só sem poder ser alterada por
 * aqui enquanto a métrica não tiver autoridade remota.
 */
@Composable
private fun SharingToggle(
    label: String,
    checked: Boolean,
    /**
     * Estado e motivo (T19.H5). `null` para os detalhes de check-in: são preferência de
     * privacidade, sem disponibilidade global — dependem de cada publicação, não do perfil.
     */
    detail: SocialFieldAvailabilityDetail?,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** Uma frase sobre o **significado** do campo publicado, quando ele difere do local. */
    note: String? = null,
    /** Um "Sincronizar dados" desta tela já terminou e a releitura veio — ver [availabilityHint]. */
    syncConfirmed: Boolean = false
) {
    val availability = detail?.status
    val switchEnabled = enabled && availability != SocialFieldAvailability.UNSUPPORTED
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
            // `weight(1f)` e não largura natural: sem ele, esta coluna é medida antes do
            // `Switch` e com a linha inteira disponível, e a legenda de disponibilidade —
            // "Este dado ainda não existe no servidor..." — consome o espaço que sobraria para o
            // interruptor, que aparecia cortado em 320/360dp (H2.8).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = label,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                if (detail != null) {
                    Text(
                        text = availabilityLabel(detail.status),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    // A explicação só aparece quando há algo a explicar, e é a do **motivo**
                    // (T19.H5): "sincronize", "falta o fuso", "semana acima do limite" pedem coisas
                    // diferentes. "Disponível" não precisa de uma linha dizendo que está tudo bem.
                    availabilityHint(detail, syncConfirmed)?.let { hint ->
                        Text(text = hint, color = TextSecondary, fontSize = 11.sp)
                    }
                }
                note?.let { Text(text = it, color = TextSecondary, fontSize = 11.sp) }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = switchEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = Lime400),
                modifier = Modifier.testTag("progress_sharing_switch_$label")
            )
        }
    }
}

/**
 * "Sincronizar dados" (T19.H5 §13–§17): aparece quando o que falta é treino no servidor, e continua
 * na tela com o resultado depois de um toque.
 *
 * É o ciclo da T16 — o mesmo do "Sincronizar agora" do Perfil. O "↻" da barra continua só
 * relendo o servidor: são ações diferentes, e o texto diz isso.
 */
@Composable
private fun SyncDataCard(
    dataSync: SharingDataSync,
    stillMissing: Boolean,
    enabled: Boolean,
    onSyncData: () -> Unit
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SYNC_DATA_CARD_TAG)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = SYNC_DATA_TITLE,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(text = SYNC_DATA_DESCRIPTION, color = TextSecondary, fontSize = 13.sp)
            when (dataSync) {
                SharingDataSync.Running -> Busy(SYNC_DATA_RUNNING)
                else -> {
                    if (dataSync is SharingDataSync.Finished) {
                        Text(
                            text = syncDataResultMessage(dataSync.result, dataSync.reread, stillMissing),
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                    // Depois de um desfecho que ainda deixa treino faltando, sincronizar de novo
                    // continua sendo a ação que resolve — o treino pode ter sido concluído agora.
                    if (stillMissing || dataSync !is SharingDataSync.Finished) {
                        PrimaryButton(text = SYNC_DATA_BUTTON, onClick = onSyncData, enabled = enabled)
                    }
                }
            }
        }
    }
}

/**
 * O servidor não declarou conhecer as estatísticas e os detalhes da T19.H3 (T19.H5 §8): um aviso
 * só, no lugar dos onze interruptores que ele recusaria. Não é "Ainda não disponível" — sincronizar
 * não resolve; resolve o servidor ser atualizado.
 */
@Composable
private fun LegacyBackendNotice() {
    Column(modifier = Modifier.testTag(LEGACY_BACKEND_NOTICE_TAG)) {
        Message(title = LEGACY_BACKEND_TITLE, body = "$LEGACY_BACKEND_MESSAGE Nada foi alterado.")
    }
}

internal const val SYNC_DATA_CARD_TAG = "progress_sharing_sync_data"
internal const val LEGACY_BACKEND_NOTICE_TAG = "progress_sharing_legacy_backend"

/**
 * A prévia: o que um amigo veria de mim **agora**.
 *
 * Ela vem do servidor, pelo mesmo caminho do perfil de amigo. Montá-la aqui a partir dos
 * interruptores mostraria o que eu liguei — e não o que o servidor consegue publicar, que é
 * justamente a diferença que esta tela existe para revelar.
 */
@Composable
private fun PreviewCard(profile: FriendSocialProfile) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Assim seus amigos veem você",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Text(
                text = profile.displayName,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            if (profile.sharedProgress.isEmpty) {
                Text(text = PREVIEW_EMPTY_MESSAGE, color = TextSecondary, fontSize = 13.sp)
            } else {
                profile.sharedProgress.level?.let {
                    PreviewRow(label = "Nível", value = "$it")
                }
                profile.sharedProgress.consistencyStreak?.let {
                    PreviewRow(
                        label = "Consistência",
                        icon = Icons.Filled.LocalFireDepartment,
                        value = if (it == 1) "1 semana" else "$it semanas"
                    )
                }
                profile.sharedProgress.weeklyWorkoutCount?.let {
                    PreviewRow(
                        label = "Esta semana",
                        icon = Icons.Filled.FitnessCenter,
                        value = if (it == 1) "1 treino" else "$it treinos"
                    )
                }
                trainingStatLines(profile.sharedProgress).forEach { (label, value) ->
                    PreviewRow(label = label, value = value)
                }
                val highlighted = profile.sharedProgress.highlightedAchievementIds
                    .mapNotNull { id -> achievementLabel(id)?.let { title -> achievementIconKey(id) to title } }
                if (highlighted.isNotEmpty()) {
                    Text(text = "Conquistas", color = TextSecondary, fontSize = 14.sp)
                    highlighted.forEach { (iconKey, title) ->
                        IconLabel(
                            icon = semanticIcon(iconKey),
                            text = title,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            iconTint = Lime400
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (icon != null) {
            IconLabel(icon = icon, text = label, color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Normal)
        } else {
            Text(text = label, color = TextSecondary, fontSize = 14.sp)
        }
        Text(text = value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
