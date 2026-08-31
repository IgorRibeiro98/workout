import re

with open('app/src/main/java/com/example/domain/engine/PremiumManifestImporter.kt', 'r') as f:
    content = f.read()

# We need to support reading the new v2 arrays as JSON Strings to store in DB.
# For example, `identity` arrays are `aliases`, etc.
# In v2, classification has `primaryMuscles`, `secondaryMuscles`, `equipment` as arrays. We can join them.
# `bodyRegion`, `trainingGoals` need to be parsed.
# `execution.setup` is an object. We can store it as string (JSON).
# `education.tips`, `commonMistakes`, `coachNote` (string instead of array of strings).
# `progression.method`, `repRange` (object), `setsRecommendation`, `progressionRule`, `increment` (object).
# `safety.riskLevel`, `attentionPoints`, `commonDiscomforts`.
# `media.gif`, `images`, `videos`.

# Replace the parsing logic.
new_parsing = """
                    // Support both v1 and v2 based on schemaVersion
                    val schemaVersion = manifestObj.optInt("schemaVersion", 1)

                    val identity = exObj.optJSONObject("identity")
                    val namePtBr = identity?.optString("namePtBr") ?: return@forEach
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
                    var bodyRegion = classification?.optString("bodyRegion")
                    var trainingGoals = classification?.optJSONArray("trainingGoals")?.toString()

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
                    exObj.optJSONObject("media")?.let { media ->
                        var gifUrl = media.optString("gifUrl")
                        var gifSource: String? = null
                        var videos = media.optJSONArray("youtubeVideoIds")?.toString()
                        var images = media.optJSONArray("imageUrls")?.toString()
                        
                        if (schemaVersion >= 2) {
                            media.optJSONObject("gif")?.let {
                                gifUrl = it.optString("url")
                                gifSource = it.optString("source")
                            }
                            videos = media.optJSONArray("videos")?.toString()
                            images = media.optJSONArray("images")?.toString()
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
                    exObj.optJSONObject(if (schemaVersion >= 2) "substitutions" else "substitutions")?.let { sub ->
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
                            objectives = if (schemaVersion >= 2) ai.optJSONArray("recommendedGoals")?.toString() else ai.optJSONArray("objectives")?.toString(),
                            keywords = ai.optJSONArray("keywords")?.toString(),
                            decisionRules = ai.optJSONArray("decisionRules")?.toString()
                        )
                        dao.insertExerciseAiContext(entity)
                    }
"""

start_idx = content.find("                    val aliases = identity")
end_idx = content.find("                }\n            }\n            \n            if (contentVersion > 0)")
if start_idx != -1 and end_idx != -1:
    content = content[:content.rfind("                    val identity = exObj.optJSONObject(\"identity\")")] + new_parsing + content[end_idx:]

with open('app/src/main/java/com/example/domain/engine/PremiumManifestImporter.kt', 'w') as f:
    f.write(content)
