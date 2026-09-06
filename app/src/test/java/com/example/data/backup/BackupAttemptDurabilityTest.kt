package com.example.data.backup

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.WorkoutProgramEntity
import com.example.data.repository.WorkoutRepository
import com.example.data.sync.DeviceIdProvider
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncAggregateSnapshotBuilder
import com.example.data.sync.SyncMutationCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A tentativa é durável, imutável e reenviável — e a Outbox só é liberada depois do servidor.
 *
 * Estes são os invariantes que separam "backup que funciona no caminho feliz" de "backup em que
 * dá para confiar":
 *
 * ```text
 * snapshot criado → app morre        → a tentativa continua lá, com os mesmos bytes
 * upload falha                       → nada local é apagado, e o retry usa o mesmo id
 * servidor grava, resposta se perde  → retry → o servidor devolve o mesmo backup
 * usuário edita durante o upload     → a alteração posterior ao corte permanece pendente
 * ```
 *
 * O banco é **em arquivo** e é fechado e reaberto de verdade: uma tentativa que só existisse em
 * memória provaria o contrário do que precisamos.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class BackupAttemptDurabilityTest {

    private val dbName = "backup-attempt-test-db"
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val uid = "uid-da-conta"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()

    // -------------------------------------------------------------------- durabilidade

    @Test
    fun `a tentativa sobrevive a fechar e reabrir o banco`() = runBlocking {
        context.deleteDatabase(dbName)
        val api = FakeBackupApi().apply { nextResult = BackupUploadResult.Network }

        val first = open()
        repositoryFor(first, api).backupNow(currentUid = uid, confirmedAdoption = true)
        val created = first.backupAttemptDao().oldestPendingFor(uid)!!
        assertTrue(created.payload.isNotBlank())
        first.close()

        val second = open()
        val recovered = second.backupAttemptDao().oldestPendingFor(uid)!!

        // Mesma identidade, mesmos bytes, mesmo corte. É isto que permite reenviar com segurança
        // depois de um process death.
        assertEquals(created.clientBackupId, recovered.clientBackupId)
        assertEquals(created.payload, recovered.payload)
        assertEquals(created.payloadHash, recovered.payloadHash)
        assertEquals(created.coveredOutboxSequence, recovered.coveredOutboxSequence)
        assertEquals(BackupAttemptStatus.PENDING.name, recovered.status)
        second.close()
    }

    @Test
    fun `o hash guardado descreve o payload guardado`() = runBlocking {
        context.deleteDatabase(dbName)
        val api = FakeBackupApi().apply { nextResult = BackupUploadResult.Network }
        val database = open()

        repositoryFor(database, api).backupNow(currentUid = uid, confirmedAdoption = true)
        val attempt = database.backupAttemptDao().oldestPendingFor(uid)!!

        assertEquals(BackupCanonicalJson.sha256(attempt.payload), attempt.payloadHash)
        // E o corpo enviado é exatamente o payload guardado — nada é remontado no caminho.
        assertEquals(attempt.payload, api.uploads.single())
        database.close()
    }

    // --------------------------------------------------------------------------- retry

    @Test
    fun `falha de rede mantem a tentativa e nao apaga nada local`() = runBlocking {
        context.deleteDatabase(dbName)
        val api = FakeBackupApi().apply { nextResult = BackupUploadResult.Network }
        val database = open()
        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))

        val result = repositoryFor(database, api).backupNow(uid, confirmedAdoption = true)
        // O vínculo existe desde a captura, então esta alteração já entra na Outbox da conta.
        workoutRepositoryFor(database).addTemplate(programId, "Treino", "T", 0)
        val outboxBefore = database.syncOutboxDao().pendingFor(uid).size

        assertEquals(BackupOperation.Network, result)
        assertNotNull("a tentativa continua recuperável", database.backupAttemptDao().oldestPendingFor(uid))
        assertEquals(uid, database.cloudDataBindingDao().get()?.ownerUid)
        assertTrue("a Outbox não pode ser limpa por uma falha", outboxBefore > 0)
        assertEquals(outboxBefore, database.syncOutboxDao().pendingFor(uid).size)
        database.close()
    }

    @Test
    fun `o retry usa o mesmo clientBackupId e os mesmos bytes`() = runBlocking {
        context.deleteDatabase(dbName)
        val api = FakeBackupApi().apply { nextResult = BackupUploadResult.Network }
        val database = open()

        repositoryFor(database, api).backupNow(uid, confirmedAdoption = true)
        val attempt = database.backupAttemptDao().oldestPendingFor(uid)!!

        api.nextResult = FakeBackupApi.success()
        repositoryFor(database, api).backupNow(uid, confirmedAdoption = false)

        assertEquals(2, api.callCount)
        assertEquals("o retry precisa mandar os mesmos bytes", api.uploads[0], api.uploads[1])
        // Uma tentativa lógica, não duas: o servidor reconhece o reenvio por `clientBackupId`.
        assertEquals(1, database.backupAttemptDao().count())
        assertEquals(attempt.clientBackupId, database.backupAttemptDao().all().single().clientBackupId)
        database.close()
    }

    @Test
    fun `resposta perdida e process death terminam em um backup so`() = runBlocking {
        context.deleteDatabase(dbName)
        // O servidor **gravou**, mas o app não recebeu a resposta.
        val api = FakeBackupApi().apply { nextResult = BackupUploadResult.Network }

        val first = open()
        repositoryFor(first, api).backupNow(uid, confirmedAdoption = true)
        val pending = first.backupAttemptDao().oldestPendingFor(uid)!!
        first.close()

        // O app reinicia e retoma a tentativa persistida.
        val second = open()
        api.nextResult = FakeBackupApi.success(backupId = "backup-que-ja-existia", createdAt = 1_800L)
        val result = repositoryFor(second, api).backupNow(uid, confirmedAdoption = false)

        assertTrue(result is BackupOperation.Success)
        assertEquals("o mesmo snapshot foi reenviado", api.uploads[0], api.uploads[1])
        val attempts = second.backupAttemptDao().all()
        assertEquals("nenhum backup lógico duplicado", 1, attempts.size)
        assertEquals(pending.clientBackupId, attempts.single().clientBackupId)
        assertEquals(BackupAttemptStatus.SUCCEEDED.name, attempts.single().status)
        assertEquals("backup-que-ja-existia", attempts.single().serverBackupId)
        second.close()
    }

    // -------------------------------------------------------------- corte e baseline

    @Test
    fun `a Outbox coberta so e liberada depois da confirmacao do servidor`() = runBlocking {
        context.deleteDatabase(dbName)
        val api = FakeBackupApi().apply { nextResult = BackupUploadResult.Unavailable }
        val database = open()
        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))
        val workouts = workoutRepositoryFor(database)

        // Adoção, e uma alteração **antes** de qualquer snapshot ser capturado.
        repositoryFor(database, api).backupNow(uid, confirmedAdoption = true)
        workouts.addTemplate(programId, "Treino A", "A", 0)
        assertEquals(1, database.syncOutboxDao().pendingFor(uid).size)

        // Retomar a mesma tentativa e falhar de novo não pode limpar nada.
        repositoryFor(database, api).backupNow(uid, confirmedAdoption = false)
        assertEquals("falha não pode liberar a fila", 1, database.syncOutboxDao().pendingFor(uid).size)

        // Sucesso — mas do snapshot **antigo**, que foi capturado antes do treino existir. A
        // entrada é posterior ao corte e continua pendente: o backup não pode alegar cobrir uma
        // alteração que ele não contém.
        api.nextResult = FakeBackupApi.success()
        repositoryFor(database, api).backupNow(uid, confirmedAdoption = false)
        assertEquals(
            "o snapshot antigo não cobre alteração posterior ao corte",
            1,
            database.syncOutboxDao().pendingFor(uid).size
        )

        // Um backup novo captura o estado atual, e aí sim a entrada é coberta.
        repositoryFor(database, api).backupNow(uid, confirmedAdoption = false)
        assertEquals(0, database.syncOutboxDao().pendingFor(uid).size)
        database.close()
    }

    @Test
    fun `alteracao durante o upload permanece pendente depois do sucesso`() = runBlocking {
        context.deleteDatabase(dbName)
        val database = open()
        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))
        val workouts = workoutRepositoryFor(database)

        // Um estado já vinculado, com uma alteração anterior ao snapshot na fila.
        val api = FakeBackupApi().apply { nextResult = FakeBackupApi.success() }
        repositoryFor(database, api).backupNow(uid, confirmedAdoption = true)
        workouts.addTemplate(programId, "Treino antes", "A", 0)
        assertEquals(1, database.syncOutboxDao().pendingFor(uid).size)

        val arrived = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        api.arrived = arrived
        api.gate = gate

        val upload = launch(Dispatchers.Default) {
            repositoryFor(database, api).backupNow(uid, confirmedAdoption = false)
        }

        // O snapshot já foi capturado e commitado quando a requisição entra.
        arrived.await()
        val covered = database.backupAttemptDao().oldestPendingFor(uid)!!.coveredOutboxSequence

        // O usuário edita um treino **enquanto** o backup sobe.
        workouts.addTemplate(programId, "Treino durante o upload", "D", 1)
        val duringUpload = database.syncOutboxDao().pendingFor(uid).filter { it.id > covered }
        assertEquals(1, duringUpload.size)

        gate.complete(Unit)
        upload.join()

        // O sucesso libera o que o snapshot cobriu — e **só** isso.
        val remaining = database.syncOutboxDao().pendingFor(uid)
        assertEquals("a alteração feita durante o upload não pode se perder", 1, remaining.size)
        assertEquals(duringUpload.single().clientMutationId, remaining.single().clientMutationId)
        assertTrue(remaining.single().id > covered)
        database.close()
    }

    @Test
    fun `dez toques produzem um backup, nao dez`() = runBlocking {
        context.deleteDatabase(dbName)
        val database = open()
        val api = FakeBackupApi().apply { nextResult = FakeBackupApi.success() }
        val repository = repositoryFor(database, api)

        val arrived = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        api.arrived = arrived
        api.gate = gate

        val first = launch(Dispatchers.Default) { repository.backupNow(uid, confirmedAdoption = true) }
        arrived.await()

        // Nove toques enquanto o primeiro está em voo: o mutex do repositório os enfileira, e a
        // tentativa pendente faz cada um retomar a **mesma** cópia em vez de criar outra.
        val others = (1..9).map {
            launch(Dispatchers.Default) { repository.backupNow(uid, confirmedAdoption = true) }
        }

        // Cada um dos nove é recusado de imediato: não vira operação nova nem fica na fila
        // esperando a vez para criar o próprio snapshot depois.
        others.forEach { it.join() }
        assertEquals("só a primeira requisição saiu", 1, api.callCount)

        gate.complete(Unit)
        first.join()

        assertEquals("um backup lógico, não dez", 1, database.backupAttemptDao().count())
        assertEquals("um upload, não dez", 1, api.callCount)
        assertEquals(uid, database.cloudDataBindingDao().get()?.ownerUid)
        database.close()
    }

    @Test
    fun `o vinculo e a tentativa nascem juntos ou nao nascem`() = runBlocking {
        context.deleteDatabase(dbName)
        val database = open()
        val api = FakeBackupApi().apply { nextResult = BackupUploadResult.Network }

        repositoryFor(database, api).backupNow(uid, confirmedAdoption = true)

        // A adoção, a captura do snapshot, o corte da Outbox e a tentativa acontecem na mesma
        // transação: não existe estado com vínculo e sem tentativa nem o contrário.
        val binding = database.cloudDataBindingDao().get()
        val attempt = database.backupAttemptDao().oldestPendingFor(uid)
        assertNotNull(binding)
        assertNotNull(attempt)
        assertEquals(binding!!.ownerUid, attempt!!.ownerUid)
        assertEquals(binding.deviceId, attempt.deviceId)
        assertNull("o vínculo só vira ENABLED com confirmação do servidor", binding.lastSuccessfulBackupId)
        database.close()
    }

    // --------------------------------------------------------------------------- helpers

    private fun workoutRepositoryFor(database: AppDatabase) = WorkoutRepository(
        database.workoutDao(),
        syncMutations = SyncMutationCoordinator(
            transactions = RoomTransactionRunner(database),
            outboxDao = database.syncOutboxDao(),
            scopeProvider = CloudDataBindingScopeProvider(database.cloudDataBindingDao())
        )
    )

    private fun repositoryFor(database: AppDatabase, api: BackupApi) = BackupRepository(
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
}
