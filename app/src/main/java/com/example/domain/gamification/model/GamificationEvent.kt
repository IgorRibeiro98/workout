package com.example.domain.gamification.model

import java.util.UUID

/**
 * Tipos de eventos suportados pela fundação de gamificação (T13.0).
 *
 * Um evento é sempre um FATO: descreve algo que aconteceu com o usuário durante o treino.
 * Ele nunca carrega recompensa, XP, nível ou qualquer regra visual — isso pertence às camadas
 * que consomem estes eventos.
 */
enum class GamificationEventType {
    // Treino
    WORKOUT_STARTED,
    WORKOUT_COMPLETED,
    FIRST_WORKOUT_COMPLETED,

    // Exercício
    EXERCISE_COMPLETED,
    FIRST_EXERCISE_COMPLETED,

    // Performance
    PERSONAL_RECORD_CREATED,

    // Consistência
    WEEKLY_GOAL_COMPLETED,
    STREAK_MILESTONE_REACHED,

    // Missões
    MISSION_COMPLETED
}

/**
 * Fato registrado pelo aplicativo, independente de qualquer regra de gamificação.
 *
 * @param id identificador único do evento.
 * @param type o que aconteceu.
 * @param timestamp quando aconteceu (epoch millis).
 * @param metadata dados brutos do fato (ids, valores), sempre em texto para sobreviver a migrações.
 * @param source origem do fato (qual módulo publicou).
 * @param dedupeKey chave de idempotência: dois fatos com a mesma chave são o mesmo acontecimento
 *        e só podem existir uma vez no histórico.
 */
data class GamificationEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: GamificationEventType,
    val timestamp: Long,
    val metadata: Map<String, String> = emptyMap(),
    val source: String = GamificationEventSource.UNKNOWN,
    val dedupeKey: String = id
)

/** Origens conhecidas de eventos. */
object GamificationEventSource {
    const val WORKOUT_ENGINE = "WORKOUT_ENGINE"
    const val CONSISTENCY = "CONSISTENCY"
    const val MISSIONS = "MISSIONS"
    const val UNKNOWN = "UNKNOWN"
}

/** Chaves de metadata usadas pelos eventos atuais. */
object GamificationEventMetadata {
    const val SESSION_ID = "sessionId"
    const val TEMPLATE_ID = "templateId"
    const val TEMPLATE_NAME = "templateName"
    const val EXERCISE_ID = "exerciseId"
    const val EXERCISE_SESSION_ID = "exerciseSessionId"
    const val EXERCISE_NAME = "exerciseName"
    const val COMPLETED_SETS = "completedSets"
    const val COMPLETED_EXERCISES = "completedExercises"
    const val DURATION_SECONDS = "durationSeconds"
    const val PR_TYPE = "prType"
    const val PR_VALUE = "value"
    const val PR_PREVIOUS_VALUE = "previousValue"
    const val WEEK_START_EPOCH_DAY = "weekStartEpochDay"
    const val WEEKLY_GOAL = "weeklyGoal"
    const val WEEKLY_COMPLETED = "weeklyCompleted"
    const val STREAK_WEEKS = "streakWeeks"
    const val MISSION_ID = "missionId"
    const val MISSION_PERIOD_KEY = "missionPeriodKey"
    const val MISSION_TARGET = "missionTarget"
    const val MISSION_REWARD_XP = "missionRewardXp"
    const val MISSION_CATALOG_VERSION = "missionCatalogVersion"
}
