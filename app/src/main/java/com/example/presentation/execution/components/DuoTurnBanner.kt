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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.WorkoutParticipantRole
import com.example.domain.workout.execution.DuoExecution
import com.example.domain.workout.execution.DuoTurn
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * Quem está na vez, quem vem depois, e o descanso de quem está esperando (T19.4).
 *
 * A tela não decide nada aqui: a vez vem de [DuoTurn], resolvido no domínio a partir das séries
 * persistidas; os dois relógios são timestamps (`ownerRestTarget` do temporizador do aparelho,
 * `duo.guest.restEndsAt` do convidado) e o único estado local é o "agora" que faz a contagem
 * andar. Uma recomposição, uma rotação ou uma morte de processo não mudam nenhum dos dois.
 */
@Composable
fun DuoTurnBanner(
    duo: DuoExecution,
    turn: DuoTurn?,
    ownerRestTarget: Long?,
    isExerciseCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    val guestRestTarget = duo.guest.restEndsAt
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ownerRestTarget, guestRestTarget) {
        while (true) {
            now = System.currentTimeMillis()
            val anyRunning = listOfNotNull(ownerRestTarget, guestRestTarget).any { it > now }
            if (!anyRunning) break
            delay(1000)
        }
    }

    val currentRole = turn?.role ?: WorkoutParticipantRole.OWNER
    val currentLabel = when {
        turn != null -> duo.label(turn.role)
        isExerciseCompleted -> "Exercício concluído pelos dois"
        else -> duo.ownerLabel
    }
    val nextLabel = turn?.nextRole?.let { duo.label(it) }
    val waitingRole = if (currentRole == WorkoutParticipantRole.OWNER) WorkoutParticipantRole.GUEST else WorkoutParticipantRole.OWNER
    val waitingRestTarget = if (waitingRole == WorkoutParticipantRole.OWNER) ownerRestTarget else guestRestTarget
    val waitingRemaining = waitingRestTarget?.let { ((it - now) / 1000).coerceAtLeast(0) } ?: 0L
    val waitingStatus = if (waitingRemaining > 0) {
        "descansando ${formatRemaining(waitingRemaining)}"
    } else {
        "pronto"
    }

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier
            .fillMaxWidth()
            .testTag("duo_turn_banner")
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (turn != null) "VEZ DE" else "DUPLA",
                        color = Lime400,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = currentLabel,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("duo_current_participant")
                    )
                }
                if (nextLabel != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "DEPOIS",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = nextLabel,
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("duo_next_participant")
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${duo.label(waitingRole)}: $waitingStatus",
                color = if (waitingRemaining > 0) Lime400 else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("duo_waiting_status")
            )
        }
    }
}

private fun formatRemaining(seconds: Long): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return "%d:%02d".format(minutes, rest)
}
