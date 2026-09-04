package com.example.domain.gamification

import com.example.data.datastore.SettingsManager
import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventSource
import com.example.domain.gamification.model.GamificationEventType
import com.example.domain.gamification.model.XpTransaction
import com.example.domain.gamification.repository.GamificationEventRepository
import com.example.domain.gamification.repository.XpTransactionRepository
import kotlinx.coroutines.flow.first

class XpReconciler(
    private val xpTransactionRepository: XpTransactionRepository,
    private val eventRepository: GamificationEventRepository,
    private val settingsManager: SettingsManager
) {
    suspend fun reconcile() {
        val currentVersion = settingsManager.xpPolicyVersionFlow.first()
        val shouldRebuild = currentVersion < XpRewardPolicy.VERSION

        if (shouldRebuild) {
            xpTransactionRepository.deleteAllTransactions()
            settingsManager.setXpPolicyVersion(XpRewardPolicy.VERSION)
        }

        val allEvents = eventRepository.getEvents().sortedBy { it.timestamp }
        
        // Handle FIRST_WORKOUT_COMPLETED derivation
        val hasFirstWorkoutEvent = allEvents.any { it.type == GamificationEventType.FIRST_WORKOUT_COMPLETED }
        if (!hasFirstWorkoutEvent) {
            val firstWorkout = allEvents.firstOrNull { it.type == GamificationEventType.WORKOUT_COMPLETED }
            if (firstWorkout != null) {
                val firstWorkoutEvent = GamificationEvent(
                    type = GamificationEventType.FIRST_WORKOUT_COMPLETED,
                    timestamp = firstWorkout.timestamp,
                    metadata = firstWorkout.metadata,
                    source = GamificationEventSource.WORKOUT_ENGINE,
                    dedupeKey = "first_workout_completed"
                )
                eventRepository.record(firstWorkoutEvent)
            }
        }
        
        // Refresh events in case we added FIRST_WORKOUT_COMPLETED
        val updatedEvents = eventRepository.getEvents().sortedBy { it.timestamp }

        for (event in updatedEvents) {
            val reward = XpRewardPolicy.rewardFor(event)
            if (reward != null) {
                val hasTransaction = xpTransactionRepository.hasTransactionForEvent(event.id)
                if (!hasTransaction) {
                    xpTransactionRepository.saveTransaction(
                        XpTransaction(
                            eventId = event.id,
                            amount = reward.amount,
                            reason = reward.reason,
                            createdAt = event.timestamp
                        )
                    )
                }
            }
        }
    }
}
