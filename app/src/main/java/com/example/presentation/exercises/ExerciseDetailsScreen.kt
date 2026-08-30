package com.example.presentation.exercises

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    val exerciseInfo by viewModel.getExerciseInfo(exerciseId).collectAsState(initial = null)
    val overrideInfo by viewModel.getUserOverride(exerciseId).collectAsState(initial = null)
    val showGifs by viewModel.showGifs.collectAsState()
    val alternatives by viewModel.getAlternatives(exerciseId).collectAsState(initial = emptyList())
    val personalRecords by viewModel.getPersonalRecords(exerciseId).collectAsState(initial = emptyList())
    val history by viewModel.getExerciseHistory(exerciseId).collectAsState(initial = emptyList())
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val current = overrideInfo ?: ExerciseUserOverrideEntity(exerciseId = exerciseId)
            viewModel.saveUserOverride(current.copy(customPhotoUri = uri.toString(), updatedAt = System.currentTimeMillis()))
        }
    }

    var showEditOverrideDialog by remember { mutableStateOf(false) }

    val resolvedName = ExerciseMediaResolver.resolveDisplayName(exerciseInfo, overrideInfo, exerciseName)
    val resolvedNotes = ExerciseMediaResolver.resolveNotes(exerciseInfo, overrideInfo)
    val resolvedMedia = ExerciseMediaResolver.resolveMedia(exerciseInfo, overrideInfo, showGifs)

    val nameEn = exerciseInfo?.nameEn
    val primaryMuscle = exerciseInfo?.primaryMuscle
    val secondaryMuscles = exerciseInfo?.secondaryMuscles?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    val equipment = exerciseInfo?.equipment
    val movementPattern = exerciseInfo?.movementPattern?.replace("_", " ")
    val substitutionGroup = exerciseInfo?.substitutionGroup

    val muscleGroup = MuscleVisualResolver.resolveGroup(primaryMuscle)
    val curatedVideo = ExerciseVideoRegistry.getVideoForExercise(context, exerciseInfo?.canonicalId, exerciseInfo?.slug, resolvedName)

    var showInlineVideo by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { showEditOverrideDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Editar Personalização", tint = Lime400)
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
                        if (!resolvedMedia.mediaUri.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(resolvedMedia.mediaUri)
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
                                    resolvedMedia.isCustomPhoto -> "Foto personalizada ativa"
                                    resolvedMedia.isGif -> "GIF animado ativo"
                                    else -> "Mídia padrão"
                                },
                                color = if (resolvedMedia.isCustomPhoto) Lime400 else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (resolvedMedia.isCustomPhoto) {
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
                                    Text(if (resolvedMedia.isCustomPhoto) "Trocar Foto" else "Adicionar Foto", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Exercise Overview Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(muscleGroup.icon, contentDescription = null, tint = muscleGroup.color, modifier = Modifier.size(20.dp))
                                Text(
                                    text = "Ficha Técnica",
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (exerciseInfo?.isUserCreated == true || overrideInfo?.displayName != null) {
                                Surface(
                                    color = Lime400.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Personalizado",
                                        color = Lime400,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // Chips row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!primaryMuscle.isNullOrEmpty()) {
                                Surface(
                                    color = muscleGroup.color.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, muscleGroup.color.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = primaryMuscle,
                                        color = muscleGroup.color,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            if (!equipment.isNullOrEmpty()) {
                                Surface(
                                    color = SurfaceHighlight,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = equipment,
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            if (!movementPattern.isNullOrEmpty()) {
                                Surface(
                                    color = SurfaceHighlight,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = movementPattern.replaceFirstChar { it.uppercase() },
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        if (!resolvedNotes.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Notas / Instruções: $resolvedNotes",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }

                        if (secondaryMuscles.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Músculos secundários: ${secondaryMuscles.joinToString(", ")}",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }

                        if (!substitutionGroup.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Grupo de substituição: ${substitutionGroup.replace("_", " ")}",
                                color = TextSecondary.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Video Guide Card (Curated or YouTube Search Fallback)
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
                                        text = curatedVideo?.title ?: "Buscar tutorial no YouTube",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (curatedVideo != null) {
                                    TextButton(onClick = { showInlineVideo = !showInlineVideo }) {
                                        Text(if (showInlineVideo) "Ocultar" else "Player", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val searchQuery = "${resolvedName} ${equipment ?: ""} execucao correta"
                                        val youtubeIntent = Intent(
                                            Intent.ACTION_SEARCH
                                        ).apply {
                                            `package` = "com.google.android.youtube"
                                            putExtra("query", searchQuery)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        try {
                                            context.startActivity(youtubeIntent)
                                        } catch (_: Exception) {
                                            // Fallback to web search
                                            val webIntent = Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(searchQuery)}")
                                            )
                                            context.startActivity(webIntent)
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = "Abrir no App do YouTube", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        if (curatedVideo != null && showInlineVideo) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        WebView(ctx).apply {
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

            // Personal Records (PRs)
            if (personalRecords.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
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
                                onNavigateToAlternative?.invoke(alt.id, alt.name)
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
                                    Text(alt.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                                    Text("${setLog.weight}kg × ${setLog.repetitions}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // Edit Custom Override Dialog
    if (showEditOverrideDialog) {
        var editName by remember { mutableStateOf(overrideInfo?.displayName ?: "") }
        var editNotes by remember { mutableStateOf(overrideInfo?.notes ?: "") }
        var editRestSeconds by remember { mutableStateOf(overrideInfo?.defaultRestSeconds?.toString() ?: "") }

        AlertDialog(
            onDismissRequest = { showEditOverrideDialog = false },
            title = { Text("Personalizar Exercício", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                }
            },
            confirmButton = {
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
                        showEditOverrideDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
                ) {
                    Text("Salvar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditOverrideDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
