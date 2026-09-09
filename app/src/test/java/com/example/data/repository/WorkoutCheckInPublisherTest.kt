package com.example.data.repository

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutSessionEntity
import com.example.data.sync.SyncOutcome
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.StubWorkoutCheckInGateway
import com.example.domain.social.UploadedCheckInMedia
import com.example.domain.social.WorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckInOutcome
import com.example.domain.social.SocialCheckInAuthor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O coordenador de publicação de check-in (T17.8 §143–§147).
 *
 * O Room é **real** e em memória: a pergunta que estes testes fazem — "esta sessão está concluída
 * e é recente?" — é sobre a entidade de verdade, e um dublê de DAO responderia sobre a fixture em
 * vez de sobre o domínio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WorkoutCheckInPublisherTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: AppDatabase
    private lateinit var gateway: FakeCheckInGateway
    private lateinit var authGateway: FakeAuthGateway
    private lateinit var publisher: WorkoutCheckInPublisher

    private var now: Long = 1_800_000_000_000L
    private var syncOutcome: SyncOutcome = SyncOutcome.Success(
        pushed = 1,
        applied = 0,
        converged = 0,
        conflicts = 0,
        deferredDeletes = 0,
        pausedAt = null
    )
    private var syncCalls: Int = 0

    private class FakeCheckInGateway : StubWorkoutCheckInGateway() {

        /** As respostas do `POST`, na ordem. A última se repete. */
        var createResults: MutableList<WorkoutCheckInOutcome<WorkoutCheckIn>> = mutableListOf(
            WorkoutCheckInOutcome.Success(
                WorkoutCheckIn(
                    checkInId = "checkin-1",
                    author = SocialCheckInAuthor("social-a", "Alice"),
                    publishedAt = 1_800_000_000_000L,
                    isCurrentUser = true
                )
            )
        )
        val createdWith = mutableListOf<Pair<String, String>>()

        /** O que cada `POST` levou de conteúdo (T17.9): legenda e `mediaId`. */
        val createdContent = mutableListOf<Pair<String?, String?>>()
        var deleteResult: WorkoutCheckInOutcome<Unit> = WorkoutCheckInOutcome.Success(Unit)

        /** As respostas do upload de foto, na ordem. A última se repete. */
        var uploadResults: MutableList<WorkoutCheckInOutcome<UploadedCheckInMedia>> = mutableListOf()
        val uploadedWith = mutableListOf<Triple<String, String, Int>>()

        override suspend fun createCheckIn(
            sessionSyncId: String,
            clientRequestId: String,
            caption: String?,
            mediaId: String?
        ): WorkoutCheckInOutcome<WorkoutCheckIn> {
            createdWith += sessionSyncId to clientRequestId
            createdContent += caption to mediaId
            return if (createdWith.size <= createResults.size) {
                createResults[createdWith.size - 1]
            } else {
                createResults.last()
            }
        }

        override suspend fun uploadMedia(
            sessionSyncId: String,
            clientUploadId: String,
            bytes: ByteArray
        ): WorkoutCheckInOutcome<UploadedCheckInMedia> {
            uploadedWith += Triple(sessionSyncId, clientUploadId, bytes.size)
            return if (uploadedWith.size <= uploadResults.size) {
                uploadResults[uploadedWith.size - 1]
            } else {
                uploadResults.lastOrNull()
                    ?: WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)
            }
        }

        override suspend fun feed(limit: Int?): WorkoutCheckInOutcome<List<WorkoutCheckIn>> =
            WorkoutCheckInOutcome.Success(emptyList())

        override suspend fun deleteCheckIn(checkInId: String): WorkoutCheckInOutcome<Unit> =
            deleteResult
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = FakeCheckInGateway()
        authGateway = FakeAuthGateway(initialAccount = SparkAccount("uid-a", "Alice"))
        publisher = WorkoutCheckInPublisher(
            workoutDao = database.workoutDao(),
            gateway = gateway,
            authGateway = authGateway,
            syncCycle = {
                syncCalls++
                syncOutcome
            },
            clock = { now }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertSession(
        status: SessionStatus = SessionStatus.COMPLETED,
        finishedAt: Long? = null,
        startedAt: Long = now - 60 * 60 * 1000L
    ): Long = database.workoutDao().insertSession(
        WorkoutSessionEntity(
            templateId = null,
            startedAt = startedAt,
            finishedAt = finishedAt ?: (now - 30 * 60 * 1000L),
            status = status.name,
            templateNameSnapshot = "Treino A"
        )
    )

    // ------------------------------------------------------------------ §143 elegibilidade

    @Test
    fun `sessao concluida e recente e elegivel`() = runTest {
        val sessionId = insertSession()
        assertEquals(CheckInEligibility.Eligible, publisher.eligibility(sessionId))
    }

    @Test
    fun `sessao nao concluida nao e elegivel`() = runTest {
        for (status in listOf(
            SessionStatus.PLANNED,
            SessionStatus.IN_PROGRESS,
            SessionStatus.PAUSED,
            SessionStatus.CANCELLED
        )) {
            val sessionId = insertSession(status = status)
            assertEquals(
                "status $status",
                CheckInEligibility.NotCompleted,
                publisher.eligibility(sessionId)
            )
        }
    }

    @Test
    fun `treino antigo demais nao e elegivel`() = runTest {
        val finished = now - CHECKIN_LOCAL_WINDOW_MS - 60_000L
        val sessionId = insertSession(finishedAt = finished, startedAt = finished - 60_000L)
        assertEquals(CheckInEligibility.OutsideWindow, publisher.eligibility(sessionId))
    }

    @Test
    fun `sem conta conectada nao ha CTA`() = runTest {
        val sessionId = insertSession()
        authGateway.signOut()
        assertEquals(CheckInEligibility.Unavailable, publisher.eligibility(sessionId))
    }

    @Test
    fun `sem backend configurado nao ha CTA`() = runTest {
        val sessionId = insertSession()
        gateway.isConfigured = false
        assertEquals(CheckInEligibility.Unavailable, publisher.eligibility(sessionId))
    }

    @Test
    fun `sessao inexistente nao e elegivel`() = runTest {
        assertEquals(CheckInEligibility.SessionMissing, publisher.eligibility(4242L))
    }

    @Test
    fun `quando finishedAt e nulo a janela usa startedAt`() = runTest {
        // O mesmo instante canônico do servidor, e nunca um significado novo de fim de treino.
        val recent = database.workoutDao().insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = now - 47 * 60 * 60 * 1000L,
                finishedAt = null,
                status = SessionStatus.COMPLETED.name
            )
        )
        assertEquals(CheckInEligibility.Eligible, publisher.eligibility(recent))

        val old = database.workoutDao().insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = now - 49 * 60 * 60 * 1000L,
                finishedAt = null,
                status = SessionStatus.COMPLETED.name
            )
        )
        assertEquals(CheckInEligibility.OutsideWindow, publisher.eligibility(old))
    }

    // ------------------------------------------------------------------ publicação

    @Test
    fun `publica enviando o syncId da sessao e o clientRequestId recebido`() = runTest {
        val sessionId = insertSession()
        val syncId = database.workoutDao().getSessionById(sessionId)!!.syncId

        val result = publisher.publish(sessionId, "req-1")

        assertTrue(result is CheckInPublishResult.Published)
        assertEquals(listOf(syncId to "req-1"), gateway.createdWith)
        assertEquals(0, syncCalls)
    }

    @Test
    fun `sessao nao elegivel nao chega a fazer requisicao`() = runTest {
        val sessionId = insertSession(status = SessionStatus.IN_PROGRESS)

        val result = publisher.publish(sessionId, "req-1")

        assertEquals(
            CheckInPublishResult.NotEligible(CheckInEligibility.NotCompleted),
            result
        )
        assertTrue(gateway.createdWith.isEmpty())
    }

    // ------------------------------------------------------------------ §145 caminho do sync

    @Test
    fun `sessao ainda nao sincronizada dispara um ciclo do sync e republica com o mesmo id`() =
        runTest {
            val sessionId = insertSession()
            val syncId = database.workoutDao().getSessionById(sessionId)!!.syncId
            gateway.createResults = mutableListOf(
                WorkoutCheckInOutcome.Failure(WorkoutCheckInError.SESSION_NOT_FOUND),
                WorkoutCheckInOutcome.Success(
                    WorkoutCheckIn("checkin-1", SocialCheckInAuthor("s", "Alice"), now, isCurrentUser = true)
                )
            )

            val result = publisher.publish(sessionId, "req-1")

            assertTrue(result is CheckInPublishResult.Published)
            assertEquals(1, syncCalls)
            // O **mesmo** `clientRequestId` nas duas tentativas: é isso que impede a segunda de
            // virar uma segunda publicação.
            assertEquals(listOf(syncId to "req-1", syncId to "req-1"), gateway.createdWith)
        }

    // ------------------------------------------------------------------ §146 falha do sync

    @Test
    fun `sync que falha nao publica e nao altera a sessao`() = runTest {
        val sessionId = insertSession()
        val before = database.workoutDao().getSessionById(sessionId)!!
        gateway.createResults = mutableListOf(
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.SESSION_NOT_FOUND)
        )
        syncOutcome = SyncOutcome.Offline

        val result = publisher.publish(sessionId, "req-1")

        assertEquals(CheckInPublishResult.SessionNotSynced, result)
        // Uma tentativa só: sem o ciclo ter convergido, não há por que perguntar de novo.
        assertEquals(1, gateway.createdWith.size)
        // O treino continua exatamente como estava: concluído, salvo, com o mesmo `syncId`.
        assertEquals(before, database.workoutDao().getSessionById(sessionId))
    }

    @Test
    fun `sync que roda mas nao traz a sessao nao insiste`() = runTest {
        val sessionId = insertSession()
        gateway.createResults = mutableListOf(
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.SESSION_NOT_FOUND)
        )

        val result = publisher.publish(sessionId, "req-1")

        assertEquals(CheckInPublishResult.SessionNotSynced, result)
        assertEquals(2, gateway.createdWith.size)
        assertEquals(1, syncCalls)
    }

    // ------------------------------------------------------------------ §147 nuvem não adotada

    @Test
    fun `sem vinculo de nuvem o app nao cria nenhum e explica`() = runTest {
        val sessionId = insertSession()
        gateway.createResults = mutableListOf(
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.SESSION_NOT_FOUND)
        )
        syncOutcome = SyncOutcome.NotEnabled

        val result = publisher.publish(sessionId, "req-1")

        assertEquals(CheckInPublishResult.CloudNotAdopted, result)
        // Nenhuma segunda tentativa, e — o ponto de §21 — nenhuma adoção implícita: o vínculo de
        // nuvem continua inexistente depois da tentativa de publicar.
        assertEquals(1, gateway.createdWith.size)
        assertNull(database.cloudDataBindingDao().get())
    }

    @Test
    fun `dataset de outra conta tambem nao vira adocao`() = runTest {
        val sessionId = insertSession()
        gateway.createResults = mutableListOf(
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.SESSION_NOT_FOUND)
        )
        syncOutcome = SyncOutcome.AccountMismatch("uid-outro")

        assertEquals(CheckInPublishResult.CloudNotAdopted, publisher.publish(sessionId, "req-1"))
    }

    // ------------------------------------------------------------------ §110 troca de conta

    @Test
    fun `resposta que chega depois da troca de conta e descartada`() = runTest {
        val sessionId = insertSession()
        gateway.createResults = mutableListOf(
            WorkoutCheckInOutcome.Success(
                WorkoutCheckIn("checkin-de-a", SocialCheckInAuthor("s", "Alice"), now, isCurrentUser = true)
            )
        )

        // A conta troca **antes** de o resultado ser interpretado.
        authGateway.nextOutcome = AuthOutcome.Success(SparkAccount("uid-b", "Bob"))
        val gatewayComTroca = object : WorkoutCheckInGateway by gateway {
            override suspend fun createCheckIn(
                sessionSyncId: String,
                clientRequestId: String,
                caption: String?,
                mediaId: String?
            ): WorkoutCheckInOutcome<WorkoutCheckIn> {
                authGateway.signIn(context)
                return gateway.createCheckIn(sessionSyncId, clientRequestId, caption, mediaId)
            }
        }
        val publisherComTroca = WorkoutCheckInPublisher(
            workoutDao = database.workoutDao(),
            gateway = gatewayComTroca,
            authGateway = authGateway,
            syncCycle = { syncOutcome },
            clock = { now }
        )

        val result = publisherComTroca.publish(sessionId, "req-1")

        assertEquals(CheckInPublishResult.AccountChanged, result)
    }

    // ------------------------------------------------------------------ erros mapeados

    @Test
    fun `janela expirada no servidor vira falha, e o treino continua salvo`() = runTest {
        val sessionId = insertSession()
        val before = database.workoutDao().getSessionById(sessionId)!!
        gateway.createResults = mutableListOf(
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.CHECKIN_WINDOW_EXPIRED)
        )

        val result = publisher.publish(sessionId, "req-1")

        assertEquals(
            CheckInPublishResult.Failed(WorkoutCheckInError.CHECKIN_WINDOW_EXPIRED),
            result
        )
        assertEquals(before, database.workoutDao().getSessionById(sessionId))
    }

    @Test
    fun `check-in ja existente para o treino e reportado como tal`() = runTest {
        val sessionId = insertSession()
        gateway.createResults = mutableListOf(
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.CHECKIN_ALREADY_EXISTS)
        )

        assertEquals(CheckInPublishResult.AlreadyShared, publisher.publish(sessionId, "req-1"))
    }
}
