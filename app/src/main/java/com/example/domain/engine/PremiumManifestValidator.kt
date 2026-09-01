package com.example.domain.engine

import org.json.JSONArray
import org.json.JSONObject

data class PremiumAuditReport(
    val isValid: Boolean,
    val totalExercises: Int,
    val completeContentCount: Int,
    val withGifCount: Int,
    val withVideoCount: Int,
    val noMediaCount: Int,
    val errors: List<String>,
    val formattedReport: String
)

class PremiumManifestValidator {

    fun validateManifest(jsonString: String): PremiumAuditReport {
        val errors = mutableListOf<String>()
        var total = 0
        var completeCount = 0
        var gifCount = 0
        var videoCount = 0
        var noMediaCount = 0

        try {
            val root = JSONObject(jsonString)
            val exercisesArray = if (root.has("exercises")) root.getJSONArray("exercises") else JSONArray()
            total = exercisesArray.length()

            if (total == 0) {
                errors.add("Quantidade incorreta de exercícios: Encontrado 0.")
            }

            for (i in 0 until exercisesArray.length()) {
                val exObj = exercisesArray.getJSONObject(i)
                val id = exObj.optString("id", "índice_$i")

                var exIsValid = true

                // Check mandatory root fields
                val requiredFields = listOf(
                    "identity", "classification", "execution", "education",
                    "progression", "safety", "substitutions", "aiContext"
                )
                for (field in requiredFields) {
                    if (!exObj.has(field) || exObj.isNull(field)) {
                        errors.add("Exercício '$id': campo obrigatório '$field' ausente.")
                        exIsValid = false
                    }
                }

                // Check execution steps >= 3
                exObj.optJSONObject("execution")?.let { exec ->
                    val stepsArr = exec.optJSONArray("steps")
                    if (stepsArr == null || stepsArr.length() < 3) {
                        errors.add("Exercício '$id': número de passos (steps) inferior a 3 (encontrado: ${stepsArr?.length() ?: 0}).")
                        exIsValid = false
                    }
                }

                // Check education: tips >= 3, commonMistakes >= 3
                exObj.optJSONObject("education")?.let { edu ->
                    val tipsArr = edu.optJSONArray("tips")
                    if (tipsArr == null || tipsArr.length() < 3) {
                        errors.add("Exercício '$id': número de dicas (tips) inferior a 3 (encontrado: ${tipsArr?.length() ?: 0}).")
                        exIsValid = false
                    }
                    val mistakesArr = edu.optJSONArray("commonMistakes")
                    if (mistakesArr == null || mistakesArr.length() < 3) {
                        errors.add("Exercício '$id': número de erros comuns (commonMistakes) inferior a 3 (encontrado: ${mistakesArr?.length() ?: 0}).")
                        exIsValid = false
                    }
                }

                // Check safety: attentionPoints >= 2
                exObj.optJSONObject("safety")?.let { safety ->
                    val pointsArr = safety.optJSONArray("attentionPoints")
                    if (pointsArr == null || pointsArr.length() < 2) {
                        errors.add("Exercício '$id': pontos de atenção (attentionPoints) inferior a 2 (encontrado: ${pointsArr?.length() ?: 0}).")
                        exIsValid = false
                    }
                }

                if (exIsValid) {
                    completeCount++
                }

                // Media check
                var hasGif = false
                var hasVideo = false

                exObj.optJSONObject("media")?.let { media ->
                    media.optJSONObject("gif")?.let { gifObj ->
                        val url = gifObj.optString("url")
                        if (url.isNotEmpty() && url != "null") {
                            hasGif = true
                        }
                    }
                    val vArr = media.optJSONArray("videos")
                    if (vArr != null && vArr.length() > 0) {
                        hasVideo = true
                    }
                }

                if (hasGif) gifCount++
                if (hasVideo) videoCount++
                if (!hasGif && !hasVideo) noMediaCount++
            }
        } catch (e: Exception) {
            errors.add("Erro crítico ao parsear manifesto: ${e.message}")
        }

        val isValid = errors.isEmpty()

        val formattedReport = buildString {
            appendLine("Premium Library Audit")
            appendLine("")
            appendLine("Total:")
            appendLine("$total")
            appendLine("")
            appendLine("Com conteúdo completo:")
            appendLine("$completeCount")
            appendLine("")
            appendLine("Sem mídia:")
            appendLine("$noMediaCount")
            appendLine("")
            appendLine("Com GIF:")
            appendLine("$gifCount")
            appendLine("")
            appendLine("Com vídeo:")
            appendLine("$videoCount")
            appendLine("")
            appendLine("Erros:")
            appendLine("${errors.size}")
            if (errors.isNotEmpty()) {
                appendLine("")
                appendLine("Detalhes dos erros:")
                errors.take(10).forEach { err ->
                    appendLine("- $err")
                }
                if (errors.size > 10) {
                    appendLine("- ... e mais ${errors.size - 10} erros.")
                }
            }
        }

        return PremiumAuditReport(
            isValid = isValid,
            totalExercises = total,
            completeContentCount = completeCount,
            withGifCount = gifCount,
            withVideoCount = videoCount,
            noMediaCount = noMediaCount,
            errors = errors,
            formattedReport = formattedReport
        )
    }
}
