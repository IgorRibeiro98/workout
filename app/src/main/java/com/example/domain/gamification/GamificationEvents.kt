package com.example.domain.gamification

import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventMetadata
import com.example.domain.gamification.model.GamificationEventSource
import com.example.domain.gamification.model.GamificationEventType

/**
 * Fábrica central dos eventos suportados.
 *
 * Concentrar a criação aqui garante que a chave de idempotência (`dedupeKey`) de cada fato seja
 * definida em um único lugar: o mesmo acontecimento sempre produz a mesma chave, então repetir a
 * publicação nunca duplica o histórico.
 */
object GamificationEvents {

    fun workoutStarted(
        sessionId: Long,
        timestamp: Long,
        templateId: Long? = null,
        templateName: String? = null
    ): GamificationEvent = GamificationEvent(
        type = GamificationEventType.WORKOUT_STARTED,
        timestamp = timestamp,
        source = GamificationEventSource.WORKOUT_ENGINE,
        dedupeKey = "workout_started:$sessionId",
        metadata = buildMap {
            put(GamificationEventMetadata.SESSION_ID, sessionId.toString())
            templateId?.let { put(GamificationEventMetadata.TEMPLATE_ID, it.toString()) }
            templateName?.let { put(GamificationEventMetadata.TEMPLATE_NAME, it) }
        }
    )

    fun workoutCompleted(
        sessionId: Long,
        timestamp: Long,
        templateName: String? = null,
        completedExercises: Int = 0,
        completedSets: Int = 0,
        durationSeconds: Long? = null
    ): GamificationEvent = GamificationEvent(
        type = GamificationEventType.WORKOUT_COMPLETED,
        timestamp = timestamp,
        source = GamificationEventSource.WORKOUT_ENGINE,
        dedupeKey = "workout_completed:$sessionId",
        metadata = buildMap {
            put(GamificationEventMetadata.SESSION_ID, sessionId.toString())
            templateName?.let { put(GamificationEventMetadata.TEMPLATE_NAME, it) }
            put(GamificationEventMetadata.COMPLETED_EXERCISES, completedExercises.toString())
            put(GamificationEventMetadata.COMPLETED_SETS, completedSets.toString())
            durationSeconds?.let { put(GamificationEventMetadata.DURATION_SECONDS, it.toString()) }
        }
    )

    fun exerciseCompleted(
        exerciseSessionId: Long,
        exerciseId: Long,
        sessionId: Long,
        timestamp: Long,
        exerciseName: String? = null,
        completedSets: Int = 0
    ): GamificationEvent = GamificationEvent(
        type = GamificationEventType.EXERCISE_COMPLETED,
        timestamp = timestamp,
        source = GamificationEventSource.WORKOUT_ENGINE,
        dedupeKey = "exercise_completed:$exerciseSessionId",
        metadata = buildMap {
            put(GamificationEventMetadata.EXERCISE_SESSION_ID, exerciseSessionId.toString())
            put(GamificationEventMetadata.EXERCISE_ID, exerciseId.toString())
            put(GamificationEventMetadata.SESSION_ID, sessionId.toString())
            exerciseName?.let { put(GamificationEventMetadata.EXERCISE_NAME, it) }
            put(GamificationEventMetadata.COMPLETED_SETS, completedSets.toString())
        }
    )

    fun firstExerciseCompleted(
        exerciseId: Long,
        sessionId: Long,
        timestamp: Long,
        exerciseName: String? = null
    ): GamificationEvent = GamificationEvent(
        type = GamificationEventType.FIRST_EXERCISE_COMPLETED,
        timestamp = timestamp,
        source = GamificationEventSource.WORKOUT_ENGINE,
        dedupeKey = "first_exercise_completed:$exerciseId",
        metadata = buildMap {
            put(GamificationEventMetadata.EXERCISE_ID, exerciseId.toString())
            put(GamificationEventMetadata.SESSION_ID, sessionId.toString())
            exerciseName?.let { put(GamificationEventMetadata.EXERCISE_NAME, it) }
        }
    )

    fun personalRecordCreated(
        exerciseId: Long,
        prType: String,
        value: Float,
        previousValue: Float,
        timestamp: Long,
        exerciseName: String? = null
    ): GamificationEvent = GamificationEvent(
        type = GamificationEventType.PERSONAL_RECORD_CREATED,
        timestamp = timestamp,
        source = GamificationEventSource.WORKOUT_ENGINE,
        dedupeKey = "personal_record:$exerciseId:$prType:${formatValue(value)}",
        metadata = buildMap {
            put(GamificationEventMetadata.EXERCISE_ID, exerciseId.toString())
            put(GamificationEventMetadata.PR_TYPE, prType)
            put(GamificationEventMetadata.PR_VALUE, formatValue(value))
            put(GamificationEventMetadata.PR_PREVIOUS_VALUE, formatValue(previousValue))
            exerciseName?.let { put(GamificationEventMetadata.EXERCISE_NAME, it) }
        }
    )

    fun weeklyGoalCompleted(
        weekStartEpochDay: Long,
        weeklyGoal: Int,
        completedSessions: Int,
        timestamp: Long
    ): GamificationEvent = GamificationEvent(
        type = GamificationEventType.WEEKLY_GOAL_COMPLETED,
        timestamp = timestamp,
        source = GamificationEventSource.CONSISTENCY,
        dedupeKey = "weekly_goal:$weekStartEpochDay",
        metadata = mapOf(
            GamificationEventMetadata.WEEK_START_EPOCH_DAY to weekStartEpochDay.toString(),
            GamificationEventMetadata.WEEKLY_GOAL to weeklyGoal.toString(),
            GamificationEventMetadata.WEEKLY_COMPLETED to completedSessions.toString()
        )
    )

    fun streakMilestoneReached(
        streakWeeks: Int,
        weekStartEpochDay: Long,
        timestamp: Long
    ): GamificationEvent = GamificationEvent(
        type = GamificationEventType.STREAK_MILESTONE_REACHED,
        timestamp = timestamp,
        source = GamificationEventSource.CONSISTENCY,
        dedupeKey = "streak_milestone:$streakWeeks:$weekStartEpochDay",
        metadata = mapOf(
            GamificationEventMetadata.STREAK_WEEKS to streakWeeks.toString(),
            GamificationEventMetadata.WEEK_START_EPOCH_DAY to weekStartEpochDay.toString()
        )
    )

    /** Valores viram texto estável para que a chave de idempotência não dependa de formatação local. */
    private fun formatValue(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)
}
