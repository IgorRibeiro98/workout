package com.example.presentation.execution

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SetLogEntity
import com.example.data.local.SetType
import com.example.domain.engine.MuscleVisualResolver
import com.example.domain.engine.RirFormatter
import com.example.R
import com.example.ui.components.DirectNumericInputSheet
import com.example.ui.components.RepsWheelPicker
import com.example.ui.components.RirSelector
import com.example.ui.components.SyncExerciseSheet
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.components.ActionBottomSheet
import com.example.ui.components.ActionItemData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.example.components.workout.execution.ExerciseExecutionHeader
import com.example.components.workout.execution.ExerciseMediaCompact
import com.example.components.workout.execution.WeightWheelPicker
import com.example.components.workout.execution.RepWheelPicker
import com.example.components.workout.execution.RirSelector
import com.example.components.workout.execution.QuickCoachTip
import com.example.components.workout.execution.ExerciseQuickInfoSheet
import com.example.components.workout.execution.WorkoutActionButton
import com.example.ui.theme.*

sealed interface WorkoutSheet {
    data object OptionsMenu : WorkoutSheet
    data object AllSets : WorkoutSheet
    data object Sync : WorkoutSheet
    data object ExercisesList : WorkoutSheet
    data object LastWorkout : WorkoutSheet
    data object Instructions : WorkoutSheet
    data object Alternatives : WorkoutSheet
    data object QuickInfo : WorkoutSheet
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

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Bottom sheet & Dialog states
    var activeSheet by remember { mutableStateOf<WorkoutSheet?>(null) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showFinishEarlyDialog by remember { mutableStateOf(false) }
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
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("Nenhum exercício encontrado.", color = TextSecondary)
            }
        }
        return
    }

    val totalSetsAcrossWorkout = remember(session) {
        session.exercises.sumOf { it.sets.size }.coerceAtLeast(1)
    }
    val completedSetsCount = remember(session) {
        session.exercises.sumOf { ex -> ex.sets.count { it.completed } }
    }
    val targetProgress = (completedSetsCount.toFloat() / totalSetsAcrossWorkout).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(
            durationMillis = AppMotion.Emphasized,
            easing = AppMotion.StandardEasing
        ),
        label = "workoutProgressBar"
    )

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
                            Text(
                                text = sessionName.uppercase(),
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
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
                        IconButton(onClick = { activeSheet = WorkoutSheet.OptionsMenu }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu do treino", tint = TextPrimary)
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
                        FocusedRestView(
                            targetTime = timerTarget ?: System.currentTimeMillis(),
                            onAdd15s = { viewModel.adjustRestTimer(15) },
                            onAdd30s = { viewModel.adjustRestTimer(30) },
                            onSkip = { viewModel.skipRestTimer() },
                            nextExerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                            nextSetIndex = (state.activeSetIndex ?: 0) + 1,
                            nextSetWeight = state.activeSet?.weight ?: 0f,
                            nextSetReps = state.activeSet?.repetitions ?: 0
                        )
                    }
                    ExecutionPhase.ACTIVE_SET -> {
                        val activeSet = state.activeSet ?: currentEx.sets.firstOrNull()
                        if (activeSet != null) {
                            AnimatedContent(
                                targetState = Pair(state.currentExerciseIndex, state.activeSetIndex ?: 0),
                                transitionSpec = {
                                    if (targetState.first != initialState.first) {
                                        if (targetState.first > initialState.first) {
                                            (slideInHorizontally(animationSpec = tween(280, easing = AppMotion.StandardEasing)) { width -> width / 3 } +
                                             fadeIn(animationSpec = tween(280))) togetherWith
                                            (slideOutHorizontally(animationSpec = tween(220, easing = AppMotion.DecelerateEasing)) { width -> -width / 3 } +
                                             fadeOut(animationSpec = tween(220)))
                                        } else {
                                            (slideInHorizontally(animationSpec = tween(280, easing = AppMotion.StandardEasing)) { width -> -width / 3 } +
                                             fadeIn(animationSpec = tween(280))) togetherWith
                                            (slideOutHorizontally(animationSpec = tween(220, easing = AppMotion.DecelerateEasing)) { width -> width / 3 } +
                                             fadeOut(animationSpec = tween(220)))
                                        }
                                    } else {
                                        (fadeIn(animationSpec = tween(250, easing = AppMotion.StandardEasing)) +
                                         scaleIn(initialScale = 0.96f, animationSpec = tween(250, easing = AppMotion.StandardEasing))) togetherWith
                                        (fadeOut(animationSpec = tween(200, easing = AppMotion.DecelerateEasing)) +
                                         scaleOut(targetScale = 1.03f, animationSpec = tween(200, easing = AppMotion.DecelerateEasing)))
                                    }
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
                                    rirRpeEnabled = rirRpeEnabled,
                                    hapticEnabled = hapticEnabled,
                                    showGifs = showGifs,
                                    onUpdateSet = { viewModel.updateSet(it) },
                                    onCompleteSet = {
                                        if (hapticEnabled) {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        }
                                        viewModel.completeSet(it)
                                    },
                                    onOpenDirectInput = { config -> directInputConfig = config },
                                    onOpenExerciseSelector = { activeSheet = WorkoutSheet.ExercisesList },
                                    onOpenQuickInfo = { activeSheet = WorkoutSheet.QuickInfo },
                                    onOpenFullDetails = {
                                        val exId = currentEx.exerciseSession.actualExerciseId ?: currentEx.exerciseSession.plannedExerciseId
                                        if (exId != null && onNavigateToExerciseDetails != null) {
                                            onNavigateToExerciseDetails(exId, currentEx.exerciseSession.exerciseNameSnapshot)
                                        } else {
                                            activeSheet = WorkoutSheet.Instructions
                                        }
                                    },
                                    onNextExercise = { viewModel.nextExercise() },
                                    onPreviousExercise = { viewModel.previousExercise() },
                                    onViewAllSets = { activeSheet = WorkoutSheet.AllSets },
                                    onViewLastWorkout = { activeSheet = WorkoutSheet.LastWorkout },
                                    onOpenSyncSheet = { activeSheet = WorkoutSheet.Sync }
                                )
                            }
                        }
                    }
                    ExecutionPhase.EXERCISE_TRANSITION -> {
                        val nextExSession = session.exercises.getOrNull(state.currentExerciseIndex + 1)
                        FocusedExerciseTransitionView(
                            completedExerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                            completedSetsCount = currentEx.sets.count { it.completed },
                            totalSetsCount = currentEx.sets.size,
                            nextExerciseName = nextExSession?.exerciseSession?.exerciseNameSnapshot,
                            nextMachineLabel = nextExSession?.exerciseSession?.machineLabelSnapshot,
                            nextPrimaryMuscle = nextExSession?.exerciseSession?.primaryMuscleSnapshot,
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

        // --- Single Controlled Active Bottom Sheet ---
        when (activeSheet) {
            WorkoutSheet.OptionsMenu -> {
                ActionBottomSheet(
                    onDismissRequest = { activeSheet = null },
                    title = stringResource(id = R.string.sheet_workout_options),
                    actions = listOf(
                        ActionItemData(
                            title = stringResource(id = R.string.sheet_action_instructions),
                            icon = Icons.Default.Info,
                            onClick = { activeSheet = WorkoutSheet.Instructions }
                        ),
                        ActionItemData(
                            title = stringResource(id = R.string.sheet_action_swap),
                            icon = Icons.Default.SwapHoriz,
                            onClick = {
                                activeSheet = WorkoutSheet.Alternatives
                                viewModel.loadAlternatives()
                            }
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
                    currentIndex = state.currentExerciseIndex,
                    onDismiss = { activeSheet = null },
                    onSelectExercise = { index ->
                        viewModel.selectExercise(index)
                        activeSheet = null
                    }
                )
            }
            WorkoutSheet.LastWorkout -> {
                PreviousExecutionBottomSheet(
                    exerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                    previousSets = state.previousExecutionSets,
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
            null -> {
                if (alternatives.isNotEmpty()) {
                    AlternativesBottomSheet(
                        alternatives = alternatives,
                        onDismiss = { viewModel.clearAlternatives() },
                        onSwap = { altId -> viewModel.swapCurrentExercise(altId, false) }
                    )
                }
            }
        }

        // --- Dialogs ---
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
    rirRpeEnabled: Boolean,
    hapticEnabled: Boolean,
    showGifs: Boolean,
    onUpdateSet: (SetLogEntity) -> Unit,
    onCompleteSet: (SetLogEntity) -> Unit,
    onOpenDirectInput: (DirectInputConfig) -> Unit,
    onOpenExerciseSelector: () -> Unit,
    onOpenQuickInfo: () -> Unit,
    onOpenFullDetails: () -> Unit,
    onNextExercise: () -> Unit,
    onPreviousExercise: () -> Unit,
    onViewAllSets: () -> Unit,
    onViewLastWorkout: () -> Unit,
    onOpenSyncSheet: () -> Unit
) {
    val machineLabel = currentEx.exerciseSession.machineLabelSnapshot
    val primaryMuscle = currentEx.exerciseSession.primaryMuscleSnapshot ?: resolvedExercise?.primaryMuscle
    val mediaUrl = resolvedExercise?.resolvedMedia?.mediaUri
    val equipment = resolvedExercise?.rawExercise?.equipment ?: currentEx.exerciseSession.machineLabelSnapshot
    val difficulty = resolvedExercise?.rawExercise?.difficulty

    var currentWeight by remember(activeSet.id) { mutableFloatStateOf(activeSet.weight) }
    var currentReps by remember(activeSet.id) { mutableIntStateOf(activeSet.repetitions) }
    var currentRir by remember(activeSet.id) { mutableStateOf(activeSet.rir) }

    LaunchedEffect(activeSet.id, activeSet.weight, activeSet.repetitions, activeSet.rir) {
        currentWeight = activeSet.weight
        currentReps = activeSet.repetitions
        currentRir = activeSet.rir
    }

    var touchOffsetX by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (touchOffsetX < -100f) {
                            onNextExercise()
                        } else if (touchOffsetX > 100f) {
                            onPreviousExercise()
                        }
                        touchOffsetX = 0f
                    },
                    onDragCancel = { touchOffsetX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        touchOffsetX += dragAmount
                    }
                )
            },
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Exercise Execution Header
            ExerciseExecutionHeader(
                exerciseName = currentEx.exerciseSession.exerciseNameSnapshot,
                primaryMuscle = primaryMuscle,
                machineLabel = machineLabel,
                difficulty = difficulty,
                currentExerciseIndex = currentExerciseIndex,
                totalExercises = totalExercises,
                onOpenExerciseSelector = onOpenExerciseSelector,
                onOpenQuickInfo = onOpenQuickInfo,
                onOpenFullDetails = onOpenFullDetails
            )

            // Compact Media
            ExerciseMediaCompact(
                mediaUrl = mediaUrl,
                primaryMuscle = primaryMuscle,
                equipment = equipment,
                difficulty = difficulty,
                onClick = onOpenFullDetails
            )

            // Quick Coach Tip
            val coachTip = parseJsonListFirst(premiumInfo?.education?.tips) ?: parseJsonListFirst(premiumInfo?.education?.coachNotes)
            val warningText = parseJsonListFirst(premiumInfo?.education?.commonMistakes) ?: parseJsonListFirst(premiumInfo?.safety?.attentionPoints)

            QuickCoachTip(
                coachTip = coachTip,
                warningText = warningText,
                onOpenQuickInfoSheet = onOpenQuickInfo
            )

            // Set badge & sync / history summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Lime400.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Lime400.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "SÉRIE ${activeSetIndex + 1} DE $totalSets",
                        color = Lime400,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Lime400.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onOpenSyncSheet)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Sincronizar exercício",
                                tint = Lime400,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(id = R.string.sync_action_label),
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (previousExecutionSets.isNotEmpty()) {
                        val summaryText = previousExecutionSets.take(3).joinToString(" · ") { setLog ->
                            val weightStr = if (setLog.weight % 1f == 0f) setLog.weight.toInt().toString() else setLog.weight.toString()
                            val rirTag = RirFormatter.formatRir(setLog.rir)
                            val rirPart = if (rirTag != null) " $rirTag" else ""
                            "${weightStr}kg×${setLog.repetitions}$rirPart"
                        }
                        Text(
                            text = "Último: $summaryText",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onViewLastWorkout)
                                .padding(4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Wheel Pickers & Adjusters
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isBodyweight = resolvedExercise?.rawExercise?.isBodyweight == true ||
                    resolvedExercise?.rawExercise?.equipment?.lowercase()?.contains("body") == true ||
                    resolvedExercise?.rawExercise?.equipment?.lowercase()?.contains("corporal") == true ||
                    currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("flexão") ||
                    currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("barra fixa") ||
                    currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("paralelas") ||
                    currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("abdominal") ||
                    currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("prancha") ||
                    currentEx.exerciseSession.exerciseNameSnapshot.lowercase().contains("peso corporal")

                if (isBodyweight && currentWeight == 0f) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(120.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceDark)
                            .border(1.dp, BorderLight, RoundedCornerShape(20.dp))
                            .clickable {
                                onOpenDirectInput(
                                    DirectInputConfig(
                                        title = "Carga Adicional (kg)",
                                        initialValue = "0",
                                        isDecimal = true,
                                        unitLabel = "kg",
                                        onConfirm = { newWeight ->
                                            currentWeight = newWeight
                                            onUpdateSet(activeSet.copy(weight = newWeight, repetitions = currentReps, rir = currentRir))
                                        }
                                    )
                                )
                            }
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessibilityNew,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Peso corporal",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "+ Carga adicional",
                            color = Lime400,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        WeightWheelPicker(
                            value = currentWeight,
                            step = 0.5f,
                            minWeight = 0f,
                            maxWeight = 500f,
                            hapticEnabled = hapticEnabled,
                            label = if (isBodyweight) "Carga adicional" else "Carga",
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
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (isBodyweight && currentWeight > 0f) {
                            Text(
                                text = "Limpar carga",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .clickable {
                                        currentWeight = 0f
                                        onUpdateSet(activeSet.copy(weight = 0f, repetitions = currentReps, rir = currentRir))
                                    }
                                    .padding(top = 2.dp)
                            )
                        }
                    }
                }

                RepWheelPicker(
                    value = currentReps,
                    step = 1,
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

            if (rirRpeEnabled) {
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

        // CTA Button
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            WorkoutActionButton(
                onClick = {
                    onCompleteSet(activeSet.copy(weight = currentWeight, repetitions = currentReps, rir = currentRir))
                },
                text = "CONCLUIR SÉRIE",
                hapticEnabled = hapticEnabled
            )

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(
                onClick = onViewAllSets,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Ver todas as séries (${currentEx.sets.count { it.completed }}/$totalSets)",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

private fun parseJsonListFirst(jsonStr: String?): String? {
    if (jsonStr.isNullOrBlank()) return null
    return try {
        val array = org.json.JSONArray(jsonStr)
        if (array.length() > 0) array.getString(0) else null
    } catch (e: Exception) {
        jsonStr
    }
}

@Composable
fun MetricAdjuster(
    label: String,
    value: String,
    unit: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label.uppercase(), color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(
                onClick = onDecrease,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Diminuir", tint = TextPrimary)
            }

            Spacer(modifier = Modifier.width(20.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = TextPrimary,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        color = TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(20.dp))

            IconButton(
                onClick = onIncrease,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Aumentar", tint = TextPrimary)
            }
        }
    }
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
    nextSetReps: Int
) {
    var timeLeft by remember { mutableStateOf(0L) }

    LaunchedEffect(targetTime) {
        while (true) {
            val remaining = (targetTime - System.currentTimeMillis()) / 1000
            if (remaining <= 0) {
                timeLeft = 0
                break
            }
            timeLeft = remaining
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("TEMPO DE DESCANSO", color = Lime400, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
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
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500.copy(alpha = 0.2f), contentColor = Emerald500),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("PULAR", fontWeight = FontWeight.Bold) }
            }
        }

        // Next set card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("PRÓXIMA SÉRIE", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(nextExerciseName, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Série $nextSetIndex", color = Lime400, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (nextSetWeight > 0f || nextSetReps > 0) {
                    Text(" · ${if (nextSetWeight % 1f == 0f) nextSetWeight.toInt() else nextSetWeight}kg × $nextSetReps reps", color = TextSecondary, fontSize = 14.sp)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(scaleX = scale, scaleY = scale, alpha = opacity),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Emerald500, modifier = Modifier.size(72.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("✓ EXERCÍCIO CONCLUÍDO!", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(4.dp))
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
            }

            Button(
                onClick = onStartNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
            ) {
                Text("COMEÇAR PRÓXIMO", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
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
            .graphicsLayer(scaleX = scale, scaleY = scale, alpha = opacity),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Lime400, modifier = Modifier.size(88.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Text("TREINO CONCLUÍDO!", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(8.dp))
            Text(sessionName, color = Lime400, fontSize = 18.sp, fontWeight = FontWeight.Bold)

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
                .height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
        ) {
            Text("FINALIZAR TREINO", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SecondaryOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    textColor: Color = TextPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = onAddSet,
                colors = ButtonDefaults.buttonColors(containerColor = Lime400.copy(alpha = 0.2f), contentColor = Lime400),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Adicionar Série", fontSize = 13.sp)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(sets) { index, setLog ->
                    Surface(
                        color = BackgroundDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (setLog.completed) Emerald500.copy(alpha = 0.5f) else BorderLight)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { onToggleComplete(setLog) }) {
                                Icon(
                                    if (setLog.completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = "Concluir série",
                                    tint = if (setLog.completed) Emerald500 else TextSecondary
                                )
                            }

                            Text(
                                text = "${index + 1}",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(24.dp)
                            )

                            // Carga
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onUpdateSet(setLog.copy(weight = (setLog.weight - 1f).coerceAtLeast(0f))) }) {
                                    Icon(Icons.Default.Remove, contentDescription = "Menos carga", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                                Text("${if (setLog.weight % 1f == 0f) setLog.weight.toInt() else setLog.weight}kg", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { onUpdateSet(setLog.copy(weight = setLog.weight + 1f)) }) {
                                    Icon(Icons.Default.Add, contentDescription = "Mais carga", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }

                            Text("×", color = TextSecondary, modifier = Modifier.padding(horizontal = 4.dp))

                            // Reps
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onUpdateSet(setLog.copy(repetitions = (setLog.repetitions - 1).coerceAtLeast(0))) }) {
                                    Icon(Icons.Default.Remove, contentDescription = "Menos reps", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                                Text("${setLog.repetitions}", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { onUpdateSet(setLog.copy(repetitions = setLog.repetitions + 1)) }) {
                                    Icon(Icons.Default.Add, contentDescription = "Mais reps", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }

                            if (rirRpeEnabled) {
                                val rirText = RirFormatter.formatRir(setLog.rir)
                                val rirLabel = if (rirText != null) "· $rirText" else "· RIR -"
                                Surface(
                                    color = if (setLog.rir == 0) Color(0xFFFF9800).copy(alpha = 0.2f) else BackgroundDark,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, if (setLog.rir == 0) Color(0xFFFF9800) else BorderLight),
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .clickable {
                                            val nextRir = when (setLog.rir) {
                                                null -> 4
                                                4 -> 3
                                                3 -> 2
                                                2 -> 1
                                                1 -> 0
                                                else -> null
                                            }
                                            onUpdateSet(setLog.copy(rir = nextRir))
                                        }
                                ) {
                                    Text(
                                        text = rirLabel,
                                        color = if (setLog.rir == 0) Color(0xFFFFB74D) else TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = if (setLog.rir == 0) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            if (sets.size > 1) {
                                IconButton(onClick = { onRemoveSet(setLog) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Excluir série", tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
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
fun WorkoutExercisesBottomSheet(
    exercises: List<ExerciseSessionWithSets>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onSelectExercise: (Int) -> Unit
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Exercícios do Treino"
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
                itemsIndexed(exercises) { idx, ex ->
                    val isSelected = idx == currentIndex
                    val completedCount = ex.sets.count { it.completed }
                    val totalSets = ex.sets.size
                    val isAllCompleted = totalSets > 0 && completedCount == totalSets

                    Surface(
                        color = if (isSelected) Lime400.copy(alpha = 0.15f) else BackgroundDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (isSelected) Lime400 else BorderLight),
                        modifier = Modifier.fillMaxWidth().clickable { onSelectExercise(idx) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${idx + 1}.", color = if (isSelected) Lime400 else TextSecondary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ex.exerciseSession.exerciseNameSnapshot, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                            val rirTag = RirFormatter.formatRir(setLog.rir)
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
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BackgroundDark),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = mediaUrl,
                        contentDescription = "Instrução do Exercício",
                        modifier = Modifier.fillMaxSize()
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

