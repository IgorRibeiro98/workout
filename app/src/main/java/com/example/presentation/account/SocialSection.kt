package com.example.presentation.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.social.SocialError
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Os recursos sociais, dentro do Perfil (T17.0).
 *
 * Uma seção do Perfil, e não uma aba nova na navegação: a T17.0 cria identidade e privacidade, e
 * não há nenhuma tela social ainda — um item de bottom navigation levaria para uma tela vazia.
 *
 * ## O que este texto pode e não pode dizer
 *
 * Desde a T17.1 ele pode falar de amigos, código compartilhável e QR Code — porque os três
 * existem. Ele continua **não** prometendo desafio, ranking, feed nem notificação, que não
 * existem, e continua afirmando o que a T17.0 entrega: identificador social, código de amigo e
 * nome social, sem publicar e-mail, treinos ou medidas.
 *
 * A desativação também não diz que "amizades serão apagadas", porque elas **não** são: elas ficam
 * suspensas, invisíveis para os dois lados, e voltam inteiras ao reativar. Treino, histórico,
 * backup e Conta Spark continuam intocados.
 */
@Composable
fun SocialSection(
    uiState: SocialUiState,
    /** O grafo social (T17.1). `null` mantém a seção exatamente como a T17.0 a entregou. */
    friendsState: com.example.presentation.account.FriendsUiState? = null,
    onOpenFriends: () -> Unit = {},
    onOpenRequests: () -> Unit = {},
    /** T17.2 — "Compartilhar progresso". Mesma área Social, sem bottom navigation nova. */
    onOpenProgressSharing: () -> Unit = {},
    onOpenChallenges: () -> Unit = {},
    /** T17.4 — "Atividade dos amigos + rankings contextuais". */
    onOpenActivity: () -> Unit = {},
    /** T17.5 — Notificações sociais */
    onOpenNotificationPreferences: () -> Unit = {},
    /** T17.6 — Usuários bloqueados */
    onOpenBlockedUsers: () -> Unit = {},
    /** T17.7 — Treinos compartilhados entre amigos */
    onOpenSharedWorkouts: () -> Unit = {},
    onOpenSocialFeed: () -> Unit = {},
    onShowFriendCode: () -> Unit = {},
    onActivate: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onConfirmActivation: () -> Unit,
    onCancelActivation: () -> Unit,
    onEditName: () -> Unit,
    onConfirmName: () -> Unit,
    onCancelEditName: () -> Unit,
    onFriendRequestsChange: (Boolean) -> Unit,
    onActivitySharingChange: (Boolean) -> Unit,
    onFriendRankingParticipationChange: (Boolean) -> Unit = {},
    onDisable: () -> Unit,
    onConfirmDisable: () -> Unit,
    onCancelDisable: () -> Unit,
    onEnable: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.phase is SocialPhase.NotConfigured) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = SOCIAL_SECTION_TITLE,
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = SOCIAL_SECTION_DESCRIPTION }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (val phase = uiState.phase) {
                    SocialPhase.NotConfigured -> Unit

                    // Reusa a Conta Spark da T16.1: quem oferece "Continuar com Google" é a seção
                    // de conta, logo acima. Duas portas de login seriam dois estados de sessão.
                    SocialPhase.SignedOut -> Explain(
                        title = "Recursos sociais",
                        body = "Entre na Conta Spark acima para se conectar com amigos. " +
                            "Treinar, ver histórico e fazer backup não dependem disso."
                    )

                    SocialPhase.Loading -> Busy("Carregando...")

                    SocialPhase.NotEnabled -> {
                        Explain(
                            title = "Conecte-se com amigos",
                            body = "Os recursos sociais são opcionais e ficam desativados até " +
                                "você ativar."
                        )
                        Primary(text = "Ativar recursos sociais", onClick = onActivate)
                    }

                    SocialPhase.Activating -> Busy("Ativando...")

                    is SocialPhase.Active -> ActiveProfile(
                        profile = phase.profile,
                        friendsState = friendsState,
                        onOpenFriends = onOpenFriends,
                        onOpenRequests = onOpenRequests,
                        onOpenProgressSharing = onOpenProgressSharing,
                        onOpenChallenges = onOpenChallenges,
                        onOpenActivity = onOpenActivity,
                        onOpenNotificationPreferences = onOpenNotificationPreferences,
                        onOpenBlockedUsers = onOpenBlockedUsers,
                        onOpenSharedWorkouts = onOpenSharedWorkouts,
                        onOpenSocialFeed = onOpenSocialFeed,
                        onShowFriendCode = onShowFriendCode,
                        enabled = true,
                        onEditName = onEditName,
                        onFriendRequestsChange = onFriendRequestsChange,
                        onActivitySharingChange = onActivitySharingChange,
                        onFriendRankingParticipationChange = onFriendRankingParticipationChange,
                        onDisable = onDisable,
                        onEnable = onEnable
                    )

                    is SocialPhase.Saving -> {
                        ActiveProfile(
                            profile = phase.profile,
                            friendsState = friendsState,
                            onOpenFriends = onOpenFriends,
                            onOpenRequests = onOpenRequests,
                            onOpenProgressSharing = onOpenProgressSharing,
                            onOpenChallenges = onOpenChallenges,
                            onOpenActivity = onOpenActivity,
                            onOpenNotificationPreferences = onOpenNotificationPreferences,
                            onOpenBlockedUsers = onOpenBlockedUsers,
                            onOpenSharedWorkouts = onOpenSharedWorkouts,
                        onOpenSocialFeed = onOpenSocialFeed,
                            onShowFriendCode = onShowFriendCode,
                            enabled = false,
                            onEditName = {},
                            onFriendRequestsChange = {},
                            onActivitySharingChange = {},
                            onFriendRankingParticipationChange = {},
                            onDisable = {},
                            onEnable = {}
                        )
                        Busy("Salvando...")
                    }

                    is SocialPhase.Disabling -> Busy("Desativando...")

                    is SocialPhase.Offline -> {
                        phase.profile?.let {
                            ActiveProfile(
                                profile = it,
                                friendsState = friendsState,
                                onOpenFriends = onOpenFriends,
                                onOpenRequests = onOpenRequests,
                                onOpenProgressSharing = onOpenProgressSharing,
                                onOpenChallenges = onOpenChallenges,
                                onOpenActivity = onOpenActivity,
                                onOpenNotificationPreferences = onOpenNotificationPreferences,
                                onOpenBlockedUsers = onOpenBlockedUsers,
                                onOpenSharedWorkouts = onOpenSharedWorkouts,
                        onOpenSocialFeed = onOpenSocialFeed,
                                onShowFriendCode = onShowFriendCode,
                                enabled = false,
                                onEditName = {},
                                onFriendRequestsChange = {},
                                onActivitySharingChange = {},
                                onFriendRankingParticipationChange = {},
                                onDisable = {},
                                onEnable = {}
                            )
                        }
                        // A frase que importa: **nada foi enviado**. Sem ela o usuário sai da tela
                        // achando que a alteração ficou guardada para depois — e ela não ficou.
                        Warning(
                            title = "Sem conexão",
                            body = "Não foi possível falar com o servidor, então nada foi " +
                                "alterado. Seus treinos, histórico e backup continuam normais."
                        )
                        Secondary(text = "Tentar de novo", onClick = onRetry)
                    }

                    is SocialPhase.Error -> {
                        phase.profile?.let {
                            ActiveProfile(
                                profile = it,
                                friendsState = friendsState,
                                onOpenFriends = onOpenFriends,
                                onOpenRequests = onOpenRequests,
                                onOpenProgressSharing = onOpenProgressSharing,
                                onOpenChallenges = onOpenChallenges,
                                onOpenActivity = onOpenActivity,
                                onOpenNotificationPreferences = onOpenNotificationPreferences,
                                onOpenBlockedUsers = onOpenBlockedUsers,
                                onOpenSharedWorkouts = onOpenSharedWorkouts,
                        onOpenSocialFeed = onOpenSocialFeed,
                                onShowFriendCode = onShowFriendCode,
                                enabled = false,
                                onEditName = {},
                                onFriendRequestsChange = {},
                                onActivitySharingChange = {},
                                onFriendRankingParticipationChange = {},
                                onDisable = {},
                                onEnable = {}
                            )
                        }
                        Warning(title = "Não foi possível concluir", body = messageFor(phase.reason))
                        Secondary(text = "Tentar de novo", onClick = onRetry)
                    }
                }
            }
        }
    }

    if (uiState.isActivationSheetOpen) {
        ActivationDialog(
            value = uiState.displayNameInput,
            isValid = uiState.isDisplayNameAcceptable,
            onValueChange = onDisplayNameChange,
            onConfirm = onConfirmActivation,
            onDismiss = onCancelActivation
        )
    }

    if (uiState.isEditingName) {
        NameDialog(
            value = uiState.displayNameInput,
            isValid = uiState.isDisplayNameAcceptable,
            onValueChange = onDisplayNameChange,
            onConfirm = onConfirmName,
            onDismiss = onCancelEditName
        )
    }

    if (uiState.isConfirmingDisable) {
        DisableDialog(onConfirm = onConfirmDisable, onDismiss = onCancelDisable)
    }
}

