package com.example.presentation.exercises.components.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ExerciseExecutionCard(
    setupJson: String?,
    stepsJson: String?,
    breathingJson: String?
) {
    if (setupJson.isNullOrEmpty() && stepsJson.isNullOrEmpty() && breathingJson.isNullOrEmpty()) return

    PremiumSectionCard(
        title = "Como Executar",
        icon = Icons.Default.PlayArrow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Setup
            if (!setupJson.isNullOrEmpty()) {
                val setupData = remember(setupJson) {
                    try {
                        val obj = JSONObject(setupJson)
                        Pair(obj.optString("title"), obj.optString("description"))
                    } catch (e: Exception) {
                        Pair("", setupJson)
                    }
                }
                if (setupData.first.isNotEmpty() || setupData.second.isNotEmpty()) {
                    Column {
                        if (setupData.first.isNotEmpty()) {
                            Text(setupData.first, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        if (setupData.second.isNotEmpty()) {
                            Text(setupData.second, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            // Steps
            if (!stepsJson.isNullOrEmpty()) {
                val stepsData = remember(stepsJson) {
                    val list = mutableListOf<Pair<String, String>>()
                    try {
                        val arr = JSONArray(stepsJson)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i)
                            if (obj != null) {
                                list.add(Pair(obj.optString("title"), obj.optString("description")))
                            } else {
                                list.add(Pair("", arr.getString(i)))
                            }
                        }
                    } catch (e: Exception) {}
                    list
                }
                stepsData.forEachIndexed { i, step ->
                    StepItem(stepNumber = i + 1, title = step.first, description = step.second)
                }
            }

            // Breathing
            if (!breathingJson.isNullOrEmpty()) {
                val breathData = remember(breathingJson) {
                    try {
                        val obj = JSONObject(breathingJson)
                        Pair(obj.optString("eccentric"), obj.optString("concentric"))
                    } catch (e: Exception) {
                        Pair("", breathingJson)
                    }
                }
                if (breathData.first.isNotEmpty() || breathData.second.isNotEmpty()) {
                    Column {
                        Text("Respiração", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (breathData.first.isNotEmpty()) Text("• Excêntrica: ${breathData.first}", color = TextSecondary, fontSize = 14.sp)
                        if (breathData.second.isNotEmpty()) Text("• Concêntrica: ${breathData.second}", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(stepNumber: Int, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 2.dp, end = 12.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(SurfaceHighlight)
        ) {
            Text(
                text = stepNumber.toString(),
                color = Lime400,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column {
            if (title.isNotEmpty()) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (description.isNotEmpty()) {
                Text(description, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
