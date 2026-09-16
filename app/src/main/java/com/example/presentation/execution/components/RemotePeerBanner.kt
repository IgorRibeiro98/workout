package com.example.presentation.execution.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.multiplayer.MultiplayerConnection
import com.example.domain.multiplayer.MultiplayerEndReason
import com.example.domain.multiplayer.MultiplayerMemberStatus
import com.example.domain.multiplayer.MultiplayerSessionState
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * O outro aparelho, visto deste (T19.5).
 *
 * Três linhas: o estado da **conexão** (nunca do treino), quem é o peer e o que ele já fez no
 * exercício em foco. Tudo aqui é derivado de [MultiplayerSessionState]; a tela não decide nada
 * — e, sobretudo, nada daqui muda a série, o descanso ou a fase de quem está olhando. "Reconectando"
 * e "sessão em dupla perdida" são sobre a sala; o treino local continua idêntico embaixo.
 */
@Composable
fun RemotePeerBanner(
    remote: MultiplayerSessionState,
    currentCanonicalExerciseId: String?,
    currentExercisePosition: Int,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val peer = remote.peer
    val connectionLabel = when (remote.connection) {
        MultiplayerConnection.CONNECTING -> "CONECTANDO"
        MultiplayerConnection.CONNECTED -> if (remote.isWaitingForPeer) "AGUARDANDO" else "EM DUPLA"
        MultiplayerConnection.RECONNECTING -> "RECONECTANDO"
        MultiplayerConnection.ENDED -> "SESSÃO EM DUPLA ENCERRADA"
    }
    val connectionColor = when (remote.connection) {
        MultiplayerConnection.CONNECTED -> Lime400
        MultiplayerConnection.CONNECTING, MultiplayerConnection.RECONNECTING -> Color(0xFFF59E0B)
        MultiplayerConnection.ENDED -> TextSecondary
    }

    val peerLine: String = when {
        remote.connection == MultiplayerConnection.ENDED -> when (remote.endReason) {
            MultiplayerEndReason.LEFT -> "Você saiu da sala. Seu treino continua normalmente."
            MultiplayerEndReason.ROOM_CLOSED -> "A sala foi encerrada. Seu treino continua normalmente."
            MultiplayerEndReason.ROOM_EXPIRED -> "A sala expirou. Seu treino continua normalmente."
            MultiplayerEndReason.OTHER_ACCOUNT -> "Esta sala é de outra conta. Seu treino continua normalmente."
            MultiplayerEndReason.AUTH_REQUIRED -> "Entre na sua conta para voltar à sala. Seu treino continua."
            MultiplayerEndReason.NOT_CONFIGURED -> "Treino em dupla à distância indisponível neste build."
            MultiplayerEndReason.ROOM_NOT_FOUND, MultiplayerEndReason.REJECTED, null ->
                "Sessão em dupla perdida. Seu treino continua normalmente."
        }
        peer == null -> "Aguardando o outro participante entrar."
        peer.status == MultiplayerMemberStatus.INVITED -> "${peer.displayName}: ainda não entrou."
        peer.status == MultiplayerMemberStatus.LEFT -> "${peer.displayName}: saiu da sala."
        peer.status == MultiplayerMemberStatus.FINISHED -> "${peer.displayName}: concluiu o treino."
        !peer.connected -> "${peer.displayName}: desconectado."
        else -> {
            val progress = peer.progress.forExercise(currentCanonicalExerciseId, currentExercisePosition)
            val current = peer.progress.currentExercise
            when {
                !peer.progress.started -> "${peer.displayName}: ainda não começou."
                progress != null -> "${peer.displayName}: ${progress.completedCount}/${progress.setCount} séries neste exercício."
                current != null -> "${peer.displayName}: no exercício ${current.exercisePosition}, ${current.completedCount}/${current.setCount} séries."
                else -> "${peer.displayName}: começou o treino."
            }
        }
    }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote_peer_banner")
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connectionLabel,
                        color = connectionColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.testTag("remote_connection_label")
                    )
                    Text(
                        text = peer?.displayName ?: "Dupla à distância",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("remote_peer_name")
                    )
                }
                if (remote.connection != MultiplayerConnection.ENDED) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onLeave, modifier = Modifier.testTag("remote_leave_button")) {
                        Text("SAIR DA SALA", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = peerLine,
                color = if (peer?.connected == true && remote.isLive) Lime400 else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("remote_peer_status")
            )
            if (remote.pendingLocalEvents > 0 && remote.connection != MultiplayerConnection.ENDED) {
                Text(
                    text = "${remote.pendingLocalEvents} série(s) aguardando rede",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("remote_pending_events")
                )
            }
        }
    }
}
