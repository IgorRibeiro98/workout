package com.example.domain.engine

import android.content.Context
import androidx.room.withTransaction
import com.example.data.datastore.SettingsManager
import com.example.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PremiumManifestImporter(
    private val database: AppDatabase,
    private val context: Context,
    private val settingsManager: SettingsManager = SettingsManager(context)
) {
    private val dao = database.workoutDao()

    suspend fun importFromAssets(
        assetPath: String = "catalog/exercise-content-manifest.v2.json",
        force: Boolean = false
    ): ImportResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        var added = 0
        var updated = 0
        var ignored = 0
        var gifCount = 0
        var videoCount = 0
        var noMediaCount = 0
        var auditReport: PremiumAuditReport? = null

        try {
            val jsonString = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            
            // Validate schemaVersion
            if (root.has("schemaVersion") && root.getInt("schemaVersion") < 1) {
                errors.add("Versão de schema inválida.")
                return@withContext ImportResult(errors = errors)
            }
            
            val validator = PremiumManifestValidator()
            val report = validator.validateManifest(jsonString)
            auditReport = report
            
            val contentVersion = root.optInt("contentVersion", 2)
            
            if (!force) {
                val currentVersion = settingsManager.installedCatalogContentVersionFlow.first()
                if (currentVersion >= contentVersion) {
                    return@withContext ImportResult(
                        ignored = root.optJSONArray("exercises")?.length() ?: 0,
                        unchanged = root.optJSONArray("exercises")?.length() ?: 0,
                        isSkippedSameVersion = true,
                        formattedReport = report.formattedReport
                    )
                }
            }
            
            val exercisesArray = if (root.has("exercises")) root.getJSONArray("exercises") else JSONArray().put(root)
            
            // Validation step
            for (i in 0 until exercisesArray.length()) {
                val exObj = exercisesArray.getJSONObject(i)
                val id = exObj.optString("id")
                if (id.isEmpty()) {
                    errors.add("Exercício no índice $i: campo id ausente.")
                    continue
                }
                
                val identity = exObj.optJSONObject("identity")
                if (identity == null) {
                    errors.add("Exercício: $id Problema: campo identity ausente.")
                    continue
                }
                val namePtBr = identity.optString("namePtBr")
                if (namePtBr.isEmpty()) {
                    errors.add("Exercício: $id Problema: campo identity.namePtBr ausente.")
                    continue
                }
                val nameEn = identity.optString("nameEn")
                if (nameEn.isEmpty()) {
                    errors.add("Exercício: $id Problema: campo identity.nameEn ausente.")
                    continue
                }
                
                val classification = exObj.optJSONObject("classification")
                if (classification == null) {
                    errors.add("Exercício: $id Problema: campo classification ausente.")
                    continue
                }
                
                val execution = exObj.optJSONObject("execution")
                if (execution == null) {
                    errors.add("Exercício: $id Problema: campo execution ausente.")
                    continue
                }
            }
            
            if (errors.isNotEmpty()) {
                return@withContext ImportResult(errors = errors)
            }
            
            database.withTransaction {
                for (i in 0 until exercisesArray.length()) {
                    val exObj = exercisesArray.getJSONObject(i)
                    val id = exObj.optString("id")
                    
                    val existing = dao.getExerciseByCanonicalId(id)
                    var exerciseId: Long = 0

                    val schemaVersion = root.optInt("schemaVersion", 2)

                    val identity = exObj.optJSONObject("identity")
                    val namePtBr = identity?.optString("namePtBr") ?: continue
                    val nameEn = identity?.optString("nameEn")
                    val shortDescription = identity?.optString("shortDescription")
                    val aliases = identity?.optJSONArray("aliases")?.let { arr -> 
                        (0 until arr.length()).map { arr.getString(it) }.joinToString(",")
                    }
                    
                    val classification = exObj.optJSONObject("classification")
                    val category = classification?.optString("category")
                    val difficulty = classification?.optString("difficulty")
                    val movementPattern = classification?.optString("movementPattern")
                    val exerciseType = classification?.optString("exerciseType")
                    
                    var primaryMuscle = ""
                    var secondaryMuscles: String? = null
                    var equipment = ""
                    val bodyRegion = classification?.optString("bodyRegion")
                    val trainingGoals = classification?.optJSONArray("trainingGoals")?.toString()

                    if (schemaVersion >= 2) {
                        primaryMuscle = classification?.optJSONArray("primaryMuscles")?.optString(0) ?: ""
                        secondaryMuscles = classification?.optJSONArray("secondaryMuscles")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }.joinToString(",")
                        }
                        equipment = classification?.optJSONArray("equipment")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }.joinToString(",")
                        } ?: ""
                    } else {
                        primaryMuscle = classification?.optString("primaryMuscle") ?: ""
                        if (primaryMuscle.isBlank()) {
                            primaryMuscle = classification?.optJSONArray("primaryMuscles")?.optString(0) ?: ""
                        }
                        secondaryMuscles = classification?.optJSONArray("secondaryMuscles")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }.joinToString(",")
                        }
                        equipment = classification?.optString("equipment") ?: ""
                    }

                    if (existing != null) {
                        val updatedEx = existing.copy(
                            name = namePtBr,
                            nameEn = nameEn ?: existing.nameEn,
                            shortDescription = shortDescription ?: existing.shortDescription,
                            aliases = aliases ?: existing.aliases,
                            category = category ?: existing.category,
                            difficulty = difficulty ?: existing.difficulty,
                            movementPattern = movementPattern ?: existing.movementPattern,
                            exerciseType = exerciseType ?: existing.exerciseType,
                            primaryMuscle = primaryMuscle.ifBlank { existing.primaryMuscle },
                            secondaryMuscles = secondaryMuscles ?: existing.secondaryMuscles,
                            equipment = equipment.ifBlank { existing.equipment },
                            contentVersion = contentVersion,
                            bodyRegion = bodyRegion ?: existing.bodyRegion,
                            trainingGoals = trainingGoals ?: existing.trainingGoals
                        )
                        dao.updateExercise(updatedEx)
                        exerciseId = existing.id
                        updated++
                    } else {
                        val newEx = ExerciseEntity(
                            name = namePtBr,
                            nameEn = nameEn,
                            canonicalId = id,
                            shortDescription = shortDescription,
                            aliases = aliases,
                            category = category,
                            difficulty = difficulty,
                            movementPattern = movementPattern,
                            exerciseType = exerciseType,
                            primaryMuscle = primaryMuscle,
                            secondaryMuscles = secondaryMuscles,
                            equipment = equipment,
                            active = true,
                            contentVersion = contentVersion,
                            bodyRegion = bodyRegion,
                            trainingGoals = trainingGoals
                        )
                        exerciseId = dao.insertExercise(newEx)
                        added++
                    }

                    // Biomechanics
                    exObj.optJSONObject("biomechanics")?.let { bio ->
                        val entity = ExerciseBiomechanicsEntity(
                            exerciseId = exerciseId,
                            jointActions = bio.optJSONArray("jointActions")?.toString(),
                            rangeOfMotion = bio.optString("rangeOfMotion"),
                            stabilityDemand = bio.optString("stabilityDemand"),
                            targetFeeling = bio.optString("targetFeeling")
                        )
                        dao.insertExerciseBiomechanics(entity)
                    }

                    // Execution
                    exObj.optJSONObject("execution")?.let { exec ->
                        val entity = ExerciseExecutionEntity(
                            exerciseId = exerciseId,
                            setup = if (schemaVersion >= 2) exec.optJSONObject("setup")?.toString() else exec.optString("setup"),
                            steps = exec.optJSONArray("steps")?.toString(),
                            breathing = if (schemaVersion >= 2) exec.optJSONObject("breathing")?.toString() else exec.optString("breathing")
                        )
                        dao.insertExerciseExecution(entity)
                    }

                    // Education
                    exObj.optJSONObject("education")?.let { edu ->
                        val entity = ExerciseEducationEntity(
                            exerciseId = exerciseId,
                            tips = edu.optJSONArray("tips")?.toString(),
                            commonMistakes = edu.optJSONArray("commonMistakes")?.toString(),
                            coachNotes = if (schemaVersion >= 2) edu.optString("coachNote") else edu.optJSONArray("coachNotes")?.toString()
                        )
                        dao.insertExerciseEducation(entity)
                    }

                    // Media
                    var hasGif = false
                    var hasVideo = false
                    exObj.optJSONObject("media")?.let { media ->
                        var gifUrl: String? = media.optString("gifUrl")
                        var gifSource: String? = null
                        var videos = media.optJSONArray("youtubeVideoIds")?.toString()
                        var images = media.optJSONArray("imageUrls")?.toString()
                        
                        if (schemaVersion >= 2) {
                            media.optJSONObject("gif")?.let {
                                val url = it.optString("url")
                                if (url.isNotEmpty() && url != "null") {
                                    gifUrl = url
                                    gifSource = it.optString("source")
                                } else {
                                    gifUrl = null
                                }
                            }
                            val vArr = media.optJSONArray("videos")
                            if (vArr != null && vArr.length() > 0) {
                                videos = vArr.toString()
                            }
                            images = media.optJSONArray("images")?.toString()
                        }

                        if (!gifUrl.isNullOrEmpty() && gifUrl != "null") {
                            hasGif = true
                        }
                        if (!videos.isNullOrEmpty() && videos != "null" && videos != "[]") {
                            hasVideo = true
                        }
                        
                        val entity = ExerciseMediaEntity(
                            exerciseId = exerciseId,
                            exerciseDbId = media.optString("exerciseDbId"),
                            youtubeVideoIds = if (schemaVersion < 2) videos else null,
                            gifUrl = gifUrl,
                            imageUrls = images,
                            gifSource = gifSource,
                            videos = if (schemaVersion >= 2) videos else null
                        )
                        dao.insertExerciseMedia(entity)
                    }

                    if (hasGif) gifCount++
                    if (hasVideo) videoCount++
                    if (!hasGif && !hasVideo) noMediaCount++

                    // Progression
                    exObj.optJSONObject("progression")?.let { prog ->
                        var repRange = prog.optString("repRange")
                        var repMin: Int? = null
                        var repMax: Int? = null
                        var incUpper: Double? = null
                        var incLower: Double? = null
                        var rule = prog.optString("increaseRule")
                        var stdSets = if (prog.has("standardSets")) prog.getInt("standardSets") else null
                        var method = prog.optString("progressionMethod")
                        
                        if (schemaVersion >= 2) {
                            prog.optJSONObject("repRange")?.let {
                                repMin = if (it.has("min")) it.getInt("min") else null
                                repMax = if (it.has("max")) it.getInt("max") else null
                                repRange = "${repMin}-${repMax}"
                            }
                            prog.optJSONObject("increment")?.let {
                                incUpper = if (it.has("upperBody")) it.getDouble("upperBody") else null
                                incLower = if (it.has("lowerBody")) it.getDouble("lowerBody") else null
                            }
                            rule = prog.optString("progressionRule")
                            stdSets = if (prog.has("setsRecommendation")) prog.getInt("setsRecommendation") else null
                            method = prog.optString("method")
                        }
                    
                        val entity = ExerciseProgressionEntity(
                            exerciseId = exerciseId,
                            repRange = repRange,
                            standardSets = stdSets,
                            progressionMethod = method,
                            increaseRule = rule,
                            repRangeMin = repMin,
                            repRangeMax = repMax,
                            incrementUpper = incUpper,
                            incrementLower = incLower
                        )
                        dao.insertExerciseProgression(entity)
                    }

                    // Substitutions
                    exObj.optJSONObject("substitutions")?.let { sub ->
                        val sameMovementStr = sub.optJSONArray("sameMovement")?.toString() 
                            ?: sub.optJSONArray("alternatives")?.toString()
                        val entity = ExerciseSubstitutionPremiumEntity(
                            exerciseId = exerciseId,
                            sameMovement = sameMovementStr,
                            sameMuscle = sub.optJSONArray("sameMuscle")?.toString(),
                            notRecommended = sub.optJSONArray("notRecommended")?.toString()
                        )
                        dao.insertExerciseSubstitutionPremium(entity)
                    }

                    // Safety
                    exObj.optJSONObject("safety")?.let { safe ->
                        val entity = ExerciseSafetyEntity(
                            exerciseId = exerciseId,
                            riskLevel = safe.optString("riskLevel"),
                            attentionPoints = safe.optJSONArray("attentionPoints")?.toString(),
                            commonDiscomforts = safe.optJSONArray("commonDiscomforts")?.toString()
                        )
                        dao.insertExerciseSafety(entity)
                    }

                    // AI Context
                    exObj.optJSONObject("aiContext")?.let { ai ->
                        val entity = ExerciseAiContextEntity(
                            exerciseId = exerciseId,
                            objectives = if (schemaVersion >= 2) ai.optJSONArray("recommendedGoals")?.toString() else ai.optJSONArray("objectives")?.toString(),
                            keywords = ai.optJSONArray("keywords")?.toString(),
                            decisionRules = ai.optJSONArray("decisionRules")?.toString()
                        )
                        dao.insertExerciseAiContext(entity)
                    }
                }
            }
            
            if (contentVersion > 0) {
                settingsManager.setInstalledCatalogContentVersion(contentVersion)
            }
            
        } catch (e: Exception) {
            errors.add("Erro ao importar manifesto premium: ${e.message}")
        }

        val reportSummary = auditReport?.formattedReport ?: buildString {
            val totalFound = added + updated + ignored
            appendLine("Importação Premium")
            appendLine("$totalFound exercícios encontrados")
            appendLine("${added + updated} importados")
            appendLine("${errors.size} erros")
            appendLine("")
            appendLine("Mídia:")
            appendLine("GIF: $gifCount")
            appendLine("Vídeos: $videoCount")
            appendLine("Sem mídia: $noMediaCount")
        }

        return@withContext ImportResult(
            added = added,
            updated = updated,
            ignored = ignored,
            errors = errors,
            formattedReport = reportSummary
        )
    }

    suspend fun seedPremiumTestWorkoutIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val templates = dao.getAllTemplatesSync()
            if (templates.none { it.name == "Peito Premium Teste" }) {
                val programs = dao.getAllProgramsSync()
                val programId = if (programs.isNotEmpty()) programs.first().id else {
                    dao.insertProgram(WorkoutProgramEntity(name = "Treinos Principais", isCurrent = true))
                }
                
                val templateId = dao.insertTemplate(
                    WorkoutTemplateEntity(
                        programId = programId,
                        name = "Peito Premium Teste",
                        shortIdentifier = "P",
                        orderInProgram = 0
                    )
                )
                
                val supinoReto = dao.getExerciseByCanonicalId("supino-reto-barra")
                val supinoInclinado = dao.getExerciseByCanonicalId("supino-inclinado-halteres")
                
                if (supinoReto != null) {
                    dao.insertTemplateExercise(
                        WorkoutTemplateExerciseEntity(
                            templateId = templateId,
                            exerciseId = supinoReto.id,
                            sortOrder = 0,
                            targetSets = 3,
                            minReps = 8,
                            maxReps = 12,
                            restDurationSeconds = 90
                        )
                    )
                }
                
                if (supinoInclinado != null) {
                    dao.insertTemplateExercise(
                        WorkoutTemplateExerciseEntity(
                            templateId = templateId,
                            exerciseId = supinoInclinado.id,
                            sortOrder = 1,
                            targetSets = 3,
                            minReps = 8,
                            maxReps = 12,
                            restDurationSeconds = 90
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
