package com.example.data.restore

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.backup.BackupMetadataDto
import com.example.data.backup.CloudDataBindingDao
import com.example.data.backup.CloudDataBindingEntity
import com.example.data.local.AchievementUnlockEntity
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.GamificationEventEntity
import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.XpTransactionEntity
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncOperation
import com.example.data.sync.SyncOutboxEntryEntity
import com.example.data.sync.SyncOutboxStatus
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O que a substituição faz — e o que ela nunca faz (T16.5).
 *
 * ```text
 * BEGIN
 *   apaga o dataset pessoal
 *   insere o snapshot
 *   grava o vínculo
 *   zera a Outbox
 * COMMIT        ← ou nada disso aconteceu
 * ```
 *
 * Os três invariantes cobertos aqui são os que separam um restore de uma perda de dado:
 *
 * 1. **atomicidade** — falhar no meio devolve o aparelho ao estado anterior, inteiro;
 * 2. **a Outbox não vira replay** — restaurar 200 sessões não produz 200 mutações para reenviar;
 * 3. **nada é premiado de novo** — histórico restaurado não gera XP, conquista nem recorde.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class RestoreTransactionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var harness: RestoreHarness
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val uid = "uid-da-conta-a"

    @Before
    fun setUp() = runTest {
        database = RestoreHarness.database(context)
        harness = RestoreHarness(
            database = database,
            context = context,
            filesRoot = File(folder.newFolder("aparelho"), "restore")
        )
        RestoreDatasetFixture.seedCatalog(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // --------------------------------------------------------------------------- rollback

    @Test
    fun `falha no meio da transacao preserva o dataset original`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val fingerprint = harness.semanticFingerprint()
        val backup = harness.api.publish(minimalSnapshotBody())

        // Um aparelho com o mesmo banco, mas com uma falha injetada na última etapa da transação —
        // depois de apagar e inserir tudo.
        val failing = RestoreHarness(
            database = database,
            context = context,
            api = harness.api,
            filesRoot = File(folder.newFolder("com-falha"), "restore"),
            bindingDao = ExplodingBindingDao(database.cloudDataBindingDao())
        )

        val ready = prepare(failing, backup)
        val outcome = failing.repository.confirm(
            plan = ready.plan,
            restoreAttemptId = ready.restoreAttemptId,
            currentUid = uid,
            confirmed = true
        )

        assertTrue("$outcome", outcome is RestoreOutcome.Failed)
        assertEquals(RestoreError.RESTORE_APPLY_FAILED, (outcome as RestoreOutcome.Failed).error)
        assertEquals(
            "o rollback do Room devolve o dataset inteiro",
            fingerprint,
            harness.semanticFingerprint()
        )
        assertEquals(3, database.workoutDao().getCompletedSessionSyncIds().size)
    }

    @Test
    fun `falha na aplicacao nao deixa a tentativa em aberto`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val backup = harness.api.publish(minimalSnapshotBody())
        val failing = RestoreHarness(
            database = database,
            context = context,
            api = harness.api,
            filesRoot = File(folder.newFolder("com-falha-2"), "restore"),
            bindingDao = ExplodingBindingDao(database.cloudDataBindingDao())
        )
        val ready = prepare(failing, backup)

        failing.repository.confirm(ready.plan, ready.restoreAttemptId, uid, confirmed = true)

        assertEquals(
            "uma falha antes do commit não trava o aparelho",
            null,
            database.restoreAttemptDao().oldestUnfinished()
        )
    }

    // ---------------------------------------------------------------------------- Outbox

    @Test
    fun `restore nao gera mutacao de Outbox por item`() = runTest {
        val body = seedAndSnapshot()

        restore(harness.api.publish(body))

        assertEquals(
            "o snapshot restaurado já é o estado que o servidor tem: não há nada a reenviar",
            0,
            database.syncOutboxDao().pendingFor(uid).size
        )
    }

    @Test
    fun `a Outbox anterior so e limpa no commit do restore`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val body = harness.canonicalSnapshotOfCurrentState()
        seedPendingMutation()
        assertEquals(1, database.syncOutboxDao().pendingFor(uid).size)

        // Download que falha: a fila continua exatamente como estava.
        val corrupt = harness.api.publish(body, backupId = "corrompido", corruptBody = "$body ")
        harness.repository.prepare(corrupt, uid)
        assertEquals(
            "limpar a fila antes da confirmação seria perda de dado sem conserto",
            1,
            database.syncOutboxDao().pendingFor(uid).size
        )

        // Restore confirmado: aí sim a fila é substituída, junto com o dataset que ela descrevia.
        restore(harness.api.publish(body, backupId = "integro"))
        assertEquals(0, database.syncOutboxDao().pendingFor(uid).size)
    }

    @Test
    fun `o preview avisa quando ha alteracoes locais que serao descartadas`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        seedPendingMutation()
        val backup = harness.api.publish(harness.canonicalSnapshotOfCurrentState())

        val ready = prepare(harness, backup)

        val warning = ready.plan.warnings
            .filterIsInstance<RestoreWarning.PendingChangesDiscarded>()
            .firstOrNull()
        assertNotNull("o usuário precisa saber o que perde antes de confirmar", warning)
        assertEquals(1, warning!!.pendingMutations)
    }

    // ------------------------------------------------------------------------ gamificação

    @Test
    fun `restore nao dispara XP, conquista nem recorde`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val body = harness.canonicalSnapshotOfCurrentState()
        seedGamification()

        restore(harness.api.publish(body))

        assertEquals(
            "nenhum evento novo é publicado por inserir histórico",
            0,
            database.gamificationEventDao().count()
        )
        assertEquals(
            "e nenhum XP é creditado",
            emptyList<XpTransactionEntity>(),
            database.xpTransactionDao().getAllTransactions().first()
        )
        assertEquals(0, database.achievementDao().getUnlocks().size)
        assertEquals(0, database.workoutDao().getPersonalRecordsCountFlow().first())
    }

    @Test
    fun `o historico restaurado nao e recalculado`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val before = database.workoutDao()
            .getSessionWithDetailsBySyncId(RestoreDatasetFixture.SESSION_1_SYNC_ID)!!
        val body = harness.canonicalSnapshotOfCurrentState()

        restore(harness.api.publish(body))

        val after = database.workoutDao()
            .getSessionWithDetailsBySyncId(RestoreDatasetFixture.SESSION_1_SYNC_ID)!!
        assertEquals(before.session.startedAt, after.session.startedAt)
        assertEquals(before.session.finishedAt, after.session.finishedAt)
        assertEquals(
            "as séries voltam como estavam — carga, repetição e conclusão",
            before.sortedExercises.flatMap { it.sets }
                .sortedBy { it.setNumber }
                .map { Triple(it.weight, it.repetitions, it.completed) },
            after.sortedExercises.flatMap { it.sets }
                .sortedBy { it.setNumber }
                .map { Triple(it.weight, it.repetitions, it.completed) }
        )
    }

    // ------------------------------------------------------------- substituição explícita

    @Test
    fun `backup antigo sobre dataset mais novo remove as sessoes posteriores`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val oldBody = harness.canonicalSnapshotOfCurrentState()
        val backup = harness.api.publish(oldBody, createdAt = 1_695_000_000_000L)

        // Depois do backup, o usuário treinou mais duas vezes neste aparelho.
        listOf(1_696_000_000_000L, 1_697_000_000_000L).forEach { instant ->
            database.workoutDao().insertSession(
                WorkoutSessionEntity(
                    templateId = null,
                    startedAt = instant,
                    finishedAt = instant + 3_600_000L,
                    status = SessionStatus.COMPLETED.name,
                    templateNameSnapshot = "Treino posterior"
                )
            )
        }
        assertEquals(5, database.workoutDao().getCompletedSessionSyncIds().size)

        val ready = prepare(harness, backup)
        val warning = ready.plan.warnings
            .filterIsInstance<RestoreWarning.OlderThanLocalHistory>()
            .firstOrNull()
        assertNotNull("o rollback temporal precisa aparecer no preview", warning)
        assertEquals(2, warning!!.localSessionsAfterBackup)

        confirm(ready)

        assertEquals(
            "substituição é substituição: as sessões posteriores não são preservadas",
            3,
            database.workoutDao().getCompletedSessionSyncIds().size
        )
    }

    @Test
    fun `restore nao apaga o catalogo nem o conteudo do app`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val body = harness.canonicalSnapshotOfCurrentState()
        val catalogBefore = database.workoutDao().getCanonicalExercisesCount()

        restore(harness.api.publish(body))

        assertEquals(
            "o catálogo canônico é conteúdo do app, não dado pessoal",
            catalogBefore,
            database.workoutDao().getCanonicalExercisesCount()
        )
    }

    @Test
    fun `treino em andamento bloqueia o restore antes de qualquer download`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val backup = harness.api.publish(harness.canonicalSnapshotOfCurrentState())
        database.workoutDao().insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = 1_700_000_000_000L,
                status = SessionStatus.IN_PROGRESS.name
            )
        )

        val preparation = harness.repository.prepare(backup, uid)

        assertEquals(
            RestoreError.WORKOUT_IN_PROGRESS,
            (preparation as RestorePreparation.Failed).error
        )
        assertEquals(0, harness.api.downloadCount)
    }

    // ------------------------------------------------------------------------ preferências

    @Test
    fun `preferencias do atleta sao restauradas e as do aparelho ficam`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        harness.settingsManager.setWeeklyGoal(6)
        harness.settingsManager.setDefaultRestSeconds(120)
        val body = harness.canonicalSnapshotOfCurrentState()

        // O "outro aparelho" tem preferências diferentes — inclusive uma que não viaja.
        harness.settingsManager.setWeeklyGoal(2)
        harness.settingsManager.setDefaultRestSeconds(30)
        harness.settingsManager.setKeepScreenOn(false)

        restore(harness.api.publish(body))

        assertEquals(6, harness.settingsManager.weeklyGoalFlow.first())
        assertEquals(120, harness.settingsManager.defaultRestSecondsFlow.first())
        assertEquals(
            "preferência de aparelho descreve este celular, não o atleta: ela não é tocada",
            false,
            harness.settingsManager.keepScreenOnFlow.first()
        )
    }

    // ------------------------------------------------------------------------- concorrência

    @Test
    fun `um restore nao comeca enquanto outra operacao de nuvem esta em curso`() = runTest {
        val backup = harness.api.publish(minimalSnapshotBody())

        val result = harness.operationLock.tryRun {
            // Enquanto uma operação de nuvem está em curso, a outra é recusada — e não enfileirada.
            harness.repository.prepare(backup, uid)
        }

        assertEquals(
            RestoreError.RESTORE_IN_PROGRESS,
            ((result as RestorePreparation).let { it as RestorePreparation.Failed }).error
        )
    }

    @Test
    fun `um backup nao comeca durante um restore`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        // A **mesma** trava do restore, como em produção: os dois disputam o mesmo banco, e um
        // snapshot capturado no meio de uma substituição descreveria um estado que nunca existiu.
        val backupRepository = com.example.data.backup.BackupRepository(
            bindingDao = database.cloudDataBindingDao(),
            attemptDao = database.backupAttemptDao(),
            outboxDao = database.syncOutboxDao(),
            snapshotBuilder = harness.snapshotBuilder,
            api = com.example.data.backup.FakeBackupApi(),
            settingsManager = harness.settingsManager,
            deviceIdProvider = com.example.data.sync.DeviceIdProvider(harness.settingsManager),
            transactions = com.example.data.sync.RoomTransactionRunner(database),
            source = RestoreHarness.SOURCE,
            operationLock = harness.operationLock
        )

        val result = harness.operationLock.tryRun {
            backupRepository.backupNow(currentUid = uid, confirmedAdoption = true)
        }

        assertEquals(
            com.example.data.backup.BackupOperation.AlreadyRunning,
            result as com.example.data.backup.BackupOperation
        )
        assertEquals(
            "nenhuma tentativa de backup nasce durante um restore",
            0,
            database.backupAttemptDao().count()
        )
    }

    // --------------------------------------------- foto local do usuário (auditoria 2026-09-12)

    @Test
    fun `a foto local do exercicio sobrevive ao restore`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val dao = database.workoutDao()
        val supino = dao.getExerciseByCanonicalId(RestoreDatasetFixture.CANONICAL_SUPINO)!!
        val custom = dao.getExerciseBySyncId(RestoreDatasetFixture.CUSTOM_EXERCISE_SYNC_ID)!!

        // Duas formas de customização, e as duas precisam manter a foto:
        //  - uma com conteúdo portátil (apelido), que **entra** no snapshot;
        //  - uma só com foto, que o backup descarta por não ter nada portátil dentro.
        dao.insertOrUpdateOverride(
            ExerciseUserOverrideEntity(
                exerciseId = supino.id,
                displayName = "Supino do canto",
                customPhotoUri = "content://media/external/images/1",
                updatedAt = 1_700_000_000_000L
            )
        )
        dao.insertOrUpdateOverride(
            ExerciseUserOverrideEntity(
                exerciseId = custom.id,
                customPhotoUri = "content://media/external/images/2",
                updatedAt = 1_700_000_000_000L
            )
        )

        val backup = harness.api.publish(harness.canonicalSnapshotOfCurrentState())
        restore(backup)

        // `content://` é um endereço **deste** aparelho: ele nunca viajou no snapshot, e por isso o
        // restore precisa preservá-lo por fora dele em vez de gravar `null` por cima.
        val restoredSupino = dao.getExerciseByCanonicalId(RestoreDatasetFixture.CANONICAL_SUPINO)!!
        assertEquals(
            "content://media/external/images/1",
            dao.getOverrideForExercise(restoredSupino.id)?.customPhotoUri
        )
        assertEquals(
            "a customização restaurada não perde o apelido",
            "Supino do canto",
            dao.getOverrideForExercise(restoredSupino.id)?.displayName
        )

        // O exercício personalizado tem `localId` novo: a foto é reencontrada pela identidade
        // portátil (`syncId`), nunca pelo número da linha do aparelho de origem.
        val restoredCustom = dao.getExerciseBySyncId(RestoreDatasetFixture.CUSTOM_EXERCISE_SYNC_ID)!!
        assertEquals(
            "uma customização só com foto não entra no backup, e é justamente a mais comum",
            "content://media/external/images/2",
            dao.getOverrideForExercise(restoredCustom.id)?.customPhotoUri
        )
    }

    @Test
    fun `foto de exercicio que o backup nao contem nao vira customizacao orfa`() = runTest {
        RestoreDatasetFixture.seedPersonalData(database)
        val dao = database.workoutDao()
        val custom = dao.getExerciseBySyncId(RestoreDatasetFixture.CUSTOM_EXERCISE_SYNC_ID)!!
        dao.insertOrUpdateOverride(
            ExerciseUserOverrideEntity(
                exerciseId = custom.id,
                customPhotoUri = "content://media/external/images/9",
                updatedAt = 1_700_000_000_000L
            )
        )

        // Um backup vazio: o exercício personalizado deixa de existir aqui. Sem linha a que
        // anexá-la, a foto é descartada — e não pode virar customização apontando para nada.
        restore(harness.api.publish(minimalSnapshotBody()))

        assertNull(
            "o exercício pessoal não está no backup vazio",
            dao.getExerciseBySyncId(RestoreDatasetFixture.CUSTOM_EXERCISE_SYNC_ID)
        )

        // O que sobra é só a foto do exercício de **catálogo** — que continua existindo, porque o
        // restore não apaga catálogo. Nenhuma customização órfã foi criada.
        val supinoId = dao.getExerciseByCanonicalId(RestoreDatasetFixture.CANONICAL_SUPINO)!!.id
        assertEquals(listOf(supinoId), dao.getAllOverrides().map { it.exerciseId })
        assertEquals(
            "content://media/external/images/42",
            dao.getAllOverrides().single().customPhotoUri
        )
        assertNull(
            "um backup vazio não traz apelido nenhum: sobra só a foto local",
            dao.getAllOverrides().single().displayName
        )
    }

    // ------------------------------------------------------------------------- helpers

    private suspend fun seedAndSnapshot(): String {
        RestoreDatasetFixture.seedPersonalData(database)
        return harness.canonicalSnapshotOfCurrentState()
    }

    private suspend fun minimalSnapshotBody(): String {
        // Um snapshot de um dataset vazio: o restore precisa substituir por *menos*, que é o caso
        // mais destrutivo.
        val empty = RestoreHarness.database(context)
        return try {
            RestoreHarness(
                database = empty,
                context = context,
                filesRoot = File(folder.newFolder("vazio-${System.nanoTime()}"), "restore")
            ).canonicalSnapshotOfCurrentState()
        } finally {
            empty.close()
        }
    }

    private suspend fun seedPendingMutation() {
        database.syncOutboxDao().insert(
            SyncOutboxEntryEntity(
                clientMutationId = "mutacao-pendente",
                ownerUid = uid,
                entityType = SyncEntityType.WORKOUT_TEMPLATE.name,
                entitySyncId = RestoreDatasetFixture.TEMPLATE_A_SYNC_ID,
                operation = SyncOperation.UPSERT.name,
                status = SyncOutboxStatus.PENDING.name,
                createdAt = 1_700_000_000_000L
            )
        )
    }

    /** Estado derivado que o aparelho tinha antes do restore. */
    private suspend fun seedGamification() {
        database.gamificationEventDao().insert(
            GamificationEventEntity(
                id = "evento-1",
                type = "WORKOUT_COMPLETED",
                timestamp = 1_690_003_600_000L,
                source = "WORKOUT_ENGINE",
                dedupeKey = "workout_completed:1",
                metadataJson = "{}"
            )
        )
        database.xpTransactionDao().insertTransaction(
            XpTransactionEntity(
                id = "xp-1",
                eventId = "evento-1",
                amount = 100,
                reason = "Treino Concluído",
                createdAt = 1_690_003_600_000L
            )
        )
        database.achievementDao().insertIfAbsent(
            AchievementUnlockEntity(
                achievementId = "primeiro-treino",
                unlockedAt = 1_690_003_600_000L,
                triggerEventId = "evento-1",
                definitionVersion = 1
            )
        )
        val supinoId = database.workoutDao()
            .getExerciseByCanonicalId(RestoreDatasetFixture.CANONICAL_SUPINO)!!.id
        database.workoutDao().insertPersonalRecord(
            PersonalRecordEntity(
                exerciseId = supinoId,
                date = 1_690_003_600_000L,
                prType = PRType.MAX_WEIGHT,
                value = 62.5f
            )
        )
    }

    private suspend fun prepare(
        harness: RestoreHarness,
        backup: BackupMetadataDto
    ): RestorePreparation.Ready {
        val preparation = harness.repository.prepare(backup, uid)
        assertTrue("$preparation", preparation is RestorePreparation.Ready)
        return preparation as RestorePreparation.Ready
    }

    private suspend fun confirm(ready: RestorePreparation.Ready) {
        val outcome = harness.repository.confirm(
            plan = ready.plan,
            restoreAttemptId = ready.restoreAttemptId,
            currentUid = uid,
            confirmed = true
        )
        assertTrue("$outcome", outcome is RestoreOutcome.Success)
    }

    private suspend fun restore(backup: BackupMetadataDto) {
        confirm(prepare(harness, backup))
    }
}

/**
 * Um DAO de vínculo que falha **depois** de a transação já ter apagado e inserido tudo.
 *
 * É o ponto de falha mais tardio possível dentro da transação — e por isso o mais exigente para o
 * rollback: se o dataset volta inteiro daqui, ele volta de qualquer lugar antes daqui.
 */
private class ExplodingBindingDao(
    private val delegate: CloudDataBindingDao
) : CloudDataBindingDao {

    override suspend fun get(id: Int): CloudDataBindingEntity? = delegate.get(id)

    override fun observe(id: Int) = delegate.observe(id)

    override suspend fun insertIfAbsent(binding: CloudDataBindingEntity): Long =
        delegate.insertIfAbsent(binding)

    override suspend fun deleteBinding() = delegate.deleteBinding()

    override suspend fun markBackupSucceeded(
        ownerUid: String,
        state: String,
        backupId: String,
        backupAt: Long,
        id: Int
    ): Unit = throw IllegalStateException("falha simulada no fim da transação de restore")
}
