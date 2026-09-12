package com.example.presentation.gamification.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun AchievementUnlockFeedback(
    title: String,
    description: String,
    icon: String,
    onAnimationEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // O estado e o efeito são presos ao conteúdo anunciado porque este componente vive num slot
    // fixo de composição alimentado por uma fila: quando o primeiro item sai, o segundo reaproveita
    // o mesmo slot. Sem chave, ele herdaria `isVisible = false` e um `LaunchedEffect` já
    // consumido — nunca apareceria, `onAnimationEnd` nunca seria chamado e a fila travaria para
    // sempre. Quem chama também envolve a chamada em `key(id)`: dois desbloqueios diferentes podem
    // ter textos iguais, e só o id os distingue.
    var isVisible by remember(title, description, icon) { mutableStateOf(true) }

    LaunchedEffect(title, description, icon) {
        delay(4000)
        isVisible = false
        delay(500)
        onAnimationEnd()
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(500)),
        exit = fadeOut(animationSpec = tween(500)) + slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(500)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column {
                Text(
                    text = "🏆 CONQUISTA DESBLOQUEADA",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = "$icon $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}
