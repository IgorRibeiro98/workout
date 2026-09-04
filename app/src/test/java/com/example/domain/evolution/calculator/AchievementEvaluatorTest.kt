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
            
            val firstWorkout = evaluations.find { it.definition.id == "first_workout" }!!
            assertEquals("First workout should be eligible if count >= 1", count >= 1, firstWorkout.eligibleForUnlock)
            if (count >= 1) {
                assertEquals(1000L, firstWorkout.reachedAt)
            }
            
            val tenWorkouts = evaluations.find { it.definition.id == "10_workouts" }!!
            assertEquals("10 workouts should be eligible if count >= 10", count >= 10, tenWorkouts.eligibleForUnlock)
            if (count >= 10) {
                assertEquals(10000L, tenWorkouts.reachedAt)
            }
        }
    }

    @Test
    fun testPerformanceAchievementEvaluation() {
        val testCases = listOf(0, 1, 4, 5, 9, 10, 24, 25)
        
        for (count in testCases) {
            val prEvents = (1..count).map {
                GamificationEvent(
                    id = UUID.randomUUID().toString(),
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
            assertEquals("1 PR should be eligible if count >= 1", count >= 1, pr1.eligibleForUnlock)
            
            val pr5 = evaluations.find { it.definition.id == "5_prs" }!!
            assertEquals("5 PR should be eligible if count >= 5", count >= 5, pr5.eligibleForUnlock)
        }
    }
    
    @Test
    fun testBodyAchievementEvaluation() {
        val measurements = listOf(
            BodyMeasurement(id = 1, date = 100000L, weightKg = 70f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null),
            BodyMeasurement(id = 2, date = 100000L, weightKg = null, heightCm = null, waistCm = 80f, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null),
            BodyMeasurement(id = 3, date = 86400000L * 2, weightKg = 69f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null),
            BodyMeasurement(id = 4, date = 86400000L * 2, weightKg = null, heightCm = null, waistCm = 79f, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)
        )
        
        val context = AchievementEvaluationContext(
            completedWorkoutsCount = 0,
            completedWorkoutsTimestamps = emptyList(),
            gamificationEvents = emptyList(),
            measurements = measurements,
            consistencyProgress = null
        )
        val evaluations = AchievementEvaluator.evaluate(context)
        
        val body1 = evaluations.find { it.definition.id == "first_measurement" }!!
        assertEquals(true, body1.eligibleForUnlock)
        
        val body5 = evaluations.find { it.definition.id == "4_measurements" }!!
        assertEquals(2, body5.currentProgress) 
        assertEquals(false, body5.eligibleForUnlock)
    }

    @Test
    fun testConsistencyAchievementEvaluation() {
        val testCases = listOf(2, 4, 8, 12, 24, 52)
        
        for (count in testCases) {
            val streakEvent = GamificationEvent(
                id = UUID.randomUUID().toString(),
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
            val eval = evaluations.find { it.definition.id == defId }
            if (eval != null) {
                assertEquals(true, eval.eligibleForUnlock)
                assertEquals(count * 1000L, eval.reachedAt)
            }
        }
    }

    @Test
    fun testNoInventedHistoricalDate() {
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
        assertNull("Should NOT have invented a date", eval.reachedAt)
    }
}
