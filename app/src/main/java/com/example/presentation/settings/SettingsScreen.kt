package com.example.presentation.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainApplication
import com.example.domain.engine.ExerciseMediaEngine
import com.example.domain.engine.ManifestImporter
import com.example.domain.engine.ProgramImporter
import com.example.ui.theme.*
import kotlinx.coroutines.launch

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
    val keepScreenOn by settingsManager.keepScreenOnFlow.collectAsState(initial = true)
    val hapticEnabled by settingsManager.hapticEnabledFlow.collectAsState(initial = true)
    val soundEnabled by settingsManager.soundEnabledFlow.collectAsState(initial = true)
    val rirRpeEnabled by settingsManager.rirRpeEnabledFlow.collectAsState(initial = true)

    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("Relatório") }
    var dialogMessage by remember { mutableStateOf("") }
    var isSyncingMedia by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf("") }

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

    Scaffold(containerColor = BackgroundDark) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Configurações", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(28.dp))
            
            Text("Treino & Execução", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            
            // Auto Check-in
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Check-in", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Registrar check-in ao iniciar o treino", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(checked = autoCheckIn, onCheckedChange = { coroutineScope.launch { settingsManager.setAutoCheckIn(it) } }, colors = SwitchDefaults.colors(checkedThumbColor = Lime400, checkedTrackColor = Lime400.copy(alpha=0.5f)))
            }
            Spacer(modifier = Modifier.height(14.dp))
            
            // Auto Check-out
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Check-out", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Registrar check-out ao finalizar o treino", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(checked = autoCheckOut, onCheckedChange = { coroutineScope.launch { settingsManager.setAutoCheckOut(it) } }, colors = SwitchDefaults.colors(checkedThumbColor = Lime400, checkedTrackColor = Lime400.copy(alpha=0.5f)))
            }
            Spacer(modifier = Modifier.height(14.dp))

            // Auto Rest Timer
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Timer automático", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Iniciar descanso ao marcar série concluída", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(checked = autoRestTimer, onCheckedChange = { coroutineScope.launch { settingsManager.setAutoRestTimerOnSet(it) } }, colors = SwitchDefaults.colors(checkedThumbColor = Lime400, checkedTrackColor = Lime400.copy(alpha=0.5f)))
            }
            Spacer(modifier = Modifier.height(14.dp))

            // Keep Screen On
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Manter tela ligada", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Evita que o dispositivo bloqueie durante a execução", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(checked = keepScreenOn, onCheckedChange = { coroutineScope.launch { settingsManager.setKeepScreenOn(it) } }, colors = SwitchDefaults.colors(checkedThumbColor = Lime400, checkedTrackColor = Lime400.copy(alpha=0.5f)))
            }
            Spacer(modifier = Modifier.height(14.dp))

            // Haptic & Sound
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Feedback tátil (Vibração)", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Vibrar ao concluir séries e no fim do timer", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(checked = hapticEnabled, onCheckedChange = { coroutineScope.launch { settingsManager.setHapticEnabled(it) } }, colors = SwitchDefaults.colors(checkedThumbColor = Lime400, checkedTrackColor = Lime400.copy(alpha=0.5f)))
            }
            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Alerta sonoro", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tocar bipe quando o timer de descanso expirar", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(checked = soundEnabled, onCheckedChange = { coroutineScope.launch { settingsManager.setSoundEnabled(it) } }, colors = SwitchDefaults.colors(checkedThumbColor = Lime400, checkedTrackColor = Lime400.copy(alpha=0.5f)))
            }
            Spacer(modifier = Modifier.height(14.dp))

            // RPE / RIR
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Campos RPE / RIR", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Permitir registrar esforço percebido por série", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(checked = rirRpeEnabled, onCheckedChange = { coroutineScope.launch { settingsManager.setRirRpeEnabled(it) } }, colors = SwitchDefaults.colors(checkedThumbColor = Lime400, checkedTrackColor = Lime400.copy(alpha=0.5f)))
            }
            Spacer(modifier = Modifier.height(14.dp))

            // Rest duration slider
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Descanso padrão", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${defaultRestSecs}s", color = Lime400, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = defaultRestSecs.toFloat(),
                onValueChange = { coroutineScope.launch { settingsManager.setDefaultRestSeconds(it.toInt()) } },
                valueRange = 30f..300f, steps = 8,
                colors = SliderDefaults.colors(thumbColor = Lime400, activeTrackColor = Lime400, inactiveTrackColor = SurfaceDark)
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Weekly goal slider
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Meta semanal de treinos", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("$weeklyGoal dias", color = Lime400, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = weeklyGoal.toFloat(),
                onValueChange = { coroutineScope.launch { settingsManager.setWeeklyGoal(it.toInt()) } },
                valueRange = 1f..7f, steps = 5,
                colors = SliderDefaults.colors(thumbColor = Lime400, activeTrackColor = Lime400, inactiveTrackColor = SurfaceDark)
            )

            Spacer(modifier = Modifier.height(28.dp))
            Text("Multimídia & Demonstrações", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Exibir GIFs e Fotos", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Mostrar animações de demonstração nas fichas", color = TextSecondary, fontSize = 13.sp)
                }
                Switch(checked = showGifs, onCheckedChange = { coroutineScope.launch { settingsManager.setShowGifs(it) } }, colors = SwitchDefaults.colors(checkedThumbColor = Lime400, checkedTrackColor = Lime400.copy(alpha=0.5f)))
            }
            Spacer(modifier = Modifier.height(12.dp))

            Button(
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
                },
                enabled = !isSyncingMedia,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = Lime400)
            ) {
                if (isSyncingMedia) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Lime400, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(syncProgress, fontSize = 13.sp)
                } else {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ATUALIZAR DEMONSTRAÇÕES (EXERCISEDB)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Catálogo e Importação", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val json = context.assets.open("catalog/catalogo_exercicios_base_ptbr.v1.json").bufferedReader().use { it.readText() }
                            val result = manifestImporter.importFromJsonString(json)
                            dialogTitle = "Catálogo Canônico"
                            dialogMessage = "Catálogo canônico sincronizado com sucesso!\n\n144 exercícios processados de forma transacional.\n\nNovos adicionados: ${result.added}\nAtualizados: ${result.updated}\nInalterados: ${result.unchanged}\nAlternativas vinculadas: ${result.alternativesAdded}"
                        } catch(e: Exception) {
                            dialogTitle = "Erro"
                            dialogMessage = "Erro ao carregar catálogo: ${e.message}"
                        }
                        showDialog = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
            ) { Text("REIMPORTAR CATÁLOGO CANÔNICO (144 EXERCÍCIOS)", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { importExercisesLauncher.launch("application/json") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextPrimary)
            ) { Text("IMPORTAR EXERCÍCIOS PERSONALIZADOS (JSON)", fontSize = 13.sp) }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = { importProgramLauncher.launch("application/json") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextPrimary)
            ) { Text("IMPORTAR PROGRAMA / FICHA (JSON)", fontSize = 13.sp) }

            Spacer(modifier = Modifier.height(28.dp))
            Text("Backup & Exportação", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { coroutineScope.launch { exportEngine.exportData() } },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = Lime400)
            ) { Text("EXPORTAR HISTÓRICO COMPLETO (JSON)", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            
            Spacer(modifier = Modifier.height(48.dp))
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

