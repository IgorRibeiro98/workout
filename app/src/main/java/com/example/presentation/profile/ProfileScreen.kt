package com.example.presentation.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.ai.model.AiCapability
import com.example.domain.evolution.model.achievement.Achievement
import com.example.feature.evolution.achievements.components.getTierColor
import com.example.feature.evolution.achievements.components.getTierName
import com.example.presentation.account.SocialPhase
import com.example.presentation.coach.isKnownDenied
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.components.HubEntryCard
import coil.compose.AsyncImage
import com.example.ui.theme.*
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Bolt
import com.example.ui.components.semanticIcon
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector

/** O botão de salvar da Meta Semanal — o gancho do teste que prova que ele está alcançável. */
internal const val WEEKLY_GOAL_SAVE_TAG = "weekly_goal_save"

private val PtBr = Locale("pt", "BR")

private fun formatInt(value: Int): String = String.format(PtBr, "%,d", value)

/**
 * Perfil do Atleta: a identidade de progressão do usuário dentro do Spark.
 *
 * A tela apenas renderiza [ProfileUiState] e emite intenções. Nível, XP, sequência, conquistas,
 * treinos e recordes são calculados pelas autoridades do domínio e chegam aqui prontos — nenhuma
 * regra de gamificação vive nesta Composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToBodyEvolution: () -> Unit,
    onNavigateToAchievements: () -> Unit,
    onNavigateToMissions: () -> Unit,
    /** T19.1 — abre o AiHome, que organiza as capacidades do Coach por capacidade. */
    onNavigateToAiHome: () -> Unit,
    /** Conta Spark (T16.1). `null` quando a identidade online não existe neste build. */
    accountViewModel: com.example.presentation.account.AccountViewModel? = null,
    /** Backup na nuvem (T16.4). `null` quando não há Spark Backend configurado neste build. */
    backupViewModel: com.example.presentation.account.BackupViewModel? = null,
    /** Restore de backup (T16.5). `null` quando não há Spark Backend configurado neste build. */
    restoreViewModel: com.example.presentation.account.RestoreViewModel? = null,
    /** Sync multi-device (T16.6). `null` quando não há Spark Backend configurado neste build. */
    syncViewModel: com.example.presentation.account.SyncViewModel? = null,
    /** Recursos sociais (T17.0). `null` quando não há Spark Backend configurado neste build. */
    socialViewModel: com.example.presentation.account.SocialViewModel? = null,
    /** O grafo social (T17.1). `null` mantém o Perfil exatamente como a T17.0 o entregou. */
    friendsViewModel: com.example.presentation.account.FriendsViewModel? = null,
    /** T19.1 — abre o SocialHome, que organiza Feed/Pessoas/Comunidades/Compartilhar/Privacidade. */
    onNavigateToSocialHome: () -> Unit = {},
    /**
     * Capabilities de IA (T19.0), só **lidas**: "Entender minha evolução" é `AI_EXPLAIN`, e se o
     * servidor já disse que esta conta não a tem, a explicação sai local, sem chamada. Abrir o
     * Perfil não dispara a consulta de capabilities — ela custa uma requisição, e quem nunca usa o
     * Coach não deve pagá-la aqui; sem o estado carregado, o backend decide na chamada de sempre.
     */
    aiCapabilitiesViewModel: com.example.presentation.coach.AiCapabilitiesViewModel? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val aiCapabilitiesState = aiCapabilitiesViewModel?.state?.collectAsStateWithLifecycle()?.value
    val explanationState by viewModel.explanationState.collectAsStateWithLifecycle()
    val weeklyGoalSave by viewModel.weeklyGoalSave.collectAsStateWithLifecycle()
    val accountState = accountViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    // Observar o estado é leitura: ele diz o que mostrar, e nenhum backup começa por isso.
    val backupState = backupViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    // Idem para o restore: observar o estado lista nada, baixa nada e restaura nada.
    val restoreState = restoreViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    // E para o sync: observar o estado não dispara ciclo nenhum.
    val syncState = syncViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    // E para o social: observar o estado é leitura. Ele não ativa recursos sociais, não cria
    // identidade e não envia nada — a ativação exige dois toques explícitos.
    val socialState = socialViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    // E para o grafo: observar o estado não carrega nada.
    val friendsState = friendsViewModel?.uiState?.collectAsStateWithLifecycle()?.value

    // T19.1 — o `socialViewModel` é compartilhado com o SocialHome (mesma instância, criada em
    // `MainScreen`), e criá-lo não lê nada: a leitura do perfil social sai daqui, ao abrir o Perfil
    // — `open()` é idempotente, então quem já leu não lê de novo, e o SocialHome reaproveita o
    // resultado. É uma leitura: `GET /v1/social/me` não cria perfil nem ativa nada.
    androidx.compose.runtime.LaunchedEffect(socialViewModel) {
        socialViewModel?.open()
    }

    // A leitura do grafo acontece quando o Perfil abre **com o perfil social ativo** — é o que
    // permite mostrar "3 amigos · 1 solicitação pendente" no cartão do SocialHome, sem entrar na
    // lista. Ela é uma leitura: não cria relação, não aceita nada e não envia nada. Sem perfil
    // social ativo não há o que pedir, e nenhuma requisição sai.
    val isSocialActive = socialState?.profile?.status == com.example.domain.social.SocialProfileStatus.ACTIVE
    androidx.compose.runtime.LaunchedEffect(isSocialActive) {
        if (isSocialActive) friendsViewModel?.open()
    }

    // O ViewModel de sync precisa saber qual conta está conectada para distinguir "entre na
    // conta" de "estes dados são de outra conta". A tela já observa isso para a seção de conta;
    // repassar é leitura, e não dispara sincronização.
    androidx.compose.runtime.LaunchedEffect(accountState?.account?.uid, syncViewModel) {
        syncViewModel?.onAccountChanged(accountState?.account?.uid)
    }

    ProfileScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToBodyEvolution = onNavigateToBodyEvolution,
        onNavigateToAchievements = onNavigateToAchievements,
        onNavigateToMissions = onNavigateToMissions,
        onNavigateToAiHome = onNavigateToAiHome,
        onWeeklyGoalChange = viewModel::setWeeklyGoal,
        weeklyGoalSave = weeklyGoalSave,
        onWeeklyGoalSaveHandled = viewModel::acknowledgeWeeklyGoalSave,
        canExplainProgress = viewModel.canExplainProgress,
        onExplainProgress = {
            viewModel.explainProgress(
                modelAllowed = aiCapabilitiesState?.isKnownDenied(AiCapability.AI_EXPLAIN) != true
            )
        },
        accountState = accountState,
        // A autenticação só começa aqui, no toque. Abrir o Perfil não abre seletor de contas.
        onAccountSignIn = { host -> accountViewModel?.signIn(host) },
        onAccountSignOut = { accountViewModel?.signOut() },
        canVerifyWithBackend = accountViewModel?.canVerifyWithBackend == true,
        onVerifyWithBackend = { accountViewModel?.verifyWithBackend() },
        backupState = backupState,
        onActivateBackup = { backupViewModel?.startAdoption() },
        onConfirmBackupAdoption = { backupViewModel?.confirmAdoption() },
        onCancelBackupAdoption = { backupViewModel?.cancelAdoption() },
        onBackupNow = { backupViewModel?.backupNow() },
        restoreState = restoreState,
        onLoadBackups = { restoreViewModel?.loadBackups() },
        onSelectBackup = { backupId -> restoreViewModel?.selectBackup(backupId) },
        onRequestRestore = { restoreViewModel?.requestRestore() },
        onConfirmReplacement = { restoreViewModel?.confirmReplacement() },
        onCancelReplacement = { restoreViewModel?.cancelReplacement() },
        onCancelRestore = { restoreViewModel?.cancel() },
        syncState = syncState,
        onSyncNow = { syncViewModel?.syncNow() },
        onResolveConflict = { id, choice -> syncViewModel?.resolveConflict(id, choice) },
        // T19.1 — o Perfil só lê o estado social para decidir se mostra o cartão "Social" e qual
        // resumo exibir nele. Ativar, desativar, editar nome e as próprias entradas do grafo social
        // moraram para o SocialHome: nenhuma dessas ações é mais alcançável a partir daqui.
        socialState = socialState,
        friendsState = friendsState,
        onNavigateToSocialHome = onNavigateToSocialHome,
        onDeleteAccount = { accountViewModel?.deleteAccount() }
    )

    com.example.presentation.coach.CoachExplanationSheet(
        state = explanationState,
        onDismiss = viewModel::dismissExplanation
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreenContent(
    uiState: ProfileUiState,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToBodyEvolution: () -> Unit,
    onNavigateToAchievements: () -> Unit,
    onNavigateToMissions: () -> Unit,
    onNavigateToAiHome: () -> Unit,
    onWeeklyGoalChange: (Int) -> Unit,
    weeklyGoalSave: WeeklyGoalSave,
    onWeeklyGoalSaveHandled: () -> Unit,
    /** `false` esconde a entrada contextual quando o Coach não está disponível neste build. */
    canExplainProgress: Boolean = false,
    onExplainProgress: () -> Unit = {},
    /** `null` esconde a área de Conta Spark inteira. */
    accountState: com.example.presentation.account.AccountUiState? = null,
    onAccountSignIn: (android.content.Context) -> Unit = {},
    onAccountSignOut: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    canVerifyWithBackend: Boolean = false,
    onVerifyWithBackend: () -> Unit = {},
    /** Backup na nuvem (T16.4). `null` quando não há Spark Backend configurado neste build. */
    backupState: com.example.presentation.account.BackupUiState? = null,
    onActivateBackup: () -> Unit = {},
    onConfirmBackupAdoption: () -> Unit = {},
    onCancelBackupAdoption: () -> Unit = {},
    onBackupNow: () -> Unit = {},
    /** Restore de backup (T16.5). `null` quando não há Spark Backend configurado neste build. */
    restoreState: com.example.presentation.account.RestoreUiState? = null,
    onLoadBackups: () -> Unit = {},
    onSelectBackup: (String) -> Unit = {},
    onRequestRestore: () -> Unit = {},
    onConfirmReplacement: () -> Unit = {},
    onCancelReplacement: () -> Unit = {},
    onCancelRestore: () -> Unit = {},
    /** Sync multi-device (T16.6). `null` quando não há Spark Backend configurado neste build. */
    syncState: com.example.presentation.account.SyncUiState? = null,
    onSyncNow: () -> Unit = {},
    onResolveConflict: (
        com.example.data.sync.SyncConflictId,
        com.example.data.sync.SyncConflictChoice
    ) -> Unit = { _, _ -> },
    /**
     * Recursos sociais (T17.0). `null` esconde o cartão "Social" inteiro.
     *
     * T19.1 — o Perfil só lê este estado para decidir se mostra o cartão e o que resumir nele
     * ("3 amigos", "Conecte-se com amigos"...). Toda ação social de verdade (ativar, entrar no
     * grafo, editar privacidade) mora no SocialHome, alcançado por [onNavigateToSocialHome].
     */
    socialState: com.example.presentation.account.SocialUiState? = null,
    /** O grafo social (T17.1), só para o resumo do cartão — "3 amigos · 1 solicitação". */
    friendsState: com.example.presentation.account.FriendsUiState? = null,
    /** T19.1 — abre o SocialHome. */
    onNavigateToSocialHome: () -> Unit = {}
) {
    var showGoalBottomSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Perfil do Atleta",
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
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configurações",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Lime400, strokeWidth = 3.dp)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AthleteHeaderCard(
                uiState = uiState,
                identity = athleteIdentityOf(accountState?.account)
            )

            StatsGrid(uiState = uiState)

            MissionsSection(onClick = onNavigateToMissions)

            AiCoachSection(
                onClick = onNavigateToAiHome,
                canExplainProgress = canExplainProgress,
                onExplainProgress = onExplainProgress
            )

            AchievementsPreviewSection(
                achievements = uiState.recentAchievements,
                onSeeAll = onNavigateToAchievements
            )

            WeeklyGoalSection(
                uiState = uiState,
                onEdit = { showGoalBottomSheet = true }
            )

            BodyEvolutionSection(
                latestWeightKg = uiState.latestWeightKg,
                onClick = onNavigateToBodyEvolution
            )

            // A conta vive dentro do Perfil, não numa aba nova: ela é identidade, não uma área
            // de produto. Fica depois do que o Spark já entrega hoje, porque é opcional.
            if (accountState != null) {
                com.example.presentation.account.AccountSection(
                    uiState = accountState,
                    onSignIn = onAccountSignIn,
                    onSignOut = onAccountSignOut,
                    canVerifyWithBackend = canVerifyWithBackend,
                    onVerifyWithBackend = onVerifyWithBackend,
                    onDeleteAccount = onDeleteAccount
                )
            }

            // O backup mora logo abaixo da conta, porque depende dela — e porque ativar backup é
            // uma decisão sobre os dados, não sobre a identidade.
            if (backupState != null) {
                com.example.presentation.account.BackupSection(
                    uiState = backupState,
                    onActivate = onActivateBackup,
                    onConfirmAdoption = onConfirmBackupAdoption,
                    onCancelAdoption = onCancelBackupAdoption,
                    onBackupNow = onBackupNow
                )
            }

            // O restore vem depois do backup, na ordem em que as decisões acontecem: primeiro
            // proteger os dados, depois trazer uma cópia de volta.
            if (restoreState != null) {
                com.example.presentation.account.RestoreSection(
                    uiState = restoreState,
                    onLoadBackups = onLoadBackups,
                    onSelectBackup = onSelectBackup,
                    onRequestRestore = onRequestRestore,
                    onConfirmReplacement = onConfirmReplacement,
                    onCancelReplacement = onCancelReplacement,
                    onCancel = onCancelRestore
                )
            }

            // A sincronização vem depois do restore, fechando a ordem das decisões sobre dados:
            // proteger (backup), trazer de volta (restore) e manter os aparelhos em dia (sync).
            if (syncState != null) {
                com.example.presentation.account.SyncSection(
                    uiState = syncState,
                    onSyncNow = onSyncNow,
                    onResolveConflict = onResolveConflict
                )
            }

            // T19.1 — o social fecha o bloco da Conta Spark, na mesma posição de sempre: ele é a
            // única capacidade aqui que não fala sobre os dados de treino, e sim sobre identidade
            // pública. Mas agora é uma única entrada para o SocialHome, e não mais uma seção
            // inteira: ativar, editar nome, privacidade e cada tela do grafo social moraram para lá.
            //
            // `NotConfigured` continua escondendo o cartão — mesma regra que a T17.0 sempre teve:
            // sem Spark Backend configurado neste build, não existe recurso social para navegar.
            if (socialState != null && socialState.phase !is SocialPhase.NotConfigured) {
                SocialEntrySection(
                    socialState = socialState,
                    friendsState = friendsState,
                    onClick = onNavigateToSocialHome
                )
            }

            SettingsSection(onClick = onNavigateToSettings)
        }
    }

    if (showGoalBottomSheet) {
        // A folha fecha quando a gravação foi **aceita**, e não no toque: uma falha silenciosa
        // deixaria a tela afirmando ter salvado uma meta que continuou a antiga (H2.4).
        LaunchedEffect(weeklyGoalSave) {
            if (weeklyGoalSave is WeeklyGoalSave.Saved) {
                showGoalBottomSheet = false
                onWeeklyGoalSaveHandled()
            }
        }
        WeeklyGoalBottomSheet(
            currentWeeklyGoal = uiState.weeklyGoal,
            nextWeeklyGoal = uiState.nextWeekGoal,
            saveState = weeklyGoalSave,
            onDismiss = {
                showGoalBottomSheet = false
                onWeeklyGoalSaveHandled()
            },
            onConfirm = onWeeklyGoalChange
        )
    }
}

