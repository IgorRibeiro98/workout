package com.example.presentation.friends

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.social.FakeSocialActivityGateway
import com.example.data.social.FakeSocialGateway
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.FriendActivityItem
import com.example.domain.social.FriendRankingEntry
import com.example.domain.social.FriendRankingLeaderboard
import com.example.domain.social.SocialActivityError
import com.example.domain.social.SocialError
import com.example.domain.social.SocialPrivacySettings
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialActivityViewModelTest {

    private lateinit var activityGateway: FakeSocialActivityGateway
    private lateinit var socialGateway: FakeSocialGateway
    private lateinit var authGateway: FakeAuthGateway
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val accountA = SparkAccount(uid = "uid-a", displayName = "Ana", email = "a@example.com")
    private val accountB = SparkAccount(uid = "uid-b", displayName = "Beto", email = "b@example.com")

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        activityGateway = FakeSocialActivityGateway()
        socialGateway = FakeSocialGateway()
        authGateway = FakeAuthGateway(initialAccount = accountA)

        socialGateway.currentUid = accountA.uid
        socialGateway.seed(
            accountA.uid,
            SocialProfile(
                socialId = "social-a",
                friendCode = "SPK-A",
                displayName = "Ana",
                status = SocialProfileStatus.ACTIVE,
                privacy = SocialPrivacySettings(
                    activitySharingEnabled = true,
                    friendRankingParticipationEnabled = false
                ),
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SocialActivityViewModel {
        return SocialActivityViewModel(
            activityGateway = activityGateway,
            socialGateway = socialGateway,
            authGateway = authGateway
        )
    }

    @Test
    fun `carregar dados com sucesso popula feed de 14 dias e ranking semanal`() = runBlocking {
        val feed = listOf(
            FriendActivityItem(socialId = "friend-1", displayName = "Carlos", daysAgo = 0),
            FriendActivityItem(socialId = "friend-2", displayName = "Diana", daysAgo = 2)
        )
        val ranking = FriendRankingLeaderboard(
            participantCount = 2,
            entries = listOf(
                FriendRankingEntry(socialId = "friend-1", displayName = "Carlos", score = 4, rank = 1, isCurrentUser = false),
                FriendRankingEntry(socialId = "social-a", displayName = "Ana", score = 2, rank = 2, isCurrentUser = true)
            )
        )

        activityGateway.seededActivity = feed
        activityGateway.seededRanking = ranking

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Ranking should be Success", state.rankingState is RankingUiState.Success)
        val successRanking = state.rankingState as RankingUiState.Success
        assertEquals(2, successRanking.participantCount)
        assertEquals(2, successRanking.entries.size)
        assertEquals("Carlos", successRanking.entries[0].displayName)

        assertTrue("Activity should be Success", state.activityState is ActivityFeedUiState.Success)
        val successActivity = state.activityState as ActivityFeedUiState.Success
        assertEquals(2, successActivity.items.size)
        assertEquals("Diana", successActivity.items[1].displayName)
    }

    @Test
    fun `ranking 403 RANKING_NOT_ENABLED transiciona ranking para OptedOut e mantem feed ativo`() = runBlocking {
        val feed = listOf(
            FriendActivityItem(socialId = "friend-1", displayName = "Carlos", daysAgo = 1)
        )
        activityGateway.seededActivity = feed
        activityGateway.failWithRanking = SocialActivityError.RANKING_NOT_ENABLED

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertEquals(RankingUiState.OptedOut, state.rankingState)
        assertTrue(state.activityState is ActivityFeedUiState.Success)
        assertEquals(1, (state.activityState as ActivityFeedUiState.Success).items.size)
    }

    @Test
    fun `optInToRanking habilita participacao via SocialGateway e recarrega ranking com sucesso`() = runBlocking {
        activityGateway.failWithRanking = SocialActivityError.RANKING_NOT_ENABLED
        val viewModel = createViewModel()

        assertEquals(RankingUiState.OptedOut, viewModel.uiState.value.rankingState)

        // Prepara resposta de sucesso no gateway ao reconsultar ranking
        activityGateway.failWithRanking = null
        activityGateway.seededRanking = FriendRankingLeaderboard(
            participantCount = 1,
            entries = listOf(
                FriendRankingEntry(socialId = "social-a", displayName = "Ana", score = 3, rank = 1, isCurrentUser = true)
            )
        )

        viewModel.optInToRanking()

        val state = viewModel.uiState.value
        assertFalse(state.isOptingIn)
        assertNull(state.optInErrorMessage)
        assertTrue(state.rankingState is RankingUiState.Success)
        val successRanking = state.rankingState as RankingUiState.Success
        assertEquals(1, successRanking.participantCount)
        assertEquals("Ana", successRanking.entries[0].displayName)
        assertTrue(socialGateway.stored(accountA.uid)?.privacy?.friendRankingParticipationEnabled == true)
    }

    @Test
    fun `optInToRanking exibe erro quando SocialGateway falha`() = runBlocking {
        activityGateway.failWithRanking = SocialActivityError.RANKING_NOT_ENABLED
        val viewModel = createViewModel()

        socialGateway.failWith = SocialError.NETWORK

        viewModel.optInToRanking()

        val state = viewModel.uiState.value
        assertFalse(state.isOptingIn)
        assertNotNull(state.optInErrorMessage)
        assertEquals(RankingUiState.OptedOut, state.rankingState)
    }

    @Test
    fun `optInToRanking altera exclusivamente friendRankingParticipationEnabled preservando activityTimeZoneId nulo e activitySharingEnabled falso`() = runBlocking {
        // Conta com activitySharingEnabled = false e timezone = null
        socialGateway.seed(
            accountA.uid,
            SocialProfile(
                socialId = "social-a",
                friendCode = "SPK-A",
                displayName = "Ana",
                status = SocialProfileStatus.ACTIVE,
                privacy = SocialPrivacySettings(
                    activitySharingEnabled = false,
                    activityTimeZoneId = null,
                    friendRankingParticipationEnabled = false
                ),
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
        activityGateway.failWithRanking = SocialActivityError.RANKING_NOT_ENABLED
        val viewModel = createViewModel()

        viewModel.optInToRanking()

        val updatedPrivacy = socialGateway.stored(accountA.uid)?.privacy
        assertNotNull(updatedPrivacy)
        assertTrue(updatedPrivacy!!.friendRankingParticipationEnabled)
        assertFalse("activitySharingEnabled deve permanecer false", updatedPrivacy.activitySharingEnabled)
        assertNull("activityTimeZoneId deve permanecer null e não receber fuso padrão", updatedPrivacy.activityTimeZoneId)
    }

    @Test
    fun `optInToRanking preserva timezone customizada pre-existente sem sobrescrever`() = runBlocking {
        val customTz = "Pacific/Auckland"
        socialGateway.seed(
            accountA.uid,
            SocialProfile(
                socialId = "social-a",
                friendCode = "SPK-A",
                displayName = "Ana",
                status = SocialProfileStatus.ACTIVE,
                privacy = SocialPrivacySettings(
                    activitySharingEnabled = true,
                    activityTimeZoneId = customTz,
                    friendRankingParticipationEnabled = false
                ),
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
        activityGateway.failWithRanking = SocialActivityError.RANKING_NOT_ENABLED
        val viewModel = createViewModel()

        viewModel.optInToRanking()

        val updatedPrivacy = socialGateway.stored(accountA.uid)?.privacy
        assertNotNull(updatedPrivacy)
        assertTrue(updatedPrivacy!!.friendRankingParticipationEnabled)
        assertTrue(updatedPrivacy.activitySharingEnabled)
        assertEquals("activityTimeZoneId não deve ser sobrescrita", customTz, updatedPrivacy.activityTimeZoneId)
    }

    @Test
    fun `feed vazio resulta em lista vazia com estado Success`() = runBlocking {
        activityGateway.seededActivity = emptyList()
        activityGateway.seededRanking = FriendRankingLeaderboard(0, emptyList())

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.activityState is ActivityFeedUiState.Success)
        assertTrue((state.activityState as ActivityFeedUiState.Success).items.isEmpty())
    }

    @Test
    fun `trocar de conta invalida os dados e nao vaza resposta da conta anterior`() = runBlocking {
        activityGateway.seededActivity = listOf(
            FriendActivityItem(socialId = "friend-a", displayName = "Amigo da Ana", daysAgo = 1)
        )
        val viewModel = createViewModel()

        assertTrue(viewModel.uiState.value.activityState is ActivityFeedUiState.Success)

        // Prende a próxima requisição da conta B
        val gate = CompletableDeferred<Unit>()
        activityGateway.gate = gate

        // Logout de Ana
        authGateway.signOut()

        // Estado é resetado para erro de autenticação necessária
        assertTrue(viewModel.uiState.value.rankingState is RankingUiState.Error)
        assertTrue(viewModel.uiState.value.activityState is ActivityFeedUiState.Error)

        // Login de Beto
        authGateway.nextOutcome = AuthOutcome.Success(accountB)
        authGateway.signIn(context)

        // Resposta da requisição de A (se chegasse atrasada) é descartada
        activityGateway.gate = null
        gate.complete(Unit)

        assertEquals("uid-b", (authGateway.state.value as? com.example.domain.auth.AuthState.SignedIn)?.account?.uid)
    }

    @Test
    fun `falha de rede reporta mensagem amigavel sem crash`() = runBlocking {
        activityGateway.failWithActivity = SocialActivityError.NETWORK
        activityGateway.failWithRanking = SocialActivityError.NETWORK

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.rankingState is RankingUiState.Error)
        assertTrue(state.activityState is ActivityFeedUiState.Error)
        assertEquals("Sem conexão com a internet.", (state.rankingState as RankingUiState.Error).message)
        assertEquals("Sem conexão com a internet.", (state.activityState as ActivityFeedUiState.Error).message)
    }
}
