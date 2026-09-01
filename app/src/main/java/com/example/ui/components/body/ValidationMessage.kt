package com.example.ui.components.body

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.Red400

@Composable
fun ValidationMessage(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = true,
    testTag: String = "validation_message"
) {
    val backgroundColor = if (isError) Red400.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    val borderColor = if (isError) Red400.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    val textColor = if (isError) Red400 else MaterialTheme.colorScheme.primary
    val icon = if (isError) Icons.Default.ErrorOutline else Icons.Default.Info

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = message,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp
        )
    }
}
