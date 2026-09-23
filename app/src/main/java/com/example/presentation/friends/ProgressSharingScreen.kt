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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.FriendSocialProfile
import com.example.domain.social.SocialFieldAvailability
import com.example.presentation.account.ProgressSharingPhase
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
 * Nível                    [ ]   Disponível / Ainda não disponível
 * Consistência             [ ]   Disponível / Ainda não disponível
 * Treinos da semana        [ ]   Disponível
 * Conquistas em destaque   [ ]   Disponível / Ainda não disponível
 *
 * [ Pré-visualizar meu perfil ]
 * ```
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
                onShareLevel = viewModel::setShareLevel,
                onShareConsistencyStreak = viewModel::setShareConsistencyStreak,
                onShareWeeklyWorkoutCount = viewModel::setShareWeeklyWorkoutCount,
                onShareHighlightedAchievements = viewModel::setShareHighlightedAchievements,
                onPreview = viewModel::loadPreview,
                onRetry = viewModel::refreshProgressSharing
            )
        }
    }
}

/** O corpo da tela, sem ViewModel — renderizável em teste com um estado montado à mão. */
@Composable
internal fun ProgressSharingBody(
    uiState: SocialProfileUiState,
    onShareLevel: (Boolean) -> Unit = {},
    onShareConsistencyStreak: (Boolean) -> Unit = {},
    onShareWeeklyWorkoutCount: (Boolean) -> Unit = {},
    onShareHighlightedAchievements: (Boolean) -> Unit = {},
    onPreview: () -> Unit = {},
    onRetry: () -> Unit = {}
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
            val enabled = phase == ProgressSharingPhase.Ready

            Text(
                text = "Escolha o que seus amigos podem ver. Tudo começa desligado, e nada é " +
                    "publicado sem você ligar.",
                color = TextSecondary,
                fontSize = 13.sp
            )

            SharingToggle(
                label = "Nível",
                checked = uiState.settings.shareLevel,
                availability = uiState.availability.level,
                enabled = enabled,
                onCheckedChange = onShareLevel,
                note = LEVEL_SHARING_NOTE
            )
            SharingToggle(
                label = "Consistência semanal",
                checked = uiState.settings.shareConsistencyStreak,
                availability = uiState.availability.consistencyStreak,
                enabled = enabled,
                onCheckedChange = onShareConsistencyStreak
            )
            SharingToggle(
                label = "Treinos da semana",
                checked = uiState.settings.shareWeeklyWorkoutCount,
                availability = uiState.availability.weeklyWorkoutCount,
                enabled = enabled,
                onCheckedChange = onShareWeeklyWorkoutCount
            )
            SharingToggle(
                label = "Conquistas em destaque",
                checked = uiState.settings.shareHighlightedAchievements,
                availability = uiState.availability.highlightedAchievements,
                enabled = enabled,
                onCheckedChange = onShareHighlightedAchievements
            )

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
    availability: SocialFieldAvailability,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** Uma frase sobre o **significado** do campo publicado, quando ele difere do local. */
    note: String? = null
) {
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
                Text(
                    text = availabilityLabel(availability),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                // A explicação só aparece quando há algo a explicar. "Disponível" não precisa de
                // uma linha dizendo que está tudo bem.
                availabilityHint(availability)?.let { hint ->
                    Text(text = hint, color = TextSecondary, fontSize = 11.sp)
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
