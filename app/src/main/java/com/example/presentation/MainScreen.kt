package com.example.presentation
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.MainApplication
import com.example.presentation.MainViewModelFactory
import com.example.presentation.body.AddBodyMeasurementScreen
import com.example.presentation.body.BodyEvolutionScreen
import com.example.presentation.body.BodyEvolutionViewModel
import com.example.presentation.execution.ExecutionScreen
import com.example.presentation.execution.ExecutionViewModel
import com.example.presentation.exercises.ExercisesScreen
import com.example.presentation.history.HistoryScreen
import com.example.presentation.navigation.Screen
import com.example.presentation.profile.ProfileScreen
import com.example.presentation.settings.SettingsScreen
import com.example.presentation.today.TodayScreen
import com.example.presentation.workouts.WorkoutsScreen
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.TextSecondary
import androidx.compose.animation.AnimatedVisibility

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val factory = MainViewModelFactory(
        database = app.database,
        repository = app.repository,
        settingsManager = app.settingsManager,
        workoutEngine = app.workoutEngine,
        notificationManager = app.notificationManager,
        bodyMeasurementRepository = app.bodyMeasurementRepository,
        getEvolutionSummaryUseCase = app.getEvolutionSummaryUseCase,
        evolutionRepository = app.evolutionRepository,
        performanceRepository = app.performanceRepository,
        consistencyRepository = app.consistencyRepository,
        xpTransactionRepository = app.xpTransactionRepository,
        achievementRepository = app.achievementRepository,
        missionRepository = app.missionRepository,
        analyzeWorkoutUseCase = app.analyzeWorkoutUseCase,
        exerciseNameResolver = { exerciseId -> app.resolveExerciseDisplayName(exerciseId) },
        generateWorkoutUseCase = app.generateWorkoutUseCase,
        saveGeneratedWorkoutUseCase = app.saveGeneratedWorkoutUseCase,
        workoutCandidateProvider = { preferences ->
            app.workoutGenerationContextBuilder.candidates(preferences)
        },
        adaptWorkoutUseCase = app.adaptWorkoutUseCase,
        applyWorkoutAdaptationUseCase = app.applyWorkoutAdaptationUseCase,
        explainCoachDecisionUseCase = app.explainCoachDecisionUseCase,
        authGateway = app.authGateway,
        sparkBackendClient = app.sparkBackendClient,
        backupRepository = app.backupRepository,
        restoreRepository = app.restoreRepository,
        syncRepository = app.syncRepository,
        syncCoordinator = app.syncCoordinator,
        socialGateway = app.socialGateway,
        friendGateway = app.friendGateway,
        socialProfileGateway = app.socialProfileGateway,
        challengeGateway = app.challengeGateway,
        socialActivityGateway = app.socialActivityGateway,
        socialNotificationGateway = app.socialNotificationGateway,
        pushRegistrationCoordinator = app.pushRegistrationCoordinator,
        pushAccountScope = app.pushAccountScope,
        blockGateway = app.blockGateway,
        reportGateway = app.reportGateway,
        accountDeletionGateway = app.accountDeletionGateway,
        workoutShareGateway = app.workoutShareGateway,
        workoutShareImporter = app.workoutShareImporter,
        workoutCheckInGateway = app.workoutCheckInGateway,
        workoutCheckInPublisher = app.workoutCheckInPublisher,
        socialMediaCache = app.socialMediaCache,
        checkInPhotoSource = app.checkInPhotoSource,
        socialGroupGateway = app.socialGroupGateway
    )

    // Um `FriendsViewModel` para as três telas do grafo (Perfil, Amigos, Solicitações). Criar um
    // por rota faria a lista ser lida três vezes e o contador do Perfil ficar velho logo depois de
    // aceitar um pedido na tela de Solicitações.
    //
    // Criá-lo aqui **não** faz requisição nenhuma: o `init` só observa a sessão para invalidar o
    // estado na troca de conta. A primeira leitura sai de `open()`, que uma tela do grafo chama
    // quando o usuário chega nela.
    val friendsViewModel: com.example.presentation.account.FriendsViewModel = viewModel(factory = factory)

    // Um `SocialProfileViewModel` para as três telas do perfil enriquecido (perfil de amigo,
    // Compartilhar progresso e a prévia). Compartilhado pelo mesmo motivo do `FriendsViewModel`:
    // alterar uma configuração precisa refletir na prévia sem uma segunda leitura, e a troca de
    // conta precisa invalidar as três de uma vez.
    //
    // Criá-lo aqui **não** faz requisição nenhuma: o `init` só observa a sessão para invalidar.
    // Um `ChallengeViewModel` para as três telas de desafio (lista, criação, detalhe). Compartilhado
    // pelo mesmo motivo dos anteriores: a lista, os convites e o detalhe são o mesmo estado de
    // conta, e um por rota faria a lista ser lida de novo a cada navegação — e o contador de
    // convites do Perfil ficar velho logo depois de responder a um.
    val challengeViewModel: com.example.presentation.account.ChallengeViewModel =
        viewModel(factory = factory)

    val socialProfileViewModel: com.example.presentation.account.SocialProfileViewModel =
        viewModel(factory = factory)
    val socialActivityViewModel: com.example.presentation.friends.SocialActivityViewModel =
        viewModel(factory = factory)
    val exercisesViewModel: com.example.presentation.exercises.ExercisesViewModel = viewModel(factory = factory)
    val workoutsViewModel: com.example.presentation.workouts.WorkoutsViewModel = viewModel(factory = factory)
    val todayViewModel: com.example.presentation.today.TodayViewModel = viewModel(factory = factory)
    val executionViewModel: ExecutionViewModel = viewModel(factory = factory)
    val summaryViewModel: com.example.presentation.execution.SummaryViewModel = viewModel(factory = factory)
    val exerciseDetailsViewModel: com.example.presentation.exercises.ExerciseDetailsViewModel = viewModel(factory = factory)
    val historyViewModel: com.example.presentation.history.HistoryViewModel = viewModel(factory = factory)
    val bodyEvolutionViewModel: BodyEvolutionViewModel = viewModel(factory = factory)
    val featureBodyEvolutionViewModel: com.example.feature.evolution.body.BodyEvolutionViewModel = viewModel(factory = factory)
    val performanceViewModel: com.example.feature.evolution.performance.PerformanceViewModel = viewModel(factory = factory)
    val consistencyViewModel: com.example.feature.evolution.consistency.ConsistencyViewModel = viewModel(factory = factory)
    val achievementsViewModel: com.example.feature.evolution.achievements.AchievementsViewModel = viewModel(factory = factory)
    val timelineViewModel: com.example.feature.evolution.timeline.TimelineViewModel = viewModel(factory = factory)
    val evolutionViewModel: com.example.feature.evolution.EvolutionViewModel = viewModel(factory = factory)

    val navController = rememberNavController()
    val items = listOf(
        Screen.Today,
        Screen.Workouts,
        Screen.Exercises,
        Screen.History,
        Screen.MyEvolution
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute != Screen.Execution.route && currentRoute != Screen.Summary.route

    val topLevelDestinationMap = mapOf(
        Screen.Today.route to Screen.Today.route,
        Screen.Summary.route to Screen.Today.route,
        Screen.Profile.route to Screen.Today.route,
        Screen.Activity.route to Screen.Today.route,
        Screen.NotificationPreferences.route to Screen.Today.route,
        Screen.BlockedUsers.route to Screen.Today.route,
        Screen.SharedWorkouts.route to Screen.Today.route,
        Screen.SocialFeed.route to Screen.Today.route,
        Screen.CheckInDetail.route to Screen.Today.route,
        Screen.Squads.route to Screen.Today.route,
        Screen.SquadDetail.route to Screen.Today.route,
        Screen.Missions.route to Screen.Today.route,
        Screen.AiCoach.route to Screen.Today.route,
        Screen.GenerateWorkout.route to Screen.Today.route,
        Screen.AdaptWorkout.route to Screen.Workouts.route,
        Screen.Settings.route to Screen.Today.route,
        Screen.Workouts.route to Screen.Workouts.route,
        Screen.ProgramDetails.route to Screen.Workouts.route,
        Screen.TemplateDetails.route to Screen.Workouts.route,
        Screen.Exercises.route to Screen.Exercises.route,
        Screen.ExerciseDetails.route to Screen.Exercises.route,
        Screen.History.route to Screen.History.route,
        Screen.MyEvolution.route to Screen.MyEvolution.route,
        Screen.BodyEvolution.route to Screen.MyEvolution.route,
        Screen.AddBodyMeasurement.route to Screen.MyEvolution.route
    )

    val navTarget by com.example.MainActivity.notificationNavTarget.collectAsState()
    LaunchedEffect(navTarget) {
        val target = navTarget ?: return@LaunchedEffect
        when (target.destination) {
            com.example.service.SocialNotificationChannels.DESTINATION_FRIEND_REQUESTS -> {
                navController.navigate(Screen.FriendRequests.route)
            }
            com.example.service.SocialNotificationChannels.DESTINATION_FRIENDS -> {
                navController.navigate(Screen.Friends.route)
            }
            com.example.service.SocialNotificationChannels.DESTINATION_CHALLENGES -> {
                navController.navigate(Screen.Challenges.route)
            }
            com.example.service.SocialNotificationChannels.DESTINATION_CHALLENGE_DETAIL -> {
                if (!target.entityId.isNullOrBlank()) {
                    navController.navigate(Screen.ChallengeDetail.createRoute(target.entityId))
                } else {
                    navController.navigate(Screen.Challenges.route)
                }
            }
            com.example.service.SocialNotificationChannels.DESTINATION_SHARED_WORKOUTS -> {
                navController.navigate(Screen.SharedWorkouts.route)
            }
            // T17.11 §93 — o convite abre a lista de Squads, onde os convites ficam no topo. Nunca
            // o detalhe do grupo: quem ainda não aceitou não é membro dele.
            com.example.service.SocialNotificationChannels.DESTINATION_SQUADS -> {
                navController.navigate(Screen.Squads.route)
            }
        }
        com.example.MainActivity.clearNotificationNavTarget()
    }

    val isRouteSelected = { tabRoute: String ->
        currentRoute != null && topLevelDestinationMap[currentRoute] == tabRoute
    }
    
    val liveUnlocksFlow = app.achievementRepository.liveUnlocks
    val unlockQueue = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<com.example.domain.evolution.model.achievement.AchievementUnlock>() }
    
    LaunchedEffect(Unit) {
        liveUnlocksFlow.collect { unlock ->
            unlockQueue.add(unlock)
        }
    }

    // Só conclusões ao vivo chegam aqui: a reconciliação não reemite este fluxo.
    val liveMissionCompletions = app.missionRepository.liveCompletions
    val missionQueue = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateListOf<com.example.domain.gamification.model.mission.MissionCompletion>()
    }

    LaunchedEffect(Unit) {
        liveMissionCompletions.collect { completion ->
            missionQueue.add(completion)
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.background(BackgroundDark).windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    HorizontalDivider(color = BorderLight)
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        items.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon, contentDescription = stringResource(screen.titleRes)) },
                                label = { 
                                    Text(
                                        stringResource(screen.titleRes), 
                                        fontSize = 10.sp, 
                                        fontWeight = FontWeight.Bold,
                                    ) 
                                },
                                selected = isRouteSelected(screen.route),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Lime400,
                                    selectedTextColor = Lime400,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary,
                                    indicatorColor = LimeTransparent
                                ),
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = Screen.Today.route
            ) {
            composable(Screen.Today.route) { 
                TodayScreen(
                    viewModel = todayViewModel,
                    onNavigateToExecution = {
                        navController.navigate(Screen.Execution.route)
                    },
                    onNavigateToProfile = {
                        navController.navigate(Screen.Profile.route)
                    },
                    onNavigateToEvolution = {
                        // Progress questions belong to Evolução, so Hoje hands them over
                        // instead of answering them inline.
                        navController.navigate(Screen.MyEvolution.route) {
                            launchSingleTop = true
                        }
                    }
                ) 
            }
            composable(Screen.Workouts.route) { 
                WorkoutsScreen(workoutsViewModel, onProgramClick = { id -> navController.navigate(Screen.ProgramDetails.createRoute(id)) }) 
            }
            composable(Screen.ProgramDetails.route) { backStackEntry ->
                val programId = backStackEntry.arguments?.getString("programId")?.toLongOrNull() ?: -1L
                val viewModel: com.example.presentation.workouts.ProgramDetailsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                LaunchedEffect(programId) { viewModel.loadProgram(programId) }
                com.example.presentation.workouts.ProgramDetailsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onTemplateClick = { id -> navController.navigate(Screen.TemplateDetails.createRoute(id)) }
                )
            }
            composable(Screen.TemplateDetails.route) { backStackEntry ->
                val templateId = backStackEntry.arguments?.getString("templateId")?.toLongOrNull() ?: -1L
                val viewModel: com.example.presentation.workouts.TemplateDetailsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                androidx.compose.runtime.LaunchedEffect(templateId) { viewModel.load(templateId) }
                com.example.presentation.workouts.TemplateDetailsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onAdaptWithCoach = { navController.navigate(Screen.AdaptWorkout.createRoute(templateId)) }
                )
            }
            composable(Screen.AdaptWorkout.route) { backStackEntry ->
                val templateId = backStackEntry.arguments?.getString("templateId")?.toLongOrNull() ?: -1L
                val adaptViewModel: com.example.presentation.coach.AdaptWorkoutViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                // Conta Spark (T16.1): observar o estado restaura a sessão que já existe e
                // habilita o convite de login quando o Coach precisar dela. O seletor de contas
                // continua abrindo só por toque.
                val accountViewModel: com.example.presentation.account.AccountViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                val accountState by accountViewModel.uiState.collectAsState()
                com.example.presentation.coach.AdaptWorkoutScreen(
                    viewModel = adaptViewModel,
                    templateId = templateId,
                    onNavigateBack = { navController.popBackStack() },
                    onSignIn = accountViewModel::signIn,
                    isSignInAvailable = accountState.isSignInAvailable,
                    isSigningIn = accountState.isBusy
                )
            }
            composable(Screen.Exercises.route) { 
                ExercisesScreen(
                    viewModel = exercisesViewModel,
                    onExerciseClick = { exerciseId, name ->
                        navController.navigate(Screen.ExerciseDetails.createRoute(exerciseId, name))
                    }
                ) 
            }
            composable(
                route = Screen.ExerciseDetails.route,
                arguments = listOf(
                    androidx.navigation.navArgument("exerciseId") { type = androidx.navigation.NavType.LongType },
                    androidx.navigation.navArgument("exerciseName") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                val exerciseId = backStackEntry.arguments?.getLong("exerciseId") ?: return@composable
                val exerciseName = backStackEntry.arguments?.getString("exerciseName") ?: ""
                com.example.presentation.exercises.ExerciseDetailsScreen(
                    exerciseId = exerciseId,
                    exerciseName = exerciseName,
                    viewModel = exerciseDetailsViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAlternative = { altId, altName ->
                        navController.navigate(Screen.ExerciseDetails.createRoute(altId, altName))
                    }
                )
            }
            composable(Screen.History.route) {
                // A mesma ViewModel de check-in do Resumo, criada por rota. Ela não faz requisição
                // nenhuma ao ser criada: o `init` só observa a sessão para invalidar o estado na
                // troca de conta, e a primeira leitura sai de `startShareFor`, que só acontece no
                // toque do usuário sobre uma sessão.
                val checkInViewModel: com.example.presentation.friends.WorkoutCheckInViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                com.example.presentation.history.HistoryScreen(
                    viewModel = historyViewModel,
                    checkInViewModel = checkInViewModel
                )
            }
            composable(Screen.Profile.route) {
                val profileViewModel: com.example.presentation.profile.ProfileViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                // Criar o ViewModel liga o Perfil ao estado do Firebase Auth (restaura sessão
                // existente). Não inicia autenticação: o seletor de contas só abre no toque.
                val accountViewModel: com.example.presentation.account.AccountViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                // Criar o ViewModel de backup **lê** o vínculo e a última tentativa para saber o
                // que mostrar. Ele não envia nada: só um toque explícito produz backup.
                val backupViewModel: com.example.presentation.account.BackupViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                // Idem para o restore: criar o ViewModel **lê** o estado (há tentativa
                // interrompida? já houve restauração aqui?). Ele não lista, não baixa e não
                // restaura — cada uma dessas coisas exige um toque.
                val restoreViewModel: com.example.presentation.account.RestoreViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                // E para o sync: criar o ViewModel **lê** o vínculo, a fila e os conflitos. Ele
                // não sincroniza — um ciclo nasce do toque, do app voltando ao primeiro plano ou
                // do trabalho agendado por uma alteração local.
                val syncViewModel: com.example.presentation.account.SyncViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                // E para o social (T17.0): criar o ViewModel **lê** o perfil que já existe no
                // servidor. Ler não cria perfil — `GET /v1/social/me` responde
                // `{ enabled: false }` sem escrever nada. Ativar exige dois toques explícitos.
                val socialViewModel: com.example.presentation.account.SocialViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                ProfileScreen(
                    viewModel = profileViewModel,
                    accountViewModel = accountViewModel,
                    backupViewModel = backupViewModel,
                    restoreViewModel = restoreViewModel,
                    syncViewModel = syncViewModel,
                    socialViewModel = socialViewModel,
                    friendsViewModel = friendsViewModel,
                    onNavigateToFriends = { navController.navigate(Screen.Friends.route) },
                    onNavigateToFriendRequests = {
                        navController.navigate(Screen.FriendRequests.route)
                    },
                    onNavigateToProgressSharing = {
                        navController.navigate(Screen.ProgressSharing.route)
                    },
                    onNavigateToChallenges = { navController.navigate(Screen.Challenges.route) },
                    onNavigateToActivity = { navController.navigate(Screen.Activity.route) },
                    onNavigateToNotificationPreferences = {
                        navController.navigate(Screen.NotificationPreferences.route)
                    },
                    onNavigateToBlockedUsers = {
                        navController.navigate(Screen.BlockedUsers.route)
                    },
                    onNavigateToSharedWorkouts = {
                        navController.navigate(Screen.SharedWorkouts.route)
                    },
                    onNavigateToSquads = {
                        navController.navigate(Screen.Squads.route)
                    },
                    onNavigateToSocialFeed = {
                        navController.navigate(Screen.SocialFeed.route)
                    },
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToMissions = { navController.navigate(Screen.Missions.route) },
                    onNavigateToAiCoach = { navController.navigate(Screen.AiCoach.route) },
                    onNavigateToBodyEvolution = { navController.navigate(Screen.BodyEvolution.route) },
                    // As conquistas continuam morando em Evolução: o Perfil só mostra uma prévia.
                    onNavigateToAchievements = {
                        navController.navigate(Screen.MyEvolution.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Friends.route) {
                com.example.presentation.friends.FriendsScreen(
                    viewModel = friendsViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToRequests = { navController.navigate(Screen.FriendRequests.route) },
                    // T17.2 — o perfil é lido no **toque**, e não ao abrir a lista: enriquecer
                    // cada linha custaria uma requisição por amigo.
                    onOpenProfile = { friend ->
                        navController.navigate(
                            Screen.FriendProfile.createRoute(friend.socialId, friend.displayName)
                        )
                    }
                )
            }
            composable(
                route = Screen.FriendProfile.route,
                arguments = listOf(
                    androidx.navigation.navArgument("socialId") {
                        type = androidx.navigation.NavType.StringType
                    },
                    androidx.navigation.navArgument("name") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                com.example.presentation.friends.FriendSocialProfileScreen(
                    socialId = entry.arguments?.getString("socialId").orEmpty(),
                    displayNameHint = entry.arguments?.getString("name")?.takeIf { it.isNotBlank() },
                    viewModel = socialProfileViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.ProgressSharing.route) {
                com.example.presentation.friends.ProgressSharingScreen(
                    viewModel = socialProfileViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Challenges.route) {
                com.example.presentation.friends.ChallengesScreen(
                    viewModel = challengeViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenChallenge = { challengeId ->
                        navController.navigate(Screen.ChallengeDetail.createRoute(challengeId))
                    },
                    onCreateChallenge = { navController.navigate(Screen.CreateChallenge.route) }
                )
            }
            composable(Screen.CreateChallenge.route) {
                com.example.presentation.friends.CreateChallengeScreen(
                    viewModel = challengeViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    // Criado: sai da tela de criação e abre o desafio. `popUpTo` evita que o botão
                    // "voltar" leve de volta a um formulário que já foi enviado.
                    onCreated = { challengeId ->
                        navController.navigate(Screen.ChallengeDetail.createRoute(challengeId)) {
                            popUpTo(Screen.CreateChallenge.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(
                route = Screen.ChallengeDetail.route,
                arguments = listOf(
                    androidx.navigation.navArgument("challengeId") {
                        type = androidx.navigation.NavType.StringType
                    },
                    androidx.navigation.navArgument("name") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                com.example.presentation.friends.ChallengeDetailScreen(
                    challengeId = entry.arguments?.getString("challengeId").orEmpty(),
                    nameHint = entry.arguments?.getString("name")?.takeIf { it.isNotBlank() },
                    viewModel = challengeViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.FriendRequests.route) {
                com.example.presentation.friends.FriendRequestsScreen(
                    viewModel = friendsViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Activity.route) {
                com.example.presentation.friends.ActivityScreen(
                    viewModel = socialActivityViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.NotificationPreferences.route) {
                val notificationViewModel: com.example.presentation.friends.NotificationPreferencesViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                com.example.presentation.friends.NotificationPreferencesScreen(
                    viewModel = notificationViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.BlockedUsers.route) {
                val blockedUsersViewModel: com.example.presentation.friends.BlockedUsersViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                com.example.presentation.friends.BlockedUsersScreen(
                    viewModel = blockedUsersViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.SharedWorkouts.route) {
                val sharedWorkoutsViewModel: com.example.presentation.friends.SharedWorkoutsViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                com.example.presentation.friends.SharedWorkoutsScreen(
                    viewModel = sharedWorkoutsViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.SocialFeed.route) {
                val socialFeedViewModel: com.example.presentation.friends.SocialFeedViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                com.example.presentation.friends.SocialFeedScreen(
                    viewModel = socialFeedViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    // Bloquear e denunciar o autor já existem no perfil do amigo (T17.2/T17.6). O
                    // Feed leva para lá em vez de repetir os diálogos (§88).
                    onOpenFriendProfile = { socialId, displayName ->
                        navController.navigate(Screen.FriendProfile.createRoute(socialId, displayName))
                    },
                    // T17.9 §118 — a conversa acontece no detalhe, e não no card.
                    onOpenCheckIn = { checkInId ->
                        navController.navigate(Screen.CheckInDetail.createRoute(checkInId))
                    }
                )
            }
            composable(Screen.CheckInDetail.route) { backStackEntry ->
                val checkInId = backStackEntry.arguments?.getString("checkInId").orEmpty()
                val detailViewModel: com.example.presentation.friends.CheckInDetailViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                // T17.11 §140 — "Compartilhar no Squad" mora no menu da própria publicação. A
                // ViewModel do seletor é separada porque a pergunta que ela faz — "em quais dos
                // meus squads este check-in já está?" — não é do detalhe nem da lista de squads.
                val shareToSquadViewModel: com.example.presentation.friends.ShareToSquadViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                com.example.presentation.friends.CheckInDetailScreen(
                    viewModel = detailViewModel,
                    checkInId = checkInId,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFriendProfile = { socialId, displayName ->
                        navController.navigate(Screen.FriendProfile.createRoute(socialId, displayName))
                    },
                    shareToSquadViewModel = shareToSquadViewModel
                )
            }
            composable(Screen.Squads.route) {
                val squadsViewModel: com.example.presentation.friends.SquadsViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                com.example.presentation.friends.SquadsScreen(
                    viewModel = squadsViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenSquad = { groupId ->
                        navController.navigate(Screen.SquadDetail.createRoute(groupId))
                    }
                )
            }
            composable(Screen.SquadDetail.route) { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId").orEmpty()
                // A ViewModel do detalhe recebe o `groupId` na construção porque ele é a
                // identidade dela: um `open(groupId)` posterior abriria espaço para a tela pedir
                // um squad e receber outro depois de uma navegação rápida.
                val squadDetailViewModel: com.example.presentation.friends.SquadDetailViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(
                        factory = factory.squadDetailFactory(groupId)
                    )
                com.example.presentation.friends.SquadDetailScreen(
                    viewModel = squadDetailViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenCheckIn = { checkInId ->
                        navController.navigate(Screen.CheckInDetail.createRoute(checkInId))
                    }
                )
            }
            composable(Screen.Missions.route) {
                val missionViewModel: com.example.presentation.missions.MissionViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                com.example.presentation.missions.MissionsScreen(
                    viewModel = missionViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.AiCoach.route) {
                val aiCoachViewModel: com.example.presentation.coach.AiCoachViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                val accountViewModel: com.example.presentation.account.AccountViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                val accountState by accountViewModel.uiState.collectAsState()
                com.example.presentation.coach.AiCoachScreen(
                    viewModel = aiCoachViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToGenerateWorkout = { navController.navigate(Screen.GenerateWorkout.route) },
                    onSignIn = accountViewModel::signIn,
                    isSignInAvailable = accountState.isSignInAvailable,
                    isSigningIn = accountState.isBusy
                )
            }
            composable(Screen.GenerateWorkout.route) {
                val generateWorkoutViewModel: com.example.presentation.coach.GenerateWorkoutViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                val accountViewModel: com.example.presentation.account.AccountViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                val accountState by accountViewModel.uiState.collectAsState()
                com.example.presentation.coach.GenerateWorkoutScreen(
                    viewModel = generateWorkoutViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    // Editar um treino gerado é editar um treino: o editor canônico assume a partir daqui.
                    onOpenTemplate = { templateId ->
                        navController.navigate(Screen.TemplateDetails.createRoute(templateId))
                    },
                    onSignIn = accountViewModel::signIn,
                    isSignInAvailable = accountState.isSignInAvailable,
                    isSigningIn = accountState.isBusy
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToMyEvolution = {
                        navController.navigate(Screen.MyEvolution.route)
                    },
                    onNavigateToBodyEvolution = {
                        navController.navigate(Screen.BodyEvolution.route)
                    }
                )
            }
            composable(Screen.MyEvolution.route) {
                com.example.feature.evolution.EvolutionScreen(
                    viewModel = evolutionViewModel,
                    bodyViewModel = featureBodyEvolutionViewModel,
                    performanceViewModel = performanceViewModel,
                    consistencyViewModel = consistencyViewModel,
                    achievementsViewModel = achievementsViewModel,
                    timelineViewModel = timelineViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToBodyEvolution = { navController.navigate(Screen.BodyEvolution.route) }
                )
            }
            composable(Screen.BodyEvolution.route) {
                BodyEvolutionScreen(
                    viewModel = bodyEvolutionViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAddMeasurement = { navController.navigate(Screen.AddBodyMeasurement.route) }
                )
            }
            composable(Screen.AddBodyMeasurement.route) {
                AddBodyMeasurementScreen(
                    viewModel = bodyEvolutionViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Execution.route) {
                ExecutionScreen(
                    viewModel = executionViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onFinish = { sessionId ->
                        navController.navigate(Screen.Summary.createRoute(sessionId)) {
                            popUpTo(Screen.Today.route) { inclusive = false }
                        }
                    },
                    onNavigateToExerciseDetails = { exerciseId, name ->
                        navController.navigate(Screen.ExerciseDetails.createRoute(exerciseId, name))
                    }
                )
            }
            composable(
                route = Screen.Summary.route,
                arguments = listOf(androidx.navigation.navArgument("sessionId") { type = androidx.navigation.NavType.LongType })
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: return@composable
                val summary by summaryViewModel.getSummary(sessionId).collectAsState(initial = null)
                val currentSummary = summary
                if (currentSummary != null) {
                    // O CTA social é montado **depois** de a sessão já estar concluída e salva: o
                    // `sessionId` desta rota só existe porque o treino terminou (§98/§99). A
                    // ViewModel é criada aqui e não dentro do `SummaryScreen` para que a tela de
                    // resumo continue sendo uma função do sumário, sem conhecer o social.
                    val checkInViewModel: com.example.presentation.friends.WorkoutCheckInViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                    com.example.presentation.execution.SummaryScreen(
                        summary = currentSummary,
                        onClose = { navController.navigate(Screen.Today.route) { popUpTo(0) } },
                        shareCheckIn = {
                            com.example.presentation.friends.ShareCheckInSection(
                                viewModel = checkInViewModel,
                                sessionId = sessionId
                            )
                        }
                    )
                }
            }
            }
            
            // Queue feedback visualizer
            if (unlockQueue.isNotEmpty()) {
                val currentUnlock = unlockQueue.first()
                val def = com.example.domain.evolution.model.achievement.AchievementCatalog.getDefinition(currentUnlock.achievementId)
                if (def != null) {
                    com.example.presentation.gamification.components.AchievementUnlockFeedback(
                        title = def.title,
                        description = def.description,
                        icon = def.icon,
                        onAnimationEnd = { unlockQueue.removeAt(0) },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 16.dp)
                    )
                } else {
                    LaunchedEffect(currentUnlock) {
                        unlockQueue.removeAt(0)
                    }
                }
            }

            if (unlockQueue.isEmpty() && missionQueue.isNotEmpty()) {
                val currentCompletion = missionQueue.first()
                val definition = com.example.domain.gamification.model.mission.MissionCatalog
                    .getDefinition(currentCompletion.missionId)
                if (definition != null) {
                    com.example.presentation.gamification.components.MissionCompletionFeedback(
                        title = definition.title,
                        rewardXp = currentCompletion.rewardXp,
                        onAnimationEnd = { missionQueue.removeAt(0) },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 16.dp)
                    )
                } else {
                    LaunchedEffect(currentCompletion) {
                        missionQueue.removeAt(0)
                    }
                }
            }
        }
    }
}
