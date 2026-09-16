package com.example.presentation.execution

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.multiplayer.MultiplayerConnection
import com.example.domain.multiplayer.MultiplayerEndReason
import com.example.domain.multiplayer.MultiplayerMemberRole
import com.example.domain.multiplayer.MultiplayerMemberStatus
import com.example.domain.multiplayer.MultiplayerSessionState
import com.example.domain.multiplayer.PeerExerciseProgress
import com.example.domain.multiplayer.PeerProgress
import com.example.domain.multiplayer.PeerView
import com.example.presentation.execution.components.RemotePeerBanner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O que a tela diz sobre o outro aparelho (T19.5 §11): o estado da conexão é sobre a sala, e a
 * perda dela nunca é descrita como falha do treino.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class RemotePeerBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun state(
        connection: MultiplayerConnection,
        peer: PeerView? = PeerView("João", MultiplayerMemberStatus.ACTIVE, connected = true, progress = PeerProgress()),
        endReason: MultiplayerEndReason? = null,
        pending: Int = 0
    ) = MultiplayerSessionState(
        roomId = "room-1",
        role = MultiplayerMemberRole.HOST,
        connection = connection,
        endReason = endReason,
        peer = peer,
        pendingLocalEvents = pending
    )

    @Test
    fun `conectado mostra o peer e o progresso dele no exercicio em foco`() {
        val progress = PeerProgress(
            started = true,
            exercises = mapOf(1 to PeerExerciseProgress(1, "supino-reto-barra", 3, setOf(1, 2)))
        )
        composeRule.setContent {
            RemotePeerBanner(
                remote = state(MultiplayerConnection.CONNECTED, peer = PeerView("João", MultiplayerMemberStatus.ACTIVE, true, progress)),
                currentCanonicalExerciseId = "supino-reto-barra",
                currentExercisePosition = 1,
                onLeave = {}
            )
        }
        composeRule.onNodeWithTag("remote_connection_label").assertTextContains("EM DUPLA")
        composeRule.onNodeWithTag("remote_peer_name").assertTextContains("João")
        composeRule.onNodeWithTag("remote_peer_status").assertTextContains("João: 2/3 séries neste exercício.")
    }

    @Test
    fun `reconectando e eventos pendentes falam de rede, nunca de treino perdido`() {
        composeRule.setContent {
            RemotePeerBanner(
                remote = state(MultiplayerConnection.RECONNECTING, pending = 2),
                currentCanonicalExerciseId = null,
                currentExercisePosition = 1,
                onLeave = {}
            )
        }
        composeRule.onNodeWithTag("remote_connection_label").assertTextContains("RECONECTANDO")
        composeRule.onNodeWithTag("remote_pending_events").assertTextContains("2 série(s) aguardando rede")
    }

    @Test
    fun `sala encerrada diz que o treino continua, e some o botao de sair`() {
        composeRule.setContent {
            RemotePeerBanner(
                remote = state(MultiplayerConnection.ENDED, endReason = MultiplayerEndReason.ROOM_CLOSED),
                currentCanonicalExerciseId = null,
                currentExercisePosition = 1,
                onLeave = {}
            )
        }
        composeRule.onNodeWithTag("remote_peer_status").assertTextContains("Seu treino continua normalmente.", substring = true)
        composeRule.onNodeWithTag("remote_leave_button").assertDoesNotExist()
    }

    @Test
    fun `peer desconectado e peer que concluiu sao estados distintos, e sair chama o callback`() {
        var left = false
        composeRule.setContent {
            RemotePeerBanner(
                remote = state(MultiplayerConnection.CONNECTED, peer = PeerView("João", MultiplayerMemberStatus.FINISHED, false, PeerProgress(finished = true))),
                currentCanonicalExerciseId = null,
                currentExercisePosition = 1,
                onLeave = { left = true }
            )
        }
        composeRule.onNodeWithTag("remote_peer_status").assertTextContains("João: concluiu o treino.")
        composeRule.onNodeWithTag("remote_leave_button").assertIsDisplayed().performClick()
        assertTrue(left)
    }
}
