package com.example.presentation.friends

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutSessionEntity
import com.example.data.media.CheckInPhotoSource
import com.example.data.media.SocialPhotoOptimizer
import com.example.data.repository.WorkoutCheckInPublisher
import com.example.data.social.WorkoutCheckInContract
import com.example.data.sync.SyncOutcome
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.CheckInComment
import com.example.domain.social.CheckInMedia
import com.example.domain.social.ReactionType
import com.example.domain.social.SocialCheckInAuthor
import com.example.domain.social.SocialDiscoverability
import com.example.domain.social.SocialError
import com.example.domain.social.SocialGateway
import com.example.domain.social.SocialOutcome
import com.example.domain.social.SocialPrivacySettings
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import com.example.domain.social.SocialReportTarget
import com.example.domain.social.StubWorkoutCheckInGateway
import com.example.domain.social.UploadedCheckInMedia
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.WorkoutCheckInOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Legenda, foto, reações e comentários no aparelho (T17.9 §180–§184).
 *
 * O que estes testes protegem é o **comportamento que o servidor não pode garantir**: que a decisão
 * de §43 chegue ao usuário em vez de virar uma publicação sem foto; que a reação otimista faça
 * rollback para o estado que veio do servidor e não para uma conta recalculada; e que a troca de
 * conta limpe tudo antes de a requisição da conta nova sair.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class CheckInContentViewModelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var database: AppDatabase
    private lateinit var authGateway: FakeAuthGateway
    private lateinit var gateway: FakeContentGateway
    private lateinit var photoSource: FakePhotoSource
    private lateinit var publisher: WorkoutCheckInPublisher
    private lateinit var viewModelStore: ViewModelStore

    private val now = 1_800_000_000_000L

    // ------------------------------------------------------------------ dublês

    private class FakePhotoSource : CheckInPhotoSource {
        var result: SocialPhotoOptimizer.Optimized? =
            SocialPhotoOptimizer.Optimized(ByteArray(2048) { 7 }, 1080, 1350)
        var calls: Int = 0

        override suspend fun optimize(uri: Uri): SocialPhotoOptimizer.Optimized? {
            calls++
            return result
        }
    }

    private class FakeContentGateway : StubWorkoutCheckInGateway() {
        var createResult: WorkoutCheckInOutcome<WorkoutCheckIn> = WorkoutCheckInOutcome.Success(
            WorkoutCheckIn("checkin-1", SocialCheckInAuthor("s", "Alice"), 1_800_000_000_000L)
        )
        val createdContent = mutableListOf<Pair<String?, String?>>()

        var uploadResult: WorkoutCheckInOutcome<UploadedCheckInMedia> =
            WorkoutCheckInOutcome.Success(UploadedCheckInMedia("media-1", 1080, 1350, 2048))
        val uploadIds = mutableListOf<String>()
        val uploadedBytes = mutableListOf<Int>()

        var feedResult: WorkoutCheckInOutcome<List<WorkoutCheckIn>> =
            WorkoutCheckInOutcome.Success(emptyList())
        var reactionResult: WorkoutCheckInOutcome<WorkoutCheckIn>? = null
        val reactionCalls = mutableListOf<Pair<String, ReactionType?>>()

        var checkInResult: WorkoutCheckInOutcome<WorkoutCheckIn>? = null
        var commentsResult: WorkoutCheckInOutcome<List<CheckInComment>> =
            WorkoutCheckInOutcome.Success(emptyList())
        var createCommentResult: WorkoutCheckInOutcome<CheckInComment>? = null
        var deleteCommentResult: WorkoutCheckInOutcome<Unit> = WorkoutCheckInOutcome.Success(Unit)
        val deletedComments = mutableListOf<String>()
        val reports = mutableListOf<Triple<SocialReportTarget, String, String>>()
        var mediaBytesResult: WorkoutCheckInOutcome<ByteArray> =
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

        override suspend fun createCheckIn(
            sessionSyncId: String,
            clientRequestId: String,
            caption: String?,
            mediaId: String?
        ): WorkoutCheckInOutcome<WorkoutCheckIn> {
            createdContent += caption to mediaId
            return createResult
        }

        override suspend fun uploadMedia(
            sessionSyncId: String,
            clientUploadId: String,
            bytes: ByteArray
        ): WorkoutCheckInOutcome<UploadedCheckInMedia> {
            uploadIds += clientUploadId
            uploadedBytes += bytes.size
            return uploadResult
        }

        override suspend fun feed(limit: Int?) = feedResult

        override suspend fun checkIn(checkInId: String) =
            checkInResult ?: WorkoutCheckInOutcome.Failure(WorkoutCheckInError.CHECKIN_NOT_FOUND)

        override suspend fun putReaction(
            checkInId: String,
            type: ReactionType
        ): WorkoutCheckInOutcome<WorkoutCheckIn> {
            reactionCalls += checkInId to type
            return reactionResult
                ?: WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)
        }

        override suspend fun removeReaction(
            checkInId: String
        ): WorkoutCheckInOutcome<WorkoutCheckIn> {
            reactionCalls += checkInId to null
            return reactionResult
                ?: WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)
        }

        override suspend fun comments(checkInId: String, limit: Int?) = commentsResult

        override suspend fun createComment(checkInId: String, body: String) =
            createCommentResult ?: WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)

        override suspend fun deleteComment(
            checkInId: String,
            commentId: String
        ): WorkoutCheckInOutcome<Unit> {
            deletedComments += commentId
            return deleteCommentResult
        }

        override suspend fun reportContent(
            target: SocialReportTarget,
            targetId: String,
            reason: String
        ): WorkoutCheckInOutcome<Unit> {
            reports += Triple(target, targetId, reason)
            return WorkoutCheckInOutcome.Success(Unit)
        }

        override suspend fun mediaBytes(mediaId: String) = mediaBytesResult
    }

    private class FakeSocialGateway : SocialGateway {
        override val isConfigured: Boolean = true

        override suspend fun profile(): SocialOutcome = SocialOutcome.Success(
            SocialProfile(
                socialId = "social-a",
                friendCode = "SPK-AAAAAAAA",
                displayName = "Alice",
                status = SocialProfileStatus.ACTIVE,
                privacy = SocialPrivacySettings(
                    discoverability = SocialDiscoverability.FRIEND_CODE_ONLY,
                    friendRequestsEnabled = true,
                    activitySharingEnabled = false
                ),
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        override suspend fun activate(displayName: String): SocialOutcome =
            throw UnsupportedOperationException()

        override suspend fun updateDisplayName(displayName: String): SocialOutcome =
            throw UnsupportedOperationException()

        override suspend fun updatePrivacy(
            discoverability: SocialDiscoverability?,
            friendRequestsEnabled: Boolean?,
            activitySharingEnabled: Boolean?,
            activityTimeZoneId: String?,
            friendRankingParticipationEnabled: Boolean?
        ): SocialOutcome = throw UnsupportedOperationException()

        override suspend fun disable(): SocialOutcome = throw UnsupportedOperationException()

        override suspend fun enable(): SocialOutcome = throw UnsupportedOperationException()
    }

    // ------------------------------------------------------------------ setup

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Executores diretos: o Room roda nos executores **dele**, e um relógio virtual não faz o
        // banco responder mais cedo. Sem isto, `advanceUntilIdle()` volta enquanto a corrotina da
        // ViewModel ainda espera uma consulta em outra thread — e o teste mede o estado errado,
        // sem falhar de forma que aponte para a causa. A mesma armadilha da T17.8.
        val inline = java.util.concurrent.Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        // A conta já conectada no `setUp`: a ViewModel nasce com `observedUid` definido, e o
        // `signIn` dos testes serve para exercitar a **troca** de conta, não a primeira entrada.
        authGateway = FakeAuthGateway(initialAccount = SparkAccount("uid-a", "Alice"))
        gateway = FakeContentGateway()
        photoSource = FakePhotoSource()
        publisher = WorkoutCheckInPublisher(
            workoutDao = database.workoutDao(),
            gateway = gateway,
            authGateway = authGateway,
            syncCycle = { SyncOutcome.NotEnabled },
            clock = { now }
        )
        viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        // A ViewModel precisa ser encerrada: o `viewModelScope` vivo mantém a coleta do
        // `authGateway.state`, e um teste seguinte a herdaria como falha sem relação aparente.
        viewModelStore.clear()
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertSession(): Long =
        database.workoutDao().insertSession(
            WorkoutSessionEntity(
                templateId = null,
                templateNameSnapshot = "Treino A",
                status = SessionStatus.COMPLETED.name,
                startedAt = now - 60_000,
                finishedAt = now - 30_000
            )
        )

    private fun shareViewModel(): WorkoutCheckInViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WorkoutCheckInViewModel(
                    publisher = publisher,
                    socialGateway = FakeSocialGateway(),
                    authGateway = authGateway,
                    photoSource = photoSource
                ) as T
        }
        return ViewModelProvider(viewModelStore, factory)[WorkoutCheckInViewModel::class.java]
    }

    private fun feedViewModel(): SocialFeedViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SocialFeedViewModel(gateway = gateway, authGateway = authGateway) as T
        }
        return ViewModelProvider(viewModelStore, factory)[SocialFeedViewModel::class.java]
    }

    private fun detailViewModel(): CheckInDetailViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CheckInDetailViewModel(gateway = gateway, authGateway = authGateway) as T
        }
        return ViewModelProvider(viewModelStore, factory)[CheckInDetailViewModel::class.java]
    }

    private suspend fun TestScope.openComposer(viewModel: WorkoutCheckInViewModel): Long {
        val sessionId = insertSession()
        // O coletor de autenticação da ViewModel precisa assentar **antes** de `prepare`: ele
        // reseta o estado ao ver a conta pela primeira vez, e um `prepare` anterior a isso teria o
        // resultado descartado. Na produção isso não acontece porque a tela chama `prepare` em um
        // `LaunchedEffect`, depois de a ViewModel existir há pelo menos um frame.
        advanceUntilIdle()
        viewModel.prepare(sessionId)
        advanceUntilIdle()
        viewModel.requestShare()
        return sessionId
    }

    // =====================================================================
    // §181 — legenda
    // =====================================================================

    @Test
    fun `legenda vazia e permitida e vira ausencia`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)

        viewModel.onCaptionChanged("   ")
        assertTrue(viewModel.uiState.value.canConfirm)
        assertTrue(viewModel.uiState.value.canConfirm)

        viewModel.confirmShare()
        advanceUntilIdle()

        // "  " não é uma legenda: o corpo sai sem ela, e não com uma string vazia.
        assertEquals(listOf<Pair<String?, String?>>(null to null), gateway.createdContent)
    }

    @Test
    fun `280 caracteres passam e 281 bloqueiam o botao`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)

        viewModel.onCaptionChanged("a".repeat(WorkoutCheckInContract.Limits.MAX_CAPTION_LENGTH))
        assertEquals(0, viewModel.uiState.value.captionRemaining)
        assertFalse(viewModel.uiState.value.isCaptionTooLong)
        assertTrue(viewModel.uiState.value.canConfirm)

        viewModel.onCaptionChanged("a".repeat(WorkoutCheckInContract.Limits.MAX_CAPTION_LENGTH + 1))
        assertTrue(viewModel.uiState.value.isCaptionTooLong)
        assertFalse(viewModel.uiState.value.canConfirm)

        // O botão desabilitado é conveniência; confirmar assim mesmo também não publica.
        viewModel.confirmShare()
        advanceUntilIdle()
        assertEquals(emptyList<Pair<String?, String?>>(), gateway.createdContent)
    }

    @Test
    fun `o contador conta code points, e nao unidades UTF-16`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)

        // 280 emojis são 560 `char` em JavaScript e em Kotlin. Contar por `length` recusaria uma
        // legenda que a tela promete aceitar — e o servidor aceitaria (§7).
        viewModel.onCaptionChanged("💪".repeat(WorkoutCheckInContract.Limits.MAX_CAPTION_LENGTH))
        assertEquals(0, viewModel.uiState.value.captionRemaining)
        assertFalse(viewModel.uiState.value.isCaptionTooLong)
    }

    @Test
    fun `a legenda vai aparada para o servidor`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)

        viewModel.onCaptionChanged("   Hoje rendeu demais   ")
        viewModel.confirmShare()
        advanceUntilIdle()

        assertEquals(listOf<Pair<String?, String?>>("Hoje rendeu demais" to null), gateway.createdContent)
    }

    // =====================================================================
    // §180 — foto
    // =====================================================================

    @Test
    fun `escolher a foto le e reduz no aparelho, sem requisicao`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)

        viewModel.onPhotoPicked(Uri.parse("content://fake/1"))
        advanceUntilIdle()

        val photo = viewModel.uiState.value.photo
        assertTrue(photo is CheckInPhotoState.Ready)
        assertEquals(1080, (photo as CheckInPhotoState.Ready).width)
        assertEquals(1, photoSource.calls)
        // Nada foi enviado: escolher não publica, e o preview é local (§102/§124).
        assertEquals(emptyList<String>(), gateway.uploadIds)
        assertEquals(emptyList<Pair<String?, String?>>(), gateway.createdContent)
    }

    @Test
    fun `uma imagem ilegivel nao vira publicacao silenciosa sem foto`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)
        photoSource.result = null

        viewModel.onPhotoPicked(Uri.parse("content://fake/quebrada"))
        advanceUntilIdle()

        assertEquals(CheckInPhotoState.None, viewModel.uiState.value.photo)
        assertTrue(viewModel.uiState.value.feedback is CheckInShareFeedback.NotShared)
    }

    @Test
    fun `remover a foto antes de publicar e permitido`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)

        viewModel.onPhotoPicked(Uri.parse("content://fake/1"))
        advanceUntilIdle()
        viewModel.removePhoto()

        assertEquals(CheckInPhotoState.None, viewModel.uiState.value.photo)

        viewModel.confirmShare()
        advanceUntilIdle()
        assertEquals(listOf<Pair<String?, String?>>(null to null), gateway.createdContent)
    }

    @Test
    fun `publicar com foto envia a imagem primeiro e o mediaId depois`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)

        viewModel.onPhotoPicked(Uri.parse("content://fake/1"))
        advanceUntilIdle()
        viewModel.onCaptionChanged("Hoje rendeu demais")
        viewModel.confirmShare()
        advanceUntilIdle()

        assertEquals(1, gateway.uploadIds.size)
        assertEquals(listOf(2048), gateway.uploadedBytes)
        assertEquals(
            listOf<Pair<String?, String?>>("Hoje rendeu demais" to "media-1"),
            gateway.createdContent
        )
        assertTrue(viewModel.uiState.value.isShared)
    }

    @Test
    fun `falha no envio da foto NAO publica sem ela — a decisao volta para o usuario`() =
        runTest(testDispatcher) {
            val viewModel = shareViewModel()
            openComposer(viewModel)
            gateway.uploadResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)

            viewModel.onPhotoPicked(Uri.parse("content://fake/1"))
            advanceUntilIdle()
            viewModel.confirmShare()
            advanceUntilIdle()

            // §43 — o bloqueante: nada foi publicado, e o estado oferece as duas saídas.
            assertEquals(emptyList<Pair<String?, String?>>(), gateway.createdContent)
            assertFalse(viewModel.uiState.value.isShared)
            val photo = viewModel.uiState.value.photo
            assertTrue(photo is CheckInPhotoState.Failed)
            assertTrue((photo as CheckInPhotoState.Failed).message.contains("Não conseguimos enviar"))
        }

    @Test
    fun `tentar novamente reusa o mesmo clientUploadId`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)
        gateway.uploadResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)

        viewModel.onPhotoPicked(Uri.parse("content://fake/1"))
        advanceUntilIdle()
        viewModel.confirmShare()
        advanceUntilIdle()

        gateway.uploadResult =
            WorkoutCheckInOutcome.Success(UploadedCheckInMedia("media-1", 1080, 1350, 2048))
        viewModel.retryPhotoUpload()
        advanceUntilIdle()

        // §36 — a mesma foto, o mesmo identificador: o servidor converge no mesmo `mediaId` em vez
        // de guardar um arquivo órfão ocupando a quota da pessoa.
        assertEquals(2, gateway.uploadIds.size)
        assertEquals(gateway.uploadIds[0], gateway.uploadIds[1])
        assertTrue(viewModel.uiState.value.isShared)
    }

    @Test
    fun `publicar sem foto e uma acao explicita depois da falha`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)
        gateway.uploadResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)

        viewModel.onPhotoPicked(Uri.parse("content://fake/1"))
        advanceUntilIdle()
        viewModel.onCaptionChanged("Hoje rendeu")
        viewModel.confirmShare()
        advanceUntilIdle()

        viewModel.publishWithoutPhoto()
        advanceUntilIdle()

        // A legenda permanece; a foto não. Foi o usuário quem escolheu.
        assertEquals(listOf<Pair<String?, String?>>("Hoje rendeu" to null), gateway.createdContent)
        assertTrue(viewModel.uiState.value.isShared)
    }

    @Test
    fun `um mediaId recusado no POST tambem para em Failed`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)
        gateway.createResult =
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.MEDIA_NOT_FOUND)

        viewModel.onPhotoPicked(Uri.parse("content://fake/1"))
        advanceUntilIdle()
        viewModel.confirmShare()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isShared)
        assertTrue(viewModel.uiState.value.photo is CheckInPhotoState.Failed)
    }

    @Test
    fun `trocar de conta descarta rascunho, foto e identificadores`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)

        viewModel.onCaptionChanged("rascunho da Alice")
        viewModel.onPhotoPicked(Uri.parse("content://fake/1"))
        advanceUntilIdle()

        authGateway.nextOutcome = AuthOutcome.Success(SparkAccount("uid-b", "Bruno"))
        authGateway.signIn(context)
        advanceUntilIdle()

        // §145/§147 — nada da conta anterior sobrevive.
        assertEquals("", viewModel.uiState.value.caption)
        assertEquals(CheckInPhotoState.None, viewModel.uiState.value.photo)
    }

    @Test
    fun `fechar o compositor descarta o rascunho`() = runTest(testDispatcher) {
        val viewModel = shareViewModel()
        openComposer(viewModel)

        viewModel.onCaptionChanged("quase publiquei")
        viewModel.onPhotoPicked(Uri.parse("content://fake/1"))
        advanceUntilIdle()
        viewModel.cancelShare()

        // §146 — rascunho é estado de tela, e não persistência social.
        assertEquals("", viewModel.uiState.value.caption)
        assertEquals(CheckInPhotoState.None, viewModel.uiState.value.photo)
        assertFalse(viewModel.uiState.value.isConfirming)
    }

    // =====================================================================
    // §183 — reações no Feed
    // =====================================================================

    private fun feedItem(
        reactions: Map<ReactionType, Int> = emptyMap(),
        current: ReactionType? = null,
        media: CheckInMedia? = null,
        caption: String? = null,
        comments: Int = 0
    ) = WorkoutCheckIn(
        checkInId = "checkin-1",
        author = SocialCheckInAuthor("social-b", "Bruno"),
        publishedAt = now - 60_000,
        caption = caption,
        media = media,
        reactions = reactions,
        currentUserReaction = current,
        commentCount = comments,
        isCurrentUser = false
    )

    @Test
    fun `reagir e otimista e reconcilia com a resposta do servidor`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Success(listOf(feedItem()))
        val viewModel = feedViewModel()
        advanceUntilIdle()

        gateway.reactionResult = WorkoutCheckInOutcome.Success(
            feedItem(reactions = mapOf(ReactionType.FIRE to 5), current = ReactionType.FIRE)
        )
        viewModel.toggleReaction("checkin-1", ReactionType.FIRE)

        // Antes de a resposta chegar, a tela já mostra a reação (§121).
        val optimisticItem =
            (viewModel.uiState.value.phase as SocialFeedPhase.Success).items.first()
        assertEquals(ReactionType.FIRE, optimisticItem.currentUserReaction)
        assertEquals(1, optimisticItem.reactions[ReactionType.FIRE])

        advanceUntilIdle()

        // Depois, o número **do servidor** substitui o otimista: a contagem é filtrada por viewer
        // lá (§69), e somar aqui recolocaria na conta gente que o bloqueio tirou.
        val reconciled = (viewModel.uiState.value.phase as SocialFeedPhase.Success).items.first()
        assertEquals(5, reconciled.reactions[ReactionType.FIRE])
    }

    @Test
    fun `trocar de reacao nao cria uma segunda`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Success(
            listOf(feedItem(reactions = mapOf(ReactionType.FIRE to 1), current = ReactionType.FIRE))
        )
        val viewModel = feedViewModel()
        advanceUntilIdle()

        gateway.reactionResult = WorkoutCheckInOutcome.Success(
            feedItem(reactions = mapOf(ReactionType.MUSCLE to 1), current = ReactionType.MUSCLE)
        )
        viewModel.toggleReaction("checkin-1", ReactionType.MUSCLE)

        val optimisticItem =
            (viewModel.uiState.value.phase as SocialFeedPhase.Success).items.first()
        assertEquals(ReactionType.MUSCLE, optimisticItem.currentUserReaction)
        assertNull(optimisticItem.reactions[ReactionType.FIRE])
        assertEquals(1, optimisticItem.reactions[ReactionType.MUSCLE])

        advanceUntilIdle()
        assertEquals(listOf("checkin-1" to ReactionType.MUSCLE), gateway.reactionCalls)
    }

    @Test
    fun `tocar na reacao atual de novo remove`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Success(
            listOf(feedItem(reactions = mapOf(ReactionType.FIRE to 3), current = ReactionType.FIRE))
        )
        val viewModel = feedViewModel()
        advanceUntilIdle()

        gateway.reactionResult = WorkoutCheckInOutcome.Success(
            feedItem(reactions = mapOf(ReactionType.FIRE to 2), current = null)
        )
        viewModel.toggleReaction("checkin-1", ReactionType.FIRE)
        advanceUntilIdle()

        // §120 — comportamento determinístico: a chamada foi de **remoção**.
        assertEquals(listOf<Pair<String, ReactionType?>>("checkin-1" to null), gateway.reactionCalls)
        val item = (viewModel.uiState.value.phase as SocialFeedPhase.Success).items.first()
        assertNull(item.currentUserReaction)
    }

    @Test
    fun `falha de rede faz rollback para o estado anterior`() = runTest(testDispatcher) {
        val original = feedItem(reactions = mapOf(ReactionType.CLAP to 2), current = null)
        gateway.feedResult = WorkoutCheckInOutcome.Success(listOf(original))
        val viewModel = feedViewModel()
        advanceUntilIdle()

        gateway.reactionResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)
        viewModel.toggleReaction("checkin-1", ReactionType.FIRE)
        advanceUntilIdle()

        // §121 — nunca deixar estado local falso.
        val item = (viewModel.uiState.value.phase as SocialFeedPhase.Success).items.first()
        assertEquals(original, item)
        assertTrue(viewModel.uiState.value.notice!!.contains("Sem conexão"))
    }

    @Test
    fun `trocar de conta limpa o feed antes da leitura da conta nova`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Success(listOf(feedItem()))
        val viewModel = feedViewModel()
        advanceUntilIdle()
        assertEquals(1, (viewModel.uiState.value.phase as SocialFeedPhase.Success).items.size)

        gateway.feedResult = WorkoutCheckInOutcome.Success(emptyList())
        authGateway.nextOutcome = AuthOutcome.Success(SparkAccount("uid-b", "Bruno"))
        authGateway.signIn(context)
        advanceUntilIdle()

        assertEquals(emptyList<WorkoutCheckIn>(), (viewModel.uiState.value.phase as SocialFeedPhase.Success).items)
        assertEquals(emptyMap<String, Any>(), viewModel.uiState.value.photos)
    }

    // =====================================================================
    // §184 — comentários no detalhe
    // =====================================================================

    private fun comment(id: String, own: Boolean = false, canDelete: Boolean = false) =
        CheckInComment(
            commentId = id,
            author = SocialCheckInAuthor(if (own) "social-a" else "social-b", if (own) "Alice" else "Bruno"),
            body = "comentário $id",
            createdAt = now,
            isCurrentUser = own,
            canDelete = canDelete
        )

    private suspend fun TestScope.openDetail(
        checkIn: WorkoutCheckIn = feedItem(),
        comments: List<CheckInComment> = emptyList()
    ): CheckInDetailViewModel {
        gateway.checkInResult = WorkoutCheckInOutcome.Success(checkIn)
        gateway.commentsResult = WorkoutCheckInOutcome.Success(comments)
        val viewModel = detailViewModel()
        viewModel.open(checkIn.checkInId)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `carrega a publicacao e os comentarios visiveis`() = runTest(testDispatcher) {
        val viewModel = openDetail(
            checkIn = feedItem(caption = "Hoje rendeu", comments = 2),
            comments = listOf(comment("c1"), comment("c2", own = true, canDelete = true))
        )

        val phase = viewModel.uiState.value.phase as CheckInDetailPhase.Success
        assertEquals("Hoje rendeu", phase.checkIn.caption)
        assertEquals(2, phase.comments.size)
        assertTrue(phase.comments[1].canDelete)
    }

    @Test
    fun `comentar espera o servidor — nada e inserido otimisticamente`() = runTest(testDispatcher) {
        val viewModel = openDetail()
        viewModel.onDraftChanged("Boa!")
        gateway.createCommentResult =
            WorkoutCheckInOutcome.Success(comment("c-novo", own = true, canDelete = true))

        viewModel.sendComment()
        // §122 — antes da resposta, a lista continua vazia: um comentário que aparece e some faz
        // quem escreveu acreditar que a outra pessoa leu.
        assertEquals(
            0,
            (viewModel.uiState.value.phase as CheckInDetailPhase.Success).comments.size
        )
        assertTrue(viewModel.uiState.value.isSendingComment)

        advanceUntilIdle()

        val phase = viewModel.uiState.value.phase as CheckInDetailPhase.Success
        assertEquals(1, phase.comments.size)
        assertEquals("c-novo", phase.comments.first().commentId)
        assertEquals(1, phase.checkIn.commentCount)
        assertEquals("", viewModel.uiState.value.draft)
    }

    @Test
    fun `comentario que falha preserva o rascunho`() = runTest(testDispatcher) {
        val viewModel = openDetail()
        viewModel.onDraftChanged("texto que custou a escrever")
        gateway.createCommentResult =
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)

        viewModel.sendComment()
        advanceUntilIdle()

        // Perder o que a pessoa escreveu porque a rede caiu é o pior desfecho desta tela.
        assertEquals("texto que custou a escrever", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.notice!!.contains("Sem conexão"))
    }

    @Test
    fun `comentario vazio e comentario grande demais nao sao enviados`() = runTest(testDispatcher) {
        val viewModel = openDetail()

        viewModel.onDraftChanged("   ")
        assertFalse(viewModel.uiState.value.canSendComment)

        viewModel.onDraftChanged("a".repeat(WorkoutCheckInContract.Limits.MAX_COMMENT_LENGTH + 1))
        assertFalse(viewModel.uiState.value.canSendComment)

        viewModel.onDraftChanged("a".repeat(WorkoutCheckInContract.Limits.MAX_COMMENT_LENGTH))
        assertTrue(viewModel.uiState.value.canSendComment)
    }

    @Test
    fun `apagar o proprio comentario remove da lista e da contagem`() = runTest(testDispatcher) {
        val viewModel = openDetail(
            checkIn = feedItem(comments = 1),
            comments = listOf(comment("c1", own = true, canDelete = true))
        )

        viewModel.deleteComment("c1")
        advanceUntilIdle()

        val phase = viewModel.uiState.value.phase as CheckInDetailPhase.Success
        assertEquals(emptyList<CheckInComment>(), phase.comments)
        assertEquals(0, phase.checkIn.commentCount)
        assertEquals(listOf("c1"), gateway.deletedComments)
    }

    @Test
    fun `reagir no detalhe faz rollback em falha`() = runTest(testDispatcher) {
        val original = feedItem(reactions = mapOf(ReactionType.FIRE to 1), current = null)
        val viewModel = openDetail(checkIn = original)

        gateway.reactionResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)
        viewModel.toggleReaction(ReactionType.MUSCLE)
        advanceUntilIdle()

        val phase = viewModel.uiState.value.phase as CheckInDetailPhase.Success
        assertEquals(original, phase.checkIn)
    }

    @Test
    fun `publicacao inacessivel responde uma coisa so`() = runTest(testDispatcher) {
        gateway.checkInResult =
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.CHECKIN_NOT_FOUND)
        val viewModel = detailViewModel()
        advanceUntilIdle()
        viewModel.open("checkin-1")
        advanceUntilIdle()

        // §51 — excluída, unfriend, bloqueio e Social desativado são a mesma resposta, e a tela não
        // inventa uma distinção que ela não tem como saber.
        assertEquals(CheckInDetailPhase.Unavailable, viewModel.uiState.value.phase)
    }

    @Test
    fun `denunciar envia o alvo, e nunca quem e o autor`() = runTest(testDispatcher) {
        val viewModel = openDetail()

        viewModel.report(SocialReportTarget.CHECKIN, "checkin-1", "SPAM")
        advanceUntilIdle()

        // §103 — o app diz **o que**; o servidor descobre **de quem**.
        assertEquals(
            listOf(Triple(SocialReportTarget.CHECKIN, "checkin-1", "SPAM")),
            gateway.reports
        )
    }

    @Test
    fun `trocar de conta limpa o detalhe`() = runTest(testDispatcher) {
        val viewModel = openDetail(comments = listOf(comment("c1")))
        assertTrue(viewModel.uiState.value.phase is CheckInDetailPhase.Success)

        gateway.checkInResult =
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.CHECKIN_NOT_FOUND)
        authGateway.nextOutcome = AuthOutcome.Success(SparkAccount("uid-b", "Bruno"))
        authGateway.signIn(context)
        advanceUntilIdle()

        // Nada da conta anterior sobrevive — nem a publicação, nem a conversa, nem a foto (§145).
        assertFalse(viewModel.uiState.value.phase is CheckInDetailPhase.Success)
        assertNull(viewModel.uiState.value.photo)
    }
}
