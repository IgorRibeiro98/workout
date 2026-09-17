package com.example.domain.workout.template

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * O valor canônico dos dias da semana (T19.8): zero, um e vários dias; sem repetição; a leitura
 * estrita da fronteira; e a leitura tolerante do rótulo legado que a coluna antiga guardava.
 */
class WeekdayScheduleTest {

    @Test
    fun `zero dias e um conjunto vazio, nao um erro`() {
        assertEquals(emptyList<DayOfWeek>(), WeekdaySchedule.normalize(emptyList()))
        assertEquals(emptyList<String>(), WeekdaySchedule.names(emptySet()))
        assertNull(WeekdaySchedule.formatShort(emptyList()))
    }

    @Test
    fun `um dia continua sendo um dia`() {
        assertEquals(listOf(DayOfWeek.MONDAY), WeekdaySchedule.normalize(listOf(DayOfWeek.MONDAY)))
        assertEquals("Seg", WeekdaySchedule.formatShort(listOf(DayOfWeek.MONDAY)))
    }

    @Test
    fun `varios dias saem sem repeticao e na ordem da semana`() {
        val days = WeekdaySchedule.normalize(listOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY))

        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY), days)
        assertEquals(listOf("MONDAY", "THURSDAY", "SUNDAY"), WeekdaySchedule.names(days))
        assertEquals("Seg · Qui · Dom", WeekdaySchedule.formatShort(days))
    }

    @Test
    fun `a fronteira le so nomes canonicos, sem repeticao`() {
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            WeekdaySchedule.parseCanonical(listOf("THURSDAY", "MONDAY"))
        )
        assertEquals(emptyList<DayOfWeek>(), WeekdaySchedule.parseCanonical(emptyList()))
        assertNull("rótulo de tela não é identidade", WeekdaySchedule.parseCanonical(listOf("Seg")))
        assertNull("caixa diferente não é o mesmo nome", WeekdaySchedule.parseCanonical(listOf("monday")))
        assertNull("repetição está fora do contrato", WeekdaySchedule.parseCanonical(listOf("MONDAY", "MONDAY")))
        assertNull(WeekdaySchedule.parseCanonical(listOf("MONDAY", "FUNDAY")))
    }

    @Test
    fun `o rotulo legado e reconhecido em todas as formas que o app ja gravou`() {
        assertEquals(DayOfWeek.MONDAY, WeekdaySchedule.fromLegacyLabel("Seg"))
        assertEquals(DayOfWeek.TUESDAY, WeekdaySchedule.fromLegacyLabel("Ter"))
        assertEquals(DayOfWeek.WEDNESDAY, WeekdaySchedule.fromLegacyLabel("Qua"))
        assertEquals(DayOfWeek.THURSDAY, WeekdaySchedule.fromLegacyLabel("Qui"))
        assertEquals(DayOfWeek.FRIDAY, WeekdaySchedule.fromLegacyLabel("Sex"))
        assertEquals(DayOfWeek.SATURDAY, WeekdaySchedule.fromLegacyLabel("Sáb"))
        assertEquals(DayOfWeek.SUNDAY, WeekdaySchedule.fromLegacyLabel("Dom"))
        // Variantes que um JSON importado manualmente ou a fixture do contrato traziam.
        assertEquals(DayOfWeek.MONDAY, WeekdaySchedule.fromLegacyLabel("MONDAY"))
        assertEquals(DayOfWeek.SATURDAY, WeekdaySchedule.fromLegacyLabel(" sabado "))
        assertEquals(DayOfWeek.WEDNESDAY, WeekdaySchedule.fromLegacyLabel("Quarta-feira"))
        assertEquals(DayOfWeek.FRIDAY, WeekdaySchedule.fromLegacyLabel("fri"))
    }

    @Test
    fun `o que nao descreve um dia vira sem dia, nunca um dia inventado`() {
        assertNull(WeekdaySchedule.fromLegacyLabel(null))
        assertNull(WeekdaySchedule.fromLegacyLabel(""))
        assertNull(WeekdaySchedule.fromLegacyLabel("   "))
        assertNull(WeekdaySchedule.fromLegacyLabel("Nenhum"))
        assertNull(WeekdaySchedule.fromLegacyLabel("quando der"))
        assertNull(WeekdaySchedule.fromLegacyLabel("Seg,Qui"))
    }

    @Test
    fun `os rotulos curtos sao os da tela e a identidade e o enum`() {
        assertEquals(
            listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom"),
            DayOfWeek.entries.map { WeekdaySchedule.shortLabel(it) }
        )
    }
}
