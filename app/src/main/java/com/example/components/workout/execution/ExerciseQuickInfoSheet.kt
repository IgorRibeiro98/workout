package com.example.components.workout.execution

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.presentation.exercises.PremiumExerciseInfo
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.theme.*
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseQuickInfoSheet(
    exerciseName: String,
    premiumInfo: PremiumExerciseInfo?,
    onOpenFullDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Dicas e Execução Rápida",
        subtitle = exerciseName
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("exercise_quick_info_sheet")
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                // 💡 Dica do Treinador / Dicas gerais
                val tipsList = parseJsonList(premiumInfo?.education?.tips)
                val coachNotes = parseJsonList(premiumInfo?.education?.coachNotes)

                if (tipsList.isNotEmpty() || coachNotes.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lightbulb,
                                        contentDescription = null,
                                        tint = Amber500,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Dica do Treinador",
                                        color = TextPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                (coachNotes + tipsList).take(3).forEach { tip ->
                                    Row(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text("• ", color = Amber500, fontWeight = FontWeight.Bold)
                                        Text(text = tip, color = TextPrimary, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // ⚠ Erros Comuns / Atenção
                val mistakesList = parseJsonList(premiumInfo?.education?.commonMistakes)
                val safetyPoints = parseJsonList(premiumInfo?.safety?.attentionPoints)

                if (mistakesList.isNotEmpty() || safetyPoints.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Red500,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Atenção e Erros Comuns",
                                        color = TextPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                (safetyPoints + mistakesList).take(3).forEach { point ->
                                    Row(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text("⚠ ", color = Red500)
                                        Text(text = point, color = TextPrimary, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // ▶ Passo a passo resumido da execução
                val stepsList = parseJsonList(premiumInfo?.execution?.steps)
                if (stepsList.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Lime400,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Passo a Passo de Execução",
                                        color = TextPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                stepsList.take(3).forEachIndexed { idx, step ->
                                    Row(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text("${idx + 1}. ", color = Lime400, fontWeight = FontWeight.Bold)
                                        Text(text = step, color = TextPrimary, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // DefaultFallback if no info
                if (tipsList.isEmpty() && coachNotes.isEmpty() && mistakesList.isEmpty() && stepsList.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Mantenha a postura correta e contração constante durante a execução.",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // CTA Button for Nível 3 Details
            Button(
                onClick = {
                    onDismiss()
                    onOpenFullDetails()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("VER DETALHES COMPLETOS", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

private fun parseJsonList(jsonStr: String?): List<String> {
    if (jsonStr.isNullOrBlank()) return emptyList()
    val trimmed = jsonStr.trim()
    return try {
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i)
                if (obj != null) {
                    list.add(extractFirstTextFromJson(obj))
                } else {
                    val item = array.getString(i)
                    list.add(parseRawStringOrJson(item))
                }
            }
            list
        } else if (trimmed.startsWith("{")) {
            listOf(extractFirstTextFromJson(org.json.JSONObject(trimmed)))
        } else {
            listOf(trimmed)
        }
    } catch (e: Exception) {
        listOf(trimmed)
    }
}

private fun extractFirstTextFromJson(obj: org.json.JSONObject): String {
    val mistake = obj.optString("mistake").takeIf { !it.isNullOrBlank() }
    val reason = obj.optString("reason").takeIf { !it.isNullOrBlank() }
    if (mistake != null && reason != null) {
        return "$mistake ($reason)"
    }
    val keys = listOf("mistake", "point", "tip", "text", "title", "description", "note", "instruction", "reason")
    for (key in keys) {
        val value = obj.optString(key)
        if (!value.isNullOrBlank()) return value
    }
    return obj.toString()
}

private fun parseRawStringOrJson(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{")) {
        return try {
            extractFirstTextFromJson(org.json.JSONObject(trimmed))
        } catch (e: Exception) {
            trimmed
        }
    }
    return trimmed
}
