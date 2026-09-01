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

    lateinit var getEvolutionSummaryUseCase: com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
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
        getEvolutionSummaryUseCase = com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase(evolutionRepository)
        workoutEngine = WorkoutEngine(database.workoutDao(), settingsManager)
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
                ManifestImporter(database, this@MainApplication).importFromAssets()
                val premiumImporter = com.example.domain.engine.PremiumManifestImporter(database, this@MainApplication)
                val result = premiumImporter.importFromAssets("catalog/exercise-content-manifest.v2.json", force = true)
                premiumImporter.seedPremiumTestWorkoutIfNeeded()
                android.util.Log.d("MainApplication", "Premium Import Report:\n${result.formattedReport}")

                val mediaEngine = com.example.domain.engine.ExerciseMediaEngine(database.workoutDao(), context = this@MainApplication)
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
