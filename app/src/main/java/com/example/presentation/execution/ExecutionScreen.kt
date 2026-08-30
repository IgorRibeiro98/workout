package com.example.presentation.execution

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SetLogEntity
import com.example.data.local.SetType
import com.example.domain.engine.MuscleVisualResolver
import com.example.domain.engine.ProgressionAction
import com.example.domain.engine.ProgressionEngine
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionScreen(
    viewModel: ExecutionViewModel,
    onNavigateBack: () -> Unit, 
    onFinish: (Long) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val timerTarget by viewModel.restTimerTarget.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val hapticEnabled by viewModel.hapticEnabled.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val preAlertEnabled by viewModel.preAlertEnabled.collectAsState()
    val rirRpeEnabled by viewModel.rirRpeEnabled.collectAsState()
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    
    // Keep Screen On during workout according to user setting
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
    
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    if (state.isLoading || state.sessionWithDetails == null) {
        Box(modifier = Modifier.fillMaxSize().background(BackgroundDark), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Lime400)
        }
        return
    }

    val currentEx = state.currentExercise
    
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        state.sessionWithDetails!!.session.templateNameSnapshot ?: "Treino", 
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                actions = {
                    Button(
                        onClick = { 
                            viewModel.finishSession()
                            val sessionId = state.sessionWithDetails?.session?.id
                            if (sessionId != null) onFinish(sessionId) else onNavigateBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = BackgroundDark),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("FINALIZAR", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.previousExercise() },
                    enabled = !state.isFirstExercise,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(54.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ANTERIOR")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = { viewModel.nextExercise() },
                    enabled = !state.isLastExercise,
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(54.dp)
                ) {
                    Text("PRÓXIMO", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    ) { innerPadding ->
        if (currentEx == null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("Nenhum exercício encontrado.", color = TextSecondary)
            }
            return@Scaffold
        }
        
        val group = MuscleVisualResolver.resolveGroup(currentEx.exerciseSession.primaryMuscleSnapshot)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = SurfaceHighlight,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(group.icon, contentDescription = null, tint = group.color, modifier = Modifier.size(14.dp))
                                Text(
                                    text = "Exercício ${state.currentExerciseIndex + 1} de ${state.sessionWithDetails!!.exercises.size}",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    TextButton(onClick = { viewModel.loadAlternatives() }) {
                        Text("MÁQUINA OCUPADA? TROCAR", color = Lime400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                Text(
                    text = currentEx.exerciseSession.exerciseNameSnapshot,
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 32.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (!currentEx.exerciseSession.machineLabelSnapshot.isNullOrBlank()) {
                    Text(
                        text = "Aparelho: ${currentEx.exerciseSession.machineLabelSnapshot}",
                        color = Lime400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            
            // Progression Recommendation Banner
            if (state.previousExecutionSets.isNotEmpty()) {
                val recommendation = ProgressionEngine.evaluateProgression(
                    currentSets = currentEx.sets,
                    previousSets = state.previousExecutionSets,
                    minTargetReps = 8,
                    maxTargetReps = 12
                )
                item {
                    val bannerBg = when (recommendation.action) {
                        ProgressionAction.INCREASE -> Emerald500.copy(alpha = 0.15f)
                        ProgressionAction.DECREASE -> Red500.copy(alpha = 0.15f)
                        ProgressionAction.MAINTAIN -> SurfaceDark
                    }
                    val bannerBorder = when (recommendation.action) {
                        ProgressionAction.INCREASE -> Emerald500.copy(alpha = 0.4f)
                        ProgressionAction.DECREASE -> Red500.copy(alpha = 0.4f)
                        ProgressionAction.MAINTAIN -> BorderLight
                    }
                    val bannerColor = when (recommendation.action) {
                        ProgressionAction.INCREASE -> Emerald500
                        ProgressionAction.DECREASE -> Red500
                        ProgressionAction.MAINTAIN -> Lime400
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(bannerBg)
                            .border(1.dp, bannerBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = bannerColor, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when (recommendation.action) {
                                        ProgressionAction.INCREASE -> "SUGESTÃO: SUBIR CARGA (+${recommendation.suggestedWeightDelta}kg)"
                                        ProgressionAction.DECREASE -> "SUGESTÃO: REDUZIR CARGA (${recommendation.suggestedWeightDelta}kg)"
                                        ProgressionAction.MAINTAIN -> "SUGESTÃO: MANTER CARGA"
                                    },
                                    color = bannerColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(recommendation.reason, color = TextSecondary, fontSize = 12.sp)
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Último treino: " + state.previousExecutionSets.joinToString(" | ") { "${it.weight}kg × ${it.repetitions}" },
                                color = TextTertiary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Séries de Hoje:",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            items(currentEx.sets, key = { it.id }) { setLog ->
                SetItem(
                    setLog = setLog,
                    rirRpeEnabled = rirRpeEnabled,
                    onUpdate = { viewModel.updateSet(it) },
                    onComplete = { 
                        if (hapticEnabled) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        }
                        viewModel.completeSet(it) 
                    },
                    onUncomplete = { viewModel.uncompleteSet(it) },
                    onRemove = { viewModel.removeSet(it) }
                )
            }
            
            item {
                TextButton(
                    onClick = { viewModel.addSet() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Adicionar", tint = Lime400)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ADICIONAR SÉRIE", color = Lime400, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
        
        // Timer Overlay
        if (timerTarget != null) {
            TimerOverlay(
                targetTime = timerTarget!!,
                soundEnabled = soundEnabled,
                hapticEnabled = hapticEnabled,
                preAlertEnabled = preAlertEnabled,
                onAdd15s = { viewModel.adjustRestTimer(15) },
                onAdd30s = { viewModel.adjustRestTimer(30) },
                onSkip = { viewModel.skipRestTimer() }
            )
        }
        
        // Alternatives Bottom Sheet
        val alternatives by viewModel.alternatives.collectAsState()
        if (alternatives.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.clearAlternatives() },
                containerColor = SurfaceDark,
                dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.7f)
                        .padding(horizontal = 16.dp)
                ) {
                    Text("Máquina ocupada?", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Escolha um exercício alternativo:", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(alternatives, key = { it.id }) { alt ->
                            var showOptions by remember { mutableStateOf(false) }
                            val altGroup = MuscleVisualResolver.resolveGroup(alt.primaryMuscle)
                            
                            Surface(
                                color = BackgroundDark,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showOptions = !showOptions },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(altGroup.icon, contentDescription = null, tint = altGroup.color, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(alt.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                                if (alt.primaryMuscle != null) {
                                                    Text(alt.primaryMuscle, color = TextSecondary, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                        Text(
                                            text = if (showOptions) "Fechar" else "Substituir",
                                            color = Lime400,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    
                                    AnimatedVisibility(visible = showOptions) {
                                        Column {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            HorizontalDivider(color = BorderLight)
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Button(
                                                    onClick = { viewModel.swapCurrentExercise(alt.id, permanent = false) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextPrimary),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("SUBSTITUIR HOJE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Button(
                                                    onClick = { viewModel.swapCurrentExercise(alt.id, permanent = true) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("SEMPRE NA FICHA", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun TimerOverlay(
    targetTime: Long,
    soundEnabled: Boolean = true,
    hapticEnabled: Boolean = true,
    preAlertEnabled: Boolean = true,
    onAdd15s: () -> Unit,
    onAdd30s: () -> Unit,
    onSkip: () -> Unit
) {
    var timeLeft by remember { mutableStateOf(0L) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var preAlertPlayed by remember { mutableStateOf(false) }
    
    LaunchedEffect(targetTime) {
        preAlertPlayed = false
        while(true) {
            val remaining = (targetTime - System.currentTimeMillis()) / 1000
            if (remaining <= 0) {
                // Final alert
                try {
                    if (soundEnabled) {
                        val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                        toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400)
                    }
                    if (hapticEnabled) {
                        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator?.vibrate(500)
                        }
                    }
                } catch (_: Exception) {}
                
                onSkip()
                break
            }

            if (remaining == 10L && preAlertEnabled && !preAlertPlayed) {
                preAlertPlayed = true
                try {
                    if (soundEnabled) {
                        val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
                        toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                    }
                } catch (_: Exception) {}
            }

            timeLeft = remaining
            kotlinx.coroutines.delay(1000)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 80.dp, start = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDark)
                .border(2.dp, Lime400, RoundedCornerShape(24.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TEMPO DE DESCANSO", color = Lime400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format("%02d:%02d", timeLeft / 60, timeLeft % 60),
                color = TextPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = onAdd15s,
                    colors = ButtonDefaults.buttonColors(containerColor = BorderLight, contentColor = TextPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("+15s") }
                
                Button(
                    onClick = onAdd30s,
                    colors = ButtonDefaults.buttonColors(containerColor = BorderLight, contentColor = TextPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("+30s") }
                
                Button(
                    onClick = onSkip,
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500.copy(alpha=0.2f), contentColor = Emerald500),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("PULAR", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun SetItem(
    setLog: SetLogEntity,
    rirRpeEnabled: Boolean = true,
    onUpdate: (SetLogEntity) -> Unit,
    onComplete: (SetLogEntity) -> Unit,
    onUncomplete: (SetLogEntity) -> Unit,
    onRemove: (SetLogEntity) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }

    val bgColor = if (setLog.completed) Emerald500.copy(alpha = 0.15f) else SurfaceDark
    val borderColor = if (setLog.completed) Emerald500.copy(alpha = 0.5f) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Set Number & Type badge
            Column(
                modifier = Modifier.clickable { isEditing = !isEditing },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (setLog.completed) Emerald500 else BorderLight),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${setLog.setNumber}", color = if (setLog.completed) BackgroundDark else TextPrimary, fontWeight = FontWeight.Bold)
                }
                if (setLog.type != SetType.NORMAL.name) {
                    Text(
                        text = when (setLog.type) {
                            SetType.WARMUP.name -> "AQUEC"
                            SetType.DROP_SET.name -> "DROP"
                            SetType.FAILURE.name -> "FALHA"
                            else -> setLog.type.take(5)
                        },
                        color = Lime400,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            
            // Weight
            Column(
                modifier = Modifier.weight(1f).clickable { isEditing = !isEditing },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("kg", color = TextSecondary, fontSize = 11.sp)
                Text(
                    text = "${if (setLog.weight % 1.0f == 0.0f) setLog.weight.toInt() else setLog.weight}", 
                    color = TextPrimary, 
                    fontSize = 24.sp, 
                    fontWeight = FontWeight.Bold
                )
            }
            
            Text("×", color = TextSecondary, fontSize = 18.sp)
            
            // Reps
            Column(
                modifier = Modifier.weight(1f).clickable { isEditing = !isEditing },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("reps", color = TextSecondary, fontSize = 11.sp)
                Text(
                    text = "${setLog.repetitions}", 
                    color = TextPrimary, 
                    fontSize = 24.sp, 
                    fontWeight = FontWeight.Bold
                )
            }

            // RPE / RIR indicator if present
            if (setLog.rpe != null || setLog.rir != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { isEditing = !isEditing }) {
                    setLog.rpe?.let { Text("@$it", color = Lime400, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    setLog.rir?.let { Text("RIR $it", color = TextSecondary, fontSize = 10.sp) }
                }
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            
            // Check Button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (setLog.completed) Emerald500 else Lime400)
                    .clickable {
                        if (setLog.completed) onUncomplete(setLog) else onComplete(setLog)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check, 
                    contentDescription = "Concluir", 
                    tint = BackgroundDark,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        
        // Inline Expansion for adjustments, set types, and RPE/RIR
        AnimatedVisibility(visible = isEditing) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                HorizontalDivider(color = BorderLight)
                Spacer(modifier = Modifier.height(8.dp))
                
                // Weight quick adjust
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Carga:", color = TextSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickAdjustButton("-5") { onUpdate(setLog.copy(weight = (setLog.weight - 5f).coerceAtLeast(0f))) }
                        QuickAdjustButton("-1") { onUpdate(setLog.copy(weight = (setLog.weight - 1f).coerceAtLeast(0f))) }
                        QuickAdjustButton("+1") { onUpdate(setLog.copy(weight = setLog.weight + 1f)) }
                        QuickAdjustButton("+5") { onUpdate(setLog.copy(weight = setLog.weight + 5f)) }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Reps quick adjust
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reps:", color = TextSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickAdjustButton("-1") { onUpdate(setLog.copy(repetitions = (setLog.repetitions - 1).coerceAtLeast(0))) }
                        QuickAdjustButton("+1") { onUpdate(setLog.copy(repetitions = setLog.repetitions + 1)) }
                        IconButton(onClick = { onRemove(setLog) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Remover", tint = Red500)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Set Type Selector chips
                Text("Tipo de Série:", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val types = listOf(
                        SetType.NORMAL.name to "Normal",
                        SetType.WARMUP.name to "Aquecimento",
                        SetType.DROP_SET.name to "Drop-set",
                        SetType.BACKOFF.name to "Backoff",
                        SetType.FAILURE.name to "Até a Falha"
                    )
                    items(types) { (key, label) ->
                        val isSelected = setLog.type == key
                        Surface(
                            color = if (isSelected) Lime400 else BackgroundDark,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable { onUpdate(setLog.copy(type = key)) }
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) BackgroundDark else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // RPE & RIR Row if enabled in settings
                if (rirRpeEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Esforço Percebido (RPE / RIR):", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // RPE selector
                        val rpeOptions = listOf(null, 6.0f, 7.0f, 8.0f, 8.5f, 9.0f, 9.5f, 10.0f)
                        LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(rpeOptions) { rpeVal ->
                                val isSelected = setLog.rpe == rpeVal
                                Surface(
                                    color = if (isSelected) Lime400 else BackgroundDark,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable { onUpdate(setLog.copy(rpe = rpeVal)) }
                                ) {
                                    Text(
                                        text = if (rpeVal == null) "RPE: -" else "@$rpeVal",
                                        color = if (isSelected) BackgroundDark else TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        // RIR selector
                        val rirOptions = listOf(null, 0, 1, 2, 3, 4)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(rirOptions) { rirVal ->
                                val isSelected = setLog.rir == rirVal
                                Surface(
                                    color = if (isSelected) Amber500 else BackgroundDark,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable { onUpdate(setLog.copy(rir = rirVal)) }
                                ) {
                                    Text(
                                        text = if (rirVal == null) "RIR: -" else "RIR $rirVal",
                                        color = if (isSelected) BackgroundDark else TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickAdjustButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BackgroundDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
