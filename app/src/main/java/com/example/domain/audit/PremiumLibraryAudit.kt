package com.example.domain.audit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AuditIssue(
    val exerciseId: String,
    val field: String,
    val issueType: String,
    val message: String
)

data class CategoryAuditResult(
    val category: String, // Peito, Costas, Pernas, Ombros, Braços, Abdômen
    val total: Int,
    val approved: Int,
    val needsReview: Int,
    val missingMedia: Int,
    val missingAlternatives: Int
)

data class MediaCoverageReport(
    val totalExercises: Int,
    val localGifCount: Int,
    val exerciseDbCount: Int,
    val youtubeCount: Int,
    val noMediaCount: Int
)

data class ExerciseAuditDetail(
    val id: String,
    val namePtBr: String,
    val nameEn: String,
    val category: String,
    val isApproved: Boolean,
    val stepsCount: Int,
    val tipsCount: Int,
    val mistakesCount: Int,
    val attentionPointsCount: Int,
    val hasAlternatives: Boolean,
    val mediaType: String,
    val issues: List<AuditIssue>
)

data class PremiumAuditReport(
    val isValid: Boolean,
    val totalExercises: Int,
    val approved: Int,
    val needsReview: Int,
    val missingMedia: Int,
    val missingAlternatives: Int,
    val mediaCoverage: MediaCoverageReport,
    val categoryBreakdown: List<CategoryAuditResult>,
    val contentIssues: List<AuditIssue>,
    val exerciseDetails: List<ExerciseAuditDetail>,
    val formattedReport: String
)

class PremiumLibraryAudit {

    fun auditAsset(context: Context, assetPath: String = "catalog/exercise-content-manifest.v2.json"): PremiumAuditReport {
        return try {
            val jsonString = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            auditManifest(jsonString)
        } catch (e: Exception) {
            val issue = AuditIssue("system", "asset", "FILE_READ_ERROR", "Erro ao carregar asset $assetPath: ${e.message}")
            PremiumAuditReport(
                isValid = false,
                totalExercises = 0,
                approved = 0,
                needsReview = 0,
                missingMedia = 0,
                missingAlternatives = 0,
                mediaCoverage = MediaCoverageReport(0, 0, 0, 0, 0),
                categoryBreakdown = emptyList(),
                contentIssues = listOf(issue),
                exerciseDetails = emptyList(),
                formattedReport = "Erro ao carregar asset $assetPath: ${e.message}"
            )
        }
    }

