package com.example.data.repository

import com.example.data.local.WorkoutShareImportReceiptEntity
import com.example.data.local.WorkoutShareReceiptDao
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareGateway

sealed interface WorkoutShareImportResult {
    data class Success(val localTemplateId: Long) : WorkoutShareImportResult
    data class AlreadyImported(val localTemplateId: Long) : WorkoutShareImportResult
    data class MissingExercises(val missingCanonicalIds: List<String>) : WorkoutShareImportResult
    data class Error(val message: String) : WorkoutShareImportResult
}

/**
 * Importador de treinos compartilhados para o Room local (T17.7).
 *
 * Cria uma cópia totalmente nova e independente do WorkoutTemplate pertencente
 * ao destinatário. Nunca cria vínculo vivo com o treino do remetente.
 *
 * Idempotente: se o shareId já tiver sido importado localmente, retorna o template existente
 * e não duplica dados no banco.
 */
open class WorkoutShareImporter(
    private val workoutRepository: WorkoutRepository? = null,
    private val receiptDao: WorkoutShareReceiptDao? = null,
    private val gateway: WorkoutShareGateway? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    open suspend fun importShare(
        shareId: String,
        snapshot: SharedWorkoutSnapshot,
        targetProgramId: Long? = null
    ): WorkoutShareImportResult {
        val repo = requireNotNull(workoutRepository) { "WorkoutRepository is required" }
        val receipts = requireNotNull(receiptDao) { "WorkoutShareReceiptDao is required" }
        val shareGateway = requireNotNull(gateway) { "WorkoutShareGateway is required" }

        // 1. Verificar idempotência local
        val existingReceipt = receipts.findReceipt(shareId)
        if (existingReceipt != null) {
            val existingTemplate = repo.getTemplate(existingReceipt.importedTemplateLocalId)
            if (existingTemplate != null) {
                return WorkoutShareImportResult.AlreadyImported(existingReceipt.importedTemplateLocalId)
            }
        }

        // 2. Resolver o programa destino
        val program = if (targetProgramId != null) {
            repo.dao.getProgramById(targetProgramId)
        } else {
            repo.getProgramForNewTemplate()
        } ?: return WorkoutShareImportResult.Error("Nenhum programa de treino encontrado.")

        // 3. Validar se todos os exercícios canônicos existem no catálogo local
        val resolvedExercises = mutableListOf<Pair<SharedExerciseSnapshot, Long>>()
        val missingCanonicalIds = mutableListOf<String>()

        for (ex in snapshot.exercises.sortedBy { it.sortOrder }) {
            val exerciseEntity = repo.getExerciseByCanonicalId(ex.canonicalExerciseId)
            if (exerciseEntity == null) {
                missingCanonicalIds.add(ex.canonicalExerciseId)
            } else {
                resolvedExercises.add(ex to exerciseEntity.id)
            }
        }

        if (missingCanonicalIds.isNotEmpty()) {
            return WorkoutShareImportResult.MissingExercises(missingCanonicalIds)
        }

        // 4. Determinar a ordem dentro do programa
        val existingTemplates = repo.dao.getTemplatesForProgramSync(program.id)
        val nextOrder = (existingTemplates.maxOfOrNull { it.orderInProgram } ?: -1) + 1

        // 5. Criar novo WorkoutTemplate
        val templateId = repo.addTemplate(
            programId = program.id,
            name = snapshot.name,
            shortId = snapshot.shortIdentifier ?: "T",
            order = nextOrder
        )

        // 6. Adicionar exercícios com séries, reps e descanso do snapshot (cargas/notas/máquinas nulas)
        for ((exSnapshot, exerciseLocalId) in resolvedExercises) {
            repo.addTemplateExercise(
                WorkoutTemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = exerciseLocalId,
                    sortOrder = exSnapshot.sortOrder,
                    targetSets = exSnapshot.targetSets,
                    minReps = exSnapshot.minReps,
                    maxReps = exSnapshot.maxReps,
                    restDurationSeconds = exSnapshot.restDurationSeconds,
                    plannedWeight = null,
                    machineLabel = null,
                    notes = null
                )
            )
        }

        // 7. Gravar recibo de importação local
        receipts.insertReceipt(
            WorkoutShareImportReceiptEntity(
                shareId = shareId,
                importedTemplateLocalId = templateId,
                createdAt = clock()
            )
        )

        // 8. Notificar backend que importação concluiu (best effort)
        shareGateway.completeImport(shareId)

        return WorkoutShareImportResult.Success(templateId)
    }
}
