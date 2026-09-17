package com.example.data.sync

import android.os.Build
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.sync.dto.WorkoutTemplateSyncDto
import java.time.DayOfWeek
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Os dias da semana do treino atravessam o sync (T19.8).
 *
 * 1. Dois aparelhos convergem com **todos** os dias, no **mesmo** treino;
 * 2. trocar a agenda substitui — o outro aparelho não fica com dia órfão;
 * 3. o mesmo payload aplicado duas vezes produz as mesmas linhas;
 * 4. um aparelho ainda na v1 (`dayOfWeek: "Seg"`) continua sendo lido, e o dia vira agenda;
 * 5. um payload com dia fora do contrato pausa o sync em vez de inventar um dia.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncScheduleTest {

    private val ownerUid = "uid-da-conta-a"

    private lateinit var server: FakeSparkSyncServer
    private lateinit var deviceA: SyncDevice
    private lateinit var deviceB: SyncDevice

    @Before
    fun setUp() = runTest {
        server = FakeSparkSyncServer()
        deviceA = SyncDevice(server, ownerUid, "device-a")
        deviceB = SyncDevice(server, ownerUid, "device-b")
        deviceA.bind()
        deviceB.bind()
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    private suspend fun SyncDevice.scheduledDays(entitySyncId: String): List<DayOfWeek> {
        val template = database.workoutDao().getTemplateBySyncId(entitySyncId)!!
        return workouts.getTemplateScheduledDays(template.id)
    }

    private suspend fun SyncDevice.setDays(entitySyncId: String, days: Set<DayOfWeek>) {
        val template = database.workoutDao().getTemplateBySyncId(entitySyncId)!!
        workouts.updateTemplateHeader(template.id, template.name, template.shortIdentifier, days)
    }

    @Test
    fun doisDiasChegamNoOutroAparelhoNoMesmoTreino() = runTest {
        val id = deviceA.workouts.addTemplate(deviceA.ensureProgram(), "Treino A", "A", 0, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        val syncId = deviceA.database.workoutDao().getTemplateById(id)!!.syncId
        deviceA.sync()

        val outcome = deviceB.sync() as SyncOutcome.Success

        assertEquals(1, outcome.applied)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), deviceB.scheduledDays(syncId))
        assertEquals("um treino só em B", 1, deviceB.database.workoutDao().countTemplates())
        assertEquals(0, deviceB.pendingCount())

        // O que subiu é a v2, com os nomes canônicos.
        val stored = Json.parseToJsonElement(server.payloadOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, syncId)!!).jsonObject
        assertEquals(listOf("MONDAY", "THURSDAY"), stored.getValue("scheduledDays").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(stored.containsKey("dayOfWeek"))
        assertEquals(WorkoutTemplateSyncDto.SCHEMA_VERSION, server.entityState(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, syncId)!!.entitySchemaVersion)
    }

    @Test
    fun trocarAAgendaSubstituiNoOutroAparelhoSemDiaOrfao() = runTest {
        val syncId = deviceA.newTemplate("Treino A")
        deviceA.setDays(syncId, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        deviceA.sync()
        deviceB.sync()
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), deviceB.scheduledDays(syncId))

        deviceA.setDays(syncId, setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY))
        deviceA.sync()
        deviceB.sync()

        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), deviceB.scheduledDays(syncId))
        assertEquals(2, deviceB.database.workoutDao().countSchedules())

        // E zero dias também viaja: "sem dia fixo" é um estado, não ausência de informação.
        deviceA.setDays(syncId, emptySet())
        deviceA.sync()
        deviceB.sync()
        assertEquals(emptyList<DayOfWeek>(), deviceB.scheduledDays(syncId))
    }

    @Test
    fun oMesmoPayloadAplicadoDuasVezesNaoDuplicaDia() = runTest {
        val syncId = deviceA.newTemplate("Treino A")
        deviceA.setDays(syncId, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        deviceA.sync()
        deviceB.sync()

        val payload = Json.parseToJsonElement(server.payloadOf(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, syncId)!!)
        repeat(2) {
            deviceB.database.withTransaction {
                assertTrue(
                    deviceB.applier.writeRemoteAggregate(
                        SyncEntityType.WORKOUT_TEMPLATE,
                        syncId,
                        WorkoutTemplateSyncDto.SCHEMA_VERSION,
                        payload
                    )
                )
            }
        }

        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), deviceB.scheduledDays(syncId))
        assertEquals(2, deviceB.database.workoutDao().countSchedules())
    }

    @Test
    fun umAparelhoAindaNaV1ContinuaSendoLidoEODiaViraAgenda() = runTest {
        // Um aparelho anterior à T19.8 sobe o treino como v1: `dayOfWeek` com o rótulo de tela.
        val syncId = "ffffffff-0000-4000-8000-0000000000b1"
        val v1Payload = Json.parseToJsonElement(
            """
            {"syncId":"$syncId","programSyncId":"${SyncDevice.SHARED_PROGRAM_SYNC_ID}","name":"Treino antigo",
             "shortIdentifier":"V","orderInProgram":0,"dayOfWeek":"Seg","exercises":[]}
            """.trimIndent()
        )
        server.push(
            ownerUid,
            "device-antigo",
            listOf(
                SyncPushMutationDto(
                    clientMutationId = "m-v1",
                    entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
                    entitySyncId = syncId,
                    entitySchemaVersion = WorkoutTemplateSyncDto.LEGACY_SCHEMA_VERSION,
                    operation = SyncOperation.UPSERT.name,
                    baseRevision = null,
                    payload = v1Payload
                )
            )
        )

        val outcome = deviceB.sync() as SyncOutcome.Success

        assertEquals(1, outcome.applied)
        assertEquals("Treino antigo", deviceB.templateName(syncId))
        assertEquals(listOf(DayOfWeek.MONDAY), deviceB.scheduledDays(syncId))

        // E quando B tocar nesse treino, o que sobe é a v2 — a v1 nunca mais é escrita.
        deviceB.setDays(syncId, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        deviceB.sync()
        assertEquals(
            WorkoutTemplateSyncDto.SCHEMA_VERSION,
            server.entityState(ownerUid, SyncEntityType.WORKOUT_TEMPLATE, syncId)!!.entitySchemaVersion
        )
        deviceA.sync()
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), deviceA.scheduledDays(syncId))
    }

    @Test
    fun diaForaDoContratoPausaOSyncEmVezDeInventarUmDia() = runTest {
        val syncId = "ffffffff-0000-4000-8000-0000000000b2"
        val payload = Json.parseToJsonElement(
            """
            {"syncId":"$syncId","programSyncId":"${SyncDevice.SHARED_PROGRAM_SYNC_ID}","name":"Treino torto",
             "shortIdentifier":"T","orderInProgram":0,"scheduledDays":["Seg"],"exercises":[]}
            """.trimIndent()
        )
        server.push(
            ownerUid,
            "device-x",
            listOf(
                SyncPushMutationDto(
                    clientMutationId = "m-bad",
                    entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
                    entitySyncId = syncId,
                    entitySchemaVersion = WorkoutTemplateSyncDto.SCHEMA_VERSION,
                    operation = SyncOperation.UPSERT.name,
                    baseRevision = null,
                    payload = payload
                )
            )
        )

        val outcome = deviceB.sync() as SyncOutcome.Success

        assertEquals("nada foi aplicado", 0, outcome.applied)
        assertTrue("o pull parou na mudança que este app não sabe ler", outcome.pausedAt != null)
        assertEquals(null, deviceB.templateName(syncId))
        assertEquals(0, deviceB.database.workoutDao().countSchedules())
    }
}
