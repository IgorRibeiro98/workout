package com.example.presentation.today

import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateScheduleEntity
import com.example.data.local.WorkoutTemplateWithSchedule
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A regra do Hoje com agenda semanal (T19.8): treino agendado para hoje vence a sequência; sem
 * agenda para hoje, a sequência continua a de sempre; a escolha manual vence as duas. Um treino
 * em dois dias é o mesmo treino nos dois — e nada disso reinterpreta o passado.
 */
class TodayTemplateSelectorTest {

    private fun template(id: Long, order: Int, vararg days: DayOfWeek) = WorkoutTemplateWithSchedule(
        template = WorkoutTemplateEntity(id = id, programId = 1, name = "T$id", shortIdentifier = "T$id", orderInProgram = order),
        schedules = days.map { WorkoutTemplateScheduleEntity(id, it.name) }
    )

    // A (Seg + Qui), B (Ter), C (sem dia)
    private val a = template(10, 0, DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
    private val b = template(20, 1, DayOfWeek.TUESDAY)
    private val c = template(30, 2)
    private val program = listOf(a, b, c)

    @Test
    fun `sem treinos nao ha sugestao`() {
        assertNull(TodayTemplateSelector.select(emptyList(), null, DayOfWeek.MONDAY, null))
    }

    @Test
    fun `o mesmo treino e sugerido em cada um dos dias dele`() {
        val monday = TodayTemplateSelector.select(program, lastCompletedTemplateId = null, today = DayOfWeek.MONDAY, overrideTemplateId = null)!!
        val thursday = TodayTemplateSelector.select(program, lastCompletedTemplateId = null, today = DayOfWeek.THURSDAY, overrideTemplateId = null)!!

        assertEquals(a, monday.next)
        assertEquals(a, thursday.next)
        assertEquals(TodaySuggestionReason.SCHEDULED_TODAY, monday.suggestedReason)
        assertEquals(TodaySuggestionReason.SCHEDULED_TODAY, thursday.suggestedReason)
        assertEquals(0, thursday.suggestedIndex)
    }

    @Test
    fun `o dia agendado vence a sequencia, mesmo que a sequencia apontasse outro treino`() {
        // Último concluído foi A; a sequência diria B. Mas hoje é quinta, e A é de quinta.
        val selection = TodayTemplateSelector.select(program, lastCompletedTemplateId = 10, today = DayOfWeek.THURSDAY, overrideTemplateId = null)!!

        assertEquals(a, selection.next)
        assertEquals(TodaySuggestionReason.SCHEDULED_TODAY, selection.suggestedReason)
    }

    @Test
    fun `sem treino agendado para hoje, a sequencia continua a de sempre`() {
        val afterA = TodayTemplateSelector.select(program, lastCompletedTemplateId = 10, today = DayOfWeek.WEDNESDAY, overrideTemplateId = null)!!
        assertEquals(b, afterA.next)
        assertEquals(1, afterA.suggestedIndex)
        assertEquals(TodaySuggestionReason.SEQUENCE, afterA.suggestedReason)

        // Depois do último, volta ao primeiro.
        val afterC = TodayTemplateSelector.select(program, lastCompletedTemplateId = 30, today = DayOfWeek.WEDNESDAY, overrideTemplateId = null)!!
        assertEquals(a, afterC.next)

        // Sem histórico: o primeiro do programa.
        val fresh = TodayTemplateSelector.select(program, lastCompletedTemplateId = null, today = DayOfWeek.SUNDAY, overrideTemplateId = null)!!
        assertEquals(a, fresh.next)

        // Último concluído de um treino que já não existe: o primeiro do programa.
        val orphan = TodayTemplateSelector.select(program, lastCompletedTemplateId = 999, today = DayOfWeek.SUNDAY, overrideTemplateId = null)!!
        assertEquals(a, orphan.next)
    }

    @Test
    fun `treino sem dia continua entrando so pela sequencia`() {
        val selection = TodayTemplateSelector.select(program, lastCompletedTemplateId = 20, today = DayOfWeek.WEDNESDAY, overrideTemplateId = null)!!

        assertEquals(c, selection.next)
        assertEquals(TodaySuggestionReason.SEQUENCE, selection.suggestedReason)
    }

    @Test
    fun `dois treinos agendados para o mesmo dia - vale a ordem do programa`() {
        val d = template(40, 3, DayOfWeek.MONDAY)
        val selection = TodayTemplateSelector.select(listOf(d, a, b), lastCompletedTemplateId = null, today = DayOfWeek.MONDAY, overrideTemplateId = null)!!

        // A lista chega na ordem do programa; o primeiro agendado para hoje é o que vem antes.
        assertEquals(d, selection.next)
        assertEquals(0, selection.suggestedIndex)
    }

    @Test
    fun `a escolha manual vence a agenda e a sequencia, mas nao muda a sugestao`() {
        val selection = TodayTemplateSelector.select(program, lastCompletedTemplateId = null, today = DayOfWeek.MONDAY, overrideTemplateId = 30)!!

        assertEquals(c, selection.next)
        assertEquals("a sugestão continua sendo A (agendado para hoje)", 0, selection.suggestedIndex)
        assertEquals(TodaySuggestionReason.SCHEDULED_TODAY, selection.suggestedReason)
    }

    @Test
    fun `escolha manual de um treino que nao existe mais e ignorada`() {
        val selection = TodayTemplateSelector.select(program, lastCompletedTemplateId = null, today = DayOfWeek.MONDAY, overrideTemplateId = 999)!!
        assertEquals(a, selection.next)
    }
}
