package com.example.presentation.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.remote.NetworkTestResult
import com.example.data.remote.provider.ExerciseProviderFactory
import com.example.domain.engine.ExerciseMediaEngine
import com.example.domain.engine.ExportEngine
import com.example.domain.engine.ManifestImporter
import com.example.domain.engine.PremiumManifestImporter
import com.example.domain.engine.ProgramImporter
import com.example.domain.engine.WorkoutEngine
import com.example.service.WorkoutNotificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** O resultado de uma operação das Configurações, já no formato que o diálogo mostra. */
data class SettingsDialogMessage(
    val title: String,
    val message: String
)

data class SettingsUiState(
    val isSyncingMedia: Boolean = false,
    /** Texto de progresso da sincronização de mídia; vazio quando ela não está correndo. */
    val syncProgress: String = "",
    val isTestingApi: Boolean = false,
    /** `null` enquanto não há resultado para mostrar. */
    val dialog: SettingsDialogMessage? = null
)

/**
 * O estado e as operações da tela de Configurações.
 *
 * Ela existe porque a tela construía `AppDatabase`, `ExportEngine`, os três importadores e — pior —
 * um **segundo** `WorkoutEngine` e um `ExerciseMediaEngine` dentro da composição, em paralelo com os
 * do `MainApplication` (arquitetura paralela, proibida pelo §3). E porque importar catálogo,
 * sincronizar mídia e exportar dados rodavam num `rememberCoroutineScope`: uma mudança de
 * configuração cancelava a operação no meio e o resultado sumia.
 *
 * O `WorkoutEngine` chega pronto, do Application — é o mesmo que executa treino. Os importadores e o
 * motor de mídia são construídos aqui, uma vez, porque o Application ainda não os expõe; eles
 * carregam só `AppDatabase` + `Context`, e nenhum deles guarda estado entre chamadas.
 */
