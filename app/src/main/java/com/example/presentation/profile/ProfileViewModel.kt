package com.example.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.WorkoutRepository
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.ai.model.AiProgressSnapshot
import com.example.domain.ai.usecase.ExplainCoachDecisionUseCase
import com.example.domain.evolution.repository.ConsistencyRepository
import com.example.presentation.coach.CoachExplanationController
import com.example.presentation.coach.CoachExplanationUiState
import com.example.domain.gamification.repository.XpTransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Reúne, sem recalcular, as autoridades de progressão que o Perfil do Atleta apresenta.
 *
 * Cada número exibido continua pertencendo ao seu dono original:
 *
 * ```
 * XpTransactionRepository    -> nível, XP total, progresso do nível
 * ConsistencyRepository      -> sequência semanal, meta e progresso da semana
 * AchievementRepository      -> conquistas desbloqueadas / total
 * WorkoutRepository          -> treinos concluídos, recordes pessoais
 * BodyMeasurementRepository  -> último peso registrado
 * ```
 *
 * O Perfil é uma projeção dessas fontes: nada aqui persiste contadores derivados nem reimplementa
 * curva de nível, streak ou desbloqueio de conquista.
 */
class ProfileViewModel(
    private val xpTransactionRepository: XpTransactionRepository,
    private val consistencyRepository: ConsistencyRepository,
    private val achievementRepository: com.example.domain.evolution.repository.AchievementRepository,
    private val workoutRepository: WorkoutRepository,
    private val bodyMeasurementRepository: BodyMeasurementRepository,
    private val settingsManager: SettingsManager,
    /**
     * O Coach contextual, opcional.
     *
     * `null` quando o Coach não está disponível neste build — o Perfil continua completo sem ele,
     * e nenhum número desta tela depende de IA para existir.
     */
    private val explainCoachDecision: ExplainCoachDecisionUseCase? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _weeklyGoalSave = MutableStateFlow<WeeklyGoalSave>(WeeklyGoalSave.Idle)

    /** O desfecho da última tentativa de alterar a meta semanal (H2.4). */
    val weeklyGoalSave: StateFlow<WeeklyGoalSave> = _weeklyGoalSave.asStateFlow()

    private val explanations = CoachExplanationController(viewModelScope)
    val explanationState: StateFlow<CoachExplanationUiState> = explanations.state

    val canExplainProgress: Boolean get() = explainCoachDecision != null

    /**
     * "Por que meu Coach diz que estou evoluindo?"
     *
     * O recorte enviado é o que a tela já mostra, vindo pronto das autoridades. A IA **explica**
     * estes números; ela não recalcula nível, curva de XP, sequência nem conquista.
     *
     * [modelAllowed] é o que a tela sabe da capability `AI_EXPLAIN` (T19.0): `false` quando o
     * servidor já disse que esta conta não a tem — a explicação sai local, sem chamada.
     */
    fun explainProgress(modelAllowed: Boolean = true) {
        val useCase = explainCoachDecision ?: return
        val state = _uiState.value
        if (state.isLoading) return
        explanations.request {
            useCase.explainProgress(
                AiProgressSnapshot(
                    level = state.level,
                    totalXp = state.totalXp,
                    currentLevelXp = state.currentLevelXp,
                    xpForNextLevel = state.xpForNextLevel,
                    streakWeeks = state.streakWeeks,
                    weeklyCompleted = state.weeklyCompleted,
                    weeklyGoal = state.weeklyGoal,
                    completedWorkouts = state.completedWorkouts,
                    unlockedAchievements = state.unlockedAchievements,
                    totalAchievements = state.totalAchievements,
                    personalRecordsCount = state.personalRecordsCount
                ),
                modelAllowed
            )
        }
    }

    fun dismissExplanation() = explanations.dismiss()

    /** Recorte do estado montado a partir das fontes de progressão. */
    private data class ProgressionSnapshot(
        val level: Int,
        val totalXp: Int,
        val currentLevelXp: Int,
        val xpForNextLevel: Int,
        val levelProgress: Float,
        val streakWeeks: Int,
        val weeklyCompleted: Int,
        val weeklyGoal: Int,
        val completedWorkouts: Int,
        val unlockedAchievements: Int,
        val totalAchievements: Int,
        val recentAchievements: List<Achievement>,
        val personalRecordsCount: Int
    )

    init {
        observeProfile()
    }

    private fun observeProfile() {
        viewModelScope.launch {
            val progressionFlow = combine(
                xpTransactionRepository.getUserProgress(),
                consistencyRepository.getConsistencyProgressFlow(),
                achievementRepository.getAchievementsFlow(),
                workoutRepository.getCompletedSessionsCountFlow(),
                workoutRepository.getPersonalRecordsCountFlow()
            ) { userProgress, consistency, achievements, completedWorkouts, personalRecords ->
                ProgressionSnapshot(
                    level = userProgress.currentLevel,
                    totalXp = userProgress.totalXp,
                    currentLevelXp = userProgress.currentLevelXp,
                    xpForNextLevel = userProgress.xpForNextLevel,
                    levelProgress = userProgress.progressPercentage,
                    streakWeeks = consistency.currentStreakWeeks,
                    weeklyCompleted = consistency.currentWeekCompleted,
                    weeklyGoal = consistency.currentWeekGoal,
                    completedWorkouts = completedWorkouts,
                    unlockedAchievements = achievements.count { it.unlockedAt != null },
                    totalAchievements = achievements.size,
                    recentAchievements = achievements.toRecentPreview(),
                    personalRecordsCount = personalRecords
                )
            }

            combine(
                progressionFlow,
                bodyMeasurementRepository.latestMeasurement,
                settingsManager.weeklyGoalFlow
            ) { progression, latestMeasurement, nextWeekGoal ->
                ProfileUiState(
                    isLoading = false,
                    level = progression.level,
                    totalXp = progression.totalXp,
                    currentLevelXp = progression.currentLevelXp,
                    xpForNextLevel = progression.xpForNextLevel,
                    levelProgress = progression.levelProgress,
                    streakWeeks = progression.streakWeeks,
                    weeklyCompleted = progression.weeklyCompleted,
                    weeklyGoal = progression.weeklyGoal,
                    nextWeekGoal = nextWeekGoal,
                    completedWorkouts = progression.completedWorkouts,
                    unlockedAchievements = progression.unlockedAchievements,
                    totalAchievements = progression.totalAchievements,
                    recentAchievements = progression.recentAchievements,
                    personalRecordsCount = progression.personalRecordsCount,
                    latestWeightKg = latestMeasurement?.weightKg
                )
            }.collect { state ->
                // Os números mudaram: uma explicação montada sobre os anteriores deixou de
                // descrever o que a tela mostra.
                if (state.progressRevision() != _uiState.value.progressRevision()) {
                    explanations.dismiss()
                }
                _uiState.value = state
            }
        }
    }

    /**
     * Altera a meta semanal preservando a vigência já implementada: a semana corrente mantém a meta
     * antiga e o novo valor passa a valer na próxima. A regra continua morando no repositório.
     *
     * ## Por que existe um estado de salvamento (H2.4)
     *
     * A tela fechava a folha no mesmo toque que disparava a gravação, sem esperar por ela. Enquanto
     * deu certo, ninguém percebeu; quando não desse, a folha fecharia igual e a meta continuaria a
     * antiga — uma falha invisível numa tela que afirma ter salvado. Agora o desfecho é estado: a
     * folha só fecha em [WeeklyGoalSave.Saved], e um erro fica visível com a folha aberta.
     *
     * Um segundo toque durante a gravação não faz nada: a mudança é **uma**, não duas.
     */
    fun setWeeklyGoal(goal: Int) {
        if (_weeklyGoalSave.value is WeeklyGoalSave.Saving) return
        _weeklyGoalSave.value = WeeklyGoalSave.Saving
        viewModelScope.launch {
            _weeklyGoalSave.value = try {
                consistencyRepository.setWeeklyGoal(goal)
                WeeklyGoalSave.Saved(goal)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                WeeklyGoalSave.Failed
            }
        }
    }

    /** A folha foi fechada (salva ou não): o desfecho anterior deixou de descrever alguma coisa. */
    fun acknowledgeWeeklyGoalSave() {
        _weeklyGoalSave.value = WeeklyGoalSave.Idle
    }

    /** A impressão dos números explicáveis desta tela. */
    private fun ProfileUiState.progressRevision(): String = AiProgressSnapshot(
        level = level,
        totalXp = totalXp,
        currentLevelXp = currentLevelXp,
        xpForNextLevel = xpForNextLevel,
        streakWeeks = streakWeeks,
        weeklyCompleted = weeklyCompleted,
        weeklyGoal = weeklyGoal,
        completedWorkouts = completedWorkouts,
        unlockedAchievements = unlockedAchievements,
        totalAchievements = totalAchievements,
        personalRecordsCount = personalRecordsCount
    ).revision

    /** As três conquistas desbloqueadas mais recentes — as demais continuam em Evolução. */
    private fun List<Achievement>.toRecentPreview(): List<Achievement> =
        filter { it.unlockedAt != null }
            .sortedByDescending { it.unlockedAt }
            .take(MAX_ACHIEVEMENT_PREVIEW)

    private companion object {
        const val MAX_ACHIEVEMENT_PREVIEW = 3
    }
}

/**
 * O desfecho de "Salvar" na Meta Semanal (H2.4).
 *
 * Existe para que a folha só feche depois de a gravação ter sido aceita pela camada que manda nela
 * ([ConsistencyRepository] + DataStore), e para que uma falha apareça em vez de sumir junto com a
 * folha.
 */
sealed interface WeeklyGoalSave {
    data object Idle : WeeklyGoalSave

    data object Saving : WeeklyGoalSave

    /** A meta foi persistida. [goal] é o valor que passa a valer na **próxima** semana. */
    data class Saved(val goal: Int) : WeeklyGoalSave

    data object Failed : WeeklyGoalSave
}
