package com.example.domain.engine

import android.content.Context
import androidx.room.withTransaction
import com.example.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PremiumManifestImporter(
    private val database: AppDatabase,
    private val context: Context
) {
    private val dao = database.workoutDao()

    suspend fun importFromAssets(assetPath: String = "catalog/exercise-content-manifest.v1.json"): ImportResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        var added = 0
        var updated = 0

        try {
            val jsonString = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            
            // Validate schemaVersion
            if (root.has("schemaVersion") && root.getInt("schemaVersion") < 1) {
                errors.add("Versão de schema inválida.")
            }
            
            val exercisesArray = if (root.has("exercises")) root.getJSONArray("exercises") else JSONArray().put(root)
            
            database.withTransaction {
                for (i in 0 until exercisesArray.length()) {
                    val exObj = exercisesArray.getJSONObject(i)
                    val id = exObj.optString("id")
                    if (id.isEmpty()) {
                        errors.add("Exercício sem ID encontrado no índice $i.")
                        continue
                    }

                    // Look for existing exercise by canonicalId
                    var existing = dao.getExerciseByCanonicalId(id)
                    var exerciseId: Long = 0

                    val identity = exObj.optJSONObject("identity")
                    val classification = exObj.optJSONObject("classification")

                    val namePtBr = identity?.optString("namePtBr") ?: id
                    val nameEn = identity?.optString("nameEn")
                    val shortDescription = identity?.optString("shortDescription")
                    val aliases = identity?.optJSONArray("aliases")?.let { arr -> 
                        (0 until arr.length()).map { arr.getString(it) }.joinToString(",")
                    }

                    val category = classification?.optString("category")
                    val difficulty = classification?.optString("difficulty")
                    val movementPattern = classification?.optString("movementPattern")
                    val exerciseType = classification?.optString("exerciseType")
                    val primaryMuscle = classification?.optJSONArray("primaryMuscles")?.optString(0)
                    val secondaryMuscles = classification?.optJSONArray("secondaryMuscles")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }.joinToString(",")
                    }
                    val equipment = classification?.optString("equipment")

                    if (existing != null) {
                        // Update existing
                        val updatedEx = existing.copy(
                            name = namePtBr,
                            nameEn = nameEn ?: existing.nameEn,
                            shortDescription = shortDescription ?: existing.shortDescription,
                            aliases = aliases ?: existing.aliases,
                            category = category ?: existing.category,
                            difficulty = difficulty ?: existing.difficulty,
                            movementPattern = movementPattern ?: existing.movementPattern,
                            exerciseType = exerciseType ?: existing.exerciseType,
                            primaryMuscle = primaryMuscle ?: existing.primaryMuscle,
                            secondaryMuscles = secondaryMuscles ?: existing.secondaryMuscles,
                            equipment = equipment ?: existing.equipment
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
                            active = true
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
                            setup = exec.optString("setup"),
                            steps = exec.optJSONArray("steps")?.toString(),
                            breathing = exec.optString("breathing")
                        )
                        dao.insertExerciseExecution(entity)
                    }

                    // Education
                    exObj.optJSONObject("education")?.let { edu ->
                        val entity = ExerciseEducationEntity(
                            exerciseId = exerciseId,
                            tips = edu.optJSONArray("tips")?.toString(),
                            commonMistakes = edu.optJSONArray("commonMistakes")?.toString(),
                            coachNotes = edu.optJSONArray("coachNotes")?.toString()
                        )
                        dao.insertExerciseEducation(entity)
                    }

                    // Media
                    exObj.optJSONObject("media")?.let { media ->
                        val entity = ExerciseMediaEntity(
                            exerciseId = exerciseId,
                            exerciseDbId = media.optString("exerciseDbId"),
                            youtubeVideoIds = media.optJSONArray("youtubeVideoIds")?.toString(),
                            gifUrl = media.optString("gifUrl"),
                            imageUrls = media.optJSONArray("imageUrls")?.toString()
                        )
                        dao.insertExerciseMedia(entity)
                    }

                    // Progression
                    exObj.optJSONObject("progression")?.let { prog ->
                        val entity = ExerciseProgressionEntity(
                            exerciseId = exerciseId,
                            repRange = prog.optString("repRange"),
                            standardSets = if (prog.has("standardSets")) prog.getInt("standardSets") else null,
                            progressionMethod = prog.optString("progressionMethod"),
                            increaseRule = prog.optString("increaseRule")
                        )
                        dao.insertExerciseProgression(entity)
                    }

                    // Substitutions
                    exObj.optJSONObject("substitutions")?.let { sub ->
                        val entity = ExerciseSubstitutionPremiumEntity(
                            exerciseId = exerciseId,
                            sameMovement = sub.optJSONArray("sameMovement")?.toString(),
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
                            objectives = ai.optJSONArray("objectives")?.toString(),
                            keywords = ai.optJSONArray("keywords")?.toString(),
                            decisionRules = ai.optJSONArray("decisionRules")?.toString()
                        )
                        dao.insertExerciseAiContext(entity)
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("Erro ao importar manifesto premium: ${e.message}")
        }

        return@withContext ImportResult(added = added, updated = updated, errors = errors)
    }
}
