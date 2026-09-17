package com.example.domain.workout.template

import java.text.Normalizer
import java.time.DayOfWeek
import java.util.Locale

/**
 * Os dias da semana em que um treino costuma acontecer (T19.8).
 *
 * Um `WorkoutTemplate` tem **0..N** dias: nenhum (treino sem dia fixo), um, ou vários — e continua
 * sendo um treino só. O valor canônico é [DayOfWeek] (`MONDAY`..`SUNDAY`), que é o que o Room, o
 * sync, o backup e o compartilhamento de programa carregam. Rótulo de tela (`Seg`, `Qui`) é
 * apresentação e nunca identidade.
 *
 * Este objeto é o único lugar que:
 * - normaliza um conjunto de dias para a forma canônica (sem repetição, na ordem da semana);
 * - lê a forma canônica vinda de uma fronteira (sync, backup, share), recusando o que não é dia;
 * - lê o rótulo **legado** que a coluna `workout_templates.dayOfWeek` guardava até a T19.8 e que
 *   ofertas/payloads anteriores ainda podem trazer;
 * - formata os dias para a tela.
 */
object WeekdaySchedule {

    /** Sem repetição, na ordem da semana (segunda → domingo). */
    fun normalize(days: Collection<DayOfWeek>): List<DayOfWeek> = days.toSortedSet().toList()

    /** Os nomes canônicos, na ordem da semana — a forma que atravessa qualquer fronteira. */
    fun names(days: Collection<DayOfWeek>): List<String> = normalize(days).map { it.name }

    /**
     * Lê nomes canônicos vindos de uma fronteira. `null` quando qualquer valor não é um
     * `DayOfWeek` ou quando há repetição: um payload que diz `[MONDAY, MONDAY]` ou `[Seg]` está
     * fora do contrato, e recusar é melhor do que corrigir em silêncio.
     */
    fun parseCanonical(names: Collection<String>): List<DayOfWeek>? {
        val parsed = names.map { name ->
            DayOfWeek.entries.firstOrNull { it.name == name } ?: return null
        }
        if (parsed.size != parsed.toSet().size) return null
        return normalize(parsed)
    }

    /**
     * O rótulo que o formulário anterior à T19.8 gravava (`Seg`..`Dom`), ou uma variante próxima
     * que um JSON importado manualmente pode trazer (`Segunda`, `segunda-feira`, `MONDAY`, `Mon`).
     *
     * `null` para vazio ou para texto que não descreve um dia da semana — a migração e as
     * fronteiras tratam isso como "sem dia", porque não há dia a inventar.
     */
    fun fromLegacyLabel(raw: String?): DayOfWeek? {
        val key = raw?.let(::fold) ?: return null
        if (key.isEmpty()) return null
        return LEGACY_LABELS[key]
    }

    /** `Seg`, `Ter`, … — o rótulo curto de um dia, para chips e listas. */
    fun shortLabel(day: DayOfWeek): String = SHORT_LABELS.getValue(day)

    /** `Seg · Qui`, ou `null` quando não há dia: a tela decide o que mostrar nesse caso. */
    fun formatShort(days: Collection<DayOfWeek>): String? =
        normalize(days).takeIf { it.isNotEmpty() }?.joinToString(" · ") { shortLabel(it) }

    private val SHORT_LABELS: Map<DayOfWeek, String> = mapOf(
        DayOfWeek.MONDAY to "Seg",
        DayOfWeek.TUESDAY to "Ter",
        DayOfWeek.WEDNESDAY to "Qua",
        DayOfWeek.THURSDAY to "Qui",
        DayOfWeek.FRIDAY to "Sex",
        DayOfWeek.SATURDAY to "Sáb",
        DayOfWeek.SUNDAY to "Dom"
    )

    private val LEGACY_LABELS: Map<String, DayOfWeek> = buildMap {
        fun register(day: DayOfWeek, vararg labels: String) {
            labels.forEach { put(fold(it), day) }
        }
        register(DayOfWeek.MONDAY, "Seg", "Segunda", "Segunda-feira", "MONDAY", "Mon")
        register(DayOfWeek.TUESDAY, "Ter", "Terça", "Terça-feira", "TUESDAY", "Tue")
        register(DayOfWeek.WEDNESDAY, "Qua", "Quarta", "Quarta-feira", "WEDNESDAY", "Wed")
        register(DayOfWeek.THURSDAY, "Qui", "Quinta", "Quinta-feira", "THURSDAY", "Thu")
        register(DayOfWeek.FRIDAY, "Sex", "Sexta", "Sexta-feira", "FRIDAY", "Fri")
        register(DayOfWeek.SATURDAY, "Sáb", "Sábado", "SATURDAY", "Sat")
        register(DayOfWeek.SUNDAY, "Dom", "Domingo", "SUNDAY", "Sun")
    }

    /** Minúsculas, sem acento, sem espaço nas pontas: `"Sáb "` e `"sab"` são o mesmo rótulo. */
    private fun fold(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
}
