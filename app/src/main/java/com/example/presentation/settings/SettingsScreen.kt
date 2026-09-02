package com.example.presentation.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import com.example.MainApplication
import com.example.R
import com.example.data.datastore.IntegrationSettings
import com.example.domain.engine.ExerciseMediaEngine
import com.example.domain.engine.ManifestImporter
import com.example.domain.engine.PremiumManifestImporter
import com.example.domain.engine.ProgramImporter
import com.example.domain.engine.SyncErrorItem
import com.example.domain.engine.SyncState
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.components.BottomSheetActionItem
import com.example.ui.components.SelectionBottomSheet
import com.example.ui.theme.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.example.data.remote.NetworkTestResult
import kotlinx.coroutines.launch

import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag

private sealed class SettingsSheetType {
    object RestBetweenSets : SettingsSheetType()
    object CustomRestBetweenSets : SettingsSheetType()
    object RestBetweenExercises : SettingsSheetType()
    object CustomRestBetweenExercises : SettingsSheetType()
    object WeeklyGoal : SettingsSheetType()
    object ManageData : SettingsSheetType()
    object ConfirmReimportCatalog : SettingsSheetType()
    object PremiumLibraryAudit : SettingsSheetType()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToMyEvolution: () -> Unit = {},
    onNavigateToBodyEvolution: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsManager = (context.applicationContext as MainApplication).settingsManager
    val coroutineScope = rememberCoroutineScope()
        
    val db = com.example.data.local.AppDatabase.getDatabase(context)
    val exportEngine = com.example.domain.engine.ExportEngine(db.workoutDao(), context)
    val manifestImporter = ManifestImporter(db, context)
    val programImporter = ProgramImporter(db, context)
    val premiumImporter = PremiumManifestImporter(db, context)
    val workoutEngine = remember(db, settingsManager) {
        com.example.domain.engine.WorkoutEngine(db.workoutDao(), settingsManager)
    }
    var pendingRestUpdate by remember { mutableStateOf<Int?>(null) }

    val mediaEngine = remember(db, settingsManager) {
        ExerciseMediaEngine(
            dao = db.workoutDao(),
            remoteDataSource = com.example.data.remote.provider.ExerciseProviderFactory.create(
                db.workoutDao(),
                settingsManager
            ),
            context = context
        )
    }

    val integrationSettings by settingsManager.integrationSettingsFlow.collectAsState(initial = IntegrationSettings())

    var activeSheet by remember { mutableStateOf<SettingsSheetType?>(null) }
    
    val preAlertEnabled by settingsManager.preAlertEnabledFlow.collectAsState(initial = false)
    val soundEnabled by settingsManager.soundEnabledFlow.collectAsState(initial = true)
    val hapticEnabled by settingsManager.hapticEnabledFlow.collectAsState(initial = true)
    val timerNotifEnabled by settingsManager.timerNotificationEnabledFlow.collectAsState(initial = true)
    val exerciseDbV2ApiKey by settingsManager.exerciseDbV2ApiKeyFlow.collectAsState(initial = "")
    val rirRpeEnabled by settingsManager.rirRpeEnabledFlow.collectAsState(initial = false)
    val showGifs by settingsManager.showGifsFlow.collectAsState(initial = true)
    val showCoachTip by settingsManager.showCoachTipFlow.collectAsState(initial = true)
    
    val defaultRestSecs by settingsManager.defaultRestSecondsFlow.collectAsState(initial = 60)
    val defaultExerciseRestSecs by settingsManager.defaultExerciseRestSecondsFlow.collectAsState(initial = 120)
    
