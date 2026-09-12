package com.example.presentation.exercises.components.premium

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

/**
 * O JSON do catálogo premium é conteúdo do app, e uma falha aqui é um defeito de conteúdo — não
 * algo que o usuário possa resolver. A seção some, e o motivo fica no Logcat em vez de
 * desaparecer: engolir a exceção calada era o que tornava "a dica sumiu" impossível de investigar.
 */
private const val PREMIUM_CARD_TAG = "PremiumExerciseCard"

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
        } catch (e: Exception) {
            android.util.Log.w(PREMIUM_CARD_TAG, "dicas do exercício com JSON inválido", e)
        }
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
