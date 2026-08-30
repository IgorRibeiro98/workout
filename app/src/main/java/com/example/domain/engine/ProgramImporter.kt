package com.example.domain.engine

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStreamReader

data class ProgramImportResult(
    val success: Boolean,
    val programName: String = "",
    val workoutsCount: Int = 0,
    val exercisesCount: Int = 0,
    val missingExercises: Int = 0,
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
        try {
            val root = JSONObject(jsonString.trim())
            val programObj = root.optJSONObject("program") 
                ?: return@withContext ProgramImportResult(false, error = "JSON inválido: objeto 'program' não encontrado")

            val pName = programObj.optString("name", "Programa Importado").trim()
            if (pName.isBlank()) {
                return@withContext ProgramImportResult(false, error = "Nome do programa não pode ser vazio")
            }

            val workoutsArr = programObj.optJSONArray("workouts")
            if (workoutsArr == null || workoutsArr.length() == 0) {
                return@withContext ProgramImportResult(false, error = "Programa não contém treinos (workouts)")
            }

            var workoutsCount = 0
            var exercisesCount = 0
            var missingExercises = 0

            database.withTransaction {
                val programId = dao.insertProgram(WorkoutProgramEntity(name = pName, isCurrent = false))

                for (i in 0 until workoutsArr.length()) {
                    val wObj = workoutsArr.getJSONObject(i)
                    val wName = wObj.optString("name", "Treino ${i + 1}")
                    val shortCode = wObj.optString("shortCode").takeIf { it.isNotBlank() }
                    val dayOfWeek = wObj.optString("dayOfWeek").takeIf { it.isNotBlank() }

                    val templateId = dao.insertTemplate(
                        WorkoutTemplateEntity(
                            programId = programId,
                            name = wName,
                            shortIdentifier = shortCode,
                            orderInProgram = i,
                            dayOfWeek = dayOfWeek
                        )
                    )
                    workoutsCount++

                    val exArr = wObj.optJSONArray("exercises")
                    if (exArr != null) {
                        for (j in 0 until exArr.length()) {
                            val weObj = exArr.getJSONObject(j)
                            val canonicalId = weObj.optString("exerciseId")
                            val exName = weObj.optString("name")

                            val exEntity = if (canonicalId.isNotBlank()) {
                                dao.getExerciseByCanonicalId(canonicalId)
                            } else if (exName.isNotBlank()) {
                                dao.getExerciseByName(exName)
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

            ProgramImportResult(true, pName, workoutsCount, exercisesCount, missingExercises)
        } catch (e: Exception) {
            ProgramImportResult(false, error = "Erro ao importar programa: ${e.message}")
        }
    }
}
