package com.example.data.social

import com.example.domain.social.SharedProgramTemplateSnapshot
import java.time.DayOfWeek
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O treino dentro de um programa compartilhado atravessa a fronteira com os dias canônicos
 * (T19.8) — e continua lendo a oferta anterior à T19.8, que trazia um dia só como rótulo.
 */
class WorkoutShareScheduleDtoTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `o que sai e scheduledDays canonico, nunca dayOfWeek`() {
        val dto = SharedProgramTemplateSnapshotDto.fromDomain(
            SharedProgramTemplateSnapshot(
                name = "Push",
                orderInProgram = 0,
                scheduledDays = listOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY)
            )
        )
        val text = json.encodeToString(SharedProgramTemplateSnapshotDto.serializer(), dto)

        assertTrue(text.contains("\"scheduledDays\":[\"MONDAY\",\"THURSDAY\"]"))
        assertFalse(text.contains("dayOfWeek"))

        // Sem dia: a lista vazia viaja explícita — "sem dia fixo" é um estado.
        val none = SharedProgramTemplateSnapshotDto.fromDomain(SharedProgramTemplateSnapshot(name = "Livre", orderInProgram = 1))
        assertTrue(json.encodeToString(SharedProgramTemplateSnapshotDto.serializer(), none).contains("\"scheduledDays\":[]"))
    }

    @Test
    fun `uma oferta anterior a T19_8 com dayOfWeek vira um dia canonico no dominio`() {
        val legacy = json.decodeFromString(
            SharedProgramTemplateSnapshotDto.serializer(),
            """{"name":"Push","shortIdentifier":"A","orderInProgram":0,"dayOfWeek":"Seg","exercises":[]}"""
        )
        assertEquals(listOf(DayOfWeek.MONDAY), legacy.toDomain().scheduledDays)

        val legacyNone = json.decodeFromString(
            SharedProgramTemplateSnapshotDto.serializer(),
            """{"name":"Pull","orderInProgram":1,"dayOfWeek":null,"exercises":[]}"""
        )
        assertEquals(emptyList<DayOfWeek>(), legacyNone.toDomain().scheduledDays)
    }

    @Test
    fun `scheduledDays vem como esta, e o que nao e dia vira sem dia em vez de erro`() {
        val current = json.decodeFromString(
            SharedProgramTemplateSnapshotDto.serializer(),
            """{"name":"Push","orderInProgram":0,"scheduledDays":["THURSDAY","MONDAY"],"exercises":[]}"""
        )
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), current.toDomain().scheduledDays)

        val broken = json.decodeFromString(
            SharedProgramTemplateSnapshotDto.serializer(),
            """{"name":"Push","orderInProgram":0,"scheduledDays":["Seg"],"exercises":[]}"""
        )
        assertEquals(emptyList<DayOfWeek>(), broken.toDomain().scheduledDays)
    }
}
