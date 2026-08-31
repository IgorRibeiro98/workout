package com.example.presentation.exercises.components.premium

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.Lime400
import com.example.ui.theme.Red500
import org.json.JSONArray

@Composable
fun ExerciseSubstitutionCard(
    sameMovement: String?,
    sameMuscle: String?,
    notRecommended: String?
) {
    if (sameMovement.isNullOrEmpty() && sameMuscle.isNullOrEmpty()) return

    PremiumSectionCard(
        title = "Alternativas",
        icon = Icons.Default.SwapHoriz
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!sameMovement.isNullOrEmpty()) {
                val list = parseJsonArray(sameMovement)
                if (list.isNotEmpty()) {
                    Column {
                        Text("🔄 Mesmo movimento", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        list.forEach { item ->
                            Text("• $item", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                        }
                    }
                }
            }
            if (!sameMuscle.isNullOrEmpty()) {
                val list = parseJsonArray(sameMuscle)
                if (list.isNotEmpty()) {
                    Column {
                        Text("💪 Mesmo grupo muscular", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        list.forEach { item ->
                            Text("• $item", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                        }
                    }
                }
            }
            if (!notRecommended.isNullOrEmpty()) {
                val list = parseJsonArray(notRecommended)
                if (list.isNotEmpty()) {
                    Column {
                        Text("❌ Não recomendado", color = Red500, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        list.forEach { item ->
                            Text("• $item", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun parseJsonArray(json: String): List<String> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
}
