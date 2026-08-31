package com.example.domain.engine

/**
 * Centralized formatter for RIR (Reps em Reserva) values.
 * RIR 0 is the single source of truth for "🔥 Falha" / "🔥 Até a falha".
 */
object RirFormatter {
    /**
     * Formats a RIR integer value into a user-friendly display string.
     * @param rir the RIR value (0 for failure, 1..3, 4+ for 4 or more, null for unspecified)
     * @param full if true, returns "🔥 Até a falha" for RIR 0 instead of "🔥 Falha"
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
