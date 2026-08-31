package com.example.presentation.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
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
import com.example.MainApplication
import com.example.R
import com.example.domain.engine.ExerciseMediaEngine
import com.example.domain.engine.ManifestImporter
import com.example.domain.engine.ProgramImporter
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.components.BottomSheetActionItem
import com.example.ui.components.SelectionBottomSheet
import com.example.ui.theme.*
import kotlinx.coroutines.launch

private sealed class SettingsSheetType {
    object RestBetweenSets : SettingsSheetType()
    object CustomRestBetweenSets : SettingsSheetType()
    object RestBetweenExercises : SettingsSheetType()
    object CustomRestBetweenExercises : SettingsSheetType()
    object WeeklyGoal : SettingsSheetType()
    object ManageData : SettingsSheetType()
    object ConfirmReimportCatalog : SettingsSheetType()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsManager = (context.applicationContext as MainApplication).settingsManager
    val coroutineScope = rememberCoroutineScope()
    
    val db = com.example.data.local.AppDatabase.getDatabase(context)
    val exportEngine = com.example.domain.engine.ExportEngine(db.workoutDao(), context)
    val manifestImporter = ManifestImporter(db, context)
    val programImporter = ProgramImporter(db, context)
    val mediaEngine = ExerciseMediaEngine(db.workoutDao(), context = context)

    val autoCheckIn by settingsManager.autoCheckInFlow.collectAsState(initial = true)
    val autoCheckOut by settingsManager.autoCheckOutFlow.collectAsState(initial = true)
    val showGifs by settingsManager.showGifsFlow.collectAsState(initial = true)
    val weeklyGoal by settingsManager.weeklyGoalFlow.collectAsState(initial = 5)
    val autoRestTimer by settingsManager.autoRestTimerOnSetFlow.collectAsState(initial = true)
    val defaultRestSecs by settingsManager.defaultRestSecondsFlow.collectAsState(initial = 90)
    val defaultExerciseRestSecs by settingsManager.defaultExerciseRestSecondsFlow.collectAsState(initial = 120)
    val keepScreenOn by settingsManager.keepScreenOnFlow.collectAsState(initial = true)
    val hapticEnabled by settingsManager.hapticEnabledFlow.collectAsState(initial = true)
    val soundEnabled by settingsManager.soundEnabledFlow.collectAsState(initial = true)
    val preAlertEnabled by settingsManager.preAlertEnabledFlow.collectAsState(initial = true)
    val rirRpeEnabled by settingsManager.rirRpeEnabledFlow.collectAsState(initial = true)

