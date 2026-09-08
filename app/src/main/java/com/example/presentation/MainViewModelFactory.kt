package com.example.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.datastore.SettingsManager
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
import com.example.domain.evolution.repository.EvolutionRepository
import com.example.domain.evolution.repository.PerformanceRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import com.example.feature.evolution.EvolutionViewModel
import com.example.presentation.body.BodyEvolutionViewModel
import com.example.presentation.exercises.ExercisesViewModel
import com.example.presentation.today.TodayViewModel
import com.example.presentation.workouts.WorkoutsViewModel
import com.example.presentation.execution.ExecutionViewModel
import com.example.presentation.history.HistoryViewModel
import com.example.service.WorkoutNotificationManager

class MainViewModelFactory(
    private val database: com.example.data.local.AppDatabase,
    private val repository: WorkoutRepository,
    private val settingsManager: SettingsManager,
    private val workoutEngine: WorkoutEngine,
    private val notificationManager: WorkoutNotificationManager,
    private val bodyMeasurementRepository: BodyMeasurementRepository,
    private val getEvolutionSummaryUseCase: GetEvolutionSummaryUseCase? = null,
    private val evolutionRepository: EvolutionRepository? = null,
    private val performanceRepository: PerformanceRepository? = null,
    private val consistencyRepository: com.example.domain.evolution.repository.ConsistencyRepository? = null,
    private val xpTransactionRepository: com.example.domain.gamification.repository.XpTransactionRepository? = null,
    private val achievementRepository: com.example.domain.evolution.repository.AchievementRepository? = null,
    private val missionRepository: com.example.domain.gamification.repository.MissionRepository? = null,
    private val analyzeWorkoutUseCase: com.example.domain.ai.usecase.AnalyzeWorkoutUseCase? = null,
    private val exerciseNameResolver: (suspend (String) -> String?)? = null,
    private val generateWorkoutUseCase: com.example.domain.ai.usecase.GenerateWorkoutUseCase? = null,
    private val saveGeneratedWorkoutUseCase: com.example.domain.ai.usecase.SaveGeneratedWorkoutUseCase? = null,
    private val workoutCandidateProvider: (suspend (com.example.domain.ai.model.WorkoutGenerationPreferences) -> List<com.example.domain.ai.model.AiCandidateExerciseContext>)? = null,
    private val adaptWorkoutUseCase: com.example.domain.ai.usecase.AdaptWorkoutUseCase? = null,
    private val applyWorkoutAdaptationUseCase: com.example.domain.ai.usecase.ApplyWorkoutAdaptationUseCase? = null,
    /** Coach contextual (T14.4). `null` desliga as entradas de explicação em todas as telas. */
    private val explainCoachDecisionUseCase: com.example.domain.ai.usecase.ExplainCoachDecisionUseCase? = null,
    /** Conta Spark (T16.1). `null` remove a área de conta do Perfil, e nada mais muda. */
    private val authGateway: com.example.domain.auth.AuthGateway? = null,
    /** Cliente do Spark Backend (T16.1). `null` quando não há endereço configurado neste build. */
    private val sparkBackendClient: com.example.data.remote.spark.SparkBackendClient? = null,
    /** Backup estruturado (T16.4). `null` remove a seção de backup do Perfil, e nada mais muda. */
    private val backupRepository: com.example.data.backup.BackupRepository? = null,
    /** Restore seguro (T16.5). `null` remove a seção de restore do Perfil, e nada mais muda. */
    private val restoreRepository: com.example.data.restore.RestoreRepository? = null,
    /**
     * Sync incremental (T16.6). `null` remove a seção de sincronização do Perfil, e nada mais muda.
     *
     * Os dois vêm juntos ou não vêm: o repositório é a leitura de estado, o coordenador é quem
     * roda o ciclo. Um sem o outro seria uma tela que mostra sem poder agir, ou o contrário.
     */
    private val syncRepository: com.example.data.sync.SyncRepository? = null,
    private val syncCoordinator: com.example.data.sync.SyncCoordinator? = null,
    /**
     * Recursos sociais (T17.0). `null` remove a seção social do Perfil, e nada mais muda.
     *
     * É um **gateway**, e não um repositório: o social é server-authoritative e não tem dado local
     * para reconciliar (ver `com.example.domain.social.SocialGateway`).
     */
    private val socialGateway: com.example.domain.social.SocialGateway? = null,
    /**
     * O grafo social (T17.1). `null` remove Amigos/Solicitações do Perfil, e nada mais muda.
     *
     * Separado do [socialGateway] porque são duas perguntas diferentes — "quem eu sou no social" e
     * "com quem eu me relaciono" —, e uma conta pode ter a primeira sem ter a segunda.
     */
    private val friendGateway: com.example.domain.social.FriendGateway? = null,
    /**
     * O perfil social enriquecido (T17.2). `null` remove "Compartilhar progresso" e o perfil de
     * amigo, e nada mais muda.
     */
    private val socialProfileGateway: com.example.domain.social.SocialProfileGateway? = null,
    private val challengeGateway: com.example.domain.social.ChallengeGateway? = null,
    private val socialActivityGateway: com.example.domain.social.SocialActivityGateway? = null,
    private val socialNotificationGateway: com.example.domain.social.SocialNotificationGateway? = null,
    private val pushRegistrationCoordinator: com.example.service.PushRegistrationCoordinator? = null,
    private val pushAccountScope: com.example.service.PushAccountScope? = null,
    private val blockGateway: com.example.domain.social.BlockGateway? = null,
    private val reportGateway: com.example.domain.social.ReportGateway? = null,
    private val accountDeletionGateway: com.example.domain.account.AccountDeletionGateway? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EvolutionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val useCase = getEvolutionSummaryUseCase 
                ?: throw IllegalStateException("GetEvolutionSummaryUseCase not provided")
            val repository = evolutionRepository
                ?: throw IllegalStateException("EvolutionRepository not provided")
            return EvolutionViewModel(useCase, repository) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.achievements.AchievementsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val repository = achievementRepository
                ?: error("AchievementRepository not provided")
            return com.example.feature.evolution.achievements.AchievementsViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.timeline.TimelineViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val summaryUseCase = getEvolutionSummaryUseCase
                ?: throw IllegalStateException("GetEvolutionSummaryUseCase not provided")
            val perfRepo = performanceRepository
                ?: throw IllegalStateException("PerformanceRepository not provided")
            val consRepo = consistencyRepository
                ?: throw IllegalStateException("ConsistencyRepository not provided")
            val bodyRepo = bodyMeasurementRepository
            val achievementRepo = achievementRepository
                ?: error("AchievementRepository not provided")
            val milestoneProvider = com.example.data.provider.WorkoutMilestoneProviderImpl(
                consistencyRepository = consRepo
            )
            val snapshotRepo = com.example.data.repository.EvolutionSnapshotRepositoryImpl(
                getEvolutionSummaryUseCase = summaryUseCase,
                performanceRepository = perfRepo,
                consistencyRepository = consRepo,
                bodyMeasurementRepository = bodyRepo,
                achievementRepository = achievementRepo,
                workoutMilestoneProvider = milestoneProvider
            )
            val timelineRepo = com.example.data.repository.TimelineRepositoryImpl(
                evolutionSnapshotRepository = snapshotRepo
            )
            return com.example.feature.evolution.timeline.TimelineViewModel(timelineRepo) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.consistency.ConsistencyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val repository = consistencyRepository
                ?: throw IllegalStateException("ConsistencyRepository not provided")
            return com.example.feature.evolution.consistency.ConsistencyViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.performance.PerformanceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val repository = performanceRepository
                ?: throw IllegalStateException("PerformanceRepository not provided")
            return com.example.feature.evolution.performance.PerformanceViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.performance.chart.PerformanceChartViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val repository = performanceRepository
                ?: throw IllegalStateException("PerformanceRepository not provided")
            return com.example.feature.evolution.performance.chart.PerformanceChartViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(BodyEvolutionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BodyEvolutionViewModel(bodyMeasurementRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.body.BodyEvolutionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.feature.evolution.body.BodyEvolutionViewModel(bodyMeasurementRepository) as T
        }
        if (modelClass.isAssignableFrom(ExercisesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ExercisesViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(WorkoutsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkoutsViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(TodayViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TodayViewModel(repository, settingsManager, workoutEngine, bodyMeasurementRepository, xpTransactionRepository, consistencyRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.profile.ProfileViewModel::class.java)) {
            val xpRepo = xpTransactionRepository
                ?: throw IllegalStateException("XpTransactionRepository not provided")
            val consRepo = consistencyRepository
                ?: throw IllegalStateException("ConsistencyRepository not provided")
            val achievementRepo = achievementRepository
                ?: throw IllegalStateException("AchievementRepository not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.profile.ProfileViewModel(
                xpTransactionRepository = xpRepo,
                consistencyRepository = consRepo,
                achievementRepository = achievementRepo,
                workoutRepository = repository,
                bodyMeasurementRepository = bodyMeasurementRepository,
                settingsManager = settingsManager,
                explainCoachDecision = explainCoachDecisionUseCase
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.account.AccountViewModel::class.java)) {
            val gateway = authGateway
                ?: throw IllegalStateException("AuthGateway not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.account.AccountViewModel(
                authGateway = gateway,
                backendClient = sparkBackendClient,
                accountDeletionGateway = accountDeletionGateway
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.account.BackupViewModel::class.java)) {
            val gateway = authGateway
                ?: throw IllegalStateException("AuthGateway not provided")
            val backup = backupRepository
                ?: throw IllegalStateException("BackupRepository not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.account.BackupViewModel(
                repository = backup,
                authGateway = gateway
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.account.RestoreViewModel::class.java)) {
            val gateway = authGateway
                ?: throw IllegalStateException("AuthGateway not provided")
            val restore = restoreRepository
                ?: throw IllegalStateException("RestoreRepository not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.account.RestoreViewModel(
                repository = restore,
                authGateway = gateway
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.account.SocialViewModel::class.java)) {
            val gateway = authGateway
                ?: throw IllegalStateException("AuthGateway not provided")
            val social = socialGateway
                ?: throw IllegalStateException("SocialGateway not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.account.SocialViewModel(
                gateway = social,
                authGateway = gateway,
                onSocialDisabled = { pushAccountScope?.clearRegisteredAccount() },
                onSocialActivated = { pushRegistrationCoordinator?.reconcile("social_activated") }
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.account.FriendsViewModel::class.java)) {
            val gateway = authGateway
                ?: throw IllegalStateException("AuthGateway not provided")
            val friends = friendGateway
                ?: throw IllegalStateException("FriendGateway not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.account.FriendsViewModel(
                gateway = friends,
                authGateway = gateway
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.account.SocialProfileViewModel::class.java)) {
            val gateway = authGateway
                ?: throw IllegalStateException("AuthGateway not provided")
            val profile = socialProfileGateway
                ?: throw IllegalStateException("SocialProfileGateway not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.account.SocialProfileViewModel(
                gateway = profile,
                authGateway = gateway,
                blockGateway = blockGateway,
                reportGateway = reportGateway
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.friends.BlockedUsersViewModel::class.java)) {
            val gateway = authGateway
                ?: throw IllegalStateException("AuthGateway not provided")
            val block = blockGateway
                ?: throw IllegalStateException("BlockGateway not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.friends.BlockedUsersViewModel(
                blockGateway = block,
                authGateway = gateway
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.account.ChallengeViewModel::class.java)) {
            val gateway = authGateway
                ?: throw IllegalStateException("AuthGateway not provided")
            val challenges = challengeGateway
                ?: throw IllegalStateException("ChallengeGateway not provided")
            // O `FriendGateway` entra para a **seleção** de amigos na criação, e para mais nada:
            // quem decide se um convidado pode participar é o servidor, na criação (§32).
            val friends = friendGateway
                ?: throw IllegalStateException("FriendGateway not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.account.ChallengeViewModel(
                gateway = challenges,
                friendGateway = friends,
                authGateway = gateway
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.friends.SocialActivityViewModel::class.java)) {
            val gateway = authGateway
                ?: throw IllegalStateException("AuthGateway not provided")
            val activity = socialActivityGateway
                ?: throw IllegalStateException("SocialActivityGateway not provided")
            val social = socialGateway
                ?: throw IllegalStateException("SocialGateway not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.friends.SocialActivityViewModel(
                activityGateway = activity,
                socialGateway = social,
                authGateway = gateway
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.friends.NotificationPreferencesViewModel::class.java)) {
            val gateway = socialNotificationGateway
                ?: throw IllegalStateException("SocialNotificationGateway not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.friends.NotificationPreferencesViewModel(
                gateway = gateway,
                onPushEnabled = { pushRegistrationCoordinator?.reconcile("push_enabled") }
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.account.SyncViewModel::class.java)) {
            val sync = syncRepository
                ?: throw IllegalStateException("SyncRepository not provided")
            val coordinator = syncCoordinator
                ?: throw IllegalStateException("SyncCoordinator not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.account.SyncViewModel(
                repository = sync,
                coordinator = coordinator
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.coach.AiCoachViewModel::class.java)) {
            val useCase = analyzeWorkoutUseCase
                ?: throw IllegalStateException("AnalyzeWorkoutUseCase not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.coach.AiCoachViewModel(
                analyzeWorkout = useCase,
                exerciseNameResolver = exerciseNameResolver ?: { null },
                explainCoachDecision = explainCoachDecisionUseCase
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.coach.GenerateWorkoutViewModel::class.java)) {
            val generate = generateWorkoutUseCase
                ?: throw IllegalStateException("GenerateWorkoutUseCase not provided")
            val save = saveGeneratedWorkoutUseCase
                ?: throw IllegalStateException("SaveGeneratedWorkoutUseCase not provided")
            val candidates = workoutCandidateProvider
                ?: throw IllegalStateException("Workout candidate provider not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.coach.GenerateWorkoutViewModel(
                generateWorkout = generate,
                saveGeneratedWorkout = save::invoke,
                listCandidates = candidates,
                explainCoachDecision = explainCoachDecisionUseCase
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.coach.AdaptWorkoutViewModel::class.java)) {
            val adapt = adaptWorkoutUseCase
                ?: throw IllegalStateException("AdaptWorkoutUseCase not provided")
            val apply = applyWorkoutAdaptationUseCase
                ?: throw IllegalStateException("ApplyWorkoutAdaptationUseCase not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.coach.AdaptWorkoutViewModel(
                adaptWorkout = adapt,
                applyAdaptation = apply::invoke,
                explainCoachDecision = explainCoachDecisionUseCase
            ) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.missions.MissionViewModel::class.java)) {
            val missionRepo = missionRepository
                ?: throw IllegalStateException("MissionRepository not provided")
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.missions.MissionViewModel(missionRepo) as T
        }
        if (modelClass.isAssignableFrom(ExecutionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ExecutionViewModel(workoutEngine, notificationManager, settingsManager) as T
        }
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(workoutEngine) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.execution.SummaryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.execution.SummaryViewModel(workoutEngine) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.workouts.TemplateDetailsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.workouts.TemplateDetailsViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.exercises.ExerciseDetailsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.exercises.ExerciseDetailsViewModel(workoutEngine, repository.dao, settingsManager) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.workouts.ProgramDetailsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.workouts.ProgramDetailsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