@Composable
private fun ActiveProfile(
    profile: SocialProfile,
    friendsState: com.example.presentation.account.FriendsUiState?,
    onOpenFriends: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenProgressSharing: () -> Unit,
    onOpenChallenges: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenNotificationPreferences: () -> Unit = {},
    onOpenBlockedUsers: () -> Unit = {},
    onOpenSharedWorkouts: () -> Unit = {},
    onOpenSocialFeed: () -> Unit = {},
    onShowFriendCode: () -> Unit,
    enabled: Boolean,
    onEditName: () -> Unit,
    onFriendRequestsChange: (Boolean) -> Unit,
    onActivitySharingChange: (Boolean) -> Unit,
    onFriendRankingParticipationChange: (Boolean) -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = if (profile.status == SocialProfileStatus.ACTIVE) {
                "Social ativo"
            } else {
                "Social desativado"
            },
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )

        Text(text = profile.displayName, color = TextPrimary, fontSize = 16.sp)

        Text(
            text = profile.friendCode,
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.semantics { contentDescription = FRIEND_CODE_DESCRIPTION }
        )
        Text(
            text = "Seu código de amigo. Quem tiver ele pode te mandar um pedido de amizade — e " +
                "só isso: ele não dá acesso aos seus treinos, medidas ou histórico.",
            color = TextSecondary,
            fontSize = 12.sp
        )

        if (profile.status == SocialProfileStatus.DISABLED) {
            Text(
                text = "Enquanto estiver desativado, ninguém consegue encontrar você. Seu nome e " +
                    "seu código continuam guardados e voltam iguais quando você reativar.",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Primary(text = "Reativar recursos sociais", onClick = onEnable, enabled = enabled)
            return@Column
        }

        // A descoberta só tem um comportamento real, então ela é informada, não oferecida: uma
        // opção que não muda nada seria uma promessa falsa de controle.
        Text(
            text = "Descoberta: somente por código de amigo",
            color = TextSecondary,
            fontSize = 12.sp
        )

        Toggle(
            label = "Aceitar pedidos de amizade",
            checked = profile.privacy.friendRequestsEnabled,
            enabled = enabled,
            onCheckedChange = onFriendRequestsChange
        )
        Toggle(
            label = "Compartilhar atividade",
            checked = profile.privacy.activitySharingEnabled,
            enabled = enabled,
            onCheckedChange = onActivitySharingChange
        )
        Text(
            text = "Permite que seus amigos vejam os dias em que você treinou nos últimos 14 dias. Não mostra exercícios, cargas nem anotações.",
            color = TextSecondary,
            fontSize = 12.sp
        )
        Toggle(
            label = "Participar do ranking semanal",
            checked = profile.privacy.friendRankingParticipationEnabled,
            enabled = enabled,
            onCheckedChange = onFriendRankingParticipationChange
        )
        Text(
            text = "Apareça no ranking dos últimos 7 dias entre amigos que também participam.",
            color = TextSecondary,
            fontSize = 12.sp
        )

        // O grafo social (T17.1). Ele só aparece com o perfil ativo: com o social desligado não
        // há amigo para listar nem pedido para responder, e o `return@Column` acima já saiu.
        if (friendsState != null) {
            FriendsEntryPoints(
                state = friendsState,
                enabled = enabled,
                onOpenFriends = onOpenFriends,
                onOpenRequests = onOpenRequests,
                onOpenProgressSharing = onOpenProgressSharing,
                onOpenChallenges = onOpenChallenges,
                onOpenActivity = onOpenActivity,
                onOpenNotificationPreferences = onOpenNotificationPreferences,
                onOpenBlockedUsers = onOpenBlockedUsers,
                onOpenSharedWorkouts = onOpenSharedWorkouts,
                onOpenSocialFeed = onOpenSocialFeed,
                onShowFriendCode = onShowFriendCode
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Secondary(text = "Editar nome", onClick = onEditName, enabled = enabled)
            Secondary(text = "Desativar", onClick = onDisable, enabled = enabled)
        }
    }
}

