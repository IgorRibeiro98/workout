import re

# Fix ExerciseDetailsScreen
with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'r') as f:
    content = f.read()
content = content.replace("title = title", "title = exerciseInfo?.name ?: \"\"")
content = content.replace("resolvedMedia?.url", "resolvedMedia?.gifUrl")
with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'w') as f:
    f.write(content)

# Fix ExerciseExecutionCard
with open('app/src/main/java/com/example/presentation/exercises/components/premium/ExerciseExecutionCard.kt', 'r') as f:
    content = f.read()

new_exec = """package com.example.presentation.exercises.components.premium

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
"""
with open('app/src/main/java/com/example/presentation/exercises/components/premium/ExerciseExecutionCard.kt', 'w') as f:
    f.write(new_exec)

# Fix ExerciseMistakesCard
with open('app/src/main/java/com/example/presentation/exercises/components/premium/ExerciseMistakesCard.kt', 'r') as f:
    content = f.read()

new_mistakes = """package com.example.presentation.exercises.components.premium

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
"""
with open('app/src/main/java/com/example/presentation/exercises/components/premium/ExerciseMistakesCard.kt', 'w') as f:
    f.write(new_mistakes)

# Fix ExerciseTipsCard
with open('app/src/main/java/com/example/presentation/exercises/components/premium/ExerciseTipsCard.kt', 'r') as f:
    content = f.read()

new_tips = """package com.example.presentation.exercises.components.premium

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
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
fun ExerciseTipsCard(tipsJson: String?) {
    if (tipsJson.isNullOrEmpty()) return

    val tipsData = remember(tipsJson) {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(tipsJson)
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

    if (tipsData.isEmpty()) return

    PremiumSectionCard(
        title = "Dicas",
        icon = Icons.Default.Lightbulb
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tipsData.forEach { data ->
                Column {
                    if (data.first.isNotEmpty()) Text("💡 ${data.first}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (data.second.isNotEmpty()) Text(data.second, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, start = if(data.first.isNotEmpty()) 20.dp else 0.dp))
                }
            }
        }
    }
}
"""
with open('app/src/main/java/com/example/presentation/exercises/components/premium/ExerciseTipsCard.kt', 'w') as f:
    f.write(new_tips)

# Fix ExerciseSafetyCard
with open('app/src/main/java/com/example/presentation/exercises/components/premium/ExerciseSafetyCard.kt', 'r') as f:
    content = f.read()

new_safety = """package com.example.presentation.exercises.components.premium

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
"""
with open('app/src/main/java/com/example/presentation/exercises/components/premium/ExerciseSafetyCard.kt', 'w') as f:
    f.write(new_safety)
