package com.example.presentation.exercises.components.premium

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextSecondary

@Composable
fun ExerciseAboutCard(
    description: String?,
    primaryMuscles: String?,
    secondaryMuscles: String?,
    equipment: String?,
    difficulty: String?
) {
    if (description.isNullOrEmpty() && primaryMuscles.isNullOrEmpty() && equipment.isNullOrEmpty()) return

    PremiumSectionCard(
        title = "Sobre",
        icon = Icons.Default.Info
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!description.isNullOrEmpty()) {
                Text(description, color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (!primaryMuscles.isNullOrEmpty()) {
                val sec = if (!secondaryMuscles.isNullOrEmpty()) ", $secondaryMuscles" else ""
                Text("Músculos: $primaryMuscles$sec", color = TextSecondary, fontSize = 14.sp)
            }
            if (!equipment.isNullOrEmpty()) {
                Text("Equipamento: $equipment", color = TextSecondary, fontSize = 14.sp)
            }
            if (!difficulty.isNullOrEmpty()) {
                Text("Dificuldade: ${difficulty.replaceFirstChar { it.uppercase() }}", color = TextSecondary, fontSize = 14.sp)
            }
        }
    }
}