    var activeSheet by remember { mutableStateOf<SettingsSheetType?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("Relatório") }
    var dialogMessage by remember { mutableStateOf("") }
    var isSyncingMedia by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf("") }

    if (activeSheet != null) {
        BackHandler {
            activeSheet = null
        }
    }

    val importExercisesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val result = manifestImporter.importExercises(uri)
                dialogTitle = "Importação de Exercícios"
                dialogMessage = "Importação concluída!\n\nAdicionados: ${result.added}\nAtualizados: ${result.updated}\nInalterados: ${result.unchanged}\nAlternativas vinculadas: ${result.alternativesAdded}\nIgnorados/Erros: ${result.ignored}"
                showDialog = true
            }
        }
    }

    val importProgramLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val result = programImporter.importProgram(uri)
                dialogTitle = "Importação de Treino/Programa"
                if (result.success) {
                    dialogMessage = "Programa '${result.programName}' importado com sucesso!\n\nTreinos: ${result.workoutsCount}\nExercícios mapeados: ${result.exercisesCount}\nExercícios não encontrados (ignorados): ${result.missingExercises}"
                } else {
                    dialogMessage = "Erro ao importar: ${result.error}"
                }
                showDialog = true
            }
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        contentWindowInsets = WindowInsets.navigationBars
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Configurações",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(28.dp))
            
            // TREINO & EXECUÇÃO
            Text("Treino & Execução", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(12.dp))

            SettingsToggleItem(
                title = "Auto Check-in",
                subtitle = "Registrar check-in ao iniciar o treino",
                checked = autoCheckIn,
                onCheckedChange = { coroutineScope.launch { settingsManager.setAutoCheckIn(it) } }
            )

            SettingsToggleItem(
                title = "Auto Check-out",
                subtitle = "Registrar check-out ao finalizar o treino",
                checked = autoCheckOut,
                onCheckedChange = { coroutineScope.launch { settingsManager.setAutoCheckOut(it) } }
            )

            SettingsToggleItem(
                title = "Timer automático",
                subtitle = "Iniciar descanso ao marcar série concluída",
                checked = autoRestTimer,
                onCheckedChange = { coroutineScope.launch { settingsManager.setAutoRestTimerOnSet(it) } }
            )

            SettingsToggleItem(
                title = "Manter tela ligada",
                subtitle = "Evita que o dispositivo bloqueie durante a execução",
                checked = keepScreenOn,
                onCheckedChange = { coroutineScope.launch { settingsManager.setKeepScreenOn(it) } }
            )

            SettingsValueItem(
                title = "Descanso padrão entre séries",
                valueText = "${defaultRestSecs}s",
                onClick = { activeSheet = SettingsSheetType.RestBetweenSets }
            )

            SettingsValueItem(
                title = "Descanso padrão entre exercícios",
                valueText = "${defaultExerciseRestSecs}s",
                onClick = { activeSheet = SettingsSheetType.RestBetweenExercises }
            )

            SettingsValueItem(
                title = "Meta semanal de treinos",
                valueText = "$weeklyGoal dias",
                onClick = { activeSheet = SettingsSheetType.WeeklyGoal }
            )

            Spacer(modifier = Modifier.height(28.dp))
            // FEEDBACK & ESFORÇO
            Text("Feedback & Esforço", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(12.dp))

            SettingsToggleItem(
                title = "Feedback tátil (Vibração)",
                subtitle = "Vibrar ao concluir séries e no fim do timer",
                checked = hapticEnabled,
                onCheckedChange = { coroutineScope.launch { settingsManager.setHapticEnabled(it) } }
            )

            SettingsToggleItem(
                title = "Alerta sonoro",
                subtitle = "Tocar bipe quando o timer de descanso expirar",
                checked = soundEnabled,
                onCheckedChange = { coroutineScope.launch { settingsManager.setSoundEnabled(it) } }
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
            // MULTIMÍDIA & DEMONSTRAÇÕES
            Text("Multimídia & Demonstrações", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(12.dp))

            SettingsToggleItem(
                title = "Exibir GIFs e Fotos",
                subtitle = "Mostrar animações de demonstração nas fichas",
                checked = showGifs,
                onCheckedChange = { coroutineScope.launch { settingsManager.setShowGifs(it) } }
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
                            val result = mediaEngine.syncExerciseGifs { cur, tot ->
                                syncProgress = "Verificando exercício $cur de $tot..."
                            }
                            isSyncingMedia = false
                            dialogTitle = "Sincronização de Demonstrações"
                            dialogMessage = if (result.isOffline) {
                                "Não foi possível conectar ao ExerciseDB.\n\nVerifique a conexão de internet. Todo o treino continua funcionando 100% offline."
                            } else {
                                "Demonstrações atualizadas com sucesso!\n\nMapeados: ${result.matched}\nAmbíguos: ${result.ambiguous}\nNão encontrados: ${result.notFound}"
                            }
                            showDialog = true
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(28.dp))
            // DADOS E IMPORTAÇÃO
            Text("Dados e Importação", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(12.dp))

            SettingsActionItem(
                title = "GERENCIAR DADOS & IMPORTAÇÃO",
                icon = Icons.Default.FolderOpen,
                onClick = { activeSheet = SettingsSheetType.ManageData }
            )
            
            Spacer(modifier = Modifier.height(48.dp))
        }
        
        // MODAL BOTTOM SHEETS
        when (activeSheet) {
            is SettingsSheetType.RestBetweenSets -> {
                val presets = listOf(30, 45, 60, 90, 120, 180, 240, 300)
                AppModalBottomSheet(
                    onDismissRequest = { activeSheet = null },
                    title = stringResource(id = R.string.sheet_rest_between_sets_title),
                    subtitle = "Descanso padrão ao concluir série"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        presets.forEach { sec ->
                            val label = if (sec < 60) "${sec} segundos" else if (sec % 60 == 0) "${sec / 60} min (${sec}s)" else "${sec}s"
                            BottomSheetActionItem(
                                title = label,
                                selected = defaultRestSecs == sec,
                                onClick = {
                                    coroutineScope.launch { settingsManager.setDefaultRestSeconds(sec) }
                                    activeSheet = null
                                }
                            )
                        }
                        val isCustomSelected = defaultRestSecs !in presets
                        BottomSheetActionItem(
                            title = if (isCustomSelected) "Personalizado (${defaultRestSecs}s)" else "Personalizado...",
                            selected = isCustomSelected,
                            onClick = { activeSheet = SettingsSheetType.CustomRestBetweenSets }
                        )
                    }
                }
            }

            is SettingsSheetType.CustomRestBetweenSets -> {
                var customText by remember { mutableStateOf(defaultRestSecs.toString()) }
                AppModalBottomSheet(
                    onDismissRequest = { activeSheet = null },
                    title = stringResource(id = R.string.sheet_custom_rest_title),
                    subtitle = "Descanso entre séries (em segundos)"
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth().imePadding()
                    ) {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Segundos") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Lime400,
                                unfocusedBorderColor = BorderLight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val sec = customText.toIntOrNull() ?: 90
                                coroutineScope.launch { settingsManager.setDefaultRestSeconds(sec) }
                                activeSheet = null
                            },
                            enabled = customText.isNotBlank() && (customText.toIntOrNull() ?: 0) > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("SALVAR", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }

            is SettingsSheetType.RestBetweenExercises -> {
                val presets = listOf(60, 90, 120, 180, 240, 300)
                AppModalBottomSheet(
                    onDismissRequest = { activeSheet = null },
                    title = stringResource(id = R.string.sheet_rest_between_exercises_title),
                    subtitle = "Descanso automático entre trocas de exercício"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        presets.forEach { sec ->
                            val label = if (sec < 60) "${sec} segundos" else if (sec % 60 == 0) "${sec / 60} min (${sec}s)" else "${sec}s"
                            BottomSheetActionItem(
                                title = label,
                                selected = defaultExerciseRestSecs == sec,
                                onClick = {
                                    coroutineScope.launch { settingsManager.setDefaultExerciseRestSeconds(sec) }
                                    activeSheet = null
                                }
                            )
                        }
                        val isCustomSelected = defaultExerciseRestSecs !in presets
                        BottomSheetActionItem(
                            title = if (isCustomSelected) "Personalizado (${defaultExerciseRestSecs}s)" else "Personalizado...",
                            selected = isCustomSelected,
                            onClick = { activeSheet = SettingsSheetType.CustomRestBetweenExercises }
                        )
                    }
                }
            }

            is SettingsSheetType.CustomRestBetweenExercises -> {
                var customText by remember { mutableStateOf(defaultExerciseRestSecs.toString()) }
                AppModalBottomSheet(
                    onDismissRequest = { activeSheet = null },
                    title = stringResource(id = R.string.sheet_custom_rest_title),
                    subtitle = "Descanso entre exercícios (em segundos)"
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth().imePadding()
                    ) {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Segundos") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Lime400,
                                unfocusedBorderColor = BorderLight,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val sec = customText.toIntOrNull() ?: 120
                                coroutineScope.launch { settingsManager.setDefaultExerciseRestSeconds(sec) }
                                activeSheet = null
                            },
                            enabled = customText.isNotBlank() && (customText.toIntOrNull() ?: 0) > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("SALVAR", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }

            is SettingsSheetType.WeeklyGoal -> {
                SelectionBottomSheet(
                    title = stringResource(id = R.string.sheet_weekly_goal_title),
                    subtitle = "Selecione a meta de dias por semana",
                    options = listOf(1, 2, 3, 4, 5, 6, 7),
                    selectedOption = weeklyGoal,
                    optionTitle = { day -> if (day == 1) "1 dia por semana" else "$day dias por semana" },
                    onOptionSelected = { goal ->
                        coroutineScope.launch { settingsManager.setWeeklyGoal(goal) }
                        activeSheet = null
                    },
                    onDismissRequest = { activeSheet = null }
                )
            }

            is SettingsSheetType.ManageData -> {
                AppModalBottomSheet(
                    onDismissRequest = { activeSheet = null },
                    title = "Gerenciar Dados",
                    subtitle = "Importar, reimportar e exportar seus dados de treino"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BottomSheetActionItem(
                            title = "Reimportar Catálogo Canônico (144 exercícios)",
                            selected = false,
                            onClick = {
                                activeSheet = SettingsSheetType.ConfirmReimportCatalog
                            }
                        )
                        BottomSheetActionItem(
                            title = "Importar Exercícios Personalizados (JSON)",
                            selected = false,
                            onClick = {
                                activeSheet = null
                                importExercisesLauncher.launch("application/json")
                            }
                        )
                        BottomSheetActionItem(
                            title = "Importar Programa / Ficha (JSON)",
                            selected = false,
                            onClick = {
                                activeSheet = null
                                importProgramLauncher.launch("application/json")
                            }
                        )
                        BottomSheetActionItem(
                            title = "Exportar Histórico Completo (JSON)",
                            selected = false,
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
                    title = stringResource(id = R.string.sheet_reimport_catalog_confirm_title),
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
                            colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
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

            null -> {}
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