/**
 * As portas de entrada do grafo social, dentro do Perfil (T17.1 §65).
 *
 * ```text
 * Social
 * Igor · SPK-7K2P9D8Q
 * 3 amigos · 1 solicitação pendente
 * [ Amigos ]  [ Solicitações ]  [ Meu código ]
 * ```
 *
 * **Nenhum item novo de bottom navigation.** Amigos e Solicitações são telas alcançadas a partir
 * daqui; "Meu código" é uma folha sobre esta mesma tela, porque o código já está carregado e abrir
 * uma tela para mostrar um dado que está na mão seria uma navegação sem conteúdo próprio.
 *
 * Os números vêm do **total** do servidor, e não do tamanho da página carregada: "3 amigos" que
 * vira "50 amigos" ao abrir a lista seria pior do que não mostrar número nenhum.
 */
@Composable
private fun FriendsEntryPoints(
    state: com.example.presentation.account.FriendsUiState,
    enabled: Boolean,
    onOpenFriends: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenProgressSharing: () -> Unit,
    onOpenChallenges: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenNotificationPreferences: () -> Unit,
    onOpenBlockedUsers: () -> Unit = {},
    onOpenSharedWorkouts: () -> Unit = {},
    onOpenSocialFeed: () -> Unit = {},
    onShowFriendCode: () -> Unit
) {
    val summary = buildString {
        append(if (state.friendCount == 1) "1 amigo" else "${state.friendCount} amigos")
        if (state.incomingCount > 0) {
            append(" · ")
            append(
                if (state.incomingCount == 1) {
                    "1 solicitação pendente"
                } else {
                    "${state.incomingCount} solicitações pendentes"
                }
            )
        }
    }

    Text(
        text = summary,
        color = TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.semantics { contentDescription = FRIENDS_SUMMARY_DESCRIPTION }
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Secondary(text = "Amigos", onClick = onOpenFriends, enabled = enabled)
        Secondary(
            text = if (state.incomingCount > 0) {
                "Solicitações (${state.incomingCount})"
            } else {
                "Solicitações"
            },
            onClick = onOpenRequests,
            enabled = enabled
        )
    }
    Secondary(text = "Meu código", onClick = onShowFriendCode, enabled = enabled)
    // T17.2 — o que os amigos veem do meu progresso. Ela fica aqui, e não junto dos interruptores
    // de privacidade acima, porque é uma tela com estado próprio: interruptores, disponibilidade
    // por campo e prévia. Espremê-la nesta seção esconderia a parte que mais importa — a diferença
    // entre "eu permiti" e "o servidor consegue mostrar".
    Secondary(text = "Compartilhar progresso", onClick = onOpenProgressSharing, enabled = enabled)

    // T17.3 — os desafios, ao lado das outras entradas da área Social. Sem item novo de bottom
    // navigation: a barra inferior é do núcleo do produto — treinar, histórico, evolução —, e o
    // social continua sendo uma área dentro do Perfil.
    Secondary(text = "Desafios", onClick = onOpenChallenges, enabled = enabled)

    // T17.4 — Atividade e ranking semanal entre amigos
    Secondary(text = "Atividade e Ranking", onClick = onOpenActivity, enabled = enabled)

    // T17.5 — Notificações sociais via push
    Secondary(text = "Notificações", onClick = onOpenNotificationPreferences, enabled = enabled)

    // T17.6 — Usuários bloqueados
    Secondary(text = "Usuários bloqueados", onClick = onOpenBlockedUsers, enabled = enabled)

    // T17.7 — Treinos compartilhados
    Secondary(text = "Treinos compartilhados", onClick = onOpenSharedWorkouts, enabled = enabled)

    // T17.8 — o Feed de check-ins. Ele fica aqui, na área Social, e **não** vira aba nova da
    // bottom navigation (§83): a barra inferior é do núcleo do produto, e o social continua sendo
    // opcional. Um item permanente apareceria vazio para quem nunca ativou.
    Secondary(text = "Feed", onClick = onOpenSocialFeed, enabled = enabled)
}

