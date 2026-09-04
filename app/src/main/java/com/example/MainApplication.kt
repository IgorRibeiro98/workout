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

    lateinit var settingsManager: SettingsManager
        internal set
        
    lateinit var workoutEngine: WorkoutEngine
        internal set
        
    lateinit var notificationManager: WorkoutNotificationManager
        internal set

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
        gamificationEventRepository = com.example.data.repository.GamificationEventRepositoryImpl(
            database.gamificationEventDao()
        )
        xpTransactionRepository = com.example.data.repository.XpTransactionRepositoryImpl(
            database.xpTransactionDao()
        )
        xpCalculatorService = com.example.domain.gamification.XpCalculatorService(
            xpTransactionRepository
        )
        gamificationEventPublisher = com.example.domain.gamification.GamificationEventRecorder(
            achievementRepository = achievementRepository,
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
                    xpTransactionRepository,
                    gamificationEventRepository,
                    settingsManager
                )
                reconciler.reconcile()

                val achievementRepo = com.example.data.repository.AchievementRepositoryImpl(
                    achievementDao = database.achievementDao(),
                    workoutDao = database.workoutDao(),
                    gamificationEventDao = database.gamificationEventDao(),
                    consistencyRepository = consistencyRepository,
                    bodyMeasurementRepository = bodyMeasurementRepository
                )
                com.example.domain.gamification.AchievementReconciler(achievementRepo).reconcile()

                // Trigger LIVE evaluation when body measurements change (since they bypass GamificationEventPublisher)
                var isInitialLoad = true
                bodyMeasurementRepository.allMeasurements.collect {
                    if (isInitialLoad) {
                        isInitialLoad = false
                    } else {
                        achievementRepo.evaluateAndUnlock(com.example.domain.evolution.repository.AchievementEvaluationOrigin.LIVE)
                    }
                }
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
