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
        // The labels already carry their own emoji; prefixing another one produced "🔥 🔥 Falha".
        return if (short) shortEffortLabels[bucket] else effortLabels.firstOrNull { it.first == bucket }?.second
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

    /** Title used wherever RIR is explained to the user. */
    const val HELP_TITLE = "O que é RIR?"

    /**
     * Plain-language definition shown to first-time users. Kept here so every entry point
     * explains the concept with exactly the same words.
     */
    const val HELP_DEFINITION =
        "RIR significa Repetições em Reserva. É uma estimativa de quantas repetições você ainda " +
            "conseguiria fazer antes de chegar à falha."

    /** Effort scale explained one line per level, hardest first. */
    val HELP_SCALE: List<Pair<String, String>> = listOf(
        "🔥 Falha (RIR 0)" to "Não conseguiria mais nenhuma repetição.",
        "😤 Muito pesado (RIR 1)" to "Conseguiria apenas mais 1 repetição.",
        "💪 Pesado (RIR 2)" to "Conseguiria mais 2 repetições com boa técnica.",
        "🙂 Controlado (RIR 3+)" to "Conseguiria 3 ou mais — aquecimento ou reserva alta."
    )

    /** Closing hint that connects the emoji scale to the number stored in the log. */
    const val HELP_FOOTNOTE =
        "Você escolhe pelo esforço; o número (RIR) fica registrado junto como informação complementar."
}
