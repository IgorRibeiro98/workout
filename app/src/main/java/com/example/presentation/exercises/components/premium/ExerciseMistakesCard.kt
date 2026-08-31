package com.example.presentation.exercises.components.premium

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
fun ExerciseMistakesCard(mistakesJson: String?) {
    if (mistakesJson.isNullOrEmpty()) return

    val mistakesData = remember(mistakesJson) {
        val list = mutableListOf<Triple<String, String, String>>()
        try {
            val arr = JSONArray(mistakesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    list.add(Triple(obj.optString("mistake"), obj.optString("why"), obj.optString("correction")))
                } else {
                    list.add(Triple(arr.getString(i), "", ""))
                }
            }
        } catch (e: Exception) {}
        list
    }

    if (mistakesData.isEmpty()) return

    PremiumSectionCard(
        title = "Erros Comuns",
        icon = Icons.Default.Warning
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            mistakesData.forEach { data ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (data.first.isNotEmpty()) Text("❌ ${data.first}", color = Red500, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (data.second.isNotEmpty()) Text("Motivo: ${data.second}", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp))
                    if (data.third.isNotEmpty()) Text("✅ ${data.third}", color = Lime400, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
                }
            }
        }
    }
}