@Composable
private fun ActivationDialog(
    value: String,
    isValid: Boolean,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Ativar recursos sociais", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Curto e concreto. Não é tela jurídica: é a lista do que será criado e do que
                // continua privado — que é o que a pessoa precisa para decidir.
                Text(
                    text = "Será criado:\n" +
                        "• um identificador social\n" +
                        "• um código de amigo\n" +
                        "• um nome social",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = "Seu e-mail, treinos e medidas não ficam públicos.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                NameField(value = value, onValueChange = onValueChange)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = isValid) {
                Text("Ativar", color = if (isValid) Lime400 else TextSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        }
    )
}

@Composable
private fun NameDialog(
    value: String,
    isValid: Boolean,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Nome social", color = TextPrimary) },
        text = { NameField(value = value, onValueChange = onValueChange) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = isValid) {
                Text("Salvar", color = if (isValid) Lime400 else TextSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        }
    )
}

@Composable
private fun DisableDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Desativar recursos sociais?", color = TextPrimary) },
        text = {
            Text(
                // O que é verdade hoje. Nada sobre amizades: elas não existem, e prometer que
                // seriam preservadas (ou apagadas) documentaria uma feature inexistente.
                text = "Seu treino, histórico, backup e Conta Spark não serão apagados.\n\n" +
                    "Seu nome social e seu código de amigo continuam guardados e voltam iguais " +
                    "se você reativar.",
                color = TextSecondary,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Desativar", color = Red400) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        }
    )
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text("Nome social") },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = NAME_FIELD_DESCRIPTION }
    )
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextPrimary, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Lime400),
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}