/**
 * Quem o cabeçalho do Perfil mostra (H2.2).
 *
 * É uma **projeção** da conta autenticada, montada a cada composição a partir de
 * [com.example.domain.auth.AuthState] — nunca uma cópia guardada. É o que garante o requisito de
 * troca de conta: sair já não tem conta, e entrar com outra já tem a outra. Não existe lugar onde
 * nome ou foto da conta anterior possam sobreviver, porque não existe lugar.
 *
 * `photoUrl` é apresentação da conta: não é persistida no Room, não entra no backup, não vai para
 * o Social e não é baixada como mídia do Spark.
 */
internal data class AthleteIdentity(
    val name: String,
    val photoUrl: String?
)

/** O nome usado quando não há conta — ou quando a conta não informa nome nem e-mail. */
internal const val DEFAULT_ATHLETE_NAME = "Atleta"

/**
 * A política de nome do cabeçalho: `displayName` → e-mail → "Atleta".
 *
 * Nenhum dos três é identidade técnica: quem identifica a conta é o `uid`, e ele não aparece aqui.
 * O e-mail entra antes de "Atleta" porque é um dado real da conta; inventar um nome a partir dele
 * (cortar antes do `@`, por exemplo) seria criar identidade que ninguém escolheu.
 */
internal fun athleteIdentityOf(account: com.example.domain.auth.SparkAccount?): AthleteIdentity =
    AthleteIdentity(
        name = account?.displayName?.takeIf { it.isNotBlank() }
            ?: account?.email?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ATHLETE_NAME,
        photoUrl = account?.photoUrl?.takeIf { it.isNotBlank() }
    )

