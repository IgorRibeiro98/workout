package com.example.data.sync

import com.example.data.sync.dto.WorkoutTemplatePayloadCompat
import com.example.data.sync.dto.WorkoutTemplateSyncDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fronteira de versão do payload de `WORKOUT_TEMPLATE` (T19.8): v1 (`dayOfWeek`) → v2
 * (`scheduledDays`), sempre para o DTO atual, e sempre estrita.
 */
class WorkoutTemplatePayloadCompatTest {

    private val json = Json { ignoreUnknownKeys = false }

    private fun v1(day: String?) = json.parseToJsonElement(
        """{"syncId":"s","name":"A","orderInProgram":0,"dayOfWeek":${if (day == null) "null" else "\"$day\""},"exercises":[]}"""
    )

    @Test
    fun `v1 com rotulo vira v2 com o dia canonico`() {
        val dto = WorkoutTemplatePayloadCompat.decode(json, WorkoutTemplateSyncDto.LEGACY_SCHEMA_VERSION, v1("Qui"))
        assertEquals(listOf("THURSDAY"), dto.scheduledDays)
    }

    @Test
    fun `v1 sem dia vira v2 sem dia`() {
        assertEquals(emptyList<String>(), WorkoutTemplatePayloadCompat.decode(json, 1, v1(null)).scheduledDays)
        assertEquals(emptyList<String>(), WorkoutTemplatePayloadCompat.decode(json, 1, v1("quando der")).scheduledDays)
    }

    @Test
    fun `v1 ja gravada com nome canonico tambem e lida`() {
        assertEquals(listOf("MONDAY"), WorkoutTemplatePayloadCompat.decode(json, 1, v1("MONDAY")).scheduledDays)
    }

    @Test
    fun `v2 volta como esta, normalizada para a ordem da semana`() {
        val v2 = json.parseToJsonElement(
            """{"syncId":"s","name":"A","orderInProgram":0,"scheduledDays":["THURSDAY","MONDAY"],"exercises":[]}"""
        )
        val dto = WorkoutTemplatePayloadCompat.decode(json, WorkoutTemplateSyncDto.SCHEMA_VERSION, v2)
        assertEquals(listOf("MONDAY", "THURSDAY"), dto.scheduledDays)
        assertEquals(v2.jsonObject, WorkoutTemplatePayloadCompat.upgrade(v2.jsonObject))
    }

    @Test
    fun `v2 com rotulo, repeticao ou as duas chaves e recusada`() {
        for (body in listOf(
            """{"syncId":"s","name":"A","orderInProgram":0,"scheduledDays":["Seg"],"exercises":[]}""",
            """{"syncId":"s","name":"A","orderInProgram":0,"scheduledDays":["MONDAY","MONDAY"],"exercises":[]}""",
            """{"syncId":"s","name":"A","orderInProgram":0,"scheduledDays":["MONDAY"],"dayOfWeek":"Seg","exercises":[]}""",
            """{"syncId":"s","name":"A","orderInProgram":0,"dayOfWeek":7,"exercises":[]}""",
            """{"syncId":"s","name":"A","orderInProgram":0,"scheduledDays":[],"campoNovo":1,"exercises":[]}"""
        )) {
            val result = runCatching { WorkoutTemplatePayloadCompat.decode(json, 2, json.parseToJsonElement(body)) }
            assertTrue("deveria recusar: $body", result.isFailure)
            assertTrue(result.exceptionOrNull() is SerializationException || result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `versao que este app nao le e recusada antes de olhar o payload`() {
        val result = runCatching { WorkoutTemplatePayloadCompat.decode(json, 3, v1("Seg")) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertFalse(3 in WorkoutTemplateSyncDto.READABLE_SCHEMA_VERSIONS)
    }
}
