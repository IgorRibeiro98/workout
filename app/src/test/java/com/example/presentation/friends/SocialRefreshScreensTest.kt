package com.example.presentation.friends

import android.os.Build
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.social.FakeChallengeGateway
import com.example.data.social.FakeFriendGateway
import com.example.data.social.FakeSocialGateway
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.SocialCheckInAuthor
import com.example.domain.social.SocialPrivacySettings
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import com.example.domain.social.StubWorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInOutcome
import com.example.presentation.account.ChallengeViewModel
import com.example.presentation.account.FriendsViewModel
import com.example.presentation.account.SocialViewModel
import com.example.presentation.social.SocialHomeScreen
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O "↻" das telas sociais, com a ViewModel **real** e o servidor como dublê (T19.H3 §49).
 *
 * Cada teste prova três coisas na tela de verdade: o botão existe e é descobrível ("Atualizar",
 * 48dp); tocar nele relê o servidor pelo **mesmo** método do gesto; e o que mudou em outro
 * aparelho aparece sem reiniciar o app — o cenário do smoke real de §56.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialRefreshScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val account = SparkAccount(uid = "uid-A", displayName = "Ana", email = "a@example.com")
    private val ana = FakeFriendGateway.Account(
        uid = "uid-A",
        socialId = "social-A",
        friendCode = "SPK-AAAAAAAA",
        displayName = "Ana"
    )
    private val bruno = FakeFriendGateway.Account(
        uid = "uid-B",
        socialId = "social-B",
        friendCode = "SPK-BBBBBBBB",
        displayName = "Bruno"
    )

    private val viewModels = ViewModelStore()
    private var keys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun <T : ViewModel> keep(viewModel: T): T =
        viewModel.also { viewModels.put("vm-${keys++}", it) }

    private fun friendsGateway() = FakeFriendGateway().apply {
        register(ana)
        register(bruno)
        currentUid = ana.uid
    }

    // ------------------------------------------------------------------ Solicitações (§7)

    @Test
    fun `Solicitacoes - o botao Atualizar traz o pedido que chegou depois de abrir`() {
        val gateway = friendsGateway()
        val viewModel = keep(FriendsViewModel(gateway, FakeAuthGateway(initialAccount = account)))

        composeRule.setContent { FriendRequestsScreen(viewModel = viewModel, onNavigateBack = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(REQUESTS_EMPTY_MESSAGE).assertExists()

        // B envia o pedido **depois** de A abrir a tela (§56).
        gateway.seedRequest(requesterUid = bruno.uid, recipientUid = ana.uid)
        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Bruno", substring = true).assertExists()
    }

    @Test
    fun `o botao tem alvo de toque de 48dp e fica desabilitado enquanto atualiza`() {
        val gateway = friendsGateway()
        val viewModel = keep(FriendsViewModel(gateway, FakeAuthGateway(initialAccount = account)))
        composeRule.setContent { FriendRequestsScreen(viewModel = viewModel, onNavigateBack = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SOCIAL_REFRESH_ACTION_TAG)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertIsEnabled()

        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL).performClick()
        composeRule.waitForIdle()

        // Em voo: o botão diz "Atualizando", não aceita um segundo toque, e a lista continua.
        composeRule.onNodeWithContentDescription(REFRESHING_ACTION_LABEL).assertIsNotEnabled()
        composeRule.onNodeWithText(REQUESTS_EMPTY_MESSAGE).assertExists()

        gateway.gate = null
        gate.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL).assertIsEnabled()
    }

    // ------------------------------------------------------------------ Amigos (§9)

    @Test
    fun `Amigos - o botao Atualizar traz a amizade feita em outro aparelho`() {
        val gateway = friendsGateway()
        val viewModel = keep(FriendsViewModel(gateway, FakeAuthGateway(initialAccount = account)))
        composeRule.setContent {
            FriendsScreen(viewModel = viewModel, onNavigateBack = {}, onNavigateToRequests = {})
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(FRIENDS_EMPTY_MESSAGE).assertExists()

        gateway.seedFriendship(ana.uid, bruno.uid)
        val readsBefore = gateway.readCalls
        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Bruno", substring = true).assertExists()
        // Uma releitura: amigos + recebidas + enviadas. Nada foi enviado nem aceito.
        assertEquals(readsBefore + 3, gateway.readCalls)
        assertEquals(0, gateway.sendCalls + gateway.acceptCalls + gateway.removeCalls)
    }

    // ------------------------------------------------------------------ Desafios (§9)

    @Test
    fun `Desafios - o botao Atualizar traz o desafio criado depois de abrir, sem esconder a lista`() {
        val challenges = FakeChallengeGateway().apply { currentUid = account.uid }
        challenges.seedChallenge(account.uid, FakeChallengeGateway.challenge(id = "c-1", name = "12 treinos"))
        val viewModel = keep(
            ChallengeViewModel(
                gateway = challenges,
                friendGateway = friendsGateway(),
                authGateway = FakeAuthGateway(initialAccount = account),
                deviceTimeZoneId = { "America/Sao_Paulo" },
                today = { LocalDate.of(2026, 9, 8) }
            )
        )
        composeRule.setContent {
            ChallengesScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onOpenChallenge = {},
                onCreateChallenge = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("12 treinos", substring = true).assertExists()

        challenges.seedChallenge(account.uid, FakeChallengeGateway.challenge(id = "c-2", name = "Setembro forte"))
        val gate = CompletableDeferred<Unit>()
        challenges.gate = gate
        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL).performClick()
        composeRule.waitForIdle()
        // Em voo, a lista anterior continua na tela (§11).
        composeRule.onNodeWithText("12 treinos", substring = true).assertExists()

        challenges.gate = null
        gate.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Setembro forte", substring = true).assertExists()
    }

    // ------------------------------------------------------------------ Feed (§8)

    @Test
    fun `Feed - o botao Atualizar traz a publicacao nova, e so le`() {
        val feed = ScriptedFeedGateway()
        val viewModel = keep(
            SocialFeedViewModel(gateway = feed, authGateway = FakeAuthGateway(initialAccount = account))
        )
        composeRule.setContent {
            SocialFeedScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onOpenFriendProfile = { _, _ -> },
                now = 10_000L
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SOCIAL_FEED_EMPTY_MESSAGE).assertExists()

        // B publica depois de A abrir o Feed (§56).
        feed.items = listOf(
            WorkoutCheckIn("checkin-b", SocialCheckInAuthor("social-B", "Bruno"), publishedAt = 9_000L)
        )
        val readsBefore = feed.feedCalls
        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("BRUNO").assertExists()
        assertEquals(readsBefore + 1, feed.feedCalls)
        assertEquals(0, feed.mutations)
    }

    // ------------------------------------------------------------------ SocialHome (§6)

    @Test
    fun `SocialHome - o botao Atualizar rele o perfil e o grafo, sem mutacao`() {
        val social = FakeSocialGateway().apply {
            currentUid = account.uid
            seed(
                account.uid,
                SocialProfile(
                    socialId = ana.socialId,
                    friendCode = ana.friendCode,
                    displayName = "Ana",
                    status = SocialProfileStatus.ACTIVE,
                    privacy = SocialPrivacySettings(),
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        }
        val friends = friendsGateway()
        val auth = FakeAuthGateway(initialAccount = account)
        val socialViewModel = keep(SocialViewModel(gateway = social, authGateway = auth))
        val friendsViewModel = keep(FriendsViewModel(friends, auth))

        composeRule.setContent {
            SocialHomeScreen(
                socialViewModel = socialViewModel,
                friendsViewModel = friendsViewModel,
                onNavigateBack = {},
                onOpenFriends = {},
                onOpenFriendRequests = {},
                onOpenProgressSharing = {},
                onOpenChallenges = {},
                onOpenActivity = {},
                onOpenNotificationPreferences = {},
                onOpenBlockedUsers = {},
                onOpenSharedWorkouts = {},
                onOpenSocialFeed = {},
                onOpenSquads = {}
            )
        }
        composeRule.waitForIdle()
        val profileReads = social.profileCalls
        val graphReads = friends.readCalls

        // Voltar ao hub não relê (open() é idempotente): só o toque explícito.
        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL).performClick()
        composeRule.waitForIdle()

        assertEquals(profileReads + 1, social.profileCalls)
        assertEquals(graphReads + 3, friends.readCalls)
        assertEquals(0, social.activateCalls)
        composeRule.onNodeWithText("Ana", substring = true).assertExists()
    }

    /** O Feed como dublê: devolve o que o teste pôs em [items], e conta leituras e mutações. */
    private class ScriptedFeedGateway : StubWorkoutCheckInGateway() {
        var items: List<WorkoutCheckIn> = emptyList()
        var feedCalls = 0
        var mutations = 0

        override suspend fun feed(limit: Int?): WorkoutCheckInOutcome<List<WorkoutCheckIn>> {
            feedCalls++
            return WorkoutCheckInOutcome.Success(items)
        }

        override suspend fun deleteCheckIn(checkInId: String): WorkoutCheckInOutcome<Unit> {
            mutations++
            return WorkoutCheckInOutcome.Success(Unit)
        }
    }
}
