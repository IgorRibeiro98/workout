package com.example.presentation
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.BorderLight
import com.example.ui.theme.BackgroundDark
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import com.example.presentation.exercises.ExercisesScreen
import com.example.presentation.history.HistoryScreen
import com.example.presentation.navigation.Screen
import com.example.presentation.settings.SettingsScreen
import com.example.presentation.today.TodayScreen
import com.example.presentation.workouts.WorkoutsScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.example.MainApplication
import com.example.presentation.MainViewModelFactory

import com.example.presentation.execution.ExecutionScreen
import com.example.presentation.execution.ExecutionViewModel
import androidx.compose.animation.AnimatedVisibility

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val factory = MainViewModelFactory(app.repository, app.settingsManager, app.workoutEngine, app.notificationManager)

    val exercisesViewModel: com.example.presentation.exercises.ExercisesViewModel = viewModel(factory = factory)
    val workoutsViewModel: com.example.presentation.workouts.WorkoutsViewModel = viewModel(factory = factory)
    val todayViewModel: com.example.presentation.today.TodayViewModel = viewModel(factory = factory)
    val executionViewModel: ExecutionViewModel = viewModel(factory = factory)
    val summaryViewModel: com.example.presentation.execution.SummaryViewModel = viewModel(factory = factory)
    val exerciseDetailsViewModel: com.example.presentation.exercises.ExerciseDetailsViewModel = viewModel(factory = factory)
    val historyViewModel: com.example.presentation.history.HistoryViewModel = viewModel(factory = factory)

    val navController = rememberNavController()
    val items = listOf(
        Screen.Today,
        Screen.Workouts,
        Screen.Exercises,
        Screen.History,
        Screen.Settings
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute != Screen.Execution.route

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                androidx.compose.foundation.layout.Column {
                    HorizontalDivider(color = BorderLight)
                    NavigationBar(
                        containerColor = BackgroundDark,
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
                                selected = currentRoute == screen.route,
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
        NavHost(
            navController = navController,
            startDestination = Screen.Today.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Today.route) { 
                TodayScreen(
                    viewModel = todayViewModel,
                    onNavigateToExecution = {
                        navController.navigate(Screen.Execution.route)
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
            composable(Screen.Settings.route) { SettingsScreen() }
            composable(Screen.Execution.route) {
                ExecutionScreen(
                    viewModel = executionViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onFinish = { sessionId ->
                        navController.navigate(Screen.Summary.createRoute(sessionId)) {
                            popUpTo(Screen.Today.route) { inclusive = false }
                        }
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
    }
}
