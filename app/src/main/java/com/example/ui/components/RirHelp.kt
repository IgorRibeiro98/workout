package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.engine.RirFormatter
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Explains RIR without getting in the way.
 *
 * The execution screen speaks in effort ("💪 Pesado") and keeps the number as a secondary label;
 * a first-time user who wonders what "RIR 2" means taps the help icon and gets the definition
 * here. All copy comes from [RirFormatter] so every entry point says the same thing.
 */
@Composable
fun RirInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("rir_info_dialog"),
        title = {
            Text(
                text = RirFormatter.HELP_TITLE,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = RirFormatter.HELP_DEFINITION,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Surface(
                    color = BackgroundDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RirFormatter.HELP_SCALE.forEach { (level, meaning) ->
                            Column {
                                Text(
                                    text = level,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = meaning,
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                Text(
                    text = RirFormatter.HELP_FOOTNOTE,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("rir_info_dialog_confirm")
            ) {
                Text("Entendi", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp)
    )
}

/**
 * A label followed by a help icon that opens [RirInfoDialog]. Use it anywhere RIR is shown so the
 * concept is never presented without a way to learn what it means.
 */
@Composable
fun RirHelpLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = TextSecondary,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    fontWeight: FontWeight = FontWeight.Bold
) {
    var showHelp by remember { mutableStateOf(false) }

    if (showHelp) {
        RirInfoDialog(onDismiss = { showHelp = false })
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { showHelp = true }
            .padding(vertical = 2.dp, horizontal = 2.dp)
            .testTag("rir_help_label")
    ) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = RirFormatter.HELP_TITLE,
            tint = color,
            modifier = Modifier.size(13.dp)
        )
    }
}
