package com.example
 
import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
import com.example.service.WorkoutNotificationManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.domain.engine.ManifestImporter

class MainApplication : Application(), ImageLoaderFactory, androidx.work.Configuration.Provider {

    /**
     * Inicialização sob demanda do WorkManager (T16.6).
     *
     * O inicializador padrão roda no startup de todo app que tenha WorkManager no classpath. O
     * Spark é local-first: quem nunca ativou a nuvem não paga por um agendador que não usa. O nó
     * `androidx.startup` foi removido do manifesto, e é esta configuração que faz o WorkManager
     * ser construído só na primeira chamada a `WorkManager.getInstance(...)` — que acontece quando
     * uma alteração local precisa ser agendada.
     */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()
    
    lateinit var database: AppDatabase
        internal set
        
    lateinit var repository: WorkoutRepository
        internal set

    lateinit var bodyMeasurementRepository: com.example.data.repository.BodyMeasurementRepository
        internal set

    lateinit var evolutionRepository: com.example.domain.evolution.repository.EvolutionRepository
        internal set

    lateinit var performanceRepository: com.example.domain.evolution.repository.PerformanceRepository
        internal set

    lateinit var consistencyRepository: com.example.domain.evolution.repository.ConsistencyRepository
        internal set

    lateinit var achievementRepository: com.example.domain.evolution.repository.AchievementRepository
        internal set

    lateinit var getEvolutionSummaryUseCase: com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
        internal set
        
    lateinit var gamificationEventRepository: com.example.domain.gamification.repository.GamificationEventRepository
        internal set

    lateinit var gamificationEventPublisher: com.example.domain.gamification.GamificationEventPublisher
        internal set

    lateinit var xpTransactionRepository: com.example.domain.gamification.repository.XpTransactionRepository
        internal set

    lateinit var xpCalculatorService: com.example.domain.gamification.XpCalculatorService
        internal set

    lateinit var missionRepository: com.example.domain.gamification.repository.MissionRepository
        internal set

    lateinit var settingsManager: SettingsManager
        internal set

    /**
     * Fundação de sincronização (T16.3) — identidade global + Outbox transacional.
     *
     * Nada aqui envia nem baixa dado. O coordenador registra intenção de sync na mesma transação
     * da alteração; o montador de snapshot existe para a T16.4 conseguir produzir o primeiro
     * backup; e o `deviceId` identifica a instalação, não a pessoa.
     */
    lateinit var syncMutationCoordinator: com.example.data.sync.SyncMutationCoordinator
        internal set

    lateinit var deviceIdProvider: com.example.data.sync.DeviceIdProvider
        internal set

    lateinit var syncAggregateSnapshotBuilder: com.example.data.sync.SyncAggregateSnapshotBuilder
        internal set

    /**
     * Backup estruturado (T16.4).
     *
     * Nada aqui dispara sozinho. Criar o repositório não vincula dado, não captura snapshot e não
     * abre conexão: a única coisa que produz um backup é o usuário tocar no botão do Perfil, e a
     * adoção do conjunto de dados por uma Conta Spark exige confirmação explícita antes disso.
     */
    lateinit var backupRepository: com.example.data.backup.BackupRepository
        internal set

    /**
     * Restore seguro (T16.5).
     *
     * Idem: criar o repositório não lista, não baixa e não restaura. A única coisa que substitui o
     * dataset é o usuário escolher um backup e confirmar — e a recuperação de abertura só conclui
     * (ou desfaz) o que ele já havia confirmado.
     */
    lateinit var restoreRepository: com.example.data.restore.RestoreRepository
        internal set

    /**
     * Sincronização incremental multi-device (T16.6).
     *
     * Criar o repositório e o coordenador **não** sincroniza, não abre conexão e não agenda nada.
     * Um ciclo nasce de três gatilhos explícitos: o toque em "Sincronizar agora", o app voltando
     * ao primeiro plano com algo pendente ou desatualizado, e o trabalho único que uma alteração
     * local agenda. Não existe laço, polling, trabalho periódico nem tempo real.
     */
    lateinit var syncRepository: com.example.data.sync.SyncRepository
        internal set

