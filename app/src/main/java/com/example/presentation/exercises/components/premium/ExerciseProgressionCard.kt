package com.example.presentation.exercises.components.premium

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.Lime400

@Composable
fun ExerciseProgressionCard(
    method: String?,
    repRange: String?,
    rule: String?,
    sets: Int?,
    incUpper: Double?,
    incLower: Double?
) {
    if (repRange.isNullOrEmpty() && rule.isNullOrEmpty()) return

    PremiumSectionCard(
        title = "Progressão",
        icon = Icons.Default.TrendingUp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!method.isNullOrEmpty()) {
                Text("Método: $method", color = TextSecondary, fontSize = 14.sp)
            }
            if (!repRange.isNullOrEmpty()) {
                Text("Faixa ideal: ", color = TextSecondary, fontSize = 14.sp)
                Text(repRange, color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (sets != null) {
                Text("Séries recomendadas: $sets", color = TextSecondary, fontSize = 14.sp)
            }
            if (!rule.isNullOrEmpty()) {
                Text("Quando evoluir:", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Text(rule, color = TextSecondary, fontSize = 14.sp)
            }
            if (incUpper != null || incLower != null) {
                Text("Incremento sugerido:", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                if (incUpper != null) Text("• Membros superiores: +${incUpper}kg", color = TextSecondary, fontSize = 14.sp)
                if (incLower != null) Text("• Membros inferiores: +${incLower}kg", color = TextSecondary, fontSize = 14.sp)
            }
        }
    }
}
