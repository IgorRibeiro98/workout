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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Alarm
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.components.BottomSheetActionItem
import com.example.ui.components.SelectionBottomSheet
import com.example.ui.theme.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff

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

/**
 * As opções de descanso. Ficam aqui, e não dentro da composição, porque a lista é a mesma coisa em
 * dois lugares — as opções oferecidas e a opção marcada — e mantê-las escritas duas vezes já fez
 * as duas divergirem.
 */
private val REST_BETWEEN_SETS_OPTIONS = listOf(
    "Desativado" to 0,
    "30 segundos" to 30,
    "45 segundos" to 45,
    "60 segundos (1 min)" to 60,
    "90 segundos (1.5 min)" to 90,
    "120 segundos (2 min)" to 120,
    "180 segundos (3 min)" to 180
)

private val REST_BETWEEN_EXERCISES_OPTIONS = listOf(
    "Desativado" to 0,
    "60 segundos (1 min)" to 60,
    "90 segundos (1.5 min)" to 90,
    "120 segundos (2 min)" to 120,
    "150 segundos (2.5 min)" to 150,
    "180 segundos (3 min)" to 180,
    "240 segundos (4 min)" to 240
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit = {},
    onNavigateToMyEvolution: () -> Unit = {},
    onNavigateToBodyEvolution: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingRestUpdate by remember { mutableStateOf<Int?>(null) }
    var activeSheet by remember { mutableStateOf<SettingsSheetType?>(null) }

    val preAlertEnabled by viewModel.preAlertEnabled.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.soundEnabled.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticEnabled.collectAsStateWithLifecycle()
    val timerNotifEnabled by viewModel.timerNotificationEnabled.collectAsStateWithLifecycle()
    val exactAlarmAllowed by viewModel.exactAlarmAllowed.collectAsStateWithLifecycle()
    val settingsContext = androidx.compose.ui.platform.LocalContext.current
    // Reavalia ao voltar ao primeiro plano: é quando o usuário retorna das Configurações do
    // sistema depois de conceder (ou não) o alarme exato.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refreshExactAlarmPermission()
        onPauseOrDispose { }
    }
    val exerciseDbV2ApiKey by viewModel.exerciseDbV2ApiKey.collectAsStateWithLifecycle()
    val rirRpeEnabled by viewModel.rirRpeEnabled.collectAsStateWithLifecycle()
    val showGifs by viewModel.showGifs.collectAsStateWithLifecycle()
    val showCoachTip by viewModel.showCoachTip.collectAsStateWithLifecycle()

    val defaultRestSecs by viewModel.defaultRestSeconds.collectAsStateWithLifecycle()
    val defaultExerciseRestSecs by viewModel.defaultExerciseRestSeconds.collectAsStateWithLifecycle()

    // A leitura do arquivo e a importação correm no `viewModelScope`: no
    // `rememberCoroutineScope` da tela, uma mudança de configuração cancelava a importação no meio
    // e o resultado sumia sem aviso.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) viewModel.importCatalogFromUri(uri)
    }

    val programImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) viewModel.importProgramFromUri(uri)
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Configurações",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BackgroundDark)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
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
            onCheckedChange = { viewModel.setSoundEnabled(it) }
        )
        SettingsToggleItem(
            title = "Vibração ao finalizar descanso",
            subtitle = "Vibrar o dispositivo ao término do tempo",
            checked = hapticEnabled,
            onCheckedChange = { viewModel.setHapticEnabled(it) }
        )
        SettingsToggleItem(
            title = "Notificação ao finalizar descanso",
            subtitle = "Mostrar alerta visual ao terminar o descanso",
            checked = timerNotifEnabled,
            onCheckedChange = { viewModel.setTimerNotificationEnabled(it) }
        )
        if (!exactAlarmAllowed) {
            // Só aparece quando o sistema nega alarme exato (Android 14+ nasce assim para quem
            // mira 33+). Sem a concessão o aviso de fim de descanso com a tela apagada pode
            // atrasar; com ela, toca na hora. Ver `WorkoutNotificationManager`.
            Spacer(modifier = Modifier.height(8.dp))
            SettingsActionItem(
                title = "Permitir alarme exato no fim do descanso",
                icon = Icons.Default.Alarm,
                onClick = {
                    viewModel.exactAlarmSettingsIntent()?.let { intent ->
                        runCatching { settingsContext.startActivity(intent) }
                    }
                }
            )
        }
        SettingsToggleItem(
            title = "Pré-alerta de descanso",
            subtitle = "Avisar 10 segundos antes do término do descanso",
            checked = preAlertEnabled,
            onCheckedChange = { viewModel.setPreAlertEnabled(it) }
        )
        SettingsToggleItem(
            title = "Campos RPE / RIR",
            subtitle = "Permitir registrar esforço percebido por série",
            checked = rirRpeEnabled,
            onCheckedChange = { viewModel.setRirRpeEnabled(it) }
        )
        
        Spacer(modifier = Modifier.height(28.dp))
        
        Text("Multimídia & Demonstrações", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(12.dp))
        SettingsToggleItem(
            title = "Exibir GIFs e Fotos",
            subtitle = "Mostrar animações de demonstração nas fichas",
            checked = showGifs,
            onCheckedChange = { viewModel.setShowGifs(it) }
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
            // O campo tem estado local próprio e só persiste ao perder o foco. Gravar a cada tecla
            // fazia a emissão do DataStore da tecla anterior reescrever `inputKey` (o
            // `remember(exerciseDbV2ApiKey)` de antes) e derrubar caracteres digitados depressa; e
            // como a gravação era `it.trim()`, um espaço à direita nunca chegava a existir no campo.
            // `null` significa "ainda não editado nesta sessão da tela": aí vale o que está gravado.
            var inputKey by remember { mutableStateOf<String?>(null) }
            var hadFocus by remember { mutableStateOf(false) }
            var keyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = inputKey ?: exerciseDbV2ApiKey,
                onValueChange = { inputKey = it },
                placeholder = { Text("Cole sua chave RapidAPI V2 aqui", fontSize = 12.sp, color = TextSecondary) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            val typed = inputKey?.trim()
                            if (typed != null && typed != exerciseDbV2ApiKey) {
                                viewModel.setExerciseDbV2ApiKey(typed)
                            }
                        }
                    },
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
            onCheckedChange = { viewModel.setShowCoachTip(it) }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        SettingsActionItem(
            title = "ATUALIZAR DEMONSTRAÇÕES (EXERCISEDB)",
            icon = Icons.Default.CloudDownload,
            isLoading = uiState.isSyncingMedia,
            loadingText = uiState.syncProgress,
            onClick = { viewModel.syncMedia() }
        )

        HorizontalDivider(color = BorderLight, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(8.dp))

        SettingsActionItem(
            title = "TESTAR CONEXÃO EXERCISEDB",
            icon = Icons.Default.Refresh,
            isLoading = uiState.isTestingApi,
            loadingText = "Testando conexão com ExerciseDB...",
            onClick = { viewModel.testConnection() }
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
                options = REST_BETWEEN_SETS_OPTIONS,
                selectedOption = REST_BETWEEN_SETS_OPTIONS.find { it.second == defaultRestSecs },
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
                options = REST_BETWEEN_EXERCISES_OPTIONS,
                selectedOption = REST_BETWEEN_EXERCISES_OPTIONS.find { it.second == defaultExerciseRestSecs },
                optionTitle = { it.first },
                onOptionSelected = {
                    viewModel.setDefaultExerciseRestSeconds(it.second)
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
                            viewModel.runExerciseDbAudit()
                        }
                    )

                    
                    BottomSheetActionItem(
                        icon = Icons.Default.Refresh,
                        title = "Sincronizar Manifesto Premium",
                        subtitle = "Carregar informações avançadas do catálogo",
                        onClick = {
                            activeSheet = null
                            viewModel.syncPremiumManifest()
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
                            viewModel.exportData()
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
                            viewModel.reimportCanonicalCatalog()
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
    
    
    val dialog = uiState.dialog
    if (dialog != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDialog() },
            title = { Text(dialog.title, color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(dialog.message, color = TextSecondary, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDialog() }) {
                    Text("OK", color = Lime400, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = SurfaceDark
        )
    }

    val newRest = pendingRestUpdate
    if (newRest != null) {
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
                        viewModel.applyRestBetweenSets(newRest, updateExistingWorkouts)
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