    var isSyncingMedia by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf("") }
    
    var isTestingApi by remember { mutableStateOf(false) }
    
    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogMessage by remember { mutableStateOf("") }
    
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    val result = manifestImporter.importFromJsonString(json)
                    dialogTitle = "Importação de Catálogo"
                    dialogMessage = "Importação concluída!\n\nAdicionados: ${result.added}\nAtualizados: ${result.updated}\nInalterados: ${result.unchanged}\nAlternativas vinculadas: ${result.alternativesAdded}\nIgnorados/Erros: ${result.ignored}"
                } catch (e: Exception) {
                    dialogTitle = "Erro"
                    dialogMessage = "Erro ao importar: ${e.message}"
                }
                showDialog = true
            }
        }
    }
    
    val programImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    val result = programImporter.importProgramFromJson(json)
                    dialogTitle = "Importação de Programa"
                    dialogMessage = "Programa '${result.programName}' importado com sucesso!\n\nTreinos: ${result.workoutsCount}\nExercícios mapeados: ${result.exercisesCount}\nExercícios não encontrados (ignorados): ${result.missingExercises}"
                } catch (e: Exception) {
                    dialogTitle = "Erro"
                    dialogMessage = "Erro ao importar: ${e.message}"
                }
                showDialog = true
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.example.ui.theme.BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Perfil & Evolução", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nav_my_evolution_item")
                .clickable { onNavigateToMyEvolution() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(LimeTransparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Minha Evolução",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Resumo do seu progresso, consistência e performance",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nav_body_evolution_item")
                .clickable { onNavigateToBodyEvolution() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(LimeTransparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Straighten,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Evolução corporal",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Acompanhar peso, medidas e progresso",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text("Treino", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(12.dp))
        
        SettingsValueItem(
            title = "Descanso entre séries",
            valueText = "${defaultRestSecs}s",
            onClick = { activeSheet = SettingsSheetType.RestBetweenSets }
        )
        
        SettingsValueItem(
            title = "Descanso entre exercícios",
            valueText = "${defaultExerciseRestSecs}s",
            onClick = { activeSheet = SettingsSheetType.RestBetweenExercises }
        )
        
        SettingsToggleItem(
            title = "Som ao finalizar descanso",
            subtitle = "Emitir sinal sonoro ao fim do descanso",
            checked = soundEnabled,
            onCheckedChange = { coroutineScope.launch { settingsManager.setSoundEnabled(it) } }
        )
        SettingsToggleItem(
            title = "Vibração ao finalizar descanso",
            subtitle = "Vibrar o dispositivo ao término do tempo",
            checked = hapticEnabled,
            onCheckedChange = { coroutineScope.launch { settingsManager.setHapticEnabled(it) } }
        )
        SettingsToggleItem(
            title = "Notificação ao finalizar descanso",
            subtitle = "Mostrar alerta visual ao terminar o descanso",
            checked = timerNotifEnabled,
            onCheckedChange = { coroutineScope.launch { settingsManager.setTimerNotificationEnabled(it) } }
        )
        SettingsToggleItem(
            title = "Pré-alerta de descanso",
            subtitle = "Avisar 10 segundos antes do término do descanso",
            checked = preAlertEnabled,
            onCheckedChange = { coroutineScope.launch { settingsManager.setPreAlertEnabled(it) } }
        )
        SettingsToggleItem(
            title = "Campos RPE / RIR",
            subtitle = "Permitir registrar esforço percebido por série",
            checked = rirRpeEnabled,
            onCheckedChange = { coroutineScope.launch { settingsManager.setRirRpeEnabled(it) } }
        )
        
        Spacer(modifier = Modifier.height(28.dp))
        
        Text("Multimídia & Demonstrações", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(12.dp))
        SettingsToggleItem(
            title = "Exibir GIFs e Fotos",
            subtitle = "Mostrar animações de demonstração nas fichas",
            checked = showGifs,
            onCheckedChange = { coroutineScope.launch { settingsManager.setShowGifs(it) } }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Chave da API ExerciseDB V2 (Opcional)",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Forneça sua chave RapidAPI V2 para usar como fallback caso o servidor OSS esteja indisponível ou limitando requisições.",
                color = TextSecondary,
                fontSize = 12.sp
            )
            var inputKey by remember(exerciseDbV2ApiKey) { mutableStateOf(exerciseDbV2ApiKey) }
            var keyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = inputKey,
                onValueChange = {
                    inputKey = it
                    coroutineScope.launch { settingsManager.setExerciseDbV2ApiKey(it.trim()) }
                },
                placeholder = { Text("Cole sua chave RapidAPI V2 aqui", fontSize = 12.sp, color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (keyVisible) "Ocultar chave" else "Mostrar chave",
                            tint = TextSecondary
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lime400,
                    unfocusedBorderColor = BorderLight,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        SettingsToggleItem(
            title = "Exibir Dica do Treinador",
            subtitle = "Mostrar a dica do exercício durante o treino",
            checked = showCoachTip,
            onCheckedChange = { coroutineScope.launch { settingsManager.setShowCoachTip(it) } }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        SettingsActionItem(
            title = "ATUALIZAR DEMONSTRAÇÕES (EXERCISEDB)",
            icon = Icons.Default.CloudDownload,
            isLoading = isSyncingMedia,
            loadingText = syncProgress,
            onClick = {
                if (!isSyncingMedia) {
                    isSyncingMedia = true
                    syncProgress = "Consultando catálogo remoto..."
                    coroutineScope.launch {
                        val result = mediaEngine.syncExerciseGifs(
                            onCatalogProgress = { loaded, total ->
                                syncProgress = if (total != null) {
                                    "Baixando catálogo: $loaded de $total exercícios..."
                                } else {
                                    "Baixando catálogo: $loaded exercícios..."
                                }
                            }
                        ) { cur, tot ->
                            syncProgress = "Verificando exercício $cur de $tot..."
                        }
                        // Progresso parcial também é gravado: um catálogo incompleto
                        // já rendeu correspondências e a próxima execução retoma o resto.
                        if (!result.isOffline && result.matched + result.alreadyUpToDate > 0) {
                            settingsManager.setLastMediaSyncAt(System.currentTimeMillis())
                            if (result.catalogComplete && result.errors.isEmpty()) {
                                settingsManager.setMediaSyncContentVersion(1)
                            }
                        }
                        val diag = mediaEngine.getLibraryDiagnostic()
                        isSyncingMedia = false
                        dialogTitle = "Sincronização de Demonstrações"
                        dialogMessage = if (result.isOffline) {
                            "Não foi possível conectar ao ExerciseDB.\n\nVerifique a conexão de internet. Todo o treino continua funcionando 100% offline."
                        } else {
                            buildString {
                                append("Catálogo ExerciseDB: ${result.catalogSize} exercícios")
                                append(if (result.catalogFromCache) " (instantâneo local)\n" else "\n")
                                if (!result.catalogComplete && result.catalogSize > 0) {
                                    append("Download incompleto — será retomado na próxima execução.\n")
                                }
                                append("\n")
                                append("Cobertura de demonstrações\n\n")
                                append("GIF: ${diag.gifsCount}\n")
                                append("Fotos: ${diag.customPhotosCount}\n")
                                append("Vídeos YouTube: ${diag.curatedVideosCount}\n")
                                append("Sem mídia: ${diag.noMediaCount}\n\n")
                                
                                append("Resultado da Sincronização:\n")
                                append("• Mapeados: ${result.matched}\n")
                                append("• Ambíguos: ${result.ambiguous}\n")
                                append("• Já atualizados: ${result.alreadyUpToDate}\n")
                                append("• Não encontrados: ${result.notFound}\n")
                                if (result.errors.isNotEmpty()) {
                                    append("\nAvisos:\n")
                                    result.errors.forEach { err ->
                                        append("• $err\n")
                                    }
                                }
                            }
                        }
                        showDialog = true
                    }
                }
            }
        )

        HorizontalDivider(color = BorderLight, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(8.dp))

        SettingsActionItem(
            title = "TESTAR CONEXÃO EXERCISEDB",
            icon = Icons.Default.Refresh,
            isLoading = isTestingApi,
            loadingText = "Testando conexão com ExerciseDB...",
            onClick = {
                if (!isTestingApi) {
                    isTestingApi = true
                    coroutineScope.launch {
                        val testRes = mediaEngine.testConnection("bench press")
                        isTestingApi = false
                        when (testRes) {
                            is com.example.data.remote.NetworkTestResult.Success -> {
                                dialogTitle = "API Conectada com Sucesso"
                                dialogMessage = buildString {
                                    append("Status: Conexão ativa com ExerciseDB\n\n")
                                    append("Consulta de teste: '${testRes.query}'\n")
                                    append("Exercício retornado: ${testRes.foundName}\n")
                                    append("ID remoto: ${testRes.exerciseId}\n")
                                    append("GIF URL: ${testRes.gifUrl ?: "Não retornado"}\n")
                                    append("Resultados encontrados: ${testRes.totalResults}\n")
                                }
                            }
                            is com.example.data.remote.NetworkTestResult.Failure -> {
                                dialogTitle = "Falha ExerciseDB"
                                dialogMessage = buildString {
                                    if (testRes.httpCode != null) {
                                        append("HTTP: ${testRes.httpCode}\n")
                                    }
                                    if (testRes.url != null) {
                                        append("URL: ${testRes.url}\n\n")
                                    }
                                    append("Mensagem: ${testRes.errorMessage}\n")
                                }
                            }
                        }
                        showDialog = true
                    }
                }
            }
        )
        
        Spacer(modifier = Modifier.height(28.dp))
        
        Text("Dados e Importação", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(12.dp))
        
        SettingsActionItem(
            title = "GERENCIAR DADOS & IMPORTAÇÃO",
            icon = Icons.Default.FolderOpen,
            onClick = { activeSheet = SettingsSheetType.ManageData }
        )
        
        Spacer(modifier = Modifier.height(48.dp))
    }
    
        when (activeSheet) {
        is SettingsSheetType.RestBetweenSets -> {
            SelectionBottomSheet(
                title = "Descanso entre séries",
                options = listOf(
                    "Desativado" to 0,
                    "30 segundos" to 30,
                    "45 segundos" to 45,
                    "60 segundos (1 min)" to 60,
                    "90 segundos (1.5 min)" to 90,
                    "120 segundos (2 min)" to 120,
                    "180 segundos (3 min)" to 180
                ),
                selectedOption = listOf("Desativado" to 0, "30 segundos" to 30, "45 segundos" to 45, "60 segundos (1 min)" to 60, "90 segundos (1.5 min)" to 90, "120 segundos (2 min)" to 120, "180 segundos (3 min)" to 180).find { it.second == defaultRestSecs },
                optionTitle = { it.first },
                onOptionSelected = { 
                    if (it.second != defaultRestSecs) {
                        pendingRestUpdate = it.second
                    }
                    activeSheet = null
                },
                onDismissRequest = { activeSheet = null }
            )
        }
        is SettingsSheetType.RestBetweenExercises -> {
             SelectionBottomSheet(
                title = "Descanso entre exercícios",
                options = listOf(
                    "Desativado" to 0,
                    "60 segundos (1 min)" to 60,
                    "90 segundos (1.5 min)" to 90,
                    "120 segundos (2 min)" to 120,
                    "150 segundos (2.5 min)" to 150,
                    "180 segundos (3 min)" to 180,
                    "240 segundos (4 min)" to 240
                ),
                selectedOption = listOf("Desativado" to 0, "60 segundos (1 min)" to 60, "90 segundos (1.5 min)" to 90, "120 segundos (2 min)" to 120, "150 segundos (2.5 min)" to 150, "180 segundos (3 min)" to 180, "240 segundos (4 min)" to 240).find { it.second == defaultExerciseRestSecs },
                optionTitle = { it.first },
                onOptionSelected = { 
                    coroutineScope.launch { settingsManager.setDefaultExerciseRestSeconds(it.second) }
                    activeSheet = null
                },
                onDismissRequest = { activeSheet = null }
            )
        }
        is SettingsSheetType.ManageData -> {
            AppModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                title = "Gerenciar Dados"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BottomSheetActionItem(
                        icon = Icons.Default.Analytics,
                        title = "Auditoria Biblioteca Premium",
                        subtitle = "Diagnóstico dos 144 exercícios e cobertura de mídia",
                        onClick = {
                            activeSheet = SettingsSheetType.PremiumLibraryAudit
                        }
                    )
                    BottomSheetActionItem(
                        icon = Icons.Default.Analytics,
                        title = "Auditoria ExerciseDB",
                        subtitle = "Verificar cobertura do catálogo",
                        onClick = {
                            activeSheet = null
                            coroutineScope.launch {
                                val diag = mediaEngine.getLibraryDiagnostic()
                                dialogTitle = "Auditoria ExerciseDB"
                                dialogMessage = "Total exercícios:\n${diag.totalExercises}\n\n" +
                                        "Com exerciseDbSearch:\n${diag.withExerciseDbSearch}\n\n" +
                                        "Sem exerciseDbSearch:\n${diag.withoutExerciseDbSearch}\n\n" +
                                        "Mapeados:\n${diag.matchedCount}\n\n" +
                                        "Ambíguos:\n${diag.ambiguousCount}\n\n" +
                                        "Não encontrados:\n${diag.notFoundCount}\n\n" +
                                        "Sem mídia:\n${diag.noMediaCount}"
                                showDialog = true
                            }
                        }
                    )

                    
                    BottomSheetActionItem(
                        icon = Icons.Default.Refresh,
                        title = "Sincronizar Manifesto Premium",
                        subtitle = "Carregar informações avançadas do catálogo",
                        onClick = {
                            activeSheet = null
                            coroutineScope.launch {
                                try {
                                    val result = premiumImporter.importFromAssets("catalog/exercise-content-manifest.v2.json", force = true)
                                    premiumImporter.seedPremiumTestWorkoutIfNeeded()
                                    dialogTitle = "Exercise Premium v2 Import"
                                    dialogMessage = if (result.formattedReport.isNotEmpty()) {
                                        result.formattedReport
                                    } else {
                                        "Importação concluída. Importados: ${result.added + result.updated}"
                                    }
                                } catch (e: Exception) {
                                    dialogTitle = "Erro"
                                    dialogMessage = "Erro ao carregar manifesto premium: ${e.message}"
                                }
                                showDialog = true
                            }
                        }
                    )
                    BottomSheetActionItem(
                        icon = Icons.Default.Refresh,
                        title = "Reimportar Catálogo Canônico",
                        subtitle = "Restaura os 144 exercícios oficiais",
                        onClick = {
                            activeSheet = SettingsSheetType.ConfirmReimportCatalog
                        }
                    )
                    BottomSheetActionItem(
                        icon = Icons.Default.UploadFile,
                        title = "Importar exercícios (JSON)",
                        subtitle = "Adicionar ou atualizar catálogo a partir de um arquivo",
                        onClick = {
                            importLauncher.launch("application/json")
                            activeSheet = null
                        }
                    )
                    BottomSheetActionItem(
                        icon = Icons.Default.UploadFile,
                        title = "Importar programa de treino",
                        subtitle = "Carregar rotina no formato GymLog",
                        onClick = {
                            programImportLauncher.launch("application/json")
                            activeSheet = null
                        }
                    )
                    BottomSheetActionItem(
                        icon = Icons.Default.FileDownload,
                        title = "Exportar todos os dados",
                        subtitle = "Salvar backup em formato legível",
                        onClick = {
                            activeSheet = null
                            coroutineScope.launch { exportEngine.exportData() }
                        }
                    )
                }
            }
        }
        is SettingsSheetType.ConfirmReimportCatalog -> {
            AppModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                title = "Reimportar Catálogo Canônico",
                subtitle = "Base canônica de 144 exercícios"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "A base oficial de exercícios será sincronizada. Seus treinos, notas e personalizações de exercícios existentes serão totalmente preservados.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    Button(
                        onClick = {
                            activeSheet = null
                            coroutineScope.launch {
                                try {
                                    val json = context.assets.open("catalog/catalogo_exercicios_base_ptbr.v1.json").bufferedReader().use { it.readText() }
                                    val result = manifestImporter.importFromJsonString(json, force = true)
                                    if (result.errors.isNotEmpty()) {
                                        dialogTitle = "Avisos/Erros no Catálogo"
                                        dialogMessage = result.errors.joinToString("\n")
                                    } else {
                                        dialogTitle = "Catálogo Canônico"
                                        dialogMessage = "Catálogo canônico sincronizado com sucesso!\n\n144 exercícios processados de forma transacional.\n\nNovos adicionados: ${result.added}\nAtualizados: ${result.updated}\nInalterados: ${result.unchanged}\nAlternativas vinculadas: ${result.alternativesAdded}"
                                    }
                                } catch (e: Exception) {
                                    dialogTitle = "Erro"
                                    dialogMessage = "Erro ao carregar catálogo: ${e.message}"
                                }
                                showDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = com.example.ui.theme.BackgroundDark),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("REIMPORTAR AGORA", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    TextButton(
                        onClick = { activeSheet = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CANCELAR", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        is SettingsSheetType.PremiumLibraryAudit -> {
            PremiumLibraryAuditSheet(
                onDismissRequest = { activeSheet = null }
            )
        }
        else -> {}
    }
    
    
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(dialogTitle, color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(dialogMessage, color = TextSecondary, fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("OK", color = Lime400, fontWeight = FontWeight.Bold) } },
            containerColor = SurfaceDark
        )
    }

    if (pendingRestUpdate != null) {
        val newRest = pendingRestUpdate!!
        var updateExistingWorkouts by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingRestUpdate = null },
            title = {
                Text(
                    text = "Deseja atualizar treinos existentes?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Escolha o escopo de aplicação para o novo tempo de descanso ($newRest seg):",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = if (!updateExistingWorkouts) Lime400.copy(alpha = 0.1f) else SurfaceHighlight,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (!updateExistingWorkouts) Lime400 else BorderLight),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { updateExistingWorkouts = false }
                                .testTag("apply_new_workouts_only_option")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = !updateExistingWorkouts,
                                    onClick = { updateExistingWorkouts = false },
                                    colors = RadioButtonDefaults.colors(selectedColor = Lime400, unselectedColor = TextSecondary)
                                )
                                Column {
                                    Text(
                                        text = "Apenas novos treinos",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Mantém treinos e prescrições já criadas",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Surface(
                            color = if (updateExistingWorkouts) Lime400.copy(alpha = 0.1f) else SurfaceHighlight,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (updateExistingWorkouts) Lime400 else BorderLight),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { updateExistingWorkouts = true }
                                .testTag("apply_existing_workouts_option")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = updateExistingWorkouts,
                                    onClick = { updateExistingWorkouts = true },
                                    colors = RadioButtonDefaults.colors(selectedColor = Lime400, unselectedColor = TextSecondary)
                                )
                                Column {
                                    Text(
                                        text = "Atualizar treinos existentes também",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Aplica em templates e treinos ativos",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val shouldUpdateExisting = updateExistingWorkouts
                        coroutineScope.launch {
                            settingsManager.setDefaultRestSeconds(newRest)
                            if (shouldUpdateExisting) {
                                workoutEngine.updateExistingWorkoutsRestDuration(newRest)
                                dialogTitle = "Descanso Atualizado"
                                dialogMessage = "Novo tempo de descanso aplicado nas configurações e aos treinos existentes com sucesso."
                                showDialog = true
                            }
                        }
                        pendingRestUpdate = null
                    },
                    modifier = Modifier.testTag("confirm_rest_update_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = com.example.ui.theme.BackgroundDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Confirmar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRestUpdate = null },
                    modifier = Modifier.testTag("cancel_rest_update_button")
                ) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                contentDescription = "$title${if (subtitle != null) ", $subtitle" else ""}, ${if (checked) "ativado" else "desativado"}"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Lime400,
                    checkedTrackColor = Lime400.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun SettingsValueItem(
    title: String,
    valueText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$title, $valueText"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = valueText,
                    color = Lime400,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsActionItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    highlighted: Boolean = false,
    isLoading: Boolean = false,
    loadingText: String? = null
) {
    Surface(
        onClick = onClick,
        enabled = !isLoading,
        color = if (highlighted) Lime400 else SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = title
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Lime400,
                    strokeWidth = 2.dp
                )
                if (loadingText != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(loadingText, color = TextPrimary, fontSize = 13.sp)
                }
            } else {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (highlighted) BackgroundDark else Lime400,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    color = if (highlighted) BackgroundDark else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatTimestamp(ts: Long?): String {
    if (ts == null || ts == 0L) return "Nunca"
    val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}
