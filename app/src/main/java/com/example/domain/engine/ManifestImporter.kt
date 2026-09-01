package com.example.domain.engine

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseAlternativeEntity
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class ImportResult(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val ignored: Int = 0,
    val alternativesAdded: Int = 0,
    val alternativesExisting: Int = 0,
    val errors: List<String> = emptyList(),
    val isSkippedSameVersion: Boolean = false,
    val warnings: List<String> = emptyList(),
    val formattedReport: String = ""
)

data class CatalogValidationResult(
    val isValid: Boolean,
    val exerciseCount: Int,
    val alternativeCount: Int,
    val localePtBr: Boolean,
    val contentVersion: Int,
    val validationErrors: List<String>,
    val validationWarnings: List<String>
)

class ManifestImporter(
    private val database: AppDatabase,
    private val context: Context,
    private val settingsManager: SettingsManager = SettingsManager(context)
) {
    constructor(dao: WorkoutDao, context: Context) : this(
        AppDatabase.getDatabase(context),
        context
    )

    private val dao: WorkoutDao = database.workoutDao()

    suspend fun importFromAssets(
        assetPath: String = "catalog/catalogo_exercicios_base_ptbr.v1.json",
        force: Boolean = false
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val jsonString = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            importFromJsonString(jsonString, force = force)
        } catch (e: Exception) {
            ImportResult(errors = listOf("Erro ao abrir asset $assetPath: ${e.message}"))
        }
    }

    suspend fun importExercises(uri: Uri, force: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) 
                ?: return@withContext ImportResult(errors = listOf("Arquivo inacessível"))
            val content = inputStream.bufferedReader().use { it.readText() }
            importFromJsonString(content, force = force)
        } catch (e: Exception) {
            ImportResult(errors = listOf("Erro ao importar do arquivo: ${e.message}"))
        }
    }

    fun validateCatalog(jsonString: String): CatalogValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var exerciseCount = 0
        var altCount = 0
        var hasPtBr = false
        var contentVersion = 0

        try {
            val trimmed = jsonString.trim()
            if (trimmed.isEmpty()) {
                errors.add("Conteúdo JSON está vazio.")
                return CatalogValidationResult(false, 0, 0, false, 0, errors, warnings)
            }

            val jsonArray: JSONArray
            if (trimmed.startsWith("[")) {
                jsonArray = JSONArray(trimmed)
                errors.add("Catálogo oficial deve conter objeto raiz com metadados (schemaVersion, contentVersion, locale).")
            } else {
                val obj = JSONObject(trimmed)
                if (!obj.has("schemaVersion")) {
                    errors.add("schemaVersion é obrigatório.")
                } else {
                    val schemaVersion = obj.getInt("schemaVersion")
                    if (schemaVersion != 1) {
                        errors.add("Schema version $schemaVersion não suportado.")
                    }
                }

                if (!obj.has("contentVersion")) {
                    errors.add("contentVersion é obrigatório.")
                } else {
                    contentVersion = obj.getInt("contentVersion")
                    if (contentVersion <= 0) {
                        errors.add("contentVersion deve ser maior que zero.")
                    }
                }

                if (!obj.has("locale")) {
                    errors.add("locale é obrigatório.")
                } else {
                    val loc = obj.getString("locale")
                    if (loc.equals("pt-BR", ignoreCase = true) || loc.equals("pt_BR", ignoreCase = true)) {
                        hasPtBr = true
                    } else {
                        errors.add("Locale incompatível: $loc. Esperado: pt-BR.")
                    }
                }

                if (!obj.has("exercises")) {
                    errors.add("Objeto raiz deve conter o array 'exercises'.")
                    jsonArray = JSONArray()
                } else {
                    jsonArray = obj.getJSONArray("exercises")
                }
                
                if (!obj.has("exerciseCount")) {
                    errors.add("exerciseCount é obrigatório.")
                } else {
                    val count = obj.getInt("exerciseCount")
                    if (count != jsonArray.length()) {
                        errors.add("exerciseCount ($count) não corresponde ao tamanho do array exercises (${jsonArray.length()}).")
                    }
                }
            }

            exerciseCount = jsonArray.length()
            if (exerciseCount == 0 && errors.isEmpty()) {
                errors.add("Manifesto não contém exercícios.")
            }

            val idSet = mutableSetOf<String>()
            val alternativeRefs = mutableListOf<Pair<String, String>>() // Pair(exerciseId, alternativeId)
            val validReasons = setOf(
                "SAME_MOVEMENT",
                "SAME_MOVEMENT_DIFFERENT_EQUIPMENT",
                "SAME_MUSCLE",
                "EQUIPMENT_CHANGE",
                "EQUIPMENT_SWAP",
                "EASIER",
                "HARDER",
                "VARIATION",
                "INJURY_FRIENDLY"
            )

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val exObj = if (item.has("exercise")) item.getJSONObject("exercise") else item
                val id = exObj.optString("id")
                if (id.isNullOrBlank()) {
                    errors.add("Exercício na posição $i não possui 'id' canônico.")
                    continue
                }
                if (!idSet.add(id)) {
                    errors.add("ID duplicado detectado: $id")
                }
                
                val identity = exObj.optJSONObject("identity")
                val namePtBr = identity?.optString("namePtBr") ?: exObj.optString("namePtBr")
                if (namePtBr.isNullOrBlank()) {
                    errors.add("Exercício '$id' não possui 'namePtBr'.")
                }
                
                val alts = exObj.optJSONArray("alternatives")
                if (alts != null) {
                    for (j in 0 until alts.length()) {
                        val altObj = alts.getJSONObject(j)
                        val altId = altObj.optString("exerciseId").takeIf { it.isNotBlank() } ?: altObj.optString("id")
                        if (altId.isBlank()) {
                            warnings.add("Exercício '$id' tem alternativa sem ID.")
                            continue
                        }
                        if (altId == id) {
                            warnings.add("Exercício '$id' lista a si mesmo como alternativa.")
                        }
                        val reason = altObj.optString("reason")
                        if (reason.isNotBlank() && reason !in validReasons) {
                            warnings.add("Exercício '$id' tem alternativa '$altId' com reason não reconhecido: $reason")
                        }
                        alternativeRefs.add(Pair(id, altId))
                    }
                    altCount += alts.length()
                }
            }
            
            // Validate alternative references exist
            for ((exId, altId) in alternativeRefs) {
                if (altId !in idSet && altId != exId) {
                    warnings.add("Exercício '$exId' referencia alternativa inexistente no catálogo: '$altId'.")
                }
            }

        } catch (e: JSONException) {
            errors.add("Sintaxe JSON inválida: ${e.message}")
        } catch (e: Exception) {
            errors.add("Erro de validação: ${e.message}")
        }

        return CatalogValidationResult(
            isValid = errors.isEmpty(),
            exerciseCount = exerciseCount,
            alternativeCount = altCount,
            localePtBr = hasPtBr,
            contentVersion = contentVersion,
            validationErrors = errors,
            validationWarnings = warnings
        )
    }

    suspend fun importFromJsonString(
        jsonString: String,
        force: Boolean = false
    ): ImportResult = withContext(Dispatchers.IO) {
        val validation = validateCatalog(jsonString)
        if (!validation.isValid) {
            return@withContext ImportResult(errors = validation.validationErrors)
        }

        // Check contentVersion in SettingsManager
        val installedVersion = settingsManager.installedCatalogContentVersionFlow.firstOrNull() ?: 0
        if (!force && validation.contentVersion > 0 && validation.contentVersion <= installedVersion) {
            return@withContext ImportResult(
                unchanged = validation.exerciseCount,
                isSkippedSameVersion = true
            )
        }

        var added = 0
        var updated = 0
        var unchanged = 0
        var ignored = 0
        var altsAdded = 0
        var altsExisting = 0
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

            // Execute transaction atomically. Any thrown exception will roll back Room transaction.
            database.withTransaction {
                // First pass: Import/Upsert all canonical exercises
                for (i in 0 until jsonArray.length()) {
                    val wrapperObj = jsonArray.getJSONObject(i)
                    val exObj = if (wrapperObj.has("exercise")) wrapperObj.getJSONObject("exercise") else wrapperObj

                    val canonicalId = exObj.optString("id")
                    if (canonicalId.isNullOrEmpty()) {
                        ignored++
                        continue
                    }

                    val slug = exObj.optString("slug", canonicalId)
                    val wrapperVersion = if (wrapperObj.has("contentVersion")) wrapperObj.getInt("contentVersion") else validation.contentVersion

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

                    val exDbAliasesArr = exObj.optJSONArray("exerciseDbAliases")
                    val exDbAliasesList = mutableListOf<String>()
                    if (exDbAliasesArr != null) {
                        for (j in 0 until exDbAliasesArr.length()) {
                            exDbAliasesList.add(exDbAliasesArr.getString(j))
                        }
                    }
                    val exDbAliasesStr = if (exDbAliasesList.isNotEmpty()) exDbAliasesList.joinToString(",") else null

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
                            // User created exercise (should not happen if canonicalId is set, but respect user flag)
                            unchanged++
                        } else if (!force && existing.contentVersion >= wrapperVersion) {
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
                                exerciseDbAliases = exDbAliasesStr ?: existing.exerciseDbAliases,
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
                            exerciseDbAliases = exDbAliasesStr,
                            gifUrl = gifUrl,
                            active = true,
                            isUserCreated = false
                        )
                        dao.insertExercise(newEx)
                        added++
                    }
                }

                // Second pass: Link alternatives idempotently without self-references
                for (i in 0 until jsonArray.length()) {
                    val wrapperObj = jsonArray.getJSONObject(i)
                    val exObj = if (wrapperObj.has("exercise")) wrapperObj.getJSONObject("exercise") else wrapperObj

                    val canonicalId = exObj.optString("id")
                    if (canonicalId.isNullOrEmpty()) continue

                    val existing = dao.getExerciseByCanonicalId(canonicalId) ?: continue

                    val altsArr = exObj.optJSONArray("alternatives")
                    if (altsArr != null) {
                        for (j in 0 until altsArr.length()) {
                            val altObj = altsArr.getJSONObject(j)
                            val altId = altObj.optString("exerciseId").takeIf { it.isNotBlank() } ?: altObj.optString("id")
                            val reason = altObj.optString("reason", "SAME_MUSCLE")

                            if (altId.equals(canonicalId, ignoreCase = true)) {
                                // Ignore self-reference
                                continue
                            }

                            val altExisting = dao.getExerciseByCanonicalId(altId)
                            if (altExisting != null && altExisting.id != existing.id) {
                                val insertedRowId = dao.insertAlternative(
                                    ExerciseAlternativeEntity(
                                        exerciseId = existing.id,
                                        alternativeExerciseId = altExisting.id,
                                        type = reason
                                    )
                                )
                                if (insertedRowId != -1L) {
                                    altsAdded++
                                } else {
                                    altsExisting++
                                }
                            }
                        }
                    }
                }
            }

            // Update content version in DataStore upon success
            if (validation.contentVersion > 0) {
                settingsManager.setInstalledCatalogContentVersion(validation.contentVersion)
            }
        } catch (e: Exception) {
            errors.add("Erro na transação de importação: ${e.message}")
            return@withContext ImportResult(errors = errors)
        }

        ImportResult(
            added = added,
            updated = updated,
            unchanged = unchanged,
            ignored = ignored,
            alternativesAdded = altsAdded,
            alternativesExisting = altsExisting,
            errors = errors
        )
    }
}
