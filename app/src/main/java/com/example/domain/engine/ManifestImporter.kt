package com.example.domain.engine

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseAlternativeEntity
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ImportResult(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val ignored: Int = 0,
    val alternativesAdded: Int = 0,
    val errors: List<String> = emptyList()
)

data class CatalogValidationResult(
    val isValid: Boolean,
    val exerciseCount: Int,
    val alternativeCount: Int,
    val localePtBr: Boolean,
    val validationErrors: List<String>
)

class ManifestImporter(
    private val database: AppDatabase,
    private val context: Context
) {
    // Secondary constructor for backwards compatibility
    constructor(dao: WorkoutDao, context: Context) : this(
        AppDatabase.getDatabase(context),
        context
    )

    private val dao: WorkoutDao = database.workoutDao()

    suspend fun importFromAssets(assetPath: String = "catalog/catalogo_exercicios_base_ptbr.v1.json"): ImportResult = withContext(Dispatchers.IO) {
        try {
            val jsonString = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            importFromJsonString(jsonString)
        } catch (e: Exception) {
            ImportResult(errors = listOf("Erro ao abrir asset $assetPath: ${e.message}"))
        }
    }

    suspend fun importExercises(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) 
                ?: return@withContext ImportResult(errors = listOf("Arquivo inacessível"))
            val content = inputStream.bufferedReader().use { it.readText() }
            importFromJsonString(content)
        } catch (e: Exception) {
            ImportResult(errors = listOf("Erro ao importar do arquivo: ${e.message}"))
        }
    }

    fun validateCatalog(jsonString: String): CatalogValidationResult {
        val errors = mutableListOf<String>()
        var exerciseCount = 0
        var altCount = 0
        var hasPtBr = true

        try {
            val trimmed = jsonString.trim()
            val jsonArray: JSONArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val obj = JSONObject(trimmed)
                if (obj.has("locale")) {
                    val loc = obj.getString("locale")
                    if (!loc.equals("pt-BR", ignoreCase = true) && !loc.equals("pt_BR", ignoreCase = true)) {
                        hasPtBr = false
                    }
                }
                if (obj.has("exercises")) obj.getJSONArray("exercises") else JSONArray().put(obj)
            }

            exerciseCount = jsonArray.length()
            if (exerciseCount == 0) {
                errors.add("Manifesto não contém exercícios.")
            }

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val exObj = if (item.has("exercise")) item.getJSONObject("exercise") else item
                val id = exObj.optString("id")
                if (id.isNullOrBlank()) {
                    errors.add("Exercício na posição $i não possui 'id' canônico.")
                }
                val identity = exObj.optJSONObject("identity")
                val namePtBr = identity?.optString("namePtBr") ?: exObj.optString("namePtBr")
                if (namePtBr.isNullOrBlank()) {
                    errors.add("Exercício '$id' não possui 'namePtBr'.")
                }
                val alts = exObj.optJSONArray("alternatives")
                if (alts != null) {
                    altCount += alts.length()
                }
            }
        } catch (e: Exception) {
            errors.add("JSON inválido ou corrompido: ${e.message}")
        }

        return CatalogValidationResult(
            isValid = errors.isEmpty(),
            exerciseCount = exerciseCount,
            alternativeCount = altCount,
            localePtBr = hasPtBr,
            validationErrors = errors
        )
    }

    suspend fun importFromJsonString(jsonString: String): ImportResult = withContext(Dispatchers.IO) {
        val validation = validateCatalog(jsonString)
        if (!validation.isValid && validation.exerciseCount == 0) {
            return@withContext ImportResult(errors = validation.validationErrors)
        }

        var added = 0
        var updated = 0
        var unchanged = 0
        var ignored = 0
        var altsAdded = 0
        val errors = mutableListOf<String>()

        try {
            val jsonArray = if (jsonString.trim().startsWith("[")) {
                JSONArray(jsonString.trim())
            } else {
                val obj = JSONObject(jsonString.trim())
                if (obj.has("exercises")) {
                    obj.getJSONArray("exercises")
                } else if (obj.has("exercise")) {
                    JSONArray().put(obj)
                } else {
                    JSONArray().put(obj)
                }
            }

            database.withTransaction {
                // First pass: Import/Upsert all canonical exercises
                for (i in 0 until jsonArray.length()) {
                    try {
                        val wrapperObj = jsonArray.getJSONObject(i)
                        val exObj = if (wrapperObj.has("exercise")) wrapperObj.getJSONObject("exercise") else wrapperObj

                        val canonicalId = exObj.optString("id")
                        if (canonicalId.isNullOrEmpty()) {
                            ignored++
                            continue
                        }

                        val slug = exObj.optString("slug", canonicalId)
                        val wrapperVersion = wrapperObj.optInt("contentVersion", 1)

                        val identity = exObj.optJSONObject("identity")
                        val name = identity?.optString("namePtBr") ?: exObj.optString("namePtBr", canonicalId)

                        val aliasesArr = identity?.optJSONArray("aliases")
                        val aliasesList = mutableListOf<String>()
                        if (aliasesArr != null) {
                            for (j in 0 until aliasesArr.length()) {
                                aliasesList.add(aliasesArr.getString(j))
                            }
                        }
                        val aliasesStr = if (aliasesList.isNotEmpty()) aliasesList.joinToString(",") else null

                        val classification = exObj.optJSONObject("classification")
                        val primaryMuscleObj = exObj.optJSONObject("muscles")?.optJSONArray("primary")?.optJSONObject(0)
                        val primaryMuscle = primaryMuscleObj?.optString("namePtBr")
                            ?: classification?.optJSONArray("bodyParts")?.optString(0)
                            ?: exObj.optString("primaryMuscle").takeIf { it.isNotEmpty() }

                        val equipmentArr = exObj.optJSONObject("equipment")?.optJSONArray("required")
                        val equipment = equipmentArr?.optJSONObject(0)?.optString("namePtBr")

                        val nameEn = exObj.optString("nameEn").takeIf { it.isNotEmpty() }
                        val secondaryMusclesArr = exObj.optJSONArray("secondaryMuscles")
                        val secMusclesList = mutableListOf<String>()
                        if (secondaryMusclesArr != null) {
                            for (j in 0 until secondaryMusclesArr.length()) {
                                secMusclesList.add(secondaryMusclesArr.getString(j))
                            }
                        }
                        val secMusclesStr = if (secMusclesList.isNotEmpty()) secMusclesList.joinToString(",") else null

                        val eqStr = exObj.optString("equipment").takeIf { it.isNotEmpty() } ?: equipment
                        val movementPattern = exObj.optString("movementPattern").takeIf { it.isNotEmpty() }
                        val substitutionGroup = exObj.optString("substitutionGroup").takeIf { it.isNotEmpty() }
                        val exerciseDbSearch = exObj.optString("exerciseDbSearch").takeIf { it.isNotEmpty() }
                        val gifUrl = exObj.optString("gifUrl").takeIf { it.isNotEmpty() }

                        val existing = dao.getExerciseByCanonicalId(canonicalId)
                        if (existing != null) {
                            if (existing.isUserCreated) {
                                // Preserve user custom exercises without modifying user fields
                                unchanged++
                            } else if (existing.contentVersion >= wrapperVersion) {
                                unchanged++
                            } else {
                                val updatedEx = existing.copy(
                                    name = name,
                                    primaryMuscle = primaryMuscle ?: existing.primaryMuscle,
                                    equipment = eqStr ?: existing.equipment,
                                    slug = slug,
                                    contentVersion = wrapperVersion,
                                    aliases = aliasesStr ?: existing.aliases,
                                    nameEn = nameEn ?: existing.nameEn,
                                    secondaryMuscles = secMusclesStr ?: existing.secondaryMuscles,
                                    movementPattern = movementPattern ?: existing.movementPattern,
                                    substitutionGroup = substitutionGroup ?: existing.substitutionGroup,
                                    exerciseDbSearch = exerciseDbSearch ?: existing.exerciseDbSearch,
                                    gifUrl = gifUrl ?: existing.gifUrl
                                )
                                dao.updateExercise(updatedEx)
                                updated++
                            }
                        } else {
                            val newEx = ExerciseEntity(
                                name = name,
                                primaryMuscle = primaryMuscle,
                                equipment = eqStr,
                                canonicalId = canonicalId,
                                slug = slug,
                                contentVersion = wrapperVersion,
                                aliases = aliasesStr,
                                nameEn = nameEn,
                                secondaryMuscles = secMusclesStr,
                                movementPattern = movementPattern,
                                substitutionGroup = substitutionGroup,
                                exerciseDbSearch = exerciseDbSearch,
                                gifUrl = gifUrl,
                                active = true,
                                isUserCreated = false
                            )
                            dao.insertExercise(newEx)
                            added++
                        }
                    } catch (e: Exception) {
                        ignored++
                        errors.add("Erro no item $i: ${e.message}")
                    }
                }

                // Second pass: Link alternatives idempotently without self-references
                for (i in 0 until jsonArray.length()) {
                    try {
                        val wrapperObj = jsonArray.getJSONObject(i)
                        val exObj = if (wrapperObj.has("exercise")) wrapperObj.getJSONObject("exercise") else wrapperObj

                        val canonicalId = exObj.optString("id")
                        if (canonicalId.isNullOrEmpty()) continue

                        val existing = dao.getExerciseByCanonicalId(canonicalId) ?: continue

                        val altsArr = exObj.optJSONArray("alternatives")
                        if (altsArr != null) {
                            for (j in 0 until altsArr.length()) {
                                val altObj = altsArr.getJSONObject(j)
                                val altId = altObj.optString("exerciseId")
                                val reason = altObj.optString("reason", "SAME_MUSCLE")

                                if (altId.equals(canonicalId, ignoreCase = true)) {
                                    // Ignore self-reference
                                    continue
                                }

                                val altExisting = dao.getExerciseByCanonicalId(altId)
                                if (altExisting != null && altExisting.id != existing.id) {
                                    dao.insertAlternative(
                                        ExerciseAlternativeEntity(
                                            exerciseId = existing.id,
                                            alternativeExerciseId = altExisting.id,
                                            type = reason
                                        )
                                    )
                                    altsAdded++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore individual alternative insertion error
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("Erro durante a transação de importação: ${e.message}")
        }

        ImportResult(added, updated, unchanged, ignored, altsAdded, errors)
    }
}
