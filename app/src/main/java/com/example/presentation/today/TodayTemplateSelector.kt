package com.example.presentation.today

import com.example.data.local.WorkoutTemplateWithSchedule
import java.time.DayOfWeek

/** Por que o Hoje está sugerindo este treino. */
enum class TodaySuggestionReason {
    /** O treino está agendado para o dia de hoje (T19.8). */
    SCHEDULED_TODAY,

    /** O próximo da sequência do programa depois do último treino concluído. */
    SEQUENCE
}

/**
 * O que a Home vai mostrar: a sugestão (e o índice dela, em torno do qual a sequência é montada)
 * e o treino efetivo — que é a sugestão, ou o que o usuário escolheu em "Trocar".
 */
data class TodaySelection(
    val suggestedIndex: Int,
    val suggestedReason: TodaySuggestionReason,
    val next: WorkoutTemplateWithSchedule
)

/**
 * Decide qual treino o Hoje sugere (T19.8).
 *
 * A regra, na ordem:
 *
 * 1. um treino **agendado para hoje** vence a sequência — o primeiro na ordem do programa, se
 *    houver mais de um. Um treino agendado para segunda **e** quinta é o mesmo treino nos dois
 *    dias; nada é duplicado para isso;
 * 2. sem treino agendado para hoje, vale o que sempre valeu: o próximo da sequência depois do
 *    último concluído (ou o primeiro do programa);
 * 3. a escolha manual ("Trocar") continua vencendo as duas — ela não muda a sugestão, muda o
 *    treino efetivo.
 *
 * Só isso. Não há "perdeu segunda, empurra para terça", não há recorrência nem calendário: a
 * agenda responde "quando este treino costuma acontecer", e o Hoje só a consulta para o dia atual.
 *
 * Pura e sem Android, para que a regra seja testável sem montar a ViewModel inteira.
 */
object TodayTemplateSelector {

    fun select(
        templates: List<WorkoutTemplateWithSchedule>,
        lastCompletedTemplateId: Long?,
        today: DayOfWeek,
        overrideTemplateId: Long?
    ): TodaySelection? {
        if (templates.isEmpty()) return null

        val scheduledIndex = templates.indexOfFirst { today in it.scheduledDays }
        val (suggestedIndex, reason) = if (scheduledIndex != -1) {
            scheduledIndex to TodaySuggestionReason.SCHEDULED_TODAY
        } else {
            sequenceIndex(templates, lastCompletedTemplateId) to TodaySuggestionReason.SEQUENCE
        }

        val override = overrideTemplateId?.let { id -> templates.firstOrNull { it.template.id == id } }
        return TodaySelection(
            suggestedIndex = suggestedIndex,
            suggestedReason = reason,
            next = override ?: templates[suggestedIndex]
        )
    }

    private fun sequenceIndex(templates: List<WorkoutTemplateWithSchedule>, lastCompletedTemplateId: Long?): Int {
        if (lastCompletedTemplateId == null) return 0
        val lastIndex = templates.indexOfFirst { it.template.id == lastCompletedTemplateId }
        return if (lastIndex == -1) 0 else (lastIndex + 1) % templates.size
    }
}