class SettingsViewModel(
    private val settingsManager: SettingsManager,
    private val database: AppDatabase,
    private val workoutEngine: WorkoutEngine,
    /**
     * O motor de mídia do `Application`, quando existe.
     *
     * Nulo só em teste; aí a tela constrói o seu, como fazia antes. Em produção passa o do
     * `MainApplication`, e é o que impede um segundo cliente HTTP para o mesmo catálogo.
     */
    private val mediaEngine: ExerciseMediaEngine? = null,
    /**
     * Quem sabe se o sistema permite alarme exato e onde o usuário concede isso.
     *
     * Nulo só em teste; aí a tela não mostra o atalho. Ver `WorkoutNotificationManager`.
     */
    private val notificationManager: WorkoutNotificationManager? = null,
    /** Sempre o `applicationContext`: importadores e exportação vivem mais que uma Activity. */
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Os valores iniciais repetem exatamente os que a tela usava com `collectAsState(initial = ...)`
    // — trocá-los mudaria o que o usuário vê no primeiro frame.
    val preAlertEnabled: StateFlow<Boolean> = stateOf(settingsManager.preAlertEnabledFlow, false)
    val soundEnabled: StateFlow<Boolean> = stateOf(settingsManager.soundEnabledFlow, true)
    val hapticEnabled: StateFlow<Boolean> = stateOf(settingsManager.hapticEnabledFlow, true)
    val timerNotificationEnabled: StateFlow<Boolean> =
        stateOf(settingsManager.timerNotificationEnabledFlow, true)

    /**
     * O sistema permite alarme exato para o fim do descanso (auditoria 2026-09-12).
     *
     * `true` por padrão porque o atalho de concessão só deve aparecer quando há certeza de que
     * falta permissão — e porque em Android 11 e anteriores ela nem existe. A tela pede
     * [refreshExactAlarmPermission] ao voltar ao primeiro plano, que é quando o usuário retorna
     * das Configurações do sistema.
     */
    private val _exactAlarmAllowed = MutableStateFlow(true)
    val exactAlarmAllowed: StateFlow<Boolean> = _exactAlarmAllowed.asStateFlow()

    fun refreshExactAlarmPermission() {
        _exactAlarmAllowed.value = notificationManager?.canScheduleExactAlarms() ?: true
    }

    /** A tela do sistema para conceder alarme exato, ou `null` onde ela não existe. */
    fun exactAlarmSettingsIntent(): android.content.Intent? = notificationManager?.exactAlarmSettingsIntent()
    val exerciseDbV2ApiKey: StateFlow<String> = stateOf(settingsManager.exerciseDbV2ApiKeyFlow, "")
    val rirRpeEnabled: StateFlow<Boolean> = stateOf(settingsManager.rirRpeEnabledFlow, false)
    val showGifs: StateFlow<Boolean> = stateOf(settingsManager.showGifsFlow, true)
    val showCoachTip: StateFlow<Boolean> = stateOf(settingsManager.showCoachTipFlow, true)
    val defaultRestSeconds: StateFlow<Int> = stateOf(settingsManager.defaultRestSecondsFlow, 60)
    val defaultExerciseRestSeconds: StateFlow<Int> =
        stateOf(settingsManager.defaultExerciseRestSecondsFlow, 120)

    private val exportEngine by lazy { ExportEngine(database.workoutDao(), appContext) }
    private val manifestImporter by lazy { ManifestImporter(database, appContext, settingsManager) }
    private val programImporter by lazy { ProgramImporter(database, appContext) }
    private val premiumImporter by lazy {
        PremiumManifestImporter(database, appContext, settingsManager)
    }
    private val media by lazy {
        mediaEngine ?: ExerciseMediaEngine(
            dao = database.workoutDao(),
            remoteDataSource = ExerciseProviderFactory.create(database.workoutDao(), settingsManager),
            context = appContext
        )
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialog = null) }
    }

    fun setSoundEnabled(enabled: Boolean) = persist { settingsManager.setSoundEnabled(enabled) }

    fun setHapticEnabled(enabled: Boolean) = persist { settingsManager.setHapticEnabled(enabled) }

    fun setTimerNotificationEnabled(enabled: Boolean) =
        persist { settingsManager.setTimerNotificationEnabled(enabled) }

    fun setPreAlertEnabled(enabled: Boolean) = persist { settingsManager.setPreAlertEnabled(enabled) }

    fun setRirRpeEnabled(enabled: Boolean) = persist { settingsManager.setRirRpeEnabled(enabled) }

    fun setShowGifs(show: Boolean) = persist { settingsManager.setShowGifs(show) }

    fun setShowCoachTip(show: Boolean) = persist { settingsManager.setShowCoachTip(show) }

    fun setDefaultExerciseRestSeconds(seconds: Int) =
        persist { settingsManager.setDefaultExerciseRestSeconds(seconds) }

    /**
     * Grava a chave da API.
     *
     * A tela chama isto quando o campo perde o foco, e não a cada tecla: gravar por tecla fazia a
     * emissão do DataStore da tecla anterior reescrever o campo e derrubar caracteres.
     */
    fun setExerciseDbV2ApiKey(key: String) = persist { settingsManager.setExerciseDbV2ApiKey(key) }

    /**
     * Aplica o novo descanso entre séries.
     *
     * Quando [updateExisting] é falso a preferência vale só para treinos novos — e é por isso que
     * o diálogo de confirmação existe. O motor que reescreve os treinos é o do Application, o mesmo
     * que executa o treino.
     */
    fun applyRestBetweenSets(seconds: Int, updateExisting: Boolean) {
        viewModelScope.launch {
            settingsManager.setDefaultRestSeconds(seconds)
            if (updateExisting) {
                workoutEngine.updateExistingWorkoutsRestDuration(seconds)
                show(
                    "Descanso Atualizado",
                    "Novo tempo de descanso aplicado nas configurações e aos treinos existentes com sucesso."
                )
            }
        }
    }

    /** Importa um catálogo de exercícios escolhido pelo usuário no seletor de arquivos. */
    fun importCatalogFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = readText(uri)
                val result = manifestImporter.importFromJsonString(json)
                show(
                    "Importação de Catálogo",
                    "Importação concluída!\n\nAdicionados: ${result.added}\nAtualizados: ${result.updated}\n" +
                        "Inalterados: ${result.unchanged}\nAlternativas vinculadas: ${result.alternativesAdded}\n" +
                        "Ignorados/Erros: ${result.ignored}"
                )
            } catch (e: CancellationException) {
                // Cancelamento não é falha: ele não vira diálogo de erro.
                throw e
            } catch (e: Exception) {
                show("Erro", "Erro ao importar: ${e.message}")
            }
        }
    }

    /** Importa um programa de treino escolhido pelo usuário no seletor de arquivos. */
    fun importProgramFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = readText(uri)
                val result = programImporter.importProgramFromJson(json)
                show(
                    "Importação de Programa",
                    "Programa '${result.programName}' importado com sucesso!\n\n" +
                        "Treinos: ${result.workoutsCount}\nExercícios mapeados: ${result.exercisesCount}\n" +
                        "Exercícios não encontrados (ignorados): ${result.missingExercises}"
                )
            } catch (e: CancellationException) {
                // Cancelamento não é falha: ele não vira diálogo de erro.
                throw e
            } catch (e: Exception) {
                show("Erro", "Erro ao importar: ${e.message}")
            }
        }
    }

    /** Reimporta o catálogo canônico que vem nos assets do app. */
    fun reimportCanonicalCatalog() {
        viewModelScope.launch {
            try {
                val json = appContext.assets
                    .open("catalog/catalogo_exercicios_base_ptbr.v1.json")
                    .bufferedReader()
                    .use { it.readText() }
                val result = manifestImporter.importFromJsonString(json, force = true)
                if (result.errors.isNotEmpty()) {
                    show("Avisos/Erros no Catálogo", result.errors.joinToString("\n"))
                } else {
                    show(
                        "Catálogo Canônico",
                        "Catálogo canônico sincronizado com sucesso!\n\n144 exercícios processados de forma transacional.\n\n" +
                            "Novos adicionados: ${result.added}\nAtualizados: ${result.updated}\n" +
                            "Inalterados: ${result.unchanged}\nAlternativas vinculadas: ${result.alternativesAdded}"
                    )
                }
            } catch (e: CancellationException) {
                // Cancelamento não é falha: ele não vira diálogo de erro.
                throw e
            } catch (e: Exception) {
                show("Erro", "Erro ao carregar catálogo: ${e.message}")
            }
        }
    }

    /** Recarrega o manifesto premium dos assets. */
    fun syncPremiumManifest() {
        viewModelScope.launch {
            try {
                val result = premiumImporter.importFromAssets(
                    "catalog/exercise-content-manifest.v2.json",
                    force = true
                )
                premiumImporter.seedPremiumTestWorkoutIfNeeded()
                show(
                    "Exercise Premium v2 Import",
                    result.formattedReport.ifEmpty {
                        "Importação concluída. Importados: ${result.added + result.updated}"
                    }
                )
            } catch (e: CancellationException) {
                // Cancelamento não é falha: ele não vira diálogo de erro.
                throw e
            } catch (e: Exception) {
                show("Erro", "Erro ao carregar manifesto premium: ${e.message}")
            }
        }
    }

    /** Diagnóstico de cobertura do catálogo remoto. */
    fun runExerciseDbAudit() {
        viewModelScope.launch {
            val diag = media.getLibraryDiagnostic()
            show(
                "Auditoria ExerciseDB",
                "Total exercícios:\n${diag.totalExercises}\n\n" +
                    "Com exerciseDbSearch:\n${diag.withExerciseDbSearch}\n\n" +
                    "Sem exerciseDbSearch:\n${diag.withoutExerciseDbSearch}\n\n" +
                    "Mapeados:\n${diag.matchedCount}\n\n" +
                    "Ambíguos:\n${diag.ambiguousCount}\n\n" +
                    "Não encontrados:\n${diag.notFoundCount}\n\n" +
                    "Sem mídia:\n${diag.noMediaCount}"
            )
        }
    }

    /** Exporta o histórico e abre o seletor de compartilhamento do sistema. */
    fun exportData() {
        viewModelScope.launch {
            try {
                exportEngine.exportData()
            } catch (e: CancellationException) {
                // Cancelamento não é falha: ele não vira diálogo de erro.
                throw e
            } catch (e: Exception) {
                // Antes desta tela ter ViewModel, uma falha aqui era silenciosa: o usuário tocava
                // "Exportar" e nada acontecia, sem erro nenhum.
                show("Erro", "Não foi possível exportar os dados: ${e.message}")
            }
        }
    }

    /** Sincroniza as demonstrações (GIFs) do ExerciseDB. Um toque durante a sincronização é ignorado. */
    fun syncMedia() {
        if (_uiState.value.isSyncingMedia) return
        _uiState.update {
            it.copy(isSyncingMedia = true, syncProgress = "Consultando catálogo remoto...")
        }
        viewModelScope.launch {
            try {
                val result = media.syncExerciseGifs(
                    onCatalogProgress = { loaded, total ->
                        _uiState.update {
                            it.copy(
                                syncProgress = if (total != null) {
                                    "Baixando catálogo: $loaded de $total exercícios..."
                                } else {
                                    "Baixando catálogo: $loaded exercícios..."
                                }
                            )
                        }
                    }
                ) { cur, tot ->
                    _uiState.update { it.copy(syncProgress = "Verificando exercício $cur de $tot...") }
                }
                // Progresso parcial também é gravado: um catálogo incompleto já rendeu
                // correspondências e a próxima execução retoma o resto.
                if (!result.isOffline && result.matched + result.alreadyUpToDate > 0) {
                    settingsManager.setLastMediaSyncAt(System.currentTimeMillis())
                    if (result.catalogComplete && result.errors.isEmpty()) {
                        settingsManager.setMediaSyncContentVersion(1)
                    }
                }
                val diag = media.getLibraryDiagnostic()
                _uiState.update { it.copy(isSyncingMedia = false, syncProgress = "") }
                val message = if (result.isOffline) {
                    "Não foi possível conectar ao ExerciseDB.\n\nVerifique a conexão de internet. " +
                        "Todo o treino continua funcionando 100% offline."
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
                            result.errors.forEach { err -> append("• $err\n") }
                        }
                    }
                }
                show("Sincronização de Demonstrações", message)
            } catch (e: CancellationException) {
                // Cancelamento não é falha: ele não vira diálogo de erro.
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncingMedia = false, syncProgress = "") }
                show("Erro", "Falha ao sincronizar demonstrações: ${e.message}")
            }
        }
    }

    /** Testa a conexão com o ExerciseDB. Um toque durante o teste é ignorado. */
    fun testConnection() {
        if (_uiState.value.isTestingApi) return
        _uiState.update { it.copy(isTestingApi = true) }
        viewModelScope.launch {
            try {
                val testRes = media.testConnection("bench press")
                _uiState.update { it.copy(isTestingApi = false) }
                when (testRes) {
                    is NetworkTestResult.Success -> show(
                        "API Conectada com Sucesso",
                        buildString {
                            append("Status: Conexão ativa com ExerciseDB\n\n")
                            append("Consulta de teste: '${testRes.query}'\n")
                            append("Exercício retornado: ${testRes.foundName}\n")
                            append("ID remoto: ${testRes.exerciseId}\n")
                            append("GIF URL: ${testRes.gifUrl ?: "Não retornado"}\n")
                            append("Resultados encontrados: ${testRes.totalResults}\n")
                        }
                    )
                    is NetworkTestResult.Failure -> show(
                        "Falha ExerciseDB",
                        buildString {
                            if (testRes.httpCode != null) append("HTTP: ${testRes.httpCode}\n")
                            if (testRes.url != null) append("URL: ${testRes.url}\n\n")
                            append("Mensagem: ${testRes.errorMessage}\n")
                        }
                    )
                }
            } catch (e: CancellationException) {
                // Cancelamento não é falha: ele não vira diálogo de erro.
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isTestingApi = false) }
                show("Erro", "Falha ao testar conexão: ${e.message}")
            }
        }
    }

    private suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun show(title: String, message: String) {
        _uiState.update { it.copy(dialog = SettingsDialogMessage(title, message)) }
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun <T> stateOf(
        flow: kotlinx.coroutines.flow.Flow<T>,
        initial: T
    ): StateFlow<T> = flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}