    fun auditManifest(jsonString: String): PremiumAuditReport {
        val contentIssues = mutableListOf<AuditIssue>()
        val exerciseDetails = mutableListOf<ExerciseAuditDetail>()

        var total = 0
        var approved = 0
        var needsReview = 0
        var missingMediaCount = 0
        var missingAlternativesCount = 0

        var localGifCount = 0
        var exerciseDbCount = 0
        var youtubeCount = 0
        var noMediaCount = 0

        val categoryStats = mutableMapOf<String, CategoryStats>()
        val defaultCategories = listOf("Peito", "Costas", "Pernas", "Ombros", "Braços", "Abdômen")
        for (cat in defaultCategories) {
            categoryStats[cat] = CategoryStats(cat)
        }

        try {
            val root = JSONObject(jsonString)
            val exercisesArray = if (root.has("exercises")) root.getJSONArray("exercises") else JSONArray()
            total = exercisesArray.length()

            for (i in 0 until exercisesArray.length()) {
                val exObj = exercisesArray.getJSONObject(i)
                val id = exObj.optString("id", "índice_$i")
                val exIssues = mutableListOf<AuditIssue>()
                var isApproved = true

                // Classification
                val classification = exObj.optJSONObject("classification")
                var cat = classification?.optString("category") ?: "Pernas"
                if (!defaultCategories.contains(cat)) {
                    cat = resolveCategoryFromMuscle(classification?.optJSONArray("primaryMuscles")?.optString(0) ?: "")
                }
                val catStat = categoryStats.getOrPut(cat) { CategoryStats(cat) }
                catStat.total++

                // Identity
                val identity = exObj.optJSONObject("identity")
                val namePtBr = identity?.optString("namePtBr") ?: ""
                val nameEn = identity?.optString("nameEn") ?: ""
                val shortDesc = identity?.optString("shortDescription") ?: ""

                if (namePtBr.isBlank()) {
                    val issue = AuditIssue(id, "identity.namePtBr", "MISSING_FIELD", "Nome PT-BR ausente")
                    exIssues.add(issue)
                    contentIssues.add(issue)
                    isApproved = false
                }
                if (nameEn.isBlank()) {
                    val issue = AuditIssue(id, "identity.nameEn", "MISSING_FIELD", "Nome EN ausente")
                    exIssues.add(issue)
                    contentIssues.add(issue)
                    isApproved = false
                }
                if (shortDesc.isBlank()) {
                    val issue = AuditIssue(id, "identity.shortDescription", "MISSING_FIELD", "Descrição curta ausente")
                    exIssues.add(issue)
                    contentIssues.add(issue)
                    isApproved = false
                }

                // Execution
                val execution = exObj.optJSONObject("execution")
                val stepsArr = execution?.optJSONArray("steps")
                val stepsCount = stepsArr?.length() ?: 0
                if (stepsCount < 3) {
                    val issue = AuditIssue(id, "execution.steps", "INSUFFICIENT_STEPS", "Passos de execução < 3 (encontrado: $stepsCount)")
                    exIssues.add(issue)
                    contentIssues.add(issue)
                    isApproved = false
                }

                // Education
                val education = exObj.optJSONObject("education")
                val tipsArr = education?.optJSONArray("tips")
                val tipsCount = tipsArr?.length() ?: 0
                if (tipsCount < 3) {
                    val issue = AuditIssue(id, "education.tips", "INSUFFICIENT_TIPS", "Dicas < 3 (encontrado: $tipsCount)")
                    exIssues.add(issue)
                    contentIssues.add(issue)
                    isApproved = false
                }

                val mistakesArr = education?.optJSONArray("commonMistakes")
                val mistakesCount = mistakesArr?.length() ?: 0
                if (mistakesCount < 3) {
                    val issue = AuditIssue(id, "education.commonMistakes", "INSUFFICIENT_MISTAKES", "Erros comuns < 3 (encontrado: $mistakesCount)")
                    exIssues.add(issue)
                    contentIssues.add(issue)
                    isApproved = false
                }

                // Safety
                val safety = exObj.optJSONObject("safety")
                val attentionPointsArr = safety?.optJSONArray("attentionPoints")
                val attentionPointsCount = attentionPointsArr?.length() ?: 0
                if (attentionPointsCount < 2) {
                    val issue = AuditIssue(id, "safety.attentionPoints", "INSUFFICIENT_ATTENTION_POINTS", "Pontos de atenção < 2 (encontrado: $attentionPointsCount)")
                    exIssues.add(issue)
                    contentIssues.add(issue)
                    isApproved = false
                }

                // Substitutions
                val substitutions = exObj.optJSONObject("substitutions")
                val sameMovement = substitutions?.optJSONArray("sameMovement")
                val hasAlternatives = sameMovement != null && sameMovement.length() > 0
                if (!hasAlternatives) {
                    missingAlternativesCount++
                    catStat.missingAlternatives++
                }

                // Media Coverage
                val media = exObj.optJSONObject("media")
                val extMappings = exObj.optJSONObject("externalMappings")

                var hasGif = false
                var hasVideo = false
                var hasExerciseDb = false

                val gifObj = media?.optJSONObject("gif")
                val gifUrl = gifObj?.optString("url") ?: media?.optString("gifUrl")
                if (!gifUrl.isNullOrEmpty() && gifUrl != "null") {
                    hasGif = true
                }

                val videosArr = media?.optJSONArray("videos") ?: media?.optJSONArray("youtubeVideoIds")
                if (videosArr != null && videosArr.length() > 0) {
                    hasVideo = true
                }

                val exDbId = extMappings?.optString("exerciseDbId") ?: media?.optString("exerciseDbId")
                val searchTerms = extMappings?.optJSONArray("searchTerms") ?: media?.optJSONArray("searchTerms")
                if (!exDbId.isNullOrEmpty() || (searchTerms != null && searchTerms.length() > 0)) {
                    hasExerciseDb = true
                }

                val mediaTypeStr = when {
                    hasGif -> {
                        localGifCount++
                        "Local GIF"
                    }
                    hasVideo -> {
                        youtubeCount++
                        "YouTube"
                    }
                    hasExerciseDb -> {
                        exerciseDbCount++
                        "ExerciseDB"
                    }
                    else -> {
                        noMediaCount++
                        missingMediaCount++
                        catStat.missingMedia++
                        "Sem mídia"
                    }
                }

                if (isApproved) {
                    approved++
                    catStat.approved++
                } else {
                    needsReview++
                    catStat.needsReview++
                }

                exerciseDetails.add(
                    ExerciseAuditDetail(
                        id = id,
                        namePtBr = namePtBr.ifEmpty { id },
                        nameEn = nameEn,
                        category = cat,
                        isApproved = isApproved,
                        stepsCount = stepsCount,
                        tipsCount = tipsCount,
                        mistakesCount = mistakesCount,
                        attentionPointsCount = attentionPointsCount,
                        hasAlternatives = hasAlternatives,
                        mediaType = mediaTypeStr,
                        issues = exIssues
                    )
                )
            }
        } catch (e: Exception) {
            val issue = AuditIssue("system", "manifest", "PARSE_ERROR", "Erro ao parsear manifesto: ${e.message}")
            contentIssues.add(issue)
        }

        val categoryList = categoryStats.values.map {
            CategoryAuditResult(
                category = it.category,
                total = it.total,
                approved = it.approved,
                needsReview = it.needsReview,
                missingMedia = it.missingMedia,
                missingAlternatives = it.missingAlternatives
            )
        }

        val mediaCoverage = MediaCoverageReport(
            totalExercises = total,
            localGifCount = localGifCount,
            exerciseDbCount = exerciseDbCount,
            youtubeCount = youtubeCount,
            noMediaCount = noMediaCount
        )

        val formattedReport = buildString {
            appendLine("Premium Library Audit — Relatório de Qualidade")
            appendLine("==========================================")
            appendLine("Exercícios Auditados: $total")
            appendLine("✓ Aprovados: $approved")
            appendLine("⚠ Revisão Necessária: $needsReview")
            appendLine("🎞 Sem Mídia: $missingMediaCount")
            appendLine("🔄 Sem Alternativas: $missingAlternativesCount")
            appendLine("")
            appendLine("Cobertura de Mídia:")
            appendLine("• GIF Local: $localGifCount")
            appendLine("• ExerciseDB Mapeado: $exerciseDbCount")
            appendLine("• YouTube Vídeos: $youtubeCount")
            appendLine("• Sem Mídia: $noMediaCount")
            appendLine("")
            appendLine("Categorias:")
            categoryList.forEach { cat ->
                appendLine("• ${cat.category}: ${cat.approved}/${cat.total} Aprovados | ${cat.missingMedia} sem mídia")
            }
            if (contentIssues.isNotEmpty()) {
                appendLine("")
                appendLine("Inconsistências Encontradas (${contentIssues.size}):")
                contentIssues.take(15).forEach { issue ->
                    appendLine("• [${issue.exerciseId}] ${issue.field}: ${issue.message}")
                }
            }
        }

        return PremiumAuditReport(
            isValid = contentIssues.isEmpty(),
            totalExercises = total,
            approved = approved,
            needsReview = needsReview,
            missingMedia = missingMediaCount,
            missingAlternatives = missingAlternativesCount,
            mediaCoverage = mediaCoverage,
            categoryBreakdown = categoryList,
            contentIssues = contentIssues,
            exerciseDetails = exerciseDetails,
            formattedReport = formattedReport
        )
    }

    private fun resolveCategoryFromMuscle(muscle: String): String {
        val m = muscle.lowercase()
        return when {
            m.contains("peito") || m.contains("peitoral") -> "Peito"
            m.contains("costas") || m.contains("dorsal") || m.contains("trapézio") || m.contains("eretores") -> "Costas"
            m.contains("ombro") || m.contains("deltoide") -> "Ombros"
            m.contains("bíceps") || m.contains("tríceps") || m.contains("braquial") || m.contains("antebraço") -> "Braços"
            m.contains("abdômen") || m.contains("core") || m.contains("oblíquo") -> "Abdômen"
            else -> "Pernas"
        }
    }

    private class CategoryStats(
        val category: String,
        var total: Int = 0,
        var approved: Int = 0,
        var needsReview: Int = 0,
        var missingMedia: Int = 0,
        var missingAlternatives: Int = 0
    )
}
