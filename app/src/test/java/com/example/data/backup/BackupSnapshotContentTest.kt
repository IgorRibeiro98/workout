package com.example.data.backup

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.BodyMeasurementEntity
import com.example.data.local.CheckInEntity
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.SessionStatus
import com.example.data.local.SetLogEntity
import com.example.data.local.WeeklyGoalHistoryEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.sync.DeviceIdProvider
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncAggregateSnapshotBuilder
import com.example.data.sync.SyncIds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O que entra e o que **não** entra no snapshot (T16.4).
 *
 * A matriz de dados decide, e este teste é o que impede a matriz de virar ficção: ele monta um
 * banco com todos os grupos — pessoal, derivado, local e mídia — e verifica o payload real.
 *
 * O outro invariante que ele cobre é o mais fácil de quebrar sem perceber: **backup só lê**.
 * Histórico concluído, templates e gamificação precisam estar byte a byte iguais depois do
 * backup, e a construção do snapshot não pode ser aproveitada para "arrumar" nada.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class BackupSnapshotContentTest {

    private lateinit var database: AppDatabase
    private lateinit var api: FakeBackupApi
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json

    private val uid = "uid-da-conta"
    private lateinit var fixture: Fixture

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = FakeBackupApi()
        kotlinx.coroutines.runBlocking { fixture = seed() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------- o que entra

    @Test
    fun `o snapshot contem apenas os agregados permitidos`() = runTest {
        val snapshot = capture()

        val types = snapshot.items.map { it.entityType }.toSet()
        assertEquals(
            setOf(
                "WORKOUT_PROGRAM",
                "WORKOUT_TEMPLATE",
                "WORKOUT_SESSION",
                "CUSTOM_EXERCISE",
                "BODY_MEASUREMENT",
                "CHECK_IN",
                "EXERCISE_OVERRIDE",
                "WEEKLY_GOAL",
                "USER_PREFERENCES"
            ),
            types
        )
        // Todo tipo presente é um tipo do registry — nada "extra" atravessa.
        types.forEach { BackupEntityType.valueOf(it) }
    }

    @Test
    fun `cada item carrega a versao de schema do proprio agregado`() = runTest {
        capture().items.forEach { item ->
            assertEquals(
                BackupEntityType.valueOf(item.entityType).schemaVersion,
                item.entitySchemaVersion
            )
        }
        assertEquals(BackupContract.SCHEMA_VERSION, capture().backupSchemaVersion)
    }

    @Test
    fun `so sessoes concluidas entram no backup`() = runTest {
        val sessions = capture().items.filter { it.entityType == "WORKOUT_SESSION" }

        // O banco tem uma concluída e uma em andamento; só a concluída sobe. Sincronizar execução
        // viva faria dois aparelhos disputarem o mesmo cursor de treino.
        assertEquals(1, sessions.size)
        assertEquals(fixture.completedSessionSyncId, sessions.single().syncId)
        assertEquals(
            "COMPLETED",
            sessions.single().payload.jsonObject["status"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `a identidade de cada item e portatil`() = runTest {
        capture().items.forEach { item ->
            when (BackupEntityType.valueOf(item.entityType)) {
                BackupEntityType.EXERCISE_OVERRIDE -> assertTrue(
                    "override usa a identidade do exercício alvo: ${item.syncId}",
                    item.syncId.startsWith("canonical:") || item.syncId.startsWith("custom:")
                )
                BackupEntityType.WEEKLY_GOAL -> assertTrue(item.syncId.startsWith("week:"))
                BackupEntityType.USER_PREFERENCES -> assertEquals("preferences", item.syncId)
                else -> assertTrue(
                    "raiz de agregado precisa de UUID: ${item.syncId}",
                    UUID_PATTERN.matches(item.syncId)
                )
            }
        }
    }

    // ------------------------------------------------------------------ o que não entra

    @Test
    fun `nenhum identificador local do Room atravessa o payload`() = runTest {
        val body = attemptPayload()

        // `id` de linha, e as chaves estrangeiras que só significam algo neste banco.
        listOf(
            "\"templateId\"",
            "\"programId\"",
            "\"exerciseId\"",
            "\"sessionId\"",
            "\"exerciseSessionId\"",
            "\"localId\"",
            "\"rowid\""
        ).forEach { field ->
            assertFalse("localId no payload: $field", body.contains(field))
        }
    }

    @Test
    fun `dado derivado nao vira autoridade remota`() = runTest {
        val body = attemptPayload()

        // XP, conquistas, missões e recordes são reconstruíveis a partir do histórico pelas
        // regras que já existem no app. Sincronizar o resultado criaria uma segunda opinião sobre
        // XP — e o restore da T16.5 recalcula em vez de herdar um número que ninguém audita.
        listOf(
            "personal_records",
            "prType",
            "gamification",
            "xpTransaction",
            "achievement",
            "streak",
            "\"level\""
        ).forEach { forbidden ->
            assertFalse("dado derivado no backup: $forbidden", body.contains(forbidden))
        }
        // E o PR que existe no banco continua lá, intocado — o backup não o apaga nem o recalcula.
        assertNotNull(database.workoutDao().getHighestPR(fixture.canonicalExerciseId, com.example.data.local.PRType.MAX_WEIGHT.name))
    }

    @Test
    fun `segredo, token e credencial nao entram no backup`() = runTest {
        SettingsManager(context).setExerciseDbV2ApiKey(SEGREDO)

        val body = attemptPayload()

        listOf(SEGREDO, "Authorization", "Bearer", "idToken", "apiKey", "GEMINI").forEach { secret ->
            assertFalse("segredo no backup: $secret", body.contains(secret))
        }
    }

    @Test
    fun `a Outbox nao e dado do usuario e nao entra no backup`() = runTest {
        val body = attemptPayload()

        listOf("clientMutationId", "sync_outbox", "outbox", "attemptCount").forEach { internal ->
            assertFalse("mecanismo interno no backup: $internal", body.contains(internal))
        }
    }

    @Test
    fun `mídia local nao e serializada como referencia portavel`() = runTest {
        val body = attemptPayload()

        // A customização do exercício tem foto — um `content://` deste aparelho. Ele não pode
        // atravessar como se fosse uma referência que outro dispositivo consegue abrir, e também
        // não vira Base64 para contornar a ausência de object storage.
        listOf("content://", "file://", "/data/user/", "customPhotoUri", "photoUri").forEach { local ->
            assertFalse("caminho local no backup: $local", body.contains(local))
        }
        // A customização em si entra, com o que é portátil dela.
        val override = capture().items.single { it.entityType == "EXERCISE_OVERRIDE" }
        assertEquals(
            "Supino do canto",
            override.payload.jsonObject["displayName"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `preferencia de aparelho e estado de execucao nao entram no backup`() = runTest {
        val preferences = capture().items.single { it.entityType == "USER_PREFERENCES" }

        assertEquals(
            setOf(
                "weeklyGoal",
                "useKg",
                "defaultRestSeconds",
                "defaultExerciseRestSeconds",
                "rirRpeEnabled",
                "autoRestTimerOnSet"
            ),
            preferences.payload.jsonObject.keys
        )

        val body = attemptPayload()
        listOf(
            "darkTheme", "keepScreenOn", "hapticEnabled", "soundEnabled",
            "restTimerDeadline", "deviceId\":\"" // `deviceId` só existe no envelope, não em item
        ).forEach { local ->
            if (local != "deviceId\":\"") {
                assertFalse("preferência de aparelho no backup: $local", body.contains(local))
            }
        }
    }

    // ---------------------------------------------------------------- backup só observa

    @Test
    fun `o backup nao altera o historico concluido`() = runTest {
        val before = historySnapshot()

        repository().backupNow(currentUid = uid, confirmedAdoption = true)

        assertEquals("histórico concluído é imutável", before, historySnapshot())
    }

    @Test
    fun `o backup nao altera templates`() = runTest {
        val dao = database.workoutDao()
        val before = dao.getAllTemplatesSync().map { it.copy() } to
            dao.getTemplateExercisesWithDetails(fixture.templateId).map { it.templateExercise.copy() }

        repository().backupNow(currentUid = uid, confirmedAdoption = true)

        val after = dao.getAllTemplatesSync().map { it.copy() } to
            dao.getTemplateExercisesWithDetails(fixture.templateId).map { it.templateExercise.copy() }
        assertEquals("a serialização é read-only", before, after)
    }

    @Test
    fun `o backup nao premia gamificacao nem recalcula recorde`() = runTest {
        val dao = database.workoutDao()
        val prType = com.example.data.local.PRType.MAX_WEIGHT.name
        val prBefore = dao.getHighestPR(fixture.canonicalExerciseId, prType)

        repository().backupNow(currentUid = uid, confirmedAdoption = true)

        // O backup observa a autoridade atual. Ele não recalcula PR, não publica evento e não
        // aproveita a serialização para "corrigir" nada.
        assertEquals(prBefore, dao.getHighestPR(fixture.canonicalExerciseId, prType))
        assertEquals(0, database.gamificationEventDao().count())
    }

    // ---------------------------------------------------------------------------- helpers

    private suspend fun capture(): BackupSnapshotDto {
        repository().backupNow(currentUid = uid, confirmedAdoption = true)
        return json.decodeFromString(BackupSnapshotDto.serializer(), api.uploads.last())
    }

    private suspend fun attemptPayload(): String {
        repository().backupNow(currentUid = uid, confirmedAdoption = true)
        return api.uploads.last()
    }

    /** O histórico concluído inteiro como texto: séries, cargas, repetições, ordem e notas. */
    private suspend fun historySnapshot(): String {
        val dao = database.workoutDao()
        val lines = mutableListOf<String>()
        for (summary in dao.getAllCompletedSessionsWithDetails()) {
            val details = dao.getSessionWithDetails(summary.session.id)!!
            val sets = details.sortedExercises
                .flatMap { it.sets }
                .sortedBy { it.setNumber }
                .joinToString(",") { "${it.setNumber}:${it.weight}:${it.repetitions}:${it.completed}" }
            val names = details.sortedExercises.joinToString(">") { it.exerciseSession.exerciseNameSnapshot }
            lines += "${summary.session.syncId}/${summary.session.status}/${summary.session.notes}/$names/$sets"
        }
        return lines.joinToString("|")
    }

    private fun repository() = BackupRepository(
        bindingDao = database.cloudDataBindingDao(),
        attemptDao = database.backupAttemptDao(),
        outboxDao = database.syncOutboxDao(),
        snapshotBuilder = BackupSnapshotBuilder(
            workoutDao = database.workoutDao(),
            bodyMeasurementDao = database.bodyMeasurementDao(),
            weeklyGoalDao = database.weeklyGoalDao(),
            aggregates = SyncAggregateSnapshotBuilder(
                database.workoutDao(),
                database.bodyMeasurementDao()
            )
        ),
        api = api,
        settingsManager = SettingsManager(context),
        deviceIdProvider = DeviceIdProvider(SettingsManager(context)),
        transactions = RoomTransactionRunner(database),
        source = BackupSourceDto(appVersionName = "teste", appVersionCode = 1, databaseVersion = 32),
        clock = { 1_700_000_000_000L }
    )

    private data class Fixture(
        val templateId: Long,
        val canonicalExerciseId: Long,
        val completedSessionSyncId: String
    )

    /** Um banco com um pouco de cada grupo da matriz — inclusive o que **não** deve subir. */
    private suspend fun seed(): Fixture {
        val dao = database.workoutDao()

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa Hipertrofia"))
        val templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A")
        )

        val canonicalId = dao.insertExercise(
            ExerciseEntity(name = "Supino Reto", canonicalId = "supino-reto-barra")
        )
        val custom = ExerciseEntity(
            name = "Rosca do canto",
            isUserCreated = true,
            syncId = SyncIds.random(),
            // Foto personalizada: local, e não pode atravessar.
            customPhotoUri = "content://media/external/images/1"
        )
        val customId = dao.insertExercise(custom)

        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = canonicalId, sortOrder = 0)
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = customId, sortOrder = 1)
        )

        // Customização com foto: o que é portátil sobe, o `content://` não.
        dao.insertOrUpdateOverride(
            ExerciseUserOverrideEntity(
                exerciseId = canonicalId,
                displayName = "Supino do canto",
                notes = "Pegada média",
                customPhotoUri = "content://media/external/images/2",
                defaultRestSeconds = 75
            )
        )

        val completed = WorkoutSessionEntity(
            templateId = templateId,
            startedAt = 1_000L,
            finishedAt = 2_000L,
            status = SessionStatus.COMPLETED.name,
            templateNameSnapshot = "Treino A"
        )
        val completedId = dao.insertSession(completed)
        val exerciseSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = completedId,
                plannedExerciseId = canonicalId,
                actualExerciseId = canonicalId,
                exerciseNameSnapshot = "Supino Reto",
                sortOrder = 0,
                plannedOrder = 0,
                executionOrder = 0
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(exerciseSessionId = exerciseSessionId, setNumber = 1, weight = 60f, repetitions = 10, completed = true),
                SetLogEntity(exerciseSessionId = exerciseSessionId, setNumber = 2, weight = 62.5f, repetitions = 8, completed = true)
            )
        )

        // Execução viva: não entra no backup.
        dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateId,
                startedAt = 3_000L,
                status = SessionStatus.IN_PROGRESS.name
            )
        )

        dao.insertCheckIn(CheckInEntity(checkInTime = 500L, checkOutTime = 900L, gymName = "Academia"))
        database.bodyMeasurementDao().insertMeasurement(
            BodyMeasurementEntity(date = 100L, weightKg = 79.4f, waistCm = 82f)
        )
        database.weeklyGoalDao().insertGoal(
            WeeklyGoalHistoryEntity(effectiveFromWeekStartEpochDay = 20_700L, goal = 4)
        )

        // Derivado: existe no banco e não pode entrar no backup.
        dao.insertPersonalRecord(
            PersonalRecordEntity(
                exerciseId = canonicalId,
                prType = com.example.data.local.PRType.MAX_WEIGHT,
                value = 62.5f,
                date = 2_000L
            )
        )

        return Fixture(
            templateId = templateId,
            canonicalExerciseId = canonicalId,
            completedSessionSyncId = completed.syncId
        )
    }

    private companion object {
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Valor de teste, deliberadamente fora do formato de uma chave real. */
        const val SEGREDO = "chave-de-teste-da-exercisedb-nao-e-uma-credencial"
    }
}
