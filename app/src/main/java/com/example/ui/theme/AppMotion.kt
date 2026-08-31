package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing

/**
 * Tokens centralizados de animação e transições (T10 / T10.2A)
 */
object AppMotion {
    // Duração recomendada das transições (em milissegundos)
    const val Fast = 150
    const val Normal = 220
    const val Emphasized = 300

    // Curvas de aceleração (Easings) recomendadas
    val StandardEasing: Easing = FastOutSlowInEasing
    val DecelerateEasing: Easing = LinearOutSlowInEasing
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
