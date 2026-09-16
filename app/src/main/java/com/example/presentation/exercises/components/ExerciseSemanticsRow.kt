package com.example.presentation.exercises.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.engine.ExerciseVisual
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * A legenda da taxonomia visual (T19.7A), dita com palavras: o ícone é o equipamento, a cor é o
 * músculo. Aparece nos detalhes do exercício e, ao vivo, nos formulários de `CUSTOM` — quem digita
 * "Máquina" vê o ícone mudar antes de salvar.
 *
 * @param origin o rótulo de origem (`Catálogo Spark` / `Criado por você`), quando o contexto
 * precisa distinguir canônico de `CUSTOM`.
 */
@Composable
fun ExerciseSemanticsRow(
    visual: ExerciseVisual,
    modifier: Modifier = Modifier,
    origin: String? = null
) {
    Row(
        modifier = modifier.testTag("exercise_semantics_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = SurfaceHighlight, shape = RoundedCornerShape(8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.color,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = visual.equipmentFamily.displayName,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Surface(color = SurfaceHighlight, shape = RoundedCornerShape(8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(visual.color)
                )
                Text(
                    text = visual.muscleGroup.displayName,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (origin != null) {
            Text(
                text = origin,
                color = if (origin == EXERCISE_ORIGIN_CUSTOM) Lime400 else TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Rótulo de origem de um exercício do catálogo canônico. */
const val EXERCISE_ORIGIN_CATALOG = "Catálogo Spark"

/** Rótulo de origem de um exercício `CUSTOM`. */
const val EXERCISE_ORIGIN_CUSTOM = "Criado por você"

fun exerciseOriginLabel(isUserCreated: Boolean): String =
    if (isUserCreated) EXERCISE_ORIGIN_CUSTOM else EXERCISE_ORIGIN_CATALOG
