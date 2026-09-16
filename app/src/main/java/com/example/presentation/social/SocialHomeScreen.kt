package com.example.presentation.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.social.SocialProfileStatus
import com.example.presentation.account.FriendsViewModel
import com.example.presentation.account.SocialSection
import com.example.presentation.account.SocialViewModel
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.TextPrimary

/**
 * SocialHome (T19.1): o hub que reúne as funcionalidades sociais do Spark numa área própria,
 * organizadas por intenção (Feed, Pessoas, Comunidades, Compartilhar, Privacidade).
 *
 * ```text
 * Profile
 * → Social
 * → SocialHome
 * → feature social existente
 * ```
 *
 * Esta tela **não** é uma nova fonte de estado social: todo o conteúdo continua vindo de
 * [SocialViewModel] e [FriendsViewModel], os mesmos que já serviam essas telas dentro do Perfil
 * (T17.0/T17.1). Abrir este hub pede exatamente o que ele mostra, e nada além: o perfil social
 * (`open()`, idempotente — quem veio do Perfil já o tem lido) e, com o perfil ativo, o resumo do
 * grafo para os contadores de Pessoas. As duas são leituras; nenhuma cria, aceita ou envia nada.
 * O hub não depende de o Perfil ter sido aberto antes — um atalho futuro direto para aqui chega
 * com o mesmo conteúdo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialHomeScreen(
    socialViewModel: SocialViewModel,
    friendsViewModel: FriendsViewModel?,
    onNavigateBack: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenFriendRequests: () -> Unit,
    onOpenProgressSharing: () -> Unit,
    onOpenChallenges: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenNotificationPreferences: () -> Unit,
    onOpenBlockedUsers: () -> Unit,
    onOpenSharedWorkouts: () -> Unit,
    onOpenSocialFeed: () -> Unit,
    onOpenSquads: () -> Unit
) {
    val socialState by socialViewModel.uiState.collectAsStateWithLifecycle()
    val friendsState = friendsViewModel?.uiState?.collectAsStateWithLifecycle()?.value

    LaunchedEffect(socialViewModel) { socialViewModel.open() }
    val isSocialActive = socialState.profile?.status == SocialProfileStatus.ACTIVE
    LaunchedEffect(isSocialActive) {
        if (isSocialActive) friendsViewModel?.open()
    }
    // "Meu código" é uma folha sobre este hub, e não uma tela: o código já está carregado, e
    // navegar para mostrar um dado que está na mão seria uma navegação sem conteúdo próprio.
    var isFriendCodeVisible by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Social",
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SocialSection(
                uiState = socialState,
                friendsState = friendsState,
                onOpenFriends = onOpenFriends,
                onOpenRequests = onOpenFriendRequests,
                onOpenProgressSharing = onOpenProgressSharing,
                onOpenChallenges = onOpenChallenges,
                onOpenActivity = onOpenActivity,
                onOpenNotificationPreferences = onOpenNotificationPreferences,
                onOpenBlockedUsers = onOpenBlockedUsers,
                onOpenSharedWorkouts = onOpenSharedWorkouts,
                onOpenSocialFeed = onOpenSocialFeed,
                onOpenSquads = onOpenSquads,
                onShowFriendCode = { isFriendCodeVisible = true },
                onActivate = socialViewModel::startActivation,
                onDisplayNameChange = socialViewModel::onDisplayNameChanged,
                onConfirmActivation = socialViewModel::confirmActivation,
                onCancelActivation = socialViewModel::cancelActivation,
                onEditName = socialViewModel::startEditingName,
                onConfirmName = socialViewModel::confirmDisplayName,
                onCancelEditName = socialViewModel::cancelEditingName,
                onFriendRequestsChange = socialViewModel::setFriendRequestsEnabled,
                onActivitySharingChange = socialViewModel::setActivitySharingEnabled,
                onFriendRankingParticipationChange =
                    socialViewModel::setFriendRankingParticipationEnabled,
                onDisable = socialViewModel::startDisable,
                onConfirmDisable = socialViewModel::confirmDisable,
                onCancelDisable = socialViewModel::cancelDisable,
                onEnable = socialViewModel::enable,
                onRetry = socialViewModel::refresh
            )
        }
    }

    if (isFriendCodeVisible) {
        socialState.profile?.let { profile ->
            com.example.presentation.friends.MyFriendCodeDialog(
                friendCode = profile.friendCode,
                onDismiss = { isFriendCodeVisible = false }
            )
        }
    }
}
