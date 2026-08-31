package com.example.presentation.exercises
import com.example.presentation.exercises.PremiumExerciseInfo
import org.json.JSONArray


import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.example.R
import com.example.ui.components.ActionBottomSheet
import com.example.ui.components.ActionItemData
import com.example.ui.components.AppModalBottomSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.engine.RirFormatter
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.PRType
import com.example.domain.engine.ExerciseMediaResolver
import com.example.domain.engine.ExerciseVideoRegistry
import com.example.domain.engine.MuscleVisualResolver
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailsScreen(
    exerciseId: Long,
    exerciseName: String,
    viewModel: ExerciseDetailsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAlternative: ((Long, String) -> Unit)? = null
) {
    val context = LocalContext.current
    val resolvedExercise by viewModel.getResolvedExercise(exerciseId).collectAsState(initial = null)
    val exerciseInfo = resolvedExercise?.rawExercise
    val overrideInfo = resolvedExercise?.override
    
    val showGifs by viewModel.showGifs.collectAsState()
    val premiumInfo by viewModel.getPremiumInfo(exerciseId).collectAsState(initial = null)
    val alternatives by viewModel.getAlternatives(exerciseId).collectAsState(initial = emptyList())
    val personalRecords by viewModel.getPersonalRecords(exerciseId).collectAsState(initial = emptyList())
    val history by viewModel.getExerciseHistory(exerciseId).collectAsState(initial = emptyList())
    val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
    val photoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val currentOverride = overrideInfo ?: com.example.data.local.ExerciseUserOverrideEntity(exerciseId = exerciseId)
            viewModel.saveUserOverride(currentOverride.copy(customPhotoUri = uri.toString()))
        }
    }
    var showActionSheet by remember { mutableStateOf(false) }
    var showEditOverrideSheet by remember { mutableStateOf(false) }

    val resolvedName = resolvedExercise?.displayName ?: exerciseName
    val resolvedNotes = resolvedExercise?.notes
    val resolvedMedia = resolvedExercise?.resolvedMedia
    
    val nameEn = resolvedExercise?.nameEn
    val primaryMuscle = resolvedExercise?.primaryMuscle
    val secondaryMuscles = resolvedExercise?.secondaryMuscles ?: emptyList()
    val equipment = resolvedExercise?.equipment
    val movementPattern = resolvedExercise?.movementPattern?.replace("_", " ")
    val substitutionGroup = resolvedExercise?.substitutionGroup


    val muscleGroup = MuscleVisualResolver.resolveGroup(primaryMuscle)
    val curatedVideo = ExerciseVideoRegistry.getVideoForExercise(context, exerciseInfo?.canonicalId, exerciseInfo?.slug, resolvedName)

    var showInlineVideo by remember { mutableStateOf(false) }

    if (showActionSheet || showEditOverrideSheet) {
        BackHandler {
            showActionSheet = false
            showEditOverrideSheet = false
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(resolvedName, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (!nameEn.isNullOrEmpty()) {
                            Text(nameEn, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showActionSheet = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(id = R.string.sheet_exercise_options), tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Photo / GIF / Media Banner
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        if (!resolvedMedia?.mediaUri.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(resolvedMedia?.mediaUri)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Mídia de demonstração para $resolvedName",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .background(SurfaceHighlight),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(muscleGroup.icon, contentDescription = null, tint = muscleGroup.color, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Sem imagem ou GIF cadastrado", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                        }

                        // Photo Actions (Add/Change Custom Photo)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    resolvedMedia?.isCustomPhoto == true -> "Foto personalizada ativa"
                                    resolvedMedia?.isGif == true -> "GIF animado ativo"
                                    else -> "Mídia padrão"
                                },
                                color = if (resolvedMedia?.isCustomPhoto == true) Lime400 else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (resolvedMedia?.isCustomPhoto == true) {
                                    IconButton(
                                        onClick = { viewModel.removeCustomPhoto(exerciseId) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remover foto", tint = Red500, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Button(
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceHighlight, contentColor = TextPrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp), tint = Lime400)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (resolvedMedia?.isCustomPhoto == true) "Trocar Foto" else "Adicionar Foto", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }


            // PREMIUM CONTENT & OVERVIEW
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val premium = premiumInfo

                    // Section: Sobre
                    Text("Sobre", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
                    HorizontalDivider(color = SurfaceHighlight)
                    if (!resolvedNotes.isNullOrBlank() || exerciseInfo?.shortDescription != null) {
                        Text(
                            text = resolvedNotes ?: exerciseInfo?.shortDescription ?: "",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                    if (!primaryMuscle.isNullOrEmpty()) {
                        Text("Músculos: ${primaryMuscle}" + if (secondaryMuscles.isNotEmpty()) ", ${secondaryMuscles.joinToString(", ")}" else "", color = TextSecondary, fontSize = 14.sp)
                    }
                    if (!equipment.isNullOrEmpty()) {
                        Text("Equipamento: $equipment", color = TextSecondary, fontSize = 14.sp)
                    }
                    exerciseInfo?.difficulty?.let { Text("Dificuldade: $it", color = TextSecondary, fontSize = 14.sp) }

                    // Section: Como executar
                    if (premium?.execution != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Como executar", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        
                        premium.execution.setup?.let { Text("Preparação: $it", color = TextSecondary, fontSize = 14.sp) }
                        
                        premium.execution.steps?.let { steps ->
                            val stepsList = try {
                                val arr = org.json.JSONArray(steps)
                                (0 until arr.length()).map { arr.getString(it) }
                            } catch(e: Exception) { emptyList<String>() }
                            
                            if (stepsList.isNotEmpty()) {
                                stepsList.forEachIndexed { i, step ->
                                    Text("${i+1}. $step", color = TextSecondary, fontSize = 14.sp)
                                }
                            }
                        }
                        
                        premium.execution.breathing?.let { Text("Respiração: $it", color = TextSecondary, fontSize = 14.sp) }
                    }

                    // Section: Dicas
                    if (premium?.education?.tips != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Dicas", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        val tipsList = try { val arr = org.json.JSONArray(premium.education.tips!!); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                        tipsList.forEach { tip ->
                            Text("• $tip", color = TextSecondary, fontSize = 14.sp)
                        }
                    }

                    // Section: Erros comuns
                    if (premium?.education?.commonMistakes != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Erros comuns", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        val mistakesList = try { val arr = org.json.JSONArray(premium.education.commonMistakes!!); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                        mistakesList.forEach { mistake ->
                            Text("• $mistake", color = TextSecondary, fontSize = 14.sp)
                        }
                    }

                    // Section: Progressão
                    if (premium?.progression != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Progressão", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        premium.progression.repRange?.let { Text("Faixa recomendada: $it", color = TextSecondary, fontSize = 14.sp) }
                        premium.progression.increaseRule?.let { Text("Regra de evolução: $it", color = TextSecondary, fontSize = 14.sp) }
                    }
                    
                    // Section: Alternativas
                    if (premium?.substitution != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Alternativas", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        premium.substitution.sameMovement?.let { sm ->
                            val smList = try { val arr = org.json.JSONArray(sm); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                            if (smList.isNotEmpty()) {
                                Text("Mesmo movimento: ${smList.joinToString(", ")}", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                        premium.substitution.sameMuscle?.let { sm ->
                            val smList = try { val arr = org.json.JSONArray(sm); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                            if (smList.isNotEmpty()) {
                                Text("Mesmo grupo muscular: ${smList.joinToString(", ")}", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                    }

                    // Section: Cuidados
                    if (premium?.safety != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Cuidados", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        premium.safety.riskLevel?.let { Text("Nível de risco: $it", color = TextSecondary, fontSize = 14.sp) }
                        premium.safety.attentionPoints?.let { points ->
                            val pointsList = try { val arr = org.json.JSONArray(points); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                            pointsList.forEach { p ->
                                Text("• $p", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                        premium.safety.commonDiscomforts?.let { points ->
                            val pointsList = try { val arr = org.json.JSONArray(points); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                            if (pointsList.isNotEmpty()) {
                                Text("Desconfortos comuns: ${pointsList.joinToString(", ")}", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // Video Guide Card - ONLY shown if curated video mapping exists
            if (curatedVideo != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PlayCircle,
                                        contentDescription = "Vídeo",
                                        tint = Red500,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Column {
                                        Text("Guia de Execução em Vídeo", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text(
                                            text = curatedVideo.title,
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                                TextButton(onClick = { showInlineVideo = !showInlineVideo }) {
                                    Text(if (showInlineVideo) "Ocultar" else "Assistir", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            if (showInlineVideo) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                ) {
                                    var webViewRef by remember { mutableStateOf<WebView?>(null) }

                                    DisposableEffect(Unit) {
                                        onDispose {
                                            webViewRef?.apply {
                                                stopLoading()
                                                loadUrl("about:blank")
                                                destroy()
                                            }
                                        }
                                    }

                                    AndroidView(
                                        factory = { ctx ->
                                            WebView(ctx).apply {
                                                webViewRef = this
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.mediaPlaybackRequiresUserGesture = false
                                                webChromeClient = WebChromeClient()
                                                webViewClient = WebViewClient()
                                                val embedHtml = """
                                                    <!DOCTYPE html>
                                                    <html>
                                                    <head>
                                                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                                        <style>body{margin:0;background-color:#000;display:flex;justify-content:center;align-items:center;height:100vh;}</style>
                                                    </head>
                                                    <body>
                                                        <iframe width="100%" height="100%" src="${curatedVideo.getEmbedUrl()}" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture" allowfullscreen></iframe>
                                                    </body>
                                                    </html>
                                                """.trimIndent()
                                                loadDataWithBaseURL("https://www.youtube.com", embedHtml, "text/html", "UTF-8", null)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Personal Records (PRs)
            if (personalRecords.isNotEmpty()) {
                item {
                    val prVisible = remember { mutableStateOf(false) }
                    LaunchedEffect(personalRecords) {
                        if (personalRecords.isNotEmpty()) prVisible.value = true
                    }
                    val prScale by animateFloatAsState(
                        targetValue = if (prVisible.value) 1f else 0.95f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
                        label = "prCardScale"
                    )
                    val prAlpha by animateFloatAsState(
                        targetValue = if (prVisible.value) 1f else 0f,
                        animationSpec = tween(durationMillis = AppMotion.Normal, easing = AppMotion.StandardEasing),
                        label = "prCardAlpha"
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer(scaleX = prScale, scaleY = prScale, alpha = prAlpha)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Amber500, modifier = Modifier.size(20.dp))
                                Text(
                                    text = "Recordes Pessoais (PRs)",
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            val highestWeight = personalRecords.firstOrNull { it.prType == PRType.MAX_WEIGHT }?.value
                            val highestVolume = personalRecords.firstOrNull { it.prType == PRType.MAX_VOLUME }?.value
                            val best1RM = personalRecords.firstOrNull { it.prType == PRType.ONE_REP_MAX }?.value

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                if (highestWeight != null) {
                                    Column {
                                        Text("Carga Máx", color = TextSecondary, fontSize = 12.sp)
                                        Text("${highestWeight.toInt()} kg", color = Amber500, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    }
                                }
                                if (best1RM != null) {
                                    Column {
                                        Text("1RM Estimado", color = TextSecondary, fontSize = 12.sp)
                                        Text("${best1RM.toInt()} kg", color = Amber500, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    }
                                }
                                if (highestVolume != null) {
                                    Column {
                                        Text("Volume Máx", color = TextSecondary, fontSize = 12.sp)
                                        Text("${highestVolume.toInt()} kg", color = Amber500, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Alternatives Section
            if (alternatives.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = Lime400, modifier = Modifier.size(20.dp))
                        Text(
                            text = "Exercícios Alternativos (${alternatives.size})",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }

                items(alternatives) { alt ->
                    val altMuscle = MuscleVisualResolver.resolveGroup(alt.primaryMuscle)
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onNavigateToAlternative?.invoke(alt.id, alt.displayName)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(altMuscle.icon, contentDescription = null, tint = altMuscle.color, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(alt.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    if (!alt.nameEn.isNullOrEmpty()) {
                                        Text(alt.nameEn, color = TextSecondary, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (!alt.equipment.isNullOrEmpty()) {
                                Surface(
                                    color = SurfaceHighlight,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        alt.equipment,
                                        color = Lime400,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // History Section
            item {
                Text(
                    text = "Histórico de Treinos",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (history.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Nenhum histórico registrado para este exercício.", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                items(history) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceDark)
                            .padding(14.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.sessionName, color = Lime400, fontWeight = FontWeight.Bold)
                            Text(dateFormat.format(Date(item.date)), color = TextSecondary, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        item.sets.sets.forEachIndexed { index, setLog ->
                            if (setLog.completed) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Série ${index + 1} (${setLog.type})", color = TextSecondary, fontSize = 13.sp)
                                    val weightStr = if (setLog.weight % 1f == 0f) setLog.weight.toInt().toString() else setLog.weight.toString()
                                    val rirTag = RirFormatter.formatRir(setLog.rir)
                                    val rirSuffix = if (rirTag != null) " · $rirTag" else ""
                                    Text("$weightStr kg × ${setLog.repetitions}$rirSuffix", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // Contextual Options ActionBottomSheet
    if (showActionSheet) {
        val actions = mutableListOf<ActionItemData>()

        actions.add(
            ActionItemData(
                title = stringResource(id = R.string.sheet_action_edit_exercise),
                subtitle = "Personalizar nome, notas e descanso",
                icon = Icons.Default.Edit,
                onClick = { showEditOverrideSheet = true }
            )
        )

        actions.add(
            ActionItemData(
                title = stringResource(id = R.string.sheet_action_set_photo),
                subtitle = if (resolvedMedia?.isCustomPhoto == true) "Alterar foto ativa" else "Adicionar foto da galeria",
                icon = Icons.Default.AddPhotoAlternate,
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )
        )

        if (resolvedMedia?.isCustomPhoto == true) {
            actions.add(
                ActionItemData(
                    title = stringResource(id = R.string.sheet_action_remove_photo),
                    subtitle = "Voltar para imagem padrão",
                    icon = Icons.Default.Delete,
                    onClick = { viewModel.removeCustomPhoto(exerciseId) }
                )
            )
        }

        ActionBottomSheet(
            onDismissRequest = { showActionSheet = false },
            title = stringResource(id = R.string.sheet_exercise_options),
            subtitle = resolvedName,
            actions = actions
        )
    }

    // Edit Custom Override Bottom Sheet
    if (showEditOverrideSheet) {
        var editName by remember { mutableStateOf(overrideInfo?.displayName ?: "") }
        var editNotes by remember { mutableStateOf(overrideInfo?.notes ?: "") }
        var editRestSeconds by remember { mutableStateOf(overrideInfo?.defaultRestSeconds?.toString() ?: "") }

        AppModalBottomSheet(
            onDismissRequest = { showEditOverrideSheet = false },
            title = "Personalizar Exercício",
            subtitle = resolvedName
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("Nome Personalizado") },
                    placeholder = { Text(exerciseInfo?.name ?: "") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lime400,
                        unfocusedBorderColor = BorderLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editNotes,
                    onValueChange = { editNotes = it },
                    label = { Text("Notas / Configuração do Aparelho") },
                    placeholder = { Text("Ex: Banco na inclinação 3, pino 4") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lime400,
                        unfocusedBorderColor = BorderLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editRestSeconds,
                    onValueChange = { editRestSeconds = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Descanso Padrão (segundos)") },
                    placeholder = { Text("90") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lime400,
                        unfocusedBorderColor = BorderLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val current = overrideInfo ?: ExerciseUserOverrideEntity(exerciseId = exerciseId)
                        val restVal = editRestSeconds.toIntOrNull()
                        viewModel.saveUserOverride(
                            current.copy(
                                displayName = editName.takeIf { it.isNotBlank() },
                                notes = editNotes.takeIf { it.isNotBlank() },
                                defaultRestSeconds = restVal,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        showEditOverrideSheet = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("SALVAR ALTERAÇÕES", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}
