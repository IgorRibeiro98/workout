package com.example.presentation.exercises.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.CustomExerciseFields
import com.example.domain.engine.ExerciseVisualResolver
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * O formulário de um exercício `CUSTOM` (T19.7C) — o mesmo para criar e para editar.
 *
 * Só expõe o que **é** o exercício: nome (obrigatório, a única obrigatoriedade real da entidade),
 * músculo principal, equipamento e descrição. Séries, repetições, descanso e carga são de
 * `WorkoutTemplateExercise`, e o rodapé diz isso para quem vier procurá-los aqui.
 *
 * A legenda visual acompanha a digitação: músculo decide a cor, equipamento decide o ícone —
 * a mesma regra que o catálogo usa ([ExerciseVisualResolver]).
 *
 * Um toque em salvar fecha o formulário e o desarma: o segundo toque de um "double tap" não
 * chega a `onSave`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomExerciseFormSheet(
    title: String,
    subtitle: String?,
    saveLabel: String,
    onDismiss: () -> Unit,
    onSave: (name: String, muscle: String?, equipment: String?, description: String?) -> Unit,
    initialName: String = "",
    initialMuscle: String = "",
    initialEquipment: String = "",
    initialDescription: String = ""
) {
    var name by remember { mutableStateOf(initialName) }
    var muscle by remember { mutableStateOf(initialMuscle) }
    var equipment by remember { mutableStateOf(initialEquipment) }
    var description by remember { mutableStateOf(initialDescription) }
    var submitted by remember { mutableStateOf(false) }

    val canSave = CustomExerciseFields.isValidName(name) && !submitted
    val visual = ExerciseVisualResolver.resolve(primaryMuscle = muscle, equipment = equipment)

    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = title,
        subtitle = subtitle
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome do exercício *") },
                supportingText = { Text("Obrigatório", color = TextSecondary, fontSize = 11.sp) },
                singleLine = true,
                isError = name.isNotEmpty() && !CustomExerciseFields.isValidName(name),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_exercise_name"),
                colors = fieldColors()
            )
            OutlinedTextField(
                value = muscle,
                onValueChange = { muscle = it },
                label = { Text("Músculo principal") },
                placeholder = { Text("Ex: Peitoral, Costas, Quadríceps") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_exercise_muscle"),
                colors = fieldColors()
            )
            OutlinedTextField(
                value = equipment,
                onValueChange = { equipment = it },
                label = { Text("Equipamento") },
                placeholder = { Text("Ex: Halteres, Barra, Máquina, Cabo, Peso corporal") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_exercise_equipment"),
                colors = fieldColors()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Descrição") },
                placeholder = { Text("Como executar, ajustes, o que observar") },
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_exercise_description"),
                colors = fieldColors()
            )

            ExerciseSemanticsRow(visual = visual)

            Text(
                text = "Séries, repetições, descanso e carga não são do exercício: você define isso em cada treino, no editor.",
                color = TextSecondary,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    if (!canSave) return@Button
                    submitted = true
                    onSave(
                        name,
                        CustomExerciseFields.optional(muscle),
                        CustomExerciseFields.optional(equipment),
                        CustomExerciseFields.optional(description)
                    )
                    onDismiss()
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("custom_exercise_save")
            ) {
                Text(saveLabel, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Lime400,
    unfocusedBorderColor = BorderLight,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)
