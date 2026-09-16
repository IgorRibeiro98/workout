package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.example.domain.model.IconKeys

/** Resolve uma chave de [IconKeys] no vetor Material correspondente; desconhecida → estrela neutra. */
fun semanticIcon(key: String?): ImageVector = when (key) {
    IconKeys.TROPHY -> Icons.Filled.EmojiEvents
    IconKeys.FIRE -> Icons.Filled.LocalFireDepartment
    IconKeys.MEDAL -> Icons.Filled.MilitaryTech
    IconKeys.SCALE -> Icons.Filled.MonitorWeight
    IconKeys.WORKOUT -> Icons.Filled.FitnessCenter
    IconKeys.TREND_UP -> Icons.Filled.TrendingUp
    IconKeys.TREND_DOWN -> Icons.Filled.TrendingDown
    IconKeys.RULER -> Icons.Filled.Straighten
    IconKeys.BOLT -> Icons.Filled.Bolt
    IconKeys.ROCKET -> Icons.Filled.RocketLaunch
    IconKeys.PLACE -> Icons.Filled.Place
    IconKeys.LOCK -> Icons.Filled.Lock
    else -> Icons.Filled.Star
}

/**
 * Um rótulo curto com ícone vetorial à esquerda — o substituto de `"🔥 4 semanas"` (T19.7B).
 *
 * O ícone herda a cor do texto, o que era exatamente o que o emoji **não** fazia: ele vinha da
 * fonte do sistema, com a paleta do sistema.
 */
@Composable
fun IconLabel(
    icon: ImageVector,
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Bold,
    iconTint: Color = color,
    iconSize: Dp = (fontSize.value + 3f).dp,
    contentDescription: String? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}
