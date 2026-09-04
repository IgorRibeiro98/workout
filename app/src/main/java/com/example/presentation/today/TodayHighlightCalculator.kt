package com.example.presentation.today

import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import java.util.Calendar
import java.util.Locale

/**
 * Decides the single piece of past news the Home screen is allowed to show.
 *
 * "Hoje" answers "qual treino eu faço agora?"; dashboards, charts and detailed history belong to
 * Evolução. Keeping the choice here — pure and free of Android types — is what makes the rule
 * testable and keeps the screen from growing a second dashboard.
 */
object TodayHighlightCalculator {

    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val RECENT_MILESTONE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    private const val MIN_STREAK_WEEKS = 2

    /**
     * Number of consecutive weeks, counting back from the current one, with at least one workout.
     *
     * The current week is allowed to be empty without breaking the streak — it is only Monday for
     * someone who has not trained yet — so counting starts from the previous week in that case.
     */
    fun calculateStreakWeeks(
        sessionTimestamps: List<Long>,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (sessionTimestamps.isEmpty()) return 0

        val trainedWeeks = sessionTimestamps.map { weekIndexOf(it) }.toSet()
        val currentWeek = weekIndexOf(nowMs)

        var week = if (trainedWeeks.contains(currentWeek)) currentWeek else currentWeek - 1
        var streak = 0
        while (trainedWeeks.contains(week)) {
            streak++
            week--
        }
        return streak
    }

    /**
     * Formats the most recent personal record, but only while it is still recent enough to feel
     * like news.
     */
    fun formatRecentMilestone(
        recentPRs: List<PersonalRecordEntity>,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val pr = recentPRs
            .filter { nowMs - it.date <= RECENT_MILESTONE_WINDOW_MS }
            .maxByOrNull { it.date } ?: return null

        val value = formatValue(pr.value)
        return when (pr.prType) {
            PRType.MAX_WEIGHT -> "Novo recorde de carga: $value kg"
            PRType.ONE_REP_MAX -> "Novo recorde estimado (1RM): $value kg"
            PRType.MAX_VOLUME -> "Novo recorde de volume: $value kg"
            PRType.MAX_REPS_AT_WEIGHT -> "Novo recorde de repetições: $value"
        }
    }

    /**
     * Picks the highlight. A fresh record wins over a streak: it is the more surprising news, and
     * only one of the two is ever shown.
     */
    fun buildHighlight(streakWeeks: Int, recentMilestone: String?): TodayHighlight? {
        return when {
            recentMilestone != null -> TodayHighlight(emoji = "🏆", text = recentMilestone)
            streakWeeks >= MIN_STREAK_WEEKS -> TodayHighlight(emoji = "🔥", text = "$streakWeeks semanas treinando")
            else -> null
        }
    }

    private fun formatValue(value: Float): String {
        return if (value % 1f == 0f) value.toInt().toString() else String.format(Locale("pt", "BR"), "%.1f", value)
    }

    /**
     * Weeks elapsed since the epoch for the week containing [timestamp], using the locale's own
     * first day of week so the count matches the weekly goal shown right above it.
     */
    private fun weekIndexOf(timestamp: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return Math.floorDiv(cal.timeInMillis, WEEK_MS)
    }
}
