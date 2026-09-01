package com.example.domain.exercise.import

import com.example.data.local.WorkoutDao

class ExerciseSyncManager(
    private val dao: WorkoutDao,
    private val adapters: List<ExternalExerciseAdapter>
) : ExerciseSyncRepository {

    override suspend fun synchronize(limit: Int, offset: Int): SyncResult {
        var imported = 0
        var updated = 0
        var skipped = 0
        var failed = 0

        for (adapter in adapters) {
            val externalList = try {
                adapter.fetchCatalog(limit = limit, offset = offset)
            } catch (_: Exception) {
                failed++
                emptyList()
            }

            for (dto in externalList) {
                try {
                    val canonical = ExerciseCanonicalMapper.toCanonical(dto, source = adapter.sourceName)
                    when (processExercise(canonical)) {
                        ProcessResult.IMPORTED -> imported++
                        ProcessResult.UPDATED -> updated++
                        ProcessResult.SKIPPED -> skipped++
                    }
                } catch (_: Exception) {
                    failed++
                }
            }
        }

        return SyncResult(
            imported = imported,
            updated = updated,
            skipped = skipped,
            failed = failed
        )
    }

    private enum class ProcessResult { IMPORTED, UPDATED, SKIPPED }

    private suspend fun processExercise(canonical: Exercise): ProcessResult {
        val ref = canonical.externalReferences.firstOrNull()
        var existingEntity = if (ref != null) {
            dao.getExerciseByCanonicalId(ref.externalId)
        } else null

        if (existingEntity == null) {
            existingEntity = dao.getExerciseByName(canonical.name)
        }

        if (existingEntity == null) {
            val newEntity = ExerciseCanonicalMapper.toEntity(canonical)
            dao.insertExercise(newEntity)
            return ProcessResult.IMPORTED
        }

        val existingCanonical = ExerciseCanonicalMapper.toCanonical(existingEntity)

        // PARTE 6: USER_CREATED exercises are protected and never altered by sync
        if (existingCanonical.origin == ExerciseOrigin.USER_CREATED) {
            return ProcessResult.SKIPPED
        }

        // PARTE 11: Curated exercise protection - preserve curated content, update only allowed missing fields
        val mergedCanonical = if (existingCanonical.isCurated) {
            existingCanonical.copy(
                instructions = if (existingCanonical.instructions.isNotEmpty()) existingCanonical.instructions else canonical.instructions,
                media = if (existingCanonical.media.isNotEmpty()) existingCanonical.media else canonical.media,
                equipment = existingCanonical.equipment ?: canonical.equipment,
                primaryMuscles = if (existingCanonical.primaryMuscles.isNotEmpty()) existingCanonical.primaryMuscles else canonical.primaryMuscles,
                externalReferences = (existingCanonical.externalReferences + canonical.externalReferences).distinctBy { "${it.source}:${it.externalId}" }
            )
        } else {
            existingCanonical.copy(
                instructions = if (canonical.instructions.isNotEmpty()) canonical.instructions else existingCanonical.instructions,
                media = if (canonical.media.isNotEmpty()) canonical.media else existingCanonical.media,
                equipment = canonical.equipment ?: existingCanonical.equipment,
                primaryMuscles = if (canonical.primaryMuscles.isNotEmpty()) canonical.primaryMuscles else existingCanonical.primaryMuscles,
                externalReferences = (existingCanonical.externalReferences + canonical.externalReferences).distinctBy { "${it.source}:${it.externalId}" }
            )
        }

        val updatedEntity = ExerciseCanonicalMapper.toEntity(mergedCanonical).copy(id = existingEntity.id)
        dao.updateExercise(updatedEntity)
        return ProcessResult.UPDATED
    }
}
