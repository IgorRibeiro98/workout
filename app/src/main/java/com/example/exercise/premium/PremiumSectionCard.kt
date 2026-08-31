package com.example.exercise.premium

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun PremiumSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    com.example.presentation.exercises.components.premium.PremiumSectionCard(
        title = title,
        icon = icon,
        modifier = modifier,
        content = content
    )
}
