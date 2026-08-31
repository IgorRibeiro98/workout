package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "exercise_education",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("exerciseId", unique = true)]
)
data class ExerciseEducationEntity(
    @PrimaryKey val exerciseId: Long,
    val tips: String? = null, // JSON list
    val commonMistakes: String? = null, // JSON list
    val coachNotes: String? = null // JSON list
)

@Entity(
    tableName = "exercise_media",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("exerciseId", unique = true)]
)
data class ExerciseMediaEntity(
    @PrimaryKey val exerciseId: Long,
    val exerciseDbId: String? = null,
    val youtubeVideoIds: String? = null, // JSON list
    val gifUrl: String? = null,
    val imageUrls: String? = null // JSON list
)

@Entity(
    tableName = "exercise_progression",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("exerciseId", unique = true)]
)
data class ExerciseProgressionEntity(
    @PrimaryKey val exerciseId: Long,
    val repRange: String? = null,
    val standardSets: Int? = null,
    val progressionMethod: String? = null,
    val increaseRule: String? = null
)

@Entity(
    tableName = "exercise_safety",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("exerciseId", unique = true)]
)
data class ExerciseSafetyEntity(
    @PrimaryKey val exerciseId: Long,
    val riskLevel: String? = null,
    val attentionPoints: String? = null, // JSON list
    val commonDiscomforts: String? = null // JSON list
)

@Entity(
    tableName = "exercise_substitutions",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("exerciseId", unique = true)]
)
data class ExerciseSubstitutionPremiumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val sameMovement: String? = null, // JSON list of IDs
    val sameMuscle: String? = null, // JSON list of IDs
    val notRecommended: String? = null // JSON list of IDs
)

@Entity(
    tableName = "exercise_ai_context",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("exerciseId", unique = true)]
)
data class ExerciseAiContextEntity(
    @PrimaryKey val exerciseId: Long,
    val objectives: String? = null, // JSON list
    val keywords: String? = null, // JSON list
    val decisionRules: String? = null // JSON list
)

@Entity(
    tableName = "exercise_biomechanics",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("exerciseId", unique = true)]
)
data class ExerciseBiomechanicsEntity(
    @PrimaryKey val exerciseId: Long,
    val jointActions: String? = null, // JSON list
    val rangeOfMotion: String? = null,
    val stabilityDemand: String? = null,
    val targetFeeling: String? = null
)

@Entity(
    tableName = "exercise_execution",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("exerciseId", unique = true)]
)
data class ExerciseExecutionEntity(
    @PrimaryKey val exerciseId: Long,
    val setup: String? = null,
    val steps: String? = null, // JSON list
    val breathing: String? = null
)
