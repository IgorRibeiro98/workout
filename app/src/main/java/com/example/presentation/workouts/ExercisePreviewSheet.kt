package com.example.presentation.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.presentation.exercises.components.premium.ExerciseAboutCard
import com.example.presentation.exercises.components.premium.ExerciseHeroCard
import com.example.presentation.exercises.components.premium.PremiumSectionCard
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.theme.Lime400
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Pré-visualização de um exercício a partir do editor de treino (T19.6).
 *
 * É **leitura**: um bottom sheet por cima do editor, que fecha e devolve o usuário exatamente
 * onde estava — sem navegar para o catálogo, sem editar o exercício base e sem tocar na
 * configuração dele no treino. Tudo o que aparece vem do que o Spark já tem: o
 * [ResolvedTemplateExercise] que a lista do editor já resolveu (exercício canônico + override do
 * usuário, mídia pelo `ExerciseMediaResolver`) e a linha do template. Nenhum campo é inventado:
 * o que o exercício não tem simplesmente não aparece, e sem mídia o card diz que não há
 * demonstração em vez de mostrar um placeholder que pareça uma.
 *
 * Um exercício `CUSTOM` passa pelo mesmo caminho com o que tiver — normalmente só nome, músculo
 * e, às vezes, equipamento — e é identificado como personalizado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePreviewSheet(
    item: ResolvedTemplateExercise,
    onDismiss: () -> Unit
) {
    val exercise = item.resolvedExercise
    val raw = exercise.rawExercise
    val config = item.templateExercise
    val movementPattern = exercise.movementPattern?.replace("_", " ")

    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Pré-visualização",
        subtitle = exercise.displayName,
        headerRightContent = {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("exercise_preview_close")) {
                Icon(Icons.Default.Close, contentDescription = "Fechar pré-visualização", tint = TextSecondary)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("exercise_preview_sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ExerciseHeroCard(
                title = exercise.displayName,
                subtitle = exercise.nameEn ?: movementPattern,
                primaryMuscle = exercise.primaryMuscle,
                equipment = exercise.equipment,
                difficulty = raw.difficulty,
                mediaUrl = exercise.resolvedMedia.mediaUri,
                movementPattern = movementPattern,
                missingMediaHint = "Este exercício não tem demonstração cadastrada"
            )

            if (exercise.isUserCreated) {
                Text(
                    text = "Exercício personalizado — mostra o que você cadastrou.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("exercise_preview_custom_badge")
                )
            }

            ExerciseAboutCard(
                description = exercise.notes ?: raw.shortDescription,
                primaryMuscles = exercise.primaryMuscle,
                secondaryMuscles = exercise.secondaryMuscles.joinToString(", "),
                equipment = exercise.equipment,
                difficulty = raw.difficulty
            )

            // A configuração dele **neste treino** — o que o editor persiste, só para ler aqui.
            PremiumSectionCard(title = "Neste treino", icon = Icons.Default.FitnessCenter) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PreviewLine("Séries", "${config.targetSets}")
                    PreviewLine("Repetições", "${config.minReps}–${config.maxReps}")
                    PreviewLine("Descanso", "${config.restDurationSeconds}s")
                    config.plannedWeight?.let { PreviewLine("Carga planejada", "${it}kg") }
                    config.machineLabel?.takeIf { it.isNotBlank() }?.let { PreviewLine("Aparelho", it) }
                    config.notes?.takeIf { it.isNotBlank() }?.let { PreviewLine("Observações", it) }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = Lime400,
                    modifier = Modifier.width(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Para alterar séries, repetições ou descanso, use \"Editar configurações\" no editor.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = "$label: ", color = TextSecondary, fontSize = 14.sp)
        Text(text = value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
