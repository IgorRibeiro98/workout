package com.example.domain.exercise.import

import com.example.data.local.ExerciseEntity
import com.example.data.remote.ExternalExerciseDto

object ExerciseCanonicalMapper {

    fun toCanonical(dto: ExternalExerciseDto, source: String = "EXERCISE_DB"): Exercise {
        val normalizedName = ExerciseNormalizer.normalizeName(dto.name ?: "")
        
        val rawBodyParts = dto.bodyParts ?: listOfNotNull(dto.bodyPart)
        val muscleGroups = rawBodyParts.map { ExerciseNormalizer.normalizeMuscleGroup(it) }.distinct()
        
        val rawTargetMuscles = dto.targetMuscles ?: listOfNotNull(dto.target)
        val primaryMuscles = rawTargetMuscles.map { ExerciseNormalizer.normalizeMuscleGroup(it) }.distinct()
        
        val equipmentStr = dto.equipments?.firstOrNull() ?: dto.equipment
        val equipment = ExerciseNormalizer.normalizeEquipment(equipmentStr)

        val externalId = dto.exerciseId ?: dto.id ?: dto.name ?: ""

        val externalRef = ExternalExerciseReference(
            source = source,
            externalId = externalId
        )

        return Exercise(
            id = 0,
            name = normalizedName,
            normalizedName = ExerciseNormalizer.cleanText(normalizedName.lowercase()),
            muscleGroups = muscleGroups,
            primaryMuscles = primaryMuscles,
            secondaryMuscles = dto.secondaryMuscles ?: emptyList(),
            equipment = equipment,
            instructions = dto.instructions?.map { ExerciseNormalizer.cleanText(it) } ?: emptyList(),
            executionTips = null,
            commonMistakes = null,
            alternatives = emptyList(),
            youtubeUrl = null,
            media = listOfNotNull(dto.gifUrl),
            source = source,
            externalReferences = listOf(externalRef),
            origin = ExerciseOrigin.SYSTEM,
            isCurated = false
        )
    }

    fun toEntity(exercise: Exercise): ExerciseEntity {
        val primaryMuscleStr = exercise.primaryMuscles.firstOrNull() ?: exercise.muscleGroups.firstOrNull()
        val externalRefJson = exercise.externalReferences.joinToString(";") { "${it.source}:${it.externalId}" }
        val instructionsStr = exercise.instructions.joinToString("\n")
        val mediaUrlStr = exercise.media.firstOrNull()
        val secondaryMusclesStr = exercise.secondaryMuscles.joinToString(",")

        return ExerciseEntity(
            id = exercise.id,
            name = exercise.name,
            description = instructionsStr.ifBlank { null },
            primaryMuscle = primaryMuscleStr,
            equipment = exercise.equipment,
            active = true,
            mediaUrl = mediaUrlStr,
            gifUrl = mediaUrlStr,
            externalExerciseId = exercise.externalReferences.firstOrNull()?.externalId,
            isUserCreated = exercise.origin == ExerciseOrigin.USER_CREATED,
            normalizedName = exercise.normalizedName ?: exercise.name.lowercase(),
            muscleGroups = exercise.muscleGroups.joinToString(","),
            primaryMuscles = exercise.primaryMuscles.joinToString(","),
            secondaryMuscles = secondaryMusclesStr.ifBlank { null },
            instructions = instructionsStr.ifBlank { null },
            executionTips = exercise.executionTips,
            commonMistakes = exercise.commonMistakes,
            alternatives = exercise.alternatives.joinToString(","),
            youtubeUrl = exercise.youtubeUrl,
            source = exercise.source,
            externalReferences = externalRefJson,
            origin = exercise.origin.name,
            isCurated = exercise.isCurated
        )
    }

    fun toCanonical(entity: ExerciseEntity): Exercise {
        val refs = entity.externalReferences?.split(";")?.mapNotNull { item ->
            val parts = item.split(":")
            if (parts.size == 2) ExternalExerciseReference(parts[0], parts[1]) else null
        }?.ifEmpty { null } ?: listOfNotNull(
            entity.externalExerciseId?.let { ExternalExerciseReference(entity.source ?: "EXERCISE_DB", it) }
        )

        val muscleGroupsList = entity.muscleGroups?.split(",")?.filter { it.isNotBlank() }
            ?: listOfNotNull(entity.bodyRegion, entity.primaryMuscle)
        val primaryMusclesList = entity.primaryMuscles?.split(",")?.filter { it.isNotBlank() }
            ?: listOfNotNull(entity.primaryMuscle)
        val secondaryMusclesList = entity.secondaryMuscles?.split(",")?.filter { it.isNotBlank() }
            ?: emptyList()
        val instructionsList = entity.instructions?.split("\n")?.filter { it.isNotBlank() }
            ?: listOfNotNull(entity.description)
        val alternativesList = entity.alternatives?.split(",")?.filter { it.isNotBlank() }
            ?: emptyList()
        val mediaList = listOfNotNull(entity.gifUrl ?: entity.mediaUrl)

        val originEnum = if (entity.isUserCreated) {
            ExerciseOrigin.USER_CREATED
        } else {
            try {
                ExerciseOrigin.valueOf(entity.origin ?: "SYSTEM")
            } catch (_: Exception) {
                ExerciseOrigin.SYSTEM
            }
        }

        return Exercise(
            id = entity.id,
            name = entity.name,
            normalizedName = entity.normalizedName ?: entity.name.lowercase(),
            muscleGroups = muscleGroupsList,
            primaryMuscles = primaryMusclesList,
            secondaryMuscles = secondaryMusclesList,
            equipment = entity.equipment,
            instructions = instructionsList,
            executionTips = entity.executionTips,
            commonMistakes = entity.commonMistakes,
            alternatives = alternativesList,
            youtubeUrl = entity.youtubeUrl,
            media = mediaList,
            source = entity.source,
            externalReferences = refs,
            origin = originEnum,
            isCurated = entity.isCurated
        )
    }
}