@Composable
private fun Explain(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text = body, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun Warning(title: String, body: String) {
    Surface(color = SurfaceHighlight, shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, color = Red400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(text = body, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Busy(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = Lime400, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Text(text = label, color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun Primary(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Lime400),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Text(text = text, color = SurfaceDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun Secondary(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text = text, color = if (enabled) Lime400 else TextSecondary, fontSize = 14.sp)
    }
}

/** Classe de falha → conselho. Mensagem de servidor não é texto de tela. */
private fun messageFor(reason: SocialError): String = when (reason) {
    SocialError.AUTH_REQUIRED ->
        "Sua sessão precisa ser renovada. Entre na Conta Spark de novo para usar os recursos sociais."
    SocialError.RATE_LIMITED -> "Muitas tentativas seguidas. Espere um pouco e tente de novo."
    SocialError.UNAVAILABLE -> "O servidor do Spark está indisponível agora. Tente de novo mais tarde."
    SocialError.INVALID_DISPLAY_NAME -> "Esse nome não pode ser usado. Escolha outro."
    SocialError.ALREADY_ENABLED, SocialError.ALREADY_DISABLED ->
        "Os recursos sociais já estavam nesse estado em outro aparelho."
    SocialError.NOT_ENABLED -> "Os recursos sociais não estão ativos nesta conta."
    SocialError.NETWORK -> "Sem conexão com o servidor. Nada foi alterado."
    SocialError.NOT_CONFIGURED -> "Os recursos sociais não estão disponíveis nesta versão do app."
    SocialError.RANKING_NOT_ENABLED ->
        "A participação no ranking não está habilitada nesta conta."
    SocialError.INVALID_ACTIVITY_TIMEZONE ->
        "O fuso horário configurado é inválido. Verifique as configurações do aparelho."
    SocialError.ACTIVITY_NOT_AVAILABLE ->
        "A atividade recente não está disponível no momento."
    SocialError.REJECTED ->
        "O servidor recusou a operação. Se continuar acontecendo, relate para o suporte."
}

const val SOCIAL_SECTION_TITLE = "Social"
const val SOCIAL_SECTION_DESCRIPTION = "Recursos sociais da Conta Spark"
const val FRIEND_CODE_DESCRIPTION = "Seu código de amigo"

/** Descrição de acessibilidade do resumo do grafo social (T17.1). */
const val FRIENDS_SUMMARY_DESCRIPTION = "Resumo de amigos e solicitações"
const val NAME_FIELD_DESCRIPTION = "Campo de nome social"