/**
 * Cabeçalho: quem sou eu no Spark hoje.
 *
 * Com conta conectada, o nome e a foto são os dela ([athleteIdentityOf]). Sem conta — ou sem os
 * campos, que o Google não garante —, o cabeçalho volta ao avatar genérico e a "Atleta".
 */
@Composable
internal fun AthleteHeaderCard(
    uiState: ProfileUiState,
    identity: AthleteIdentity = AthleteIdentity(DEFAULT_ATHLETE_NAME, null)
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Lime400.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    // O ícone fica **atrás** da foto: sem `photoUrl`, ou com o download falhando,
                    // ele continua visível e o cabeçalho não quebra (mesma regra do AccountSection).
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Avatar",
                        tint = Lime400,
                        modifier = Modifier.size(36.dp)
                    )
                    identity.photoUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Foto da conta",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                        )
                    }
                }

                // `weight(1f)`: um nome real ("Maria Fernanda de Albuquerque") empurrava o bloco
                // inteiro para fora do cartão sem ele.
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = identity.name,
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Nível ${uiState.level}",
                        color = Lime400,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${formatInt(uiState.totalXp)} XP total",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            LinearProgressIndicator(
                progress = { uiState.levelProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = Lime400,
                trackColor = SurfaceHighlight
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatInt(uiState.currentLevelXp)} / ${formatInt(uiState.xpForNextLevel)} XP",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "${formatInt(uiState.xpToNextLevel)} XP para o nível ${uiState.level + 1}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun StatsGrid(uiState: ProfileUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                icon = Icons.Filled.LocalFireDepartment,
                value = "${uiState.streakWeeks}",
                unit = if (uiState.streakWeeks == 1) "semana" else "semanas",
                label = "Sequência",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Filled.FitnessCenter,
                value = formatInt(uiState.completedWorkouts),
                unit = null,
                label = "Treinos",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                icon = Icons.Filled.EmojiEvents,
                value = "${uiState.unlockedAchievements} / ${uiState.totalAchievements}",
                unit = null,
                label = "Conquistas",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Filled.Bolt,
                value = formatInt(uiState.personalRecordsCount),
                unit = null,
                label = "Recordes",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    value: String,
    unit: String?,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier.fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Lime400,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = value,
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Prévia das conquistas. A coleção completa continua sendo responsabilidade da área de Evolução.
 */
@Composable
private fun AchievementsPreviewSection(
    achievements: List<Achievement>,
    onSeeAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Conquistas",
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
                .clip(RoundedCornerShape(16.dp))
                .clickable { onSeeAll() }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (achievements.isEmpty()) {
                    Text(
                        text = "Sua primeira conquista está a caminho.",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Continue treinando para desbloqueá-la.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                } else {
                    achievements.forEach { achievement ->
                        AchievementPreviewRow(achievement = achievement)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ver todas",
                        color = Lime400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Lime400,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AchievementPreviewRow(achievement: Achievement) {
    val tierColor = getTierColor(achievement.tier)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tierColor.copy(alpha = 0.15f))
                .border(1.dp, tierColor.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = semanticIcon(achievement.icon),
                contentDescription = null,
                tint = tierColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = achievement.title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = getTierName(achievement.tier),
                color = tierColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun WeeklyGoalSection(
    uiState: ProfileUiState,
    onEdit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Meta Semanal",
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
                .clip(RoundedCornerShape(16.dp))
                .clickable { onEdit() }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LimeTransparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarToday,
                                contentDescription = null,
                                tint = Lime400,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "${uiState.weeklyCompleted} / ${uiState.weeklyGoal} treinos",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            if (uiState.hasPendingGoalChange) {
                                Text(
                                    text = "Próxima semana: ${uiState.nextWeekGoal} treinos",
                                    color = Lime400,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Editar",
                            color = Lime400,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val goalProgress = if (uiState.weeklyGoal > 0) {
                    (uiState.weeklyCompleted.toFloat() / uiState.weeklyGoal).coerceIn(0f, 1f)
                } else {
                    0f
                }

                LinearProgressIndicator(
                    progress = { goalProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Lime400,
                    trackColor = SurfaceHighlight
                )
            }
        }
    }
}

/**
 * Porta de entrada para as missões. O Perfil continua sendo o hub de progressão: as missões vivem
 * na própria área, com sua autoridade de avaliação.
 */
@Composable
private fun MissionsSection(onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Missões",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        HubEntryCard(
            icon = Icons.Default.Flag,
            title = "Missões e Desafios",
            subtitle = "Objetivos da semana e marcos do seu treino",
            onClick = onClick
        )
    }
}

/**
 * Porta de entrada do Coach IA — o AiHome (T19.1), que organiza Analisar/Gerar/Adaptar por
 * capacidade.
 *
 * Nenhuma ação de IA acontece por navegar até aqui: cada capacidade só fala com o provider depois
 * de um toque explícito, dentro da tela real de destino.
 */
@Composable
private fun AiCoachSection(
    onClick: () -> Unit,
    canExplainProgress: Boolean,
    onExplainProgress: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Coach IA",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        HubEntryCard(
            icon = Icons.Default.AutoAwesome,
            title = "Coach IA",
            subtitle = "Analisar, gerar e adaptar treinos com IA",
            onClick = onClick
        )

        // Entrada contextual sobre os números que esta tela já mostra. Ela continua aqui, e não no
        // AiHome (T19.1): o que ela explica são os números de progresso do próprio Perfil, e não
        // uma capacidade que existe fora dele. Os valores continuam vindo das autoridades: o Coach
        // lê e explica, nunca recalcula.
        if (canExplainProgress) {
            com.example.presentation.coach.CoachExplanationTrigger(
                text = "Entender minha evolução",
                onClick = onExplainProgress
            )
        }
    }
}

/**
 * Porta de entrada do Social — o SocialHome (T19.1), que organiza Feed/Pessoas/Comunidades/
 * Compartilhar/Privacidade por intenção.
 *
 * O resumo do cartão é só leitura do que os ViewModels compartilhados já observam: nenhuma
 * requisição nasce de mostrar "3 amigos" aqui.
 */
@Composable
private fun SocialEntrySection(
    socialState: com.example.presentation.account.SocialUiState,
    friendsState: com.example.presentation.account.FriendsUiState?,
    onClick: () -> Unit
) {
    val subtitle = when {
        socialState.phase is SocialPhase.Active || socialState.phase is SocialPhase.Saving -> {
            val count = friendsState?.friendCount ?: 0
            val pending = friendsState?.incomingCount ?: 0
            buildString {
                append(if (count == 1) "1 amigo" else "$count amigos")
                if (pending > 0) {
                    append(if (pending == 1) " · 1 solicitação pendente" else " · $pending solicitações pendentes")
                }
            }
        }
        else -> "Amigos, feed, squads e desafios"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Social",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        HubEntryCard(
            icon = Icons.Default.Group,
            title = "Social",
            subtitle = subtitle,
            onClick = onClick
        )
    }
}

@Composable
private fun BodyEvolutionSection(
    latestWeightKg: Float?,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Evolução Corporal",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        HubEntryCard(
            icon = Icons.Default.Straighten,
            title = "Evolução e Medidas Corporais",
            subtitle = if (latestWeightKg != null) {
                "Último peso: ${String.format(PtBr, "%.1f kg", latestWeightKg)}"
            } else {
                "Nenhuma medição registrada"
            },
            onClick = onClick
        )
    }
}

