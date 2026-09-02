package com.example.presentation.execution

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.components.workout.execution.ExerciseMediaCompact
import com.example.components.workout.execution.ExercisePerformanceCard
import com.example.components.workout.execution.ExerciseQuickInfoSheet
import com.example.components.workout.execution.ExerciseTargetCard
import com.example.components.workout.execution.QuickAdjustValueCard
import com.example.components.workout.execution.QuickCoachTip
import com.example.components.workout.execution.RepWheelPicker
import com.example.components.workout.execution.SetFeedbackBanner
import com.example.components.workout.execution.WeightWheelPicker
import com.example.components.workout.execution.WorkoutActionButton
import com.example.components.workout.execution.WorkoutProgressHeader
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SetLogEntity
import com.example.domain.engine.MuscleVisualResolver
import com.example.domain.engine.RirFormatter
import com.example.domain.workout.execution.ExerciseExecutionContext
import com.example.ui.components.ActionBottomSheet
import com.example.ui.components.ActionItemData
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.components.DirectNumericInputSheet
import com.example.ui.components.RirSelector
import com.example.ui.components.SyncExerciseSheet
import com.example.ui.theme.*
import kotlinx.coroutines.launch

sealed interface WorkoutSheet {
    data object OptionsMenu : WorkoutSheet
    data object AllSets : WorkoutSheet
    data object Sync : WorkoutSheet
    data object ExercisesList : WorkoutSheet
    data object LastWorkout : WorkoutSheet
    data object Instructions : WorkoutSheet
    data object Alternatives : WorkoutSheet
    data object QuickInfo : WorkoutSheet
    data class Reorder(val exerciseToMove: com.example.domain.workout.execution.WorkoutExerciseExecution) : WorkoutSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionScreen(
    viewModel: ExecutionViewModel,
    onNavigateBack: () -> Unit,
    onFinish: (Long) -> Unit,
    onNavigateToExerciseDetails: ((Long, String) -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()
    val timerTarget by viewModel.restTimerTarget.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val hapticEnabled by viewModel.hapticEnabled.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val preAlertEnabled by viewModel.preAlertEnabled.collectAsState()
    val rirRpeEnabled by viewModel.rirRpeEnabled.collectAsState()
    val showGifs by viewModel.showGifs.collectAsState()
    val showCoachTip by viewModel.showCoachTip.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Bottom sheet & Dialog states
    var activeSheet by remember { mutableStateOf<WorkoutSheet?>(null) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showFinishEarlyDialog by remember { mutableStateOf(false) }
    var completedReorderConfirm by remember { mutableStateOf<com.example.domain.workout.execution.WorkoutExerciseExecution?>(null) }
    var directInputConfig by remember { mutableStateOf<DirectInputConfig?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val currentExId = state.currentExercise?.exerciseSession?.actualExerciseId ?: state.currentExercise?.exerciseSession?.plannedExerciseId
    val premiumInfo by remember(currentExId) {
        if (currentExId != null) viewModel.getPremiumInfo(currentExId) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)

    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    if (state.isLoading || state.sessionWithDetails == null) {
        Box(modifier = Modifier.fillMaxSize().background(BackgroundDark), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Lime400)
        }
        return
    }

    val session = state.sessionWithDetails!!
    val currentEx = state.currentExercise

    if (currentEx == null) {
        Scaffold(
            containerColor = BackgroundDark,
            topBar = {
                TopAppBar(
                    title = { Text("Treino Vazio", color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nenhum exercício encontrado.", color = TextSecondary)
            }
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = state.currentExerciseIndex,
        pageCount = { session.exercises.size }
    )

    LaunchedEffect(state.currentExerciseIndex) {
        if (pagerState.currentPage != state.currentExerciseIndex && state.currentExerciseIndex in 0 until session.exercises.size) {
            pagerState.animateScrollToPage(state.currentExerciseIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != state.currentExerciseIndex && pagerState.currentPage in 0 until session.exercises.size) {
            viewModel.selectExercise(pagerState.currentPage)
        }
    }

    // Animated Workout Progress
    val totalSetsCount = session.exercises.sumOf { it.sets.size }
    val completedSetsCount = session.exercises.sumOf { it.sets.count { s -> s.completed } }
    val targetProgress = if (totalSetsCount > 0) completedSetsCount.toFloat() / totalSetsCount.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(
            durationMillis = AppMotion.Emphasized,
            easing = AppMotion.StandardEasing
        ),
        label = "workoutProgressBar"
    )

    val openFullDetails: () -> Unit = {
        val exId = currentEx.exerciseSession.actualExerciseId ?: currentEx.exerciseSession.plannedExerciseId
        if (exId != null && onNavigateToExerciseDetails != null) {
            onNavigateToExerciseDetails(exId, currentEx.exerciseSession.exerciseNameSnapshot)
        } else {
            activeSheet = WorkoutSheet.Instructions
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { activeSheet = WorkoutSheet.ExercisesList }
                                .padding(vertical = 4.dp, horizontal = 8.dp)
                        ) {
                            val sessionName = session.session.templateNameSnapshot ?: "Treino"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = sessionName.uppercase(),
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (state.isOrderAdapted) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = "⚡ Ordem adaptada",
                                            color = Color(0xFFF59E0B),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Exercício ${state.currentExerciseIndex + 1} de ${session.exercises.size}",
                                    color = Lime400,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Ver lista de exercícios",
                                    tint = Lime400,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { showCancelDialog = true }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar / Opções de saída", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { activeSheet = WorkoutSheet.QuickInfo },
                            modifier = Modifier.testTag("quick_info_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Informações rápidas",
                                tint = Lime400
                            )
                        }
                        IconButton(
                            onClick = openFullDetails,
                            modifier = Modifier.testTag("full_details_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Ver exercício completo",
                                tint = TextSecondary
                            )
                        }
                        IconButton(
                            onClick = { activeSheet = WorkoutSheet.OptionsMenu },
                            modifier = Modifier.testTag("workout_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu do treino",
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = Lime400,
                    trackColor = SurfaceDark
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Crossfade(
                targetState = state.phase,
                animationSpec = tween(durationMillis = AppMotion.Normal, easing = AppMotion.StandardEasing),
                label = "FocusModePhaseTransition"
            ) { phase ->
                when (phase) {
                    ExecutionPhase.RESTING -> {
                        val isPreparingNext = state.isExerciseCompleted
                        val nextExSession = if (isPreparingNext) {
                            session.exercises.getOrNull(state.currentExerciseIndex + 1)
                        } else {
                            null
                        }
                        FocusedRestView(
                            targetTime = timerTarget ?: System.currentTimeMillis(),
                            onAdd15s = { viewModel.adjustRestTimer(15) },
                            onAdd30s = { viewModel.adjustRestTimer(30) },
                            onSkip = {
                                viewModel.skipRestTimer()
                                if (isPreparingNext) {
                                    viewModel.nextExercise()
                                }
                            },
                            isPreparingNextExercise = isPreparingNext,
                            nextExerciseName = if (isPreparingNext) {
                                nextExSession?.exerciseSession?.exerciseNameSnapshot ?: "Próximo Exercício"
                            } else {
                                currentEx.exerciseSession.exerciseNameSnapshot
                            },
                            nextMachineLabel = if (isPreparingNext) nextExSession?.exerciseSession?.machineLabelSnapshot else null,
                            nextSetIndex = if (isPreparingNext) 1 else ((state.activeSetIndex ?: 0) + 1),
                            nextSetWeight = if (isPreparingNext) (nextExSession?.sets?.firstOrNull()?.weight ?: 0f) else (state.activeSet?.weight ?: 0f),
                            nextSetReps = if (isPreparingNext) (nextExSession?.sets?.firstOrNull()?.repetitions ?: 0) else (state.activeSet?.repetitions ?: 0),
                            totalSets = if (isPreparingNext) (nextExSession?.sets?.size ?: 1) else currentEx.sets.size
                        )
                    }
                    ExecutionPhase.ACTIVE_SET -> {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            if (page == state.currentExerciseIndex) {
                                val activeSet = state.activeSet ?: currentEx.sets.firstOrNull()
                                if (activeSet != null) {
                                    AnimatedContent(
                                        targetState = state.activeSetIndex ?: 0,
                                        transitionSpec = {
                                            (fadeIn(animationSpec = tween(250, easing = AppMotion.StandardEasing)) +
                                             scaleIn(initialScale = 0.96f, animationSpec = tween(250, easing = AppMotion.StandardEasing))) togetherWith
                                            (fadeOut(animationSpec = tween(200, easing = AppMotion.DecelerateEasing)) +
                                             scaleOut(targetScale = 1.03f, animationSpec = tween(200, easing = AppMotion.DecelerateEasing)))
                                        },
                                        label = "ActiveSetTransition"
                                    ) { _ ->
                                        FocusedActiveSetView(
                                            currentEx = currentEx,
                                            activeSet = activeSet,
                                            activeSetIndex = state.activeSetIndex ?: 0,
                                            totalSets = currentEx.sets.size,
                                            currentExerciseIndex = state.currentExerciseIndex,
                                            totalExercises = session.exercises.size,
                                            resolvedExercise = state.currentResolvedExercise,
                                            premiumInfo = premiumInfo,
                                            previousExecutionSets = state.previousExecutionSets,
                                            exerciseExecutionContext = state.exerciseExecutionContext,
                                            lastSetFeedback = state.lastSetFeedback,
                                            isLastExercise = state.isLastExercise,
                                            rirRpeEnabled = rirRpeEnabled,
                                            hapticEnabled = hapticEnabled,
                                            showGifs = showGifs,
                                            showCoachTip = showCoachTip,
                                            isOrderAdapted = state.isOrderAdapted,
                                            onUpdateSet = { viewModel.updateSet(it) },
                                            onCompleteSet = {
                                                if (hapticEnabled) {
                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                }
                                                viewModel.completeSet(it)
                                            },
                                            onDismissFeedback = { viewModel.dismissFeedback() },
                                            onOpenDirectInput = { config -> directInputConfig = config },
                                            onOpenExerciseSelector = { activeSheet = WorkoutSheet.ExercisesList },
                                            onOpenQuickInfo = { activeSheet = WorkoutSheet.QuickInfo },
                                            onOpenFullDetails = openFullDetails,
                                            onNextExercise = { viewModel.nextExercise() },
                                            onPreviousExercise = { viewModel.previousExercise() },
                                            onViewAllSets = { activeSheet = WorkoutSheet.AllSets },
                                            onViewLastWorkout = { activeSheet = WorkoutSheet.LastWorkout },
                                            onOpenSyncSheet = { activeSheet = WorkoutSheet.Sync },
                                            onReplicateCurrentSet = { setLog ->
                                                viewModel.updateSet(setLog)
                                                viewModel.replicateCurrentSet { result ->
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar("Séries sincronizadas: ${result.updatedCount} séries atualizadas")
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            } else {
                                val adjacentEx = session.exercises.getOrNull(page)
                                if (adjacentEx != null) {
                                    AdjacentExercisePreview(
                                        exercise = adjacentEx,
                                        index = page,
                                        total = session.exercises.size
                                    )
                                }
                            }
                        }
                    }
                    ExecutionPhase.EXERCISE_TRANSITION -> {
                        val nextExSession = session.exercises.getOrNull(state.currentExerciseIndex + 1)
                        val recRest = nextExSession?.exerciseSession?.restDurationSecondsSnapshot
                            ?: currentEx.exerciseSession.restDurationSecondsSnapshot
                            ?: 90
                        FocusedExerciseTransitionView(
                            completedExerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                            completedSetsCount = currentEx.sets.count { it.completed },
                            totalSetsCount = currentEx.sets.size,
                            nextExerciseName = nextExSession?.exerciseSession?.exerciseNameSnapshot,
                            nextMachineLabel = nextExSession?.exerciseSession?.machineLabelSnapshot,
                            nextPrimaryMuscle = nextExSession?.exerciseSession?.primaryMuscleSnapshot,
                            recommendedRestSeconds = recRest,
                            onStartRest = { duration ->
                                viewModel.startRestTimer(duration)
                            },
                            onStartNext = { viewModel.nextExercise() }
                        )
                    }
                    ExecutionPhase.WORKOUT_COMPLETE -> {
                        FocusedWorkoutCompleteView(
                            sessionName = session.session.templateNameSnapshot ?: "Treino",
                            totalExercises = session.exercises.size,
                            totalCompletedSets = session.exercises.sumOf { ex -> ex.sets.count { it.completed } },
                            onFinishWorkout = {
                                viewModel.finishSession()
                                onFinish(session.session.id)
                            }
                        )
                    }
                }
            }
        }

        val alternatives by viewModel.alternatives.collectAsState()

        // Handle Android Back button to close open sheets before navigating back
        BackHandler(enabled = activeSheet != null || alternatives.isNotEmpty()) {
            if (alternatives.isNotEmpty()) {
                viewModel.clearAlternatives()
            } else {
                activeSheet = null
            }
        }

        // Action BottomSheets
        when (activeSheet) {
            WorkoutSheet.OptionsMenu -> {
                ActionBottomSheet(
                    onDismissRequest = { activeSheet = null },
                    title = stringResource(id = R.string.sheet_workout_options),
                    actions = listOf(
                        ActionItemData(
                            title = stringResource(id = R.string.sheet_action_swap),
                            icon = Icons.Default.SwapHoriz,
                            onClick = {
                                activeSheet = null
                                viewModel.loadAlternatives()
                            }
                        ),
                        ActionItemData(
                            title = stringResource(id = R.string.sheet_action_instructions),
                            icon = Icons.Default.HelpOutline,
                            onClick = { activeSheet = WorkoutSheet.Instructions }
                        ),
                        ActionItemData(
                            title = stringResource(id = R.string.sheet_action_sync),
                            icon = Icons.Default.Sync,
                            onClick = { activeSheet = WorkoutSheet.Sync }
                        ),
                        ActionItemData(
                            title = stringResource(id = R.string.sheet_action_all_sets),
                            icon = Icons.AutoMirrored.Filled.List,
                            onClick = { activeSheet = WorkoutSheet.AllSets }
                        ),
                        ActionItemData(
                            title = stringResource(id = R.string.sheet_action_last_workout),
                            icon = Icons.Default.History,
                            onClick = { activeSheet = WorkoutSheet.LastWorkout }
                        ),
                        ActionItemData(
                            title = stringResource(id = R.string.sheet_action_finish_early),
                            icon = Icons.Default.CheckCircle,
                            destructive = true,
                            onClick = {
                                activeSheet = null
                                showFinishEarlyDialog = true
                            }
                        )
                    )
                )
            }
            WorkoutSheet.AllSets -> {
                AllSetsBottomSheet(
                    exerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                    sets = currentEx.sets,
                    rirRpeEnabled = rirRpeEnabled,
                    onDismiss = { activeSheet = null },
                    onUpdateSet = { viewModel.updateSet(it) },
                    onToggleComplete = { set ->
                        if (set.completed) viewModel.uncompleteSet(set) else viewModel.completeSet(set)
                    },
                    onAddSet = { viewModel.addSet() },
                    onRemoveSet = { viewModel.removeSet(it) },
                    onOpenSyncSheet = { activeSheet = WorkoutSheet.Sync }
                )
            }
            WorkoutSheet.Sync -> {
                SyncExerciseSheet(
                    exerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                    activeSet = state.activeSet,
                    allCurrentSets = currentEx.sets,
                    previousExecutionSets = state.previousExecutionSets,
                    onDismissRequest = { activeSheet = null },
                    onReplicateCurrentSet = {
                        activeSheet = null
                        viewModel.replicateCurrentSet { result ->
                            coroutineScope.launch {
                                val msg = if (result.updatedCount > 0) {
                                    context.getString(R.string.sync_applied_success, result.updatedCount)
                                } else {
                                    context.getString(R.string.sync_no_pending_sets)
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    onRestoreLastWorkout = {
                        activeSheet = null
                        viewModel.restoreLastExecutionValues { result ->
                            coroutineScope.launch {
                                val msg = if (result.updatedCount > 0) {
                                    context.getString(R.string.sync_restored_success, result.updatedCount)
                                } else {
                                    context.getString(R.string.sync_no_pending_sets)
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    }
                )
            }
            WorkoutSheet.ExercisesList -> {
                WorkoutExercisesBottomSheet(
                    exercises = session.exercises,
                    exerciseExecutions = state.exerciseExecutions,
                    currentIndex = state.currentExerciseIndex,
                    isOrderAdapted = state.isOrderAdapted,
                    onDismiss = { activeSheet = null },
                    onSelectExercise = { index ->
                        viewModel.selectExercise(index)
                        activeSheet = null
                    },
                    onMoveExerciseToLater = { exId ->
                        viewModel.moveExerciseToLater(exId)
                    },
                    onRequestReorderPosition = { exExec ->
                        if (exExec.status == com.example.domain.workout.execution.ExerciseExecutionStatus.COMPLETED) {
                            completedReorderConfirm = exExec
                        } else {
                            activeSheet = WorkoutSheet.Reorder(exExec)
                        }
                    }
                )
            }
            is WorkoutSheet.Reorder -> {
                com.example.presentation.execution.components.ExerciseReorderBottomSheet(
                    exerciseToMove = (activeSheet as WorkoutSheet.Reorder).exerciseToMove,
                    allExercises = state.exerciseExecutions,
                    onDismiss = { activeSheet = null },
                    onSelectPosition = { newPos ->
                        viewModel.moveExercise((activeSheet as WorkoutSheet.Reorder).exerciseToMove.exerciseId, newPos)
                        activeSheet = null
                    }
                )
            }
            WorkoutSheet.LastWorkout -> {
                PreviousExecutionBottomSheet(
                    exerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                    previousSets = state.previousExecutionSets,
                    summary = state.exerciseExecutionContext?.summary,
                    onDismiss = { activeSheet = null }
                )
            }
            WorkoutSheet.Instructions -> {
                ExerciseInstructionsBottomSheet(
                    resolvedExercise = state.currentResolvedExercise,
                    exerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                    onDismiss = { activeSheet = null }
                )
            }
            WorkoutSheet.QuickInfo -> {
                ExerciseQuickInfoSheet(
                    exerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                    premiumInfo = premiumInfo,
                    onOpenFullDetails = {
                        val exId = currentEx.exerciseSession.actualExerciseId ?: currentEx.exerciseSession.plannedExerciseId
                        if (exId != null && onNavigateToExerciseDetails != null) {
                            activeSheet = null
                            onNavigateToExerciseDetails(exId, currentEx.exerciseSession.exerciseNameSnapshot)
                        } else {
                            activeSheet = WorkoutSheet.Instructions
                        }
                    },
                    onDismiss = { activeSheet = null }
                )
            }
            WorkoutSheet.Alternatives -> {
                if (alternatives.isNotEmpty()) {
                    AlternativesBottomSheet(
                        alternatives = alternatives,
                        onDismiss = {
                            viewModel.clearAlternatives()
                            activeSheet = null
                        },
                        onSwap = { altId ->
                            viewModel.swapCurrentExercise(altId, false)
                            activeSheet = null
                        }
                    )
                }
            }
            null -> {}
        }

        if (completedReorderConfirm != null) {
            val exExec = completedReorderConfirm!!
            AlertDialog(
                onDismissRequest = { completedReorderConfirm = null },
                containerColor = SurfaceDark,
                title = { Text("Mover Exercício Concluído?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text("Este exercício já foi concluído. Mover para outra posição reorganizará o histórico do treino.", color = TextSecondary) },
                confirmButton = {
                    Button(
                        onClick = {
                            val toMove = exExec
                            completedReorderConfirm = null
                            activeSheet = WorkoutSheet.Reorder(toMove)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
                    ) {
                        Text("Sim, Continuar", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { completedReorderConfirm = null }) {
                        Text("Cancelar", color = TextSecondary)
                    }
                }
            )
        }

        if (showCancelDialog) {
            AlertDialog(
                onDismissRequest = { showCancelDialog = false },
                containerColor = SurfaceDark,
                title = { Text("Sair do Treino?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text("O treino continuará em andamento em segundo plano. Deseja sair ou cancelar?", color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = {
                        showCancelDialog = false
                        onNavigateBack()
                    }) {
                        Text("Sair e Manter", color = Lime400, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCancelDialog = false
                        viewModel.cancelSession()
                        onNavigateBack()
                    }) {
                        Text("Cancelar Treino", color = Color.Red.copy(alpha = 0.8f))
                    }
                }
            )
        }

        if (showFinishEarlyDialog) {
            AlertDialog(
                onDismissRequest = { showFinishEarlyDialog = false },
                containerColor = SurfaceDark,
                title = { Text("Finalizar Treino?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text("Deseja concluir a sessão de treino agora?", color = TextSecondary) },
                confirmButton = {
                    Button(
                        onClick = {
                            showFinishEarlyDialog = false
                            viewModel.finishSession()
                            onFinish(session.session.id)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
                    ) {
                        Text("Sim, Finalizar", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFinishEarlyDialog = false }) {
                        Text("Continuar Treinando", color = TextSecondary)
                    }
                }
            )
        }

        if (directInputConfig != null) {
            val config = directInputConfig!!
            DirectNumericInputSheet(
                title = config.title,
                initialValue = config.initialValue,
                isDecimal = config.isDecimal,
                unitLabel = config.unitLabel,
                onConfirm = { newValue ->
                    config.onConfirm(newValue)
                    directInputConfig = null
                },
                onDismiss = { directInputConfig = null }
            )
        }
    }
}

data class DirectInputConfig(
    val title: String,
    val initialValue: String,
    val isDecimal: Boolean,
    val unitLabel: String,
    val onConfirm: (Float) -> Unit
)

@Composable
fun AdjacentExercisePreview(
    exercise: ExerciseSessionWithSets,
    index: Int,
    total: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = Lime400.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Lime400.copy(alpha = 0.3f))
        ) {
            Text(
                text = "EXERCÍCIO ${index + 1} DE $total",
                color = Lime400,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = exercise.exerciseSession.exerciseNameSnapshot,
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        if (!exercise.exerciseSession.machineLabelSnapshot.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = exercise.exerciseSession.machineLabelSnapshot!!,
                color = Lime400,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else if (!exercise.exerciseSession.primaryMuscleSnapshot.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = exercise.exerciseSession.primaryMuscleSnapshot!!,
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "${exercise.sets.size} séries planejadas · ${exercise.sets.count { it.completed }} concluídas",
            color = TextSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
fun FocusedActiveSetView(
    currentEx: ExerciseSessionWithSets,
    activeSet: SetLogEntity,
    activeSetIndex: Int,
    totalSets: Int,
    currentExerciseIndex: Int,
    totalExercises: Int,
    resolvedExercise: com.example.domain.model.ResolvedExercise?,
    premiumInfo: com.example.presentation.exercises.PremiumExerciseInfo?,
    previousExecutionSets: List<SetLogEntity>,
    exerciseExecutionContext: ExerciseExecutionContext? = null,
    lastSetFeedback: com.example.presentation.execution.SetCompletionFeedback? = null,
    isLastExercise: Boolean = false,
    rirRpeEnabled: Boolean,
    hapticEnabled: Boolean,
    showGifs: Boolean,
    showCoachTip: Boolean,
    isOrderAdapted: Boolean = false,
    onUpdateSet: (SetLogEntity) -> Unit,
    onCompleteSet: (SetLogEntity) -> Unit,
    onDismissFeedback: () -> Unit = {},
    onOpenDirectInput: (DirectInputConfig) -> Unit,
    onOpenExerciseSelector: () -> Unit,
    onOpenQuickInfo: () -> Unit,
    onOpenFullDetails: () -> Unit,
    onNextExercise: () -> Unit,
    onPreviousExercise: () -> Unit,
    onViewAllSets: () -> Unit,
    onViewLastWorkout: () -> Unit,
    onOpenSyncSheet: () -> Unit,
    onReplicateCurrentSet: (SetLogEntity) -> Unit
) {
    val primaryMuscle = currentEx.exerciseSession.primaryMuscleSnapshot ?: resolvedExercise?.primaryMuscle
    val mediaUrl = resolvedExercise?.resolvedMedia?.mediaUri
    val equipment = resolvedExercise?.rawExercise?.equipment ?: currentEx.exerciseSession.machineLabelSnapshot
    val difficulty = resolvedExercise?.rawExercise?.difficulty

    var currentWeight by remember(activeSet.id) { mutableFloatStateOf(activeSet.weight) }
    var currentReps by remember(activeSet.id) { mutableIntStateOf(activeSet.repetitions) }
    var currentRir by remember(activeSet.id) { mutableStateOf(activeSet.rir) }
    var showReplicateConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(activeSet.id, activeSet.weight, activeSet.repetitions, activeSet.rir) {
        currentWeight = activeSet.weight
        currentReps = activeSet.repetitions
        currentRir = activeSet.rir
    }

    val scrollState = rememberScrollState()

    val isBodyweight = resolvedExercise?.rawExercise?.isBodyweight == true ||
        resolvedExercise?.rawExercise?.equipment?.lowercase()?.contains("body") == true ||
        resolvedExercise?.rawExercise?.equipment?.lowercase()?.contains("corporal") == true ||
        currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("flexão") ||
        currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("barra fixa") ||
        currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("paralelas") ||
        currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("abdominal") ||
        currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("prancha") ||
        currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("peso corporal")

    val actionButtonText = when {
        isLastExercise && activeSetIndex == totalSets - 1 -> "CONCLUIR TREINO"
        activeSetIndex == totalSets - 1 -> "CONCLUIR E IR PARA PRÓXIMO"
        else -> "CONCLUIR SÉRIE ${activeSetIndex + 1}/$totalSets"
    }

    if (showReplicateConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showReplicateConfirmDialog = false },
            title = {
                Text(
                    text = "Sincronizar séries?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                val formattedWeight = if (currentWeight % 1f == 0f) "${currentWeight.toInt()}kg" else "${currentWeight}kg"
                Text(
                    text = "Deseja sincronizar $formattedWeight e $currentReps reps para as próximas séries deste exercício?",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReplicateConfirmDialog = false
                        onReplicateCurrentSet(activeSet.copy(weight = currentWeight, repetitions = currentReps, rir = currentRir))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Sincronizar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplicateConfirmDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(16.dp)
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactLayout = LocalDensity.current.fontScale >= 1.5f || maxHeight < 560.dp

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Scrollable Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Exercise Name (Header)
                Text(
                    text = currentEx.exerciseSession.exerciseNameSnapshot,
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("exercise_execution_header")
                )

                // Media em linha exclusiva + Card de informações contextuais
                ExerciseMediaCompact(
                    mediaUrl = mediaUrl,
                    primaryMuscle = primaryMuscle,
                    equipment = equipment,
                    difficulty = difficulty,
                    nameEn = resolvedExercise?.nameEn,
                    onClick = onOpenFullDetails
                )

                // 1. Set Badge and Actions (SÉRIE X DE Y, Sincronizar séries & Ver todas)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("set_actions_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Lime400.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Lime400.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onViewAllSets)
                            .testTag("set_badge")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                                tint = Lime400,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "SÉRIE ${activeSetIndex + 1} DE $totalSets (${currentEx.sets.count { it.completed }}/$totalSets)",
                                color = Lime400,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (totalSets > 1) {
                            Surface(
                                color = SurfaceDark,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, BorderLight),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showReplicateConfirmDialog = true }
                                    .testTag("sync_sets_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = "Sincronizar séries",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Sincronizar séries",
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Ver todas",
                            color = Lime400,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onViewAllSets)
                                .padding(4.dp)
                        )
                    }
                }

                // 2. Performance Card (Última vez + Recorde)
                ExercisePerformanceCard(
                    context = exerciseExecutionContext,
                    onViewHistory = onViewLastWorkout,
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. Target Card (Meta da série)
                if (exerciseExecutionContext?.suggestedLoad != null || exerciseExecutionContext?.targetReps != null) {
                    ExerciseTargetCard(
                        context = exerciseExecutionContext,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 4. Feedback Banner (se houver feedback ativo)
                SetFeedbackBanner(
                    feedback = lastSetFeedback,
                    onDismiss = onDismissFeedback,
                    modifier = Modifier.fillMaxWidth()
                )

                // 5. Coach Tip (se habilitado)
                if (showCoachTip) {
                    val coachTip = parseJsonListFirst(premiumInfo?.education?.tips) ?: parseJsonListFirst(premiumInfo?.education?.coachNotes)
                    val warningText = parseJsonListFirst(premiumInfo?.education?.commonMistakes) ?: parseJsonListFirst(premiumInfo?.safety?.attentionPoints)

                    QuickCoachTip(
                        coachTip = coachTip,
                        warningText = warningText,
                        onOpenQuickInfoSheet = onOpenQuickInfo
                    )
                }

                // If compact layout, place effort selector inside scroll
                if (compactLayout && rirRpeEnabled) {
                    RirSelector(
                        currentRir = currentRir,
                        onRirSelected = { newRir ->
                            currentRir = newRir
                            onUpdateSet(activeSet.copy(weight = currentWeight, repetitions = currentReps, rir = newRir))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Anchored Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("active_set_controls"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Vertical Scroll Wheel Pickers (Carga & Reps)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Carga Wheel Picker
                    WeightWheelPicker(
                        value = currentWeight,
                        step = 0.5f,
                        minWeight = 0f,
                        maxWeight = 500f,
                        label = if (isBodyweight) "Carga Adicional" else "Carga",
                        hapticEnabled = hapticEnabled,
                        onValueSettled = { newWeight ->
                            currentWeight = newWeight
                            onUpdateSet(activeSet.copy(weight = newWeight, repetitions = currentReps, rir = currentRir))
                        },
                        onDirectInputRequest = {
                            onOpenDirectInput(
                                DirectInputConfig(
                                    title = if (isBodyweight) "Carga Adicional (kg)" else "Ajustar Carga (kg)",
                                    initialValue = if (currentWeight % 1f == 0f) currentWeight.toInt().toString() else currentWeight.toString(),
                                    isDecimal = true,
                                    unitLabel = "kg",
                                    onConfirm = { newWeight ->
                                        currentWeight = newWeight
                                        onUpdateSet(activeSet.copy(weight = newWeight, repetitions = currentReps, rir = currentRir))
                                    }
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // Reps Wheel Picker
                    RepWheelPicker(
                        value = currentReps,
                        minReps = 1,
                        maxReps = 100,
                        hapticEnabled = hapticEnabled,
                        onValueSettled = { newReps ->
                            currentReps = newReps
                            onUpdateSet(activeSet.copy(weight = currentWeight, repetitions = newReps, rir = currentRir))
                        },
                        onDirectInputRequest = {
                            onOpenDirectInput(
                                DirectInputConfig(
                                    title = "Ajustar Repetições",
                                    initialValue = currentReps.toString(),
                                    isDecimal = false,
                                    unitLabel = "reps",
                                    onConfirm = { newReps ->
                                        val r = newReps.toInt().coerceAtLeast(1)
                                        currentReps = r
                                        onUpdateSet(activeSet.copy(weight = currentWeight, repetitions = r, rir = currentRir))
                                    }
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Effort selector if not compact
                if (!compactLayout && rirRpeEnabled) {
                    RirSelector(
                        currentRir = currentRir,
                        onRirSelected = { newRir ->
                            currentRir = newRir
                            onUpdateSet(activeSet.copy(weight = currentWeight, repetitions = currentReps, rir = newRir))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Action Button (with contextual copy)
                WorkoutActionButton(
                    onClick = {
                        onCompleteSet(activeSet.copy(weight = currentWeight, repetitions = currentReps, rir = currentRir))
                    },
                    text = actionButtonText,
                    hapticEnabled = hapticEnabled
                )
            }
        }
    }
}

private fun parseJsonListFirst(jsonStr: String?): String? {
    if (jsonStr.isNullOrBlank()) return null
    val trimmed = jsonStr.trim()
    return try {
        if (trimmed.startsWith("[")) {
            val array = org.json.JSONArray(trimmed)
            if (array.length() == 0) return null
            val obj = array.optJSONObject(0)
            if (obj != null) {
                extractFirstTextFromJson(obj)
            } else {
                val firstItem = array.getString(0)
                parseRawStringOrJson(firstItem)
            }
        } else if (trimmed.startsWith("{")) {
            extractFirstTextFromJson(org.json.JSONObject(trimmed))
        } else {
            trimmed
        }
    } catch (e: Exception) {
        trimmed
    }
}

private fun extractFirstTextFromJson(obj: org.json.JSONObject): String {
    val keys = listOf("mistake", "point", "tip", "text", "title", "description", "note", "instruction", "reason")
    for (key in keys) {
        val value = obj.optString(key)
        if (!value.isNullOrBlank()) return value
    }
    return obj.toString()
}

private fun parseRawStringOrJson(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{")) {
        return try {
            extractFirstTextFromJson(org.json.JSONObject(trimmed))
        } catch (e: Exception) {
            trimmed
        }
    }
    return trimmed
}

@Composable
fun FocusedRestView(
    targetTime: Long,
    onAdd15s: () -> Unit,
    onAdd30s: () -> Unit,
    onSkip: () -> Unit,
    nextExerciseName: String,
    nextSetIndex: Int,
    nextSetWeight: Float,
    nextSetReps: Int,
    isPreparingNextExercise: Boolean = false,
    nextMachineLabel: String? = null,
    totalSets: Int = 1
) {
    var timeLeft by remember { mutableStateOf(0L) }

    LaunchedEffect(targetTime) {
        while (true) {
            val remaining = (targetTime - System.currentTimeMillis()) / 1000
            if (remaining <= 0) {
                timeLeft = 0
                if (isPreparingNextExercise) {
                    onSkip()
                }
                break
            }
            timeLeft = remaining
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("focused_rest_view"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = if (isPreparingNextExercise) Lime400.copy(alpha = 0.15f) else SurfaceDark,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, if (isPreparingNextExercise) Lime400.copy(alpha = 0.4f) else BorderLight)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    if (isPreparingNextExercise) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Série concluída ✓",
                            color = Lime400,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Descanso iniciado",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isPreparingNextExercise) "PREPARANDO PRÓXIMO EXERCÍCIO" else "TEMPO DE DESCANSO",
                color = Lime400,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = String.format("%02d:%02d", (timeLeft.coerceAtLeast(0)) / 60, (timeLeft.coerceAtLeast(0)) % 60),
                color = TextPrimary,
                fontSize = 72.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onAdd15s,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("+15s") }

                Button(
                    onClick = onAdd30s,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("+30s") }

                Button(
                    onClick = onSkip,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPreparingNextExercise) Lime400 else Emerald500.copy(alpha = 0.2f),
                        contentColor = if (isPreparingNextExercise) BackgroundDark else Emerald500
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isPreparingNextExercise) "COMEÇAR AGORA" else "PULAR",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Next set card / Próximo Exercício
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, if (isPreparingNextExercise) Lime400.copy(alpha = 0.4f) else BorderLight, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isPreparingNextExercise) "PRÓXIMO EXERCÍCIO" else "PRÓXIMA SÉRIE",
                color = if (isPreparingNextExercise) Lime400 else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = nextExerciseName,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (isPreparingNextExercise) {
                if (!nextMachineLabel.isNullOrBlank()) {
                    Text(
                        text = nextMachineLabel,
                        color = Lime400,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "Série 1",
                        color = Lime400,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Série $nextSetIndex de $totalSets",
                        color = Lime400,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (nextSetWeight > 0f || nextSetReps > 0) {
                        Text(
                            text = " · ${if (nextSetWeight % 1f == 0f) nextSetWeight.toInt() else nextSetWeight}kg × $nextSetReps reps",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FocusedExerciseTransitionView(
    completedExerciseName: String,
    completedSetsCount: Int,
    totalSetsCount: Int,
    nextExerciseName: String?,
    nextMachineLabel: String?,
    nextPrimaryMuscle: String?,
    recommendedRestSeconds: Int = 90,
    onStartRest: (Int) -> Unit = {},
    onStartNext: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.5f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "exCompleteScale"
    )
    val opacity by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppMotion.Emphasized, easing = AppMotion.StandardEasing),
        label = "exCompleteOpacity"
    )

    val formattedRest = remember(recommendedRestSeconds) {
        String.format("%02d:%02d", (recommendedRestSeconds.coerceAtLeast(0)) / 60, (recommendedRestSeconds.coerceAtLeast(0)) % 60)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(scaleX = scale, scaleY = scale, alpha = opacity)
            .testTag("exercise_transition_view"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Surface(
                color = Lime400.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Lime400.copy(alpha = 0.4f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Lime400,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Série concluída ✓",
                        color = Lime400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Preparando próximo exercício", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "$completedExerciseName ($completedSetsCount/$totalSetsCount séries)",
                color = TextSecondary,
                fontSize = 15.sp
            )
        }

        if (nextExerciseName != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(1.dp, Lime400.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("PRÓXIMO EXERCÍCIO", color = Lime400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = nextExerciseName,
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                if (!nextMachineLabel.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = nextMachineLabel, color = Lime400, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                } else if (!nextPrimaryMuscle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = nextPrimaryMuscle, color = TextSecondary, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = BackgroundDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Descanso recomendado: $formattedRest",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onStartRest(recommendedRestSeconds) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("start_rest_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
                ) {
                    Icon(Icons.Default.HourglassEmpty, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Iniciar descanso", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onStartNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("start_next_button"),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Lime400.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime400)
                ) {
                    Text("Começar próximo", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun FocusedWorkoutCompleteView(
    sessionName: String,
    totalExercises: Int,
    totalCompletedSets: Int,
    onFinishWorkout: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.4f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "workoutCompleteScale"
    )
    val opacity by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppMotion.Emphasized, easing = AppMotion.StandardEasing),
        label = "workoutCompleteOpacity"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(scaleX = scale, scaleY = scale, alpha = opacity)
            .testTag("workout_complete_view"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Lime400, modifier = Modifier.size(88.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Text("Treino finalizado 🎉", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Resumo do treino", color = Lime400, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$totalExercises", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Exercícios", color = TextSecondary, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$totalCompletedSets", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Séries", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        Button(
            onClick = onFinishWorkout,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .testTag("finish_workout_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
        ) {
            Text("Ver evolução", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllSetsBottomSheet(
    exerciseName: String,
    sets: List<SetLogEntity>,
    rirRpeEnabled: Boolean,
    onDismiss: () -> Unit,
    onUpdateSet: (SetLogEntity) -> Unit,
    onToggleComplete: (SetLogEntity) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (SetLogEntity) -> Unit,
    onOpenSyncSheet: () -> Unit
) {
    var editingSet by remember { mutableStateOf<SetLogEntity?>(null) }

    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Todas as Séries",
        subtitle = exerciseName,
        headerRightContent = {
            IconButton(onClick = onOpenSyncSheet) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Sincronizar exercício",
                    tint = Lime400
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(sets) { index, setLog ->
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (setLog.completed) Emerald500.copy(alpha = 0.5f) else BorderLight),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingSet = setLog }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left: Check status + Set Number + Load/Reps + RIR
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                IconButton(
                                    onClick = { onToggleComplete(setLog) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (setLog.completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = "Concluir série",
                                        tint = if (setLog.completed) Emerald500 else TextSecondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Série ${index + 1}",
                                            color = if (setLog.completed) Emerald500 else TextSecondary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )

                                        if (setLog.completed) {
                                            Surface(
                                                color = Emerald500.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "Concluída",
                                                    color = Emerald500,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    val weightStr = if (setLog.weight % 1f == 0f) "${setLog.weight.toInt()}" else "${setLog.weight}"
                                    Text(
                                        text = "${weightStr}kg  ×  ${setLog.repetitions} reps",
                                        color = TextPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Black
                                    )

                                    if (rirRpeEnabled) {
                                        val effortText = when (setLog.rir) {
                                            0 -> "🔥 Falha (RIR 0)"
                                            1 -> "😤 Muito pesado (RIR 1)"
                                            2 -> "💪 Pesado (RIR 2)"
                                            3 -> "🙂 Controlado (RIR 3+)"
                                            else -> "Sem esforço registrado"
                                        }
                                        Text(
                                            text = effortText,
                                            color = if (setLog.rir == 0) Color(0xFFFFB74D) else if (setLog.rir != null) Lime400 else TextSecondary.copy(alpha = 0.6f),
                                            fontSize = 12.sp,
                                            fontWeight = if (setLog.rir != null) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
                            }

                            // Right: Edit and Delete actions
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = BackgroundDark,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, BorderLight),
                                    modifier = Modifier.clickable { editingSet = setLog }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = Lime400, modifier = Modifier.size(14.dp))
                                        Text("Editar", color = Lime400, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                if (sets.size > 1) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { onRemoveSet(setLog) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Excluir série",
                                            tint = Color.Red.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onAddSet,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime400.copy(alpha = 0.18f), contentColor = Lime400),
                border = BorderStroke(1.dp, Lime400.copy(alpha = 0.4f))
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Adicionar Série", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // Modal Edit Set Dialog
    editingSet?.let { activeEditingSet ->
        var currentWeight by remember(activeEditingSet.id) { mutableFloatStateOf(activeEditingSet.weight) }
        var currentReps by remember(activeEditingSet.id) { mutableIntStateOf(activeEditingSet.repetitions) }
        var currentRir by remember(activeEditingSet.id) { mutableStateOf(activeEditingSet.rir) }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingSet = null },
            title = {
                Text(
                    text = "Editar Série ${activeEditingSet.setNumber}",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Carga Control
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Carga (kg)", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { currentWeight = (currentWeight - 2.5f).coerceAtLeast(0f) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Text("-2.5", color = Lime400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { currentWeight = (currentWeight - 0.5f).coerceAtLeast(0f) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Menos", tint = TextPrimary)
                            }
                            Text(
                                text = "${if (currentWeight % 1f == 0f) currentWeight.toInt() else currentWeight} kg",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                            IconButton(
                                onClick = { currentWeight += 0.5f },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Mais", tint = TextPrimary)
                            }
                            IconButton(
                                onClick = { currentWeight += 2.5f },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Text("+2.5", color = Lime400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Repetições Control
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Repetições", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { currentReps = (currentReps - 1).coerceAtLeast(1) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Menos reps", tint = TextPrimary)
                            }
                            Text(
                                text = "$currentReps reps",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                            IconButton(
                                onClick = { currentReps += 1 },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Mais reps", tint = TextPrimary)
                            }
                        }
                    }

                    // RIR Selector
                    if (rirRpeEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Esforço (RIR)", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            com.example.ui.components.RirSelector(
                                currentRir = currentRir,
                                onRirSelected = { currentRir = it }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateSet(activeEditingSet.copy(weight = currentWeight, repetitions = currentReps, rir = currentRir))
                        editingSet = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
                ) {
                    Text("Salvar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSet = null }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutExercisesBottomSheet(
    exercises: List<ExerciseSessionWithSets>,
    exerciseExecutions: List<com.example.domain.workout.execution.WorkoutExerciseExecution>,
    currentIndex: Int,
    isOrderAdapted: Boolean,
    onDismiss: () -> Unit,
    onSelectExercise: (Int) -> Unit,
    onMoveExerciseToLater: (exerciseId: String) -> Unit,
    onRequestReorderPosition: (execution: com.example.domain.workout.execution.WorkoutExerciseExecution) -> Unit
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Exercícios do Treino"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
        ) {
            if (isOrderAdapted) {
                Surface(
                    color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "A ordem dos exercícios foi adaptada dinamicamente.",
                            color = Color(0xFFF59E0B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(exercises) { idx, ex ->
                    val isCurrent = idx == currentIndex
                    val completedCount = ex.sets.count { it.completed }
                    val totalSets = ex.sets.size
                    val isAllCompleted = completedCount == totalSets && totalSets > 0
                    val executionModel = exerciseExecutions.getOrNull(idx)
                    var menuExpanded by remember { mutableStateOf(false) }

                    Surface(
                        color = if (isCurrent) Lime400.copy(alpha = 0.1f) else BackgroundDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            when {
                                isCurrent -> Lime400
                                isAllCompleted -> Emerald500.copy(alpha = 0.4f)
                                else -> BorderLight
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectExercise(idx) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${idx + 1}",
                                color = if (isCurrent) Lime400 else TextSecondary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ex.exerciseSession.exerciseNameSnapshot,
                                    color = if (isCurrent) Lime400 else TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                )
                                if (!ex.exerciseSession.machineLabelSnapshot.isNullOrBlank()) {
                                    Text(ex.exerciseSession.machineLabelSnapshot!!, color = Lime400, fontSize = 12.sp)
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$completedCount/$totalSets",
                                color = if (isAllCompleted) Emerald500 else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (executionModel != null) {
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Opções do exercício",
                                            tint = TextSecondary
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        modifier = Modifier.background(SurfaceDark)
                                    ) {
                                        val isCompleted = executionModel.status == com.example.domain.workout.execution.ExerciseExecutionStatus.COMPLETED

                                        if (!isCompleted) {
                                            DropdownMenuItem(
                                                text = { Text(if (isCurrent) "Fazer depois" else "Mover para depois", color = TextPrimary) },
                                                onClick = {
                                                    menuExpanded = false
                                                    onMoveExerciseToLater(executionModel.exerciseId)
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = Lime400)
                                                }
                                            )
                                        }

                                        DropdownMenuItem(
                                            text = { Text("Alterar posição", color = TextPrimary) },
                                            onClick = {
                                                menuExpanded = false
                                                onRequestReorderPosition(executionModel)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.SwapVert, contentDescription = null, tint = Lime400)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviousExecutionBottomSheet(
    exerciseName: String,
    previousSets: List<SetLogEntity>,
    summary: com.example.domain.workout.execution.ExercisePerformanceSummary? = null,
    onDismiss: () -> Unit
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Última Execução",
        subtitle = exerciseName
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // General history summary (PARTE 8)
            if (summary != null && (summary.maxWeight != null || summary.maxVolume != null || summary.totalExecutions > 0)) {
                Surface(
                    color = SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Histórico Geral",
                            color = Lime400,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            if (summary.maxWeight != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Maior carga", color = TextSecondary, fontSize = 11.sp)
                                    val wStr = if (summary.maxWeight % 1f == 0f) "${summary.maxWeight.toInt()}" else "${summary.maxWeight}"
                                    Text("${wStr}kg", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (summary.maxVolume != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Maior volume", color = TextSecondary, fontSize = 11.sp)
                                    val vStr = if (summary.maxVolume % 1f == 0f) "${summary.maxVolume.toInt()}" else "${summary.maxVolume}"
                                    Text("${vStr}kg", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (summary.totalExecutions > 0) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Execuções", color = TextSecondary, fontSize = 11.sp)
                                    Text("${summary.totalExecutions}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            if (previousSets.isEmpty()) {
                Text("Nenhum registro anterior encontrado para este exercício.", color = TextSecondary, fontSize = 14.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    previousSets.forEach { setLog ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BackgroundDark)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Série ${setLog.setNumber}", color = TextSecondary, fontSize = 14.sp)
                            val rirTag = RirFormatter.formatEffort(setLog.rir, short = true)
                            val rirSuffix = if (rirTag != null) " · $rirTag" else ""
                            Text(
                                text = "${if (setLog.weight % 1f == 0f) setLog.weight.toInt() else setLog.weight} kg  ×  ${setLog.repetitions} reps$rirSuffix",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseInstructionsBottomSheet(
    resolvedExercise: com.example.domain.model.ResolvedExercise?,
    exerciseName: String,
    onDismiss: () -> Unit
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Como Executar",
        subtitle = exerciseName
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
        ) {
            val mediaUrl = resolvedExercise?.resolvedMedia?.mediaUri
            if (!mediaUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D0D0E)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = mediaUrl,
                        contentDescription = "Instrução do Exercício",
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        alignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (!resolvedExercise?.notes.isNullOrBlank()) {
                Text("Instruções / Notas:", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(resolvedExercise!!.notes!!, color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            } else {
                Text("Informações do Exercício:", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Músculo Principal: ${resolvedExercise?.primaryMuscle ?: "N/I"}", color = TextSecondary, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlternativesBottomSheet(
    alternatives: List<com.example.domain.model.ResolvedExercise>,
    onDismiss: () -> Unit,
    onSwap: (Long) -> Unit
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Máquina Ocupada / Alternativa",
        subtitle = "Escolha um exercício alternativo:"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(alternatives, key = { it.id }) { alt ->
                    val altGroup = MuscleVisualResolver.resolveGroup(alt.primaryMuscle ?: "")
                    Surface(
                        color = BackgroundDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderLight),
                        modifier = Modifier.fillMaxWidth().clickable { onSwap(alt.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(altGroup.icon, contentDescription = null, tint = altGroup.color, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(alt.displayName, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(alt.primaryMuscle ?: "", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
