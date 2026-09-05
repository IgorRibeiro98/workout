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

class MainApplication : Application(), ImageLoaderFactory {
    
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
        
    lateinit var workoutEngine: WorkoutEngine
        internal set
        
    lateinit var notificationManager: WorkoutNotificationManager
        internal set

    /**
     * Coach IA (T14.0/T14.1).
     *
     * `by lazy` de propósito: o Spark é local-first e não pode pagar inicialização de Firebase
     * no startup. Nada de IA é tocado enquanto o usuário não pedir uma análise.
     */
    val aiCoachGateway: com.example.domain.ai.AiCoachGateway by lazy {
        com.example.data.ai.FirebaseAiCoachGateway(this)
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

    /**
     * Traduz um `exerciseId` do Coach de volta para o nome exibido.
     *
     * A identidade continua sendo o id: isto existe só para a leitura da recomendação.
     */
    suspend fun resolveExerciseDisplayName(exerciseId: String): String? {
        val dao = database.workoutDao()
        val localId = exerciseId
            .removePrefix(com.example.domain.ai.AiCoachContextProjector.LOCAL_ID_PREFIX)
            .takeIf { it != exerciseId }
            ?.toLongOrNull()
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
        repository = WorkoutRepository(database.workoutDao(), settingsManager = settingsManager)
        bodyMeasurementRepository = com.example.data.repository.BodyMeasurementRepository(database.bodyMeasurementDao())
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
            gamificationEvents = gamificationEventPublisher
        )
        notificationManager = WorkoutNotificationManager(this)

        try {
            val app = try {
                com.google.firebase.FirebaseApp.getInstance()
            } catch (e: Exception) {
                com.google.firebase.FirebaseApp.initializeApp(
                    this,
                    com.google.firebase.FirebaseOptions.Builder()
                        .setApplicationId("1:638822756779:android:3cd9d02c9fbaa5342fa2ee")
                        .setApiKey("AIzaSyD5y3HrGeQBWHC5vuxhDzhXj2LVj9ZIq2g")
                        .setProjectId("spark-36b11")
                        .setStorageBucket("spark-36b11.firebasestorage.app")
                        .build()
                )
            }
            if (app != null) {
                val token = com.example.data.ai.FirebaseAiCoachGateway.getCurrentDebugToken(this)
                val persistenceKey = try { app.persistenceKey } catch (e: Exception) { "+DEFAULT" }
                val targetPrefs = listOf(
                    "com.google.firebase.appcheck.debug.DebugAppCheckProvider:$persistenceKey",
                    "com.google.firebase.appcheck.debug.DebugAppCheckProvider:[DEFAULT]",
                    "com.google.firebase.appcheck.debug.DebugAppCheckProvider",
                    "com.google.firebase.appcheck.debug.store",
                    "com.google.firebase.appcheck.debug.store.${app.options.applicationId}"
                )
                for (name in targetPrefs) {
                    getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", token)
                        .putString("DEBUG_SECRET", token)
                        .apply()
                }
                val providerFactory = try {
                    com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory.getInstance()
                } catch (e: Exception) {
                    com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
                }
                com.google.firebase.appcheck.FirebaseAppCheck.getInstance(app)
                    .installAppCheckProviderFactory(providerFactory)
                com.google.firebase.appcheck.FirebaseAppCheck.getInstance(app)
                    .setTokenAutoRefreshEnabled(true)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainApplication", "Firebase AppCheck init: ${e.message}")
        }

        CoroutineScope(Dispatchers.Main).launch {
            workoutEngine.restTimerTarget.collect { target ->
                if (target == null) {
                    notificationManager.cancelNotification()
                }
            }
        }


        CoroutineScope(Dispatchers.IO).launch {
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