    lateinit var syncCoordinator: com.example.data.sync.SyncCoordinator
        internal set
        
    lateinit var workoutEngine: WorkoutEngine
        internal set
        
    lateinit var notificationManager: WorkoutNotificationManager
        internal set

    /**
     * Coach IA (T14 → T16.2).
     *
     * A partir da T16.2 o caminho é **um só**: o Spark Backend. O app não fala com o Gemini, não
     * carrega credencial de modelo e não tem um segundo provider de reserva — um fallback
     * escondido para o Firebase AI Logic criaria custo duplicado e comportamento divergente.
     *
     * `by lazy` de propósito: o Spark é local-first e nada de IA é tocado enquanto o usuário não
     * pedir. Sem endereço de backend configurado, o Coach responde indisponível e o restante do
     * app continua completo.
     */
    val aiCoachGateway: com.example.domain.ai.AiCoachGateway by lazy {
        com.example.data.ai.SparkBackendAiCoachGateway(sparkBackendClient)
    }

    val analyzeWorkoutUseCase: com.example.domain.ai.usecase.AnalyzeWorkoutUseCase by lazy {
        com.example.domain.ai.usecase.AnalyzeWorkoutUseCase(
            contextBuilder = com.example.data.ai.WorkoutAiCoachContextBuilder(
                workoutDao = database.workoutDao(),
                settingsManager = settingsManager
            ),
            gateway = aiCoachGateway,
            telemetry = com.example.data.ai.LogcatAiCoachTelemetry()
        )
    }

    val workoutGenerationContextBuilder: com.example.domain.ai.AiWorkoutGenerationContextBuilder by lazy {
        com.example.data.ai.WorkoutAiGenerationContextBuilder(workoutDao = database.workoutDao())
    }

    val generateWorkoutUseCase: com.example.domain.ai.usecase.GenerateWorkoutUseCase by lazy {
        com.example.domain.ai.usecase.GenerateWorkoutUseCase(
            contextBuilder = workoutGenerationContextBuilder,
            gateway = aiCoachGateway,
            telemetry = com.example.data.ai.LogcatAiCoachTelemetry()
        )
    }

    /** A confirmação do usuário escreve pelo repositório canônico de treinos, como a criação manual. */
    val saveGeneratedWorkoutUseCase: com.example.domain.ai.usecase.SaveGeneratedWorkoutUseCase by lazy {
        com.example.domain.ai.usecase.SaveGeneratedWorkoutUseCase(repository)
    }

    val workoutAdaptationContextBuilder: com.example.domain.ai.AiWorkoutAdaptationContextBuilder by lazy {
        com.example.data.ai.WorkoutAiAdaptationContextBuilder(workoutDao = database.workoutDao())
    }

    val adaptWorkoutUseCase: com.example.domain.ai.usecase.AdaptWorkoutUseCase by lazy {
        com.example.domain.ai.usecase.AdaptWorkoutUseCase(
            contextBuilder = workoutAdaptationContextBuilder,
            gateway = aiCoachGateway,
            telemetry = com.example.data.ai.LogcatAiCoachTelemetry()
        )
    }

    /** A confirmação do usuário edita o treino pelo repositório canônico, como a edição manual. */
    val applyWorkoutAdaptationUseCase: com.example.domain.ai.usecase.ApplyWorkoutAdaptationUseCase by lazy {
        com.example.domain.ai.usecase.ApplyWorkoutAdaptationUseCase(repository)
    }

    /**
     * Coach contextual (T14.4).
     *
     * Instância única para o cache em memória de explicações valer entre as telas: abrir a mesma
     * explicação de novo, na mesma sessão de uso, não paga uma segunda chamada.
     *
     * Recebe apenas leitura — o gateway e o builder de contexto de adaptação. Não recebe
     * `WorkoutRepository`, DAO de escrita nem publicador de gamificação: uma explicação não tem
     * como alterar o domínio porque não tem por onde.
     */
    val explainCoachDecisionUseCase: com.example.domain.ai.usecase.ExplainCoachDecisionUseCase by lazy {
        com.example.domain.ai.usecase.ExplainCoachDecisionUseCase(
            gateway = aiCoachGateway,
            adaptationContextBuilder = workoutAdaptationContextBuilder,
            telemetry = com.example.data.ai.LogcatAiCoachTelemetry()
        )
    }

