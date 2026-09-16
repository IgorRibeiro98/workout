package com.example.presentation.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.Orange400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextSecondary

/**
 * O aviso mínimo de capability para as telas do Coach (T19.0).
 *
 * Não renderiza nada para [CoachActionAvailability.ALLOWED] e [CoachActionAvailability.DETERMINING]
 * — a segunda é deliberada: carregando ou sem conta não é motivo para um aviso, é o silêncio de
 * sempre até a tela ter algo definitivo a dizer. Só [CoachActionAvailability.DENIED] e
 * [CoachActionAvailability.UNKNOWN] produzem um card, e o backend continua sendo a autoridade em
 * qualquer um dos quatro estados: este componente só existe para a tela não convidar uma ação que
 * o próprio servidor recusaria.
 */
@Composable
fun CoachCapabilityNotice(
    availability: CoachActionAvailability,
    deniedMessage: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    when (availability) {
        CoachActionAvailability.DENIED -> Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(text = deniedMessage, color = TextSecondary, fontSize = 13.sp)
            }
        }

        CoachActionAvailability.UNKNOWN -> Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Orange400,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Não foi possível confirmar a disponibilidade deste recurso agora.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
                TextButton(onClick = onRetry) {
                    Text(text = "Tentar de novo", color = Lime400, fontSize = 13.sp)
                }
            }
        }

        CoachActionAvailability.ALLOWED, CoachActionAvailability.DETERMINING -> Unit
    }
}
