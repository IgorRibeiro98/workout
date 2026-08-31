package com.example.domain.engine

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStreamReader

data class ProgramImportResult(
    val success: Boolean,
    val programName: String = "",
    val workoutsCount: Int = 0,
    val exercisesCount: Int = 0,
    val missingExercises: Int = 0,
    val isSkippedSameVersion: Boolean = false,
    val error: String? = null
)

class ProgramImporter(
    private val database: AppDatabase,
    private val context: Context
) {
    constructor(dao: WorkoutDao, context: Context) : this(
        AppDatabase.getDatabase(context),
        context
    )

    private val dao: WorkoutDao = database.workoutDao()

    suspend fun importProgram(uri: Uri): ProgramImportResult = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ProgramImportResult(false, error = "Arquivo inacessível")
            val jsonString = InputStreamReader(inputStream).readText()
            importProgramFromJson(jsonString)
        } catch (e: Exception) {
            ProgramImportResult(false, error = "Erro ao processar arquivo: ${e.message}")
        }
    }

    suspend fun importProgramFromJson(jsonString: String): ProgramImportResult = withContext(Dispatchers.IO) {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) {
            return@withContext ProgramImportResult(false, error = "Conteúdo JSON está vazio")
        }

        try {
            val root = JSONObject(trimmed)
            val programObj = root.optJSONObject("program") 
                ?: return@withContext ProgramImportResult(false, error = "JSON inválido: objeto 'program' não encontrado")

            val schemaVersion = root.optInt("schemaVersion", 1)
            val locale = root.optString("locale").takeIf { it.isNotBlank() }
            val rootContentVersion = if (root.has("contentVersion")) root.getInt("contentVersion") else null

            val pName = programObj.optString("name", "Programa Importado").trim()
            if (pName.isBlank()) {
                return@withContext ProgramImportResult(false, error = "Nome do programa não pode ser vazio")
            }

            val pDescription = programObj.optString("description").takeIf { it.isNotBlank() }

            val workoutsArr = programObj.optJSONArray("workouts")
            if (workoutsArr == null || workoutsArr.length() == 0) {
                return@withContext ProgramImportResult(false, error = "Programa não contém treinos (workouts)")
            }

            var workoutsCount = 0
            var exercisesCount = 0
            var missingExercises = 0

            // Support program.id, externalId, canonicalId
            val programExternalId = programObj.optString("id").takeIf { it.isNotBlank() }
                ?: programObj.optString("externalId").takeIf { it.isNotBlank() }
                ?: programObj.optString("canonicalId").takeIf { it.isNotBlank() }

            val programContentVersion = if (programObj.has("contentVersion")) {
                programObj.getInt("contentVersion")
            } else {
                rootContentVersion ?: 0
            }

            var isSkipped = false

            database.withTransaction {
                val existingProgram = if (programExternalId != null) {
                    dao.getProgramByExternalId(programExternalId)
                } else {
                    null
                }

                val programId = if (existingProgram != null) {
                    // Check version: same externalId + higher version -> update; same version -> idempotent
                    if (programContentVersion > existingProgram.contentVersion) {
                        dao.updateProgram(
                            existingProgram.copy(
                                name = pName,
                                description = pDescription ?: existingProgram.description,
                                contentVersion = programContentVersion
                            )
                        )
                        existingProgram.id
                    } else {
                        // Idempotent, ignore
                        isSkipped = true
                        return@withTransaction
                    }
                } else {
                    dao.insertProgram(
                        WorkoutProgramEntity(
                            name = pName,
                            description = pDescription,
                            isCurrent = false,
                            externalId = programExternalId,
                            contentVersion = programContentVersion
                        )
                    )
                }


                for (i in 0 until workoutsArr.length()) {
                    val wObj = workoutsArr.getJSONObject(i)
                    val wName = wObj.optString("name", "Treino ${i + 1}")
                    val shortCode = wObj.optString("shortCode").takeIf { it.isNotBlank() }
                    val dayOfWeek = wObj.optString("dayOfWeek").takeIf { it.isNotBlank() }

                    // Check if template with same name in program exists
                    val existingTemplates = dao.getTemplatesForProgramSync(programId)
                    val existingTpl = existingTemplates.firstOrNull { it.name == wName }

                    val templateId = if (existingTpl != null) {
                        dao.updateTemplate(existingTpl.copy(shortIdentifier = shortCode, dayOfWeek = dayOfWeek, orderInProgram = i))
                        // Clear old template exercises to re-import freshly
                        dao.deleteTemplateExercisesForTemplate(existingTpl.id)
                        existingTpl.id
                    } else {
                        dao.insertTemplate(
                            WorkoutTemplateEntity(
                                programId = programId,
                                name = wName,
                                shortIdentifier = shortCode,
                                orderInProgram = i,
                                dayOfWeek = dayOfWeek
                            )
                        )
                    }
                    workoutsCount++

                    val exArr = wObj.optJSONArray("exercises")
                    if (exArr != null) {
                        for (j in 0 until exArr.length()) {
                            val weObj = exArr.getJSONObject(j)
                            val externalId = weObj.optString("externalId").takeIf { it.isNotBlank() }
                            val canonicalId = weObj.optString("exerciseId").takeIf { it.isNotBlank() } ?: weObj.optString("canonicalId").takeIf { it.isNotBlank() }

                            val targetId = externalId ?: canonicalId
                            
                            val exEntity = if (targetId != null) {
                                dao.getExerciseByCanonicalId(targetId)
                            } else null

                            if (exEntity != null) {
                                dao.insertTemplateExercise(
                                    WorkoutTemplateExerciseEntity(
                                        templateId = templateId,
                                        exerciseId = exEntity.id,
                                        sortOrder = weObj.optInt("order", j),
                                        targetSets = weObj.optInt("sets", 3),
                                        minReps = weObj.optInt("minReps", 8),
                                        maxReps = weObj.optInt("maxReps", 12),
                                        restDurationSeconds = weObj.optInt("restSeconds", 90),
                                        plannedWeight = if (weObj.has("plannedWeight")) weObj.getDouble("plannedWeight").toFloat() else null,
                                        machineLabel = if (weObj.has("machineLabel")) weObj.optString("machineLabel") else null,
                                        notes = if (weObj.has("notes")) weObj.optString("notes") else null
                                    )
                                )
                                exercisesCount++
                            } else {
                                missingExercises++
                            }
                        }
                    }
                }
            }

            ProgramImportResult(
                success = true,
                programName = pName,
                workoutsCount = workoutsCount,
                exercisesCount = exercisesCount,
                missingExercises = missingExercises,
                isSkippedSameVersion = isSkipped
            )
        } catch (e: JSONException) {
            ProgramImportResult(false, error = "JSON malformatado: ${e.message}")
        } catch (e: Exception) {
            ProgramImportResult(false, error = "Erro ao importar programa: ${e.message}")
        }
    }
}