    /**
     * Conta Spark (T16.1) — Firebase Authentication + Sign in with Google.
     *
     * `by lazy` pelo mesmo motivo do Coach: o Spark é local-first e não paga inicialização de
     * autenticação no startup. Nada de Firebase Auth ou Credential Manager é tocado enquanto o
     * usuário não abrir a área de conta. Uma sessão já existente é restaurada quando isso
     * acontece — sem seletor de contas, que só aparece por toque explícito.
     */
    val authGateway: com.example.domain.auth.AuthGateway by lazy {
        firebaseAuthGateway
    }

    /**
     * O mesmo objeto, na fronteira de token.
     *
     * Uma instância só: o estado da sessão tem um dono, e quem monta o `Authorization: Bearer`
     * pergunta a ele em vez de guardar token em lugar nenhum.
     */
    val authTokenProvider: com.example.domain.auth.AuthTokenProvider by lazy {
        firebaseAuthGateway
    }

    private val firebaseAuthGateway: com.example.data.auth.FirebaseAuthGateway by lazy {
        com.example.data.auth.FirebaseAuthGateway(this)
    }

    /**
     * Cliente do Spark Backend (T16.1), agora também o transporte do Coach (T16.2).
     *
     * `null` quando o build não tem endereço configurado — que é o padrão hoje, porque a VPS
     * ainda não foi provisionada. O núcleo do Spark não depende dele para nada: sem endereço,
     * treino, execução, histórico, templates e gamificação continuam completos, e só o Coach
     * responde indisponível.
     */
    val sparkBackendClient: com.example.data.remote.spark.SparkBackendClient? by lazy {
        BuildConfig.SPARK_BACKEND_BASE_URL
            .takeIf { it.isNotBlank() }
            ?.let { baseUrl ->
                com.example.data.remote.spark.SparkBackendClient(
                    baseUrl = baseUrl,
                    tokens = authTokenProvider
                )
            }
    }

