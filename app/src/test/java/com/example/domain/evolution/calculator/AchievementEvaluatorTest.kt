package com.example.domain.evolution.calculator

import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.consistency.WeeklyConsistencyStatus
import com.example.domain.evolution.model.achievement.AchievementEvaluationContext
import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.model.GamificationEventMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AchievementEvaluatorTest {

    @Test
    fun testTrainingAchievementEvaluation() {
        val testCases = listOf(0, 1, 9, 10, 24, 25, 49, 50, 99, 100)
        
        for (count in testCases) {
            val timestamps = (1..count).map { it * 1000L }
            val context = AchievementEvaluationContext(
                completedWorkoutsCount = count,
                completedWorkoutsTimestamps = timestamps,
                gamificationEvents = emptyList(),
                measurements = emptyList(),
                consistencyProgress = null
            )
            
            val evaluations = AchievementEvaluator.evaluate(context)
            
            val first = evaluations.find { it.definition.id == "first_workout" }!!
            assertEquals("First workout (target 1) for count $count", count >= 1, first.eligibleForUnlock)
            if (count >= 1) assertEquals(1000L, first.reachedAt)

            val ten = evaluations.find { it.definition.id == "10_workouts" }!!
            assertEquals("10 workouts (target 10) for count $count", count >= 10, ten.eligibleForUnlock)
            if (count >= 10) assertEquals(10000L, ten.reachedAt)

            val twentyFive = evaluations.find { it.definition.id == "25_workouts" }!!
            assertEquals("25 workouts (target 25) for count $count", count >= 25, twentyFive.eligibleForUnlock)
            if (count >= 25) assertEquals(25000L, twentyFive.reachedAt)

            val fifty = evaluations.find { it.definition.id == "50_workouts" }!!
            assertEquals("50 workouts (target 50) for count $count", count >= 50, fifty.eligibleForUnlock)
            if (count >= 50) assertEquals(50000L, fifty.reachedAt)

            val hundred = evaluations.find { it.definition.id == "100_workouts" }!!
            assertEquals("100 workouts (target 100) for count $count", count >= 100, hundred.eligibleForUnlock)
            if (count >= 100) assertEquals(100000L, hundred.reachedAt)
        }
    }

    @Test
    fun testPerformanceAchievementEvaluation() {
        val testCases = listOf(0, 1, 4, 5, 9, 10, 24, 25)
        
        for (count in testCases) {
            val prEvents = (1..count).map {
                GamificationEvent(
                    id = "pr_event_$it",
                    type = GamificationEventType.PERSONAL_RECORD_CREATED,
                    timestamp = it * 1000L,
                    metadata = mapOf()
                )
            }
            
            val context = AchievementEvaluationContext(
                completedWorkoutsCount = 0,
                completedWorkoutsTimestamps = emptyList(),
                gamificationEvents = prEvents,
                measurements = emptyList(),
                consistencyProgress = null
            )
            val evaluations = AchievementEvaluator.evaluate(context)
            
            val pr1 = evaluations.find { it.definition.id == "first_pr" }!!
            assertEquals("1 PR for count $count", count >= 1, pr1.eligibleForUnlock)
            if (count >= 1) {
                assertEquals(1000L, pr1.reachedAt)
                assertEquals("pr_event_1", pr1.triggerEventId)
            }
            
            val pr5 = evaluations.find { it.definition.id == "5_prs" }!!
            assertEquals("5 PR for count $count", count >= 5, pr5.eligibleForUnlock)
            if (count >= 5) {
                assertEquals(5000L, pr5.reachedAt)
                assertEquals("pr_event_5", pr5.triggerEventId)
            }

            val pr10 = evaluations.find { it.definition.id == "10_prs" }!!
            assertEquals("10 PR for count $count", count >= 10, pr10.eligibleForUnlock)
            if (count >= 10) {
                assertEquals(10000L, pr10.reachedAt)
                assertEquals("pr_event_10", pr10.triggerEventId)
            }

            val pr25 = evaluations.find { it.definition.id == "25_prs" }!!
            assertEquals("25 PR for count $count", count >= 25, pr25.eligibleForUnlock)
            if (count >= 25) {
                assertEquals(25000L, pr25.reachedAt)
                assertEquals("pr_event_25", pr25.triggerEventId)
            }
        }
    }
    
    @Test
    fun testBodyAchievementEvaluation() {
        val testCases = listOf(0, 1, 3, 4, 11, 12, 23, 24)
        
        for (count in testCases) {
            val measurements = (1..count).map { dayIndex ->
                BodyMeasurement(
                    id = dayIndex.toLong(),
                    date = dayIndex * 86400000L, // distinct days
                    weightKg = 70f + dayIndex,
                    heightCm = null, waistCm = null, abdomenCm = null,
                    chestCm = null, leftArmCm = null, rightArmCm = null,
                    leftThighCm = null, rightThighCm = null, leftCalfCm = null,
                    rightCalfCm = null, hipCm = null, bodyFatPercentage = null
                )
            }

            val context = AchievementEvaluationContext(
                completedWorkoutsCount = 0,
                completedWorkoutsTimestamps = emptyList(),
                gamificationEvents = emptyList(),
                measurements = measurements,
                consistencyProgress = null
            )
            val evaluations = AchievementEvaluator.evaluate(context)

            val m1 = evaluations.find { it.definition.id == "first_measurement" }!!
            assertEquals("1 measurement for count $count", count >= 1, m1.eligibleForUnlock)
            if (count >= 1) assertEquals(86400000L, m1.reachedAt)

            val m4 = evaluations.find { it.definition.id == "4_measurements" }!!
            assertEquals("4 measurements for count $count", count >= 4, m4.eligibleForUnlock)
            if (count >= 4) assertEquals(4 * 86400000L, m4.reachedAt)

            val m12 = evaluations.find { it.definition.id == "12_measurements" }!!
            assertEquals("12 measurements for count $count", count >= 12, m12.eligibleForUnlock)
            if (count >= 12) assertEquals(12 * 86400000L, m12.reachedAt)

            val m24 = evaluations.find { it.definition.id == "24_measurements" }!!
            assertEquals("24 measurements for count $count", count >= 24, m24.eligibleForUnlock)
            if (count >= 24) assertEquals(24 * 86400000L, m24.reachedAt)
        }

        // Test multiple measurements on the same day:
        val sameDayMeasurements = listOf(
            BodyMeasurement(id = 1, date = 100000L, weightKg = 70f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null),
            BodyMeasurement(id = 2, date = 100000L, weightKg = null, heightCm = null, waistCm = 80f, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null),
            BodyMeasurement(id = 3, date = 86400000L * 2, weightKg = 69f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null),
            BodyMeasurement(id = 4, date = 86400000L * 2, weightKg = null, heightCm = null, waistCm = 79f, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)
        )
        val context = AchievementEvaluationContext(
            completedWorkoutsCount = 0,
            completedWorkoutsTimestamps = emptyList(),
            gamificationEvents = emptyList(),
            measurements = sameDayMeasurements,
            consistencyProgress = null
        )
        val evaluations = AchievementEvaluator.evaluate(context)
        val body1 = evaluations.find { it.definition.id == "first_measurement" }!!
        assertEquals(true, body1.eligibleForUnlock)
        
        val body4 = evaluations.find { it.definition.id == "4_measurements" }!!
        assertEquals(2, body4.currentProgress) 
        assertEquals(false, body4.eligibleForUnlock)
    }

    @Test
    fun testConsistencyAchievementEvaluation() {
        val testCases = listOf(2, 4, 8, 12, 24, 52)
        
        // Exact events test
        for (count in testCases) {
            val streakEvent = GamificationEvent(
                id = "streak_event_$count",
                type = GamificationEventType.STREAK_MILESTONE_REACHED,
                timestamp = count * 1000L, 
                metadata = mapOf(GamificationEventMetadata.STREAK_WEEKS to count.toString())
            )
            
            val context = AchievementEvaluationContext(
                completedWorkoutsCount = 0,
                completedWorkoutsTimestamps = emptyList(),
                gamificationEvents = listOf(streakEvent),
                measurements = emptyList(),
                consistencyProgress = ConsistencyProgress(
                    longestStreakWeeks = count,
                    currentStreakWeeks = count,
                    currentWeekCompleted = 1,
                    currentWeekGoal = 1,
                    currentWeekStatus = WeeklyConsistencyStatus.COMPLETED
                )
            )
            
            val evaluations = AchievementEvaluator.evaluate(context)
            
            val defId = "streak_${count}_weeks"
            val eval = evaluations.find { it.definition.id == defId }!!
            assertEquals("Streak $count should be eligible", true, eval.eligibleForUnlock)
            assertEquals("Streak $count reachedAt matches exact event", count * 1000L, eval.reachedAt)
            assertEquals("Streak $count triggerEventId matches exact event", "streak_event_$count", eval.triggerEventId)
        }
    }

    @Test
    fun testConsistencyHigherMilestoneDoesNotServeAsTimestampForLower() {
        // Only milestone 8 event exists, but user has streak = 8
        val streak8Event = GamificationEvent(
            id = "streak_event_8",
            type = GamificationEventType.STREAK_MILESTONE_REACHED,
            timestamp = 80000L,
            metadata = mapOf(GamificationEventMetadata.STREAK_WEEKS to "8")
        )

        val context = AchievementEvaluationContext(
            completedWorkoutsCount = 0,
            completedWorkoutsTimestamps = emptyList(),
            gamificationEvents = listOf(streak8Event),
            measurements = emptyList(),
            consistencyProgress = ConsistencyProgress(
                longestStreakWeeks = 8,
                currentStreakWeeks = 8,
                currentWeekCompleted = 1,
                currentWeekGoal = 1,
                currentWeekStatus = WeeklyConsistencyStatus.COMPLETED
            )
        )

        val evaluations = AchievementEvaluator.evaluate(context)

        // streak_4_weeks: eligible = true, but reachedAt MUST BE NULL because only milestone 8 exists!
        val streak4 = evaluations.find { it.definition.id == "streak_4_weeks" }!!
        assertTrue("streak_4_weeks is eligible because streak is 8", streak4.eligibleForUnlock)
        assertNull("streak_4_weeks MUST NOT use milestone 8 event timestamp", streak4.reachedAt)
        assertNull("streak_4_weeks MUST NOT use milestone 8 triggerEventId", streak4.triggerEventId)

        // streak_2_weeks: eligible = true, but reachedAt MUST BE NULL
        val streak2 = evaluations.find { it.definition.id == "streak_2_weeks" }!!
        assertTrue("streak_2_weeks is eligible because streak is 8", streak2.eligibleForUnlock)
        assertNull("streak_2_weeks MUST NOT use milestone 8 event timestamp", streak2.reachedAt)
        assertNull("streak_2_weeks MUST NOT use milestone 8 triggerEventId", streak2.triggerEventId)

        // streak_8_weeks: eligible = true, reachedAt = 80000L
        val streak8 = evaluations.find { it.definition.id == "streak_8_weeks" }!!
        assertTrue("streak_8_weeks is eligible", streak8.eligibleForUnlock)
        assertEquals(80000L, streak8.reachedAt)
        assertEquals("streak_event_8", streak8.triggerEventId)
    }

    @Test
    fun testNoInventedHistoricalDateOnAbsenceOfEvent() {
        val context = AchievementEvaluationContext(
            completedWorkoutsCount = 0,
            completedWorkoutsTimestamps = emptyList(),
            gamificationEvents = emptyList(),
            measurements = emptyList(),
            consistencyProgress = ConsistencyProgress(
                longestStreakWeeks = 10,
                currentStreakWeeks = 10,
                currentWeekCompleted = 1,
                currentWeekGoal = 1,
                currentWeekStatus = WeeklyConsistencyStatus.COMPLETED
            )
        )
        
        val evaluations = AchievementEvaluator.evaluate(context)
        val eval = evaluations.find { it.definition.id == "streak_8_weeks" }!!
        
        assertTrue("Should be eligible since streak is 10", eval.eligibleForUnlock)
        assertNull("Should NOT have invented a date when event is absent", eval.reachedAt)
    }

    @Test
    fun testTrainingTriggerEventIdSafeCorrelation() {
        // Workout 1 at 1000L, Workout 2 at 2000L, Workout 3 at 3000L, ... Workout 10 at 10000L
        val timestamps = (1..10).map { it * 1000L }

        // Gamification events: only an event for workout at 1000L and an event at an unrelated timestamp 99999L
        val events = listOf(
            GamificationEvent(
                id = "ev_workout_1",
                type = GamificationEventType.WORKOUT_COMPLETED,
                timestamp = 1000L,
                source = com.example.domain.gamification.model.GamificationEventSource.WORKOUT_ENGINE,
                dedupeKey = "k1",
                metadata = emptyMap()
            ),
            GamificationEvent(
                id = "ev_workout_unrelated",
                type = GamificationEventType.WORKOUT_COMPLETED,
                timestamp = 99999L,
                source = com.example.domain.gamification.model.GamificationEventSource.WORKOUT_ENGINE,
                dedupeKey = "k2",
                metadata = emptyMap()
            )
        )

        val context = AchievementEvaluationContext(
            completedWorkoutsCount = 10,
            completedWorkoutsTimestamps = timestamps,
            gamificationEvents = events,
            measurements = emptyList(),
            consistencyProgress = null
        )

        val evaluations = AchievementEvaluator.evaluate(context)

        // first_workout: reachedAt = 1000L, matching event at 1000L exists -> triggerEventId = "ev_workout_1"
        val first = evaluations.find { it.definition.id == "first_workout" }!!
        assertEquals(1000L, first.reachedAt)
        assertEquals("ev_workout_1", first.triggerEventId)

        // 10_workouts: reachedAt = 10000L, no matching event at 10000L -> triggerEventId = null (does NOT grab unrelated event)
        val ten = evaluations.find { it.definition.id == "10_workouts" }!!
        assertEquals(10000L, ten.reachedAt)
        assertNull("10_workouts must not have triggerEventId when no event correlates at reachedAt", ten.triggerEventId)
    }
}
