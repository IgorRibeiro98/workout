package com.example.presentation.exercises.components.premium

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import org.json.JSONArray

@Composable
fun ExerciseSafetyCard(
    riskLevel: String?,
    attentionPointsJson: String?,
    discomfortsJson: String?
) {
    if (attentionPointsJson.isNullOrEmpty() && discomfortsJson.isNullOrEmpty()) return

    val attentionData = remember(attentionPointsJson) {
        val list = mutableListOf<Pair<String, String>>()
        if (!attentionPointsJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(attentionPointsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null) list.add(Pair(obj.optString("title"), obj.optString("description")))
                    else list.add(Pair("", arr.getString(i)))
                }
            } catch (e: Exception) {}
        }
        list
    }

    val discomfortsData = remember(discomfortsJson) {
        val list = mutableListOf<Triple<String, String, String>>()
        if (!discomfortsJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(discomfortsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null) list.add(Triple(obj.optString("location"), obj.optString("possibleCause"), obj.optString("adjustment")))
                    else list.add(Triple(arr.getString(i), "", ""))
                }
            } catch (e: Exception) {}
        }
        list
    }

    PremiumSectionCard(
        title = "Segurança",
        icon = Icons.Default.HealthAndSafety
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!riskLevel.isNullOrEmpty()) {
                val riskColor = if (riskLevel.equals("high", ignoreCase = true)) Red500 else Amber500
                Row {
                    Text("Nível de risco: ", color = TextSecondary, fontSize = 14.sp)
                    Text(riskLevel.uppercase(), color = riskColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            if (attentionData.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚠ Atenção", color = Amber500, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    attentionData.forEach { data ->
                        Column {
                            if (data.first.isNotEmpty()) Text("• ${data.first}", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            if (data.second.isNotEmpty()) Text(data.second, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }

            if (discomfortsData.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Desconfortos comuns", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    discomfortsData.forEach { data ->
                        Column {
                            if (data.first.isNotEmpty()) Text("📍 ${data.first}", color = TextSecondary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            if (data.second.isNotEmpty()) Text("Causa: ${data.second}", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp))
                            if (data.third.isNotEmpty()) Text("Ajuste: ${data.third}", color = Lime400, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp))
                        }
                    }
                }
            }
        }
    }
}