    /**
     * Traduz um `exerciseId` do Coach de volta para o nome exibido.
     *
     * A identidade continua sendo o id: isto existe só para a leitura da recomendação.
     */
    suspend fun resolveExerciseDisplayName(exerciseId: String): String? {
        val dao = database.workoutDao()
        val localId = com.example.domain.ai.AiCoachContextProjector.localRowIdOf(exerciseId)
        val exercise = if (localId != null) {
            dao.getExerciseById(localId)
        } else {
            dao.getExerciseByCanonicalId(exerciseId)
        }
        return exercise?.name
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        settingsManager = SettingsManager(this)

        // Fronteira transacional com a Outbox (T16.3).
        //
        // Um coordenador só, compartilhado por quem escreve dado pessoal. O estado da nuvem é lido
        // do DataStore a cada mutação e o padrão do Spark é **desligado**: nenhuma entrada nasce,
        // e nenhum dado local ganha dono por existir um login. A adoção explícita é da T16.4.
        syncMutationCoordinator = com.example.data.sync.SyncMutationCoordinator(
            transactions = com.example.data.sync.RoomTransactionRunner(database),
            outboxDao = database.syncOutboxDao(),
            scopeProvider = com.example.data.backup.CloudDataBindingScopeProvider(
                database.cloudDataBindingDao()
            ),
            // Depois do commit, e só quando uma entrada realmente nasceu: agenda **um** ciclo com
            // restrição de rede (T16.6). Não é uma requisição HTTP — salvar um treino nunca
            // espera o servidor.
            onMutationsRecorded = {
                if (this::syncCoordinator.isInitialized) syncCoordinator.onLocalMutation()
            }
        )
        deviceIdProvider = com.example.data.sync.DeviceIdProvider(settingsManager)
        syncAggregateSnapshotBuilder = com.example.data.sync.SyncAggregateSnapshotBuilder(
            workoutDao = database.workoutDao(),
            bodyMeasurementDao = database.bodyMeasurementDao()
        )

        // Backup estruturado (T16.4) — Android → snapshot completo → Spark Backend → SQLite.
        //
        // O montador de agregados é o **mesmo** da T16.3: não existe um segundo serializador de
        // treino no Spark. O que a T16.4 acrescenta é o envelope, a identidade da tentativa, o
        // corte da Outbox e o transporte.
        val backupSnapshotBuilder = com.example.data.backup.BackupSnapshotBuilder(
            workoutDao = database.workoutDao(),
            bodyMeasurementDao = database.bodyMeasurementDao(),
            weeklyGoalDao = database.weeklyGoalDao(),
            aggregates = syncAggregateSnapshotBuilder
        )
        val backupSource = com.example.data.backup.BackupSourceDto(
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            databaseVersion = com.example.data.local.AppDatabase.SCHEMA_VERSION
        )
        // Uma trava de nuvem para o aparelho inteiro (T16.5): backup e restore disputam o mesmo
        // banco, e um snapshot capturado no meio de uma substituição descreveria um estado que
        // nunca existiu.
        val cloudOperationLock = com.example.data.sync.CloudOperationLock()

        backupRepository = com.example.data.backup.BackupRepository(
            bindingDao = database.cloudDataBindingDao(),
            attemptDao = database.backupAttemptDao(),
            outboxDao = database.syncOutboxDao(),
            snapshotBuilder = backupSnapshotBuilder,
            api = com.example.data.backup.SparkBackupApi(sparkBackendClient),
            settingsManager = settingsManager,
            deviceIdProvider = deviceIdProvider,
            transactions = com.example.data.sync.RoomTransactionRunner(database),
            source = backupSource,
            operationLock = cloudOperationLock
        )

        // Restore seguro (T16.5) — Spark Backend → validação → preview → transação Room.
        //
        // Os arquivos vivem no armazenamento **privado** do app: um snapshot é o histórico inteiro
        // da pessoa. O snapshot de segurança usa o mesmo montador do backup, então desfazer um
        // restore é restaurar — mesmo leitor, mesmo validador, mesma transação.
        val restoreFiles = com.example.data.restore.RestoreFileStore(
            java.io.File(filesDir, "restore")
        )
        restoreRepository = com.example.data.restore.RestoreRepository(
            api = com.example.data.restore.SparkRestoreApi(sparkBackendClient),
            attemptDao = database.restoreAttemptDao(),
            restoreDao = database.restoreDao(),
            bindingDao = database.cloudDataBindingDao(),
            outboxDao = database.syncOutboxDao(),
            snapshotBuilder = backupSnapshotBuilder,
            planBuilder = com.example.data.restore.RestorePlanBuilder(database.workoutDao()),
            transaction = com.example.data.restore.RestoreTransaction(
                transactions = com.example.data.sync.RoomTransactionRunner(database),
                restoreDao = database.restoreDao(),
                workoutDao = database.workoutDao(),
                bodyMeasurementDao = database.bodyMeasurementDao(),
                weeklyGoalDao = database.weeklyGoalDao(),
                bindingDao = database.cloudDataBindingDao()
            ),
            safetySnapshots = com.example.data.restore.RestoreSafetySnapshotStore(
                snapshotBuilder = backupSnapshotBuilder,
                settingsManager = settingsManager,
                deviceIdProvider = deviceIdProvider,
                transactions = com.example.data.sync.RoomTransactionRunner(database),
                files = restoreFiles,
                source = backupSource
            ),
            files = restoreFiles,
            settingsManager = settingsManager,
            deviceIdProvider = deviceIdProvider,
            transactions = com.example.data.sync.RoomTransactionRunner(database),
            operationLock = cloudOperationLock
        )

        // Sincronização incremental (T16.6) — Android ⇄ Spark Backend, por mudança.
        //
        // Ela reusa tudo que já existe: o `SyncAggregateSnapshotBuilder` da T16.3 monta os
        // payloads (não há um segundo serializador de treino no Spark), o `SparkBackendClient` da
        // T16.1 é o transporte, e a **mesma** `CloudOperationLock` do backup e do restore impede
        // um ciclo de rodar no meio de uma substituição de dataset.
        syncRepository = com.example.data.sync.SyncRepository(
            bindingDao = database.cloudDataBindingDao(),
            outboxDao = database.syncOutboxDao(),
            metadataDao = database.entitySyncMetadataDao(),
            cursorDao = database.syncCursorDao(),
            conflictDao = database.syncConflictDao(),
            pushBuilder = com.example.data.sync.SyncPushBuilder(
                outboxDao = database.syncOutboxDao(),
                metadataDao = database.entitySyncMetadataDao(),
                snapshotBuilder = syncAggregateSnapshotBuilder
            ),
            applier = com.example.data.sync.SyncRemoteApplier(
                transactions = com.example.data.sync.RoomTransactionRunner(database),
                workoutDao = database.workoutDao(),
                bodyMeasurementDao = database.bodyMeasurementDao(),
                outboxDao = database.syncOutboxDao(),
                metadataDao = database.entitySyncMetadataDao(),
                cursorDao = database.syncCursorDao(),
                conflictDao = database.syncConflictDao(),
                snapshotBuilder = syncAggregateSnapshotBuilder
            ),
            api = com.example.data.sync.SparkSyncApi(sparkBackendClient),
            deviceId = { deviceIdProvider.deviceId() },
            transactions = com.example.data.sync.RoomTransactionRunner(database),
            operationLock = cloudOperationLock
        )

        syncCoordinator = com.example.data.sync.SyncCoordinator(
            repository = syncRepository,
            // A sessão é lida do Firebase Auth **quando** um ciclo já decidiu que vai acontecer.
            // Sem vínculo e sem backend, nada de autenticação é tocado.
            accounts = com.example.data.sync.SyncAccountProvider {
                (authGateway.state.value as? com.example.domain.auth.AuthState.SignedIn)
                    ?.account?.uid
            },
            scheduler = com.example.service.WorkManagerSyncScheduler(this),
            scope = CoroutineScope(Dispatchers.Default)
        )

        repository = WorkoutRepository(
            database.workoutDao(),
            settingsManager = settingsManager,
            syncMutations = syncMutationCoordinator
        )
        bodyMeasurementRepository = com.example.data.repository.BodyMeasurementRepository(
            dao = database.bodyMeasurementDao(),
            syncMutations = syncMutationCoordinator
        )
        evolutionRepository = com.example.data.repository.EvolutionRepositoryImpl(bodyMeasurementRepository, database.workoutDao())
        performanceRepository = com.example.data.repository.PerformanceRepositoryImpl(database.workoutDao())
        consistencyRepository = com.example.data.repository.ConsistencyRepositoryImpl(
            workoutDao = database.workoutDao(),
            weeklyGoalDao = database.weeklyGoalDao(),
            settingsManager = settingsManager
        )
        getEvolutionSummaryUseCase = com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase(evolutionRepository)
        achievementRepository = com.example.data.repository.AchievementRepositoryImpl(achievementDao = database.achievementDao(), workoutDao = database.workoutDao(), gamificationEventDao = database.gamificationEventDao(), consistencyRepository = consistencyRepository, bodyMeasurementRepository = bodyMeasurementRepository)
        bodyMeasurementRepository.onMeasurementChanged = {
            achievementRepository.evaluateAndUnlock(com.example.domain.evolution.repository.AchievementEvaluationOrigin.LIVE)
        }
        gamificationEventRepository = com.example.data.repository.GamificationEventRepositoryImpl(
            database.gamificationEventDao()
        )
        xpTransactionRepository = com.example.data.repository.XpTransactionRepositoryImpl(
            database.xpTransactionDao()
        )
        xpCalculatorService = com.example.domain.gamification.XpCalculatorService(
            xpTransactionRepository
        )
        missionRepository = com.example.data.repository.MissionRepositoryImpl(
            consistencyRepository = consistencyRepository,
            gamificationEventRepository = gamificationEventRepository,
            xpCalculatorService = xpCalculatorService
        )
        gamificationEventPublisher = com.example.domain.gamification.GamificationEventRecorder(
            achievementRepository = achievementRepository,
            missionRepository = missionRepository,
            repository = gamificationEventRepository,
            xpCalculatorService = xpCalculatorService,
            workoutTimestampsProvider = { database.workoutDao().getCompletedSessionTimestamps() },
            weeklyGoalProvider = { settingsManager.weeklyGoalFlow.first() },
            goalSnapshotsProvider = { consistencyRepository.getGoalSnapshots() },
            trackingStartedAtProvider = { settingsManager.trackingStartedAtFlow.first() }
        )
        workoutEngine = WorkoutEngine(
            dao = database.workoutDao(),
            settingsManager = settingsManager,
            gamificationEvents = gamificationEventPublisher,
            syncMutations = syncMutationCoordinator
        )
        notificationManager = WorkoutNotificationManager(this)

        // Nada de Firebase, App Check ou IA acontece no startup: o Spark é local-first. O Coach
        // só fala com o Spark Backend dentro de uma chamada que o usuário pediu, e quem instala o
        // App Check da variante de build é o `FirebaseAuthGateway`, quando a conta é usada.

        CoroutineScope(Dispatchers.Main).launch {
            workoutEngine.restTimerTarget.collect { target ->
                if (target == null) {
                    notificationManager.cancelNotification()
                }
            }
        }


        CoroutineScope(Dispatchers.IO).launch {
            // Recuperação de restore **antes** de qualquer outra coisa (T16.5).
            //
            // Um restore interrompido pode ter deixado o Room já substituído e as preferências
            // não. Retomar ou desfazer é a primeira decisão da abertura: reconciliar gamificação,
            // importar catálogo ou deixar o usuário treinar sobre um dataset em transição
            // produziria estado derivado de um estado que ainda não é o final.
            //
            // Isto **não** é um restore automático: ele só termina o que o usuário já confirmou.
            // Sem tentativa interrompida, a chamada não faz nada.
            try {
                restoreRepository.recover()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                (consistencyRepository as? com.example.data.repository.ConsistencyRepositoryImpl)?.initialize()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            try {
                val reconciler = com.example.domain.gamification.XpReconciler(
                    xpTransactionRepository = xpTransactionRepository,
                    eventRepository = gamificationEventRepository,
                    xpCalculatorService = xpCalculatorService,
                    xpPolicyVersionProvider = { settingsManager.xpPolicyVersionFlow.first() },
                    xpPolicyVersionWriter = { settingsManager.setXpPolicyVersion(it) },
                    firstCompletedWorkoutProvider = {
                        // Histórico canônico de treinos: o evento só é recriado com prova real.
                        database.workoutDao().getFirstCompletedSession()?.let { session ->
                            com.example.domain.gamification.CompletedWorkoutReference(
                                sessionId = session.id,
                                completedAt = session.finishedAt ?: session.startedAt
                            )
                        }
                    }
                )
                reconciler.reconcile()

                com.example.domain.gamification.AchievementReconciler(achievementRepository).reconcile()

                // Missões perdidas por um encerramento no meio do caminho voltam ao estado correto
                // aqui, sem recompensa nem comemoração repetidas.
                com.example.domain.gamification.mission.MissionReconciler(missionRepository).reconcile()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            try {
                ManifestImporter(database, this@MainApplication).importFromAssets()
                val premiumImporter = com.example.domain.engine.PremiumManifestImporter(database, this@MainApplication)
                // force = false agora é seguro: o manifesto premium tem chave de versão própria,
                // e assim as 354 entradas não são reimportadas a cada abertura do app.
                val result = premiumImporter.importFromAssets("catalog/exercise-content-manifest.v2.json")
                premiumImporter.seedPremiumTestWorkoutIfNeeded()
                android.util.Log.d("MainApplication", "Premium Import Report:\n${result.formattedReport}")

                val mediaEngine = com.example.domain.engine.ExerciseMediaEngine(
                    dao = database.workoutDao(),
                    remoteDataSource = com.example.data.remote.provider.ExerciseProviderFactory.create(
                        database.workoutDao(),
                        settingsManager
                    ),
                    context = this@MainApplication
                )
                mediaEngine.syncOpportunistic(settingsManager, currentCatalogVersion = 2)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .respectCacheHeaders(false)
            .build()
    }
}
