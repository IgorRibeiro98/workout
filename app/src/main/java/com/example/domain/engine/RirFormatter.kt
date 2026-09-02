package com.example.domain.engine

/**
 * Centralized formatter for RIR (Reps em Reserva) values.
 *
 * RIR is still what gets stored (0 = failure, 1..3, 4+ meaning four or more), but the
 * app speaks to the user in effort labels rather than numbers. RIR 0 is the single
 * source of truth for "Falha".
 */
object RirFormatter {

    /**
     * Effort label for a RIR value, ordered here from hardest to easiest so that the
     * selector and any other list share one ordering.
     */
    private val effortLabels: List<Pair<Int, String>> = listOf(
        0 to "🔥 Até a falha",
        1 to "😤 Muito pesado",
        2 to "💪 Pesado",
        3 to "🙂 Controlado",
        4 to "🙂 Controlado"
    )

    private val shortEffortLabels: Map<Int, String> = mapOf(
        0 to "🔥 Falha",
        1 to "😤 M. pesado",
        2 to "💪 Pesado",
        3 to "🙂 Controlado",
        4 to "🙂 Controlado"
    )

    /** Secondary label displaying the technical RIR value */
    fun formatSecondaryRir(rir: Int?): String {
        return when (rir) {
            null -> ""
            0 -> "RIR 0"
            1 -> "RIR 1"
            2 -> "RIR 2"
            3 -> "RIR 3"
            else -> "RIR 4+"
        }
    }

    /** RIR values in the order the effort selector presents them: hardest first. */
    val effortScale: List<Int> = effortLabels.map { it.first }

    /**
     * Formats a RIR value as a user-facing effort label.
     *
     * @param rir the stored RIR value; anything at or above 4 collapses onto "Leve"
     * @param short use the abbreviated form, for one-line summaries such as set history
     */
    fun formatEffort(rir: Int?, short: Boolean = false): String? {
        if (rir == null) return null
        val bucket = rir.coerceIn(0, 4)
        val label = if (short) shortEffortLabels[bucket] else effortLabels.firstOrNull { it.first == bucket }?.second
        return label?.let { if (isFailure(bucket)) "🔥 $it" else it }
    }

    /**
     * Formats a RIR value into its numeric display string.
     *
     * Kept for places that genuinely need the number rather than the effort label.
     */
    fun formatRir(rir: Int?, full: Boolean = false): String? {
        if (rir == null) return null
        return when {
            rir == 0 -> if (full) "🔥 Até a falha" else "🔥 Falha"
            rir >= 4 -> "RIR 4+"
            else -> "RIR $rir"
        }
    }

    /**
     * Helper to check if a RIR value represents failure.
     */
    fun isFailure(rir: Int?): Boolean = rir == 0
}
