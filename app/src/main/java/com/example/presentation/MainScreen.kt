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
        xpTransactionRepository = app.xpTransactionRepository
    )

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
                com.example.presentation.workouts.TemplateDetailsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
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
            composable(Screen.History.route) { com.example.presentation.history.HistoryScreen(historyViewModel) }
            composable(Screen.Profile.route) {
                ProfileScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToBodyEvolution = { navController.navigate(Screen.BodyEvolution.route) }
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
                    com.example.presentation.execution.SummaryScreen(
                        summary = currentSummary,
                        onClose = { navController.navigate(Screen.Today.route) { popUpTo(0) } }
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
        }
    }
}