@Composable
private fun SettingsSection(onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Configurações",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        HubEntryCard(
            icon = Icons.Default.Settings,
            title = "Configurações do Aplicativo",
            subtitle = "Preferências do aplicativo",
            onClick = onClick
        )
    }
}

/**
 * Edição da meta semanal.
 *
 * A vigência continua sendo decidida pelo repositório de consistência: a semana atual mantém a meta
 * antiga e o novo valor passa a valer na próxima. Aqui só explicamos isso ao usuário.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeeklyGoalBottomSheet(
    currentWeeklyGoal: Int,
    nextWeeklyGoal: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    saveState: WeeklyGoalSave = WeeklyGoalSave.Idle
) {
    var selectedValue by rememberSaveable { mutableIntStateOf(nextWeeklyGoal) }
    val isSaving = saveState is WeeklyGoalSave.Saving

    AppModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = "Meta Semanal de Treinos",
        subtitle = "Quantos dias por semana você pretende treinar?",
        // As sete opções, o aviso de vigência e o botão passavam de 800dp: numa tela de 640dp o
        // botão de salvar ficava fora do alcance, sem rolagem e sem aviso (H2.4). Agora o miolo
        // rola e o botão é rodapé fixo — ele existe em qualquer altura de tela.
        scrollableContent = true,
        footer = {
            Spacer(modifier = Modifier.height(12.dp))
            if (saveState is WeeklyGoalSave.Failed) {
                Text(
                    text = "Não foi possível salvar a meta agora. Nada foi alterado — tente de novo.",
                    color = Red400,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Button(
                onClick = { onConfirm(selectedValue) },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime400,
                    contentColor = BackgroundDark,
                    disabledContainerColor = SurfaceHighlight,
                    disabledContentColor = TextSecondary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(WEEKLY_GOAL_SAVE_TAG)
            ) {
                Text(
                    text = when {
                        isSaving -> "Salvando..."
                        selectedValue != nextWeeklyGoal -> "SALVAR NOVA META"
                        else -> "Salvar Meta"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            (1..7).forEach { days ->
                val isSelected = days == selectedValue
                Surface(
                    color = if (isSelected) Lime400.copy(alpha = 0.15f) else SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) Lime400 else BorderLight
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selectedValue = days }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$days ${if (days == 1) "treino" else "treinos"} por semana",
                                color = if (isSelected) Lime400 else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            val freqDesc = when (days) {
                                1 -> "Manutenção básica"
                                2 -> "Ritmo leve"
                                3 -> "Frequência recomendada para iniciantes"
                                4 -> "Equilíbrio ideal para hipertrofia"
                                5 -> "Frequência avançada"
                                6 -> "Rotina intensa"
                                else -> "Atividade diária"
                            }
                            Text(
                                text = freqDesc,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selecionado",
                                tint = Lime400,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            if (selectedValue != currentWeeklyGoal) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = SurfaceHighlight,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Sua nova meta começará na próxima semana.",
                            color = Lime400,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Esta semana continua com meta de $currentWeeklyGoal ${if (currentWeeklyGoal == 1) "treino" else "treinos"}.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

        }
    }
}
