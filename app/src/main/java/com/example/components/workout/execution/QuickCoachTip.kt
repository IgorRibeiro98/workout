package com.example.components.workout.execution

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * Collapsed entry point to the coaching content for the current exercise.
 *
 * The tip used to occupy several lines in the middle of the set screen, which is the
 * space the controls need at larger font sizes. It is now a single row that opens the
 * quick-info sheet, where the same content reads better and has room to breathe.
 */
@Composable
fun QuickCoachTip(
    coachTip: String?,
    warningText: String?,
    onOpenQuickInfoSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (coachTip.isNullOrBlank() && warningText.isNullOrBlank()) return

    val hasWarning = !warningText.isNullOrBlank()

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Lime400.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenQuickInfoSheet)
            .testTag("quick_coach_tip")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                tint = Amber500,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Dica do Treinador",
                color = Amber500,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (hasWarning) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Há um ponto de atenção para este exercício",
                    tint = Red500,
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Ver tudo",
                color = Lime400,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Lime400,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
