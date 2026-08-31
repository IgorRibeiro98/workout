package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.util.Date

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    val primaryMuscle: String? = null,
    val equipment: String? = null,
    val active: Boolean = true,
    val mediaUrl: String? = null,
    val rirEnabled: Boolean = false, // Phase 13
    val isBodyweight: Boolean = false, // Phase 13
    val canonicalId: String? = null,
    val slug: String? = null,
    val contentVersion: Int = 0,
    val aliases: String? = null,
    val nameEn: String? = null,
    val secondaryMuscles: String? = null,
    val movementPattern: String? = null,
    val substitutionGroup: String? = null,
    val exerciseDbSearch: String? = null,
    val exerciseDbAliases: String? = null,
    val externalExerciseId: String? = null,
    val gifUrl: String? = null,
    val lastVerifiedAt: Long? = null,
    val isUserCreated: Boolean = false,
    val customPhotoUri: String? = null,
    val mappingStatus: String? = null,
    val shortDescription: String? = null,
    val category: String? = null,
    val difficulty: String? = null,
    val exerciseType: String? = null,
    val bodyRegion: String? = null,
    val trainingGoals: String? = null
)

@Entity(tableName = "workout_programs", indices = [androidx.room.Index(value = ["externalId"], unique = true)])
data class WorkoutProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    val isCurrent: Boolean = false,
    val externalId: String? = null,
    val contentVersion: Int = 0
)

@Entity(
    tableName = "workout_templates",
    foreignKeys = [ForeignKey(entity = WorkoutProgramEntity::class, parentColumns = ["id"], childColumns = ["programId"], onDelete = ForeignKey.CASCADE)],
    indices = [androidx.room.Index("programId")]
)
data class WorkoutTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val programId: Long,
    val name: String,
    val shortIdentifier: String? = null,
    val orderInProgram: Int = 0,
    val dayOfWeek: String? = null
)

@Entity(
    tableName = "workout_template_exercises",
    foreignKeys = [
        ForeignKey(entity = WorkoutTemplateEntity::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [androidx.room.Index("templateId"), androidx.room.Index("exerciseId")]
)
data class WorkoutTemplateExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val exerciseId: Long,
    val sortOrder: Int = 0,
    val targetSets: Int = 3,
    val minReps: Int = 8,
    val maxReps: Int = 12,
    val restDurationSeconds: Int = 90,
    val plannedWeight: Float? = null,
    val machineLabel: String? = null,
    val notes: String? = null
)

enum class SessionStatus { PLANNED, IN_PROGRESS, PAUSED, COMPLETED, CANCELLED }

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long?,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: String = SessionStatus.IN_PROGRESS.name,
    val notes: String? = null,
    val templateNameSnapshot: String? = null
)

@Entity(
    tableName = "exercise_sessions",
    foreignKeys = [
        ForeignKey(entity = WorkoutSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [androidx.room.Index("sessionId")]
)
data class ExerciseSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val plannedExerciseId: Long?,
    val actualExerciseId: Long?,
    val exerciseNameSnapshot: String, // Preserva o nome histórico mesmo se o Exercise original mudar
    val sortOrder: Int = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val notes: String? = null,
    val replacementReason: String? = null,
    val machineLabelSnapshot: String? = null,
    val primaryMuscleSnapshot: String? = null,
    val restDurationSecondsSnapshot: Int? = null
)

enum class SetType { NORMAL, WARMUP, DROP_SET, BACKOFF, AMRAP, REST_PAUSE, FAILURE }

enum class AlternativeType { 
    SAME_MOVEMENT, SAME_MUSCLE, EQUIPMENT_CHANGE;

    fun toFriendlyString(): String = when (this) {
        SAME_MOVEMENT -> "Mesmo movimento"
        SAME_MUSCLE -> "Mesmo grupo muscular"
        EQUIPMENT_CHANGE -> "Equipamento diferente"
    }
}

@Entity(
    tableName = "check_ins",
    foreignKeys = [
        ForeignKey(entity = WorkoutSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [androidx.room.Index("sessionId")]
)
data class CheckInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val checkInTime: Long,
    val checkOutTime: Long? = null,
    val gymName: String? = null,
    val sessionId: Long? = null
)

@Entity(
    tableName = "exercise_alternatives",
    foreignKeys = [
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["alternativeExerciseId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [
        androidx.room.Index("exerciseId"),
        androidx.room.Index("alternativeExerciseId"),
        androidx.room.Index(value = ["exerciseId", "alternativeExerciseId", "type"], unique = true)
    ]
)
data class ExerciseAlternativeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val alternativeExerciseId: Long,
    val type: String = AlternativeType.SAME_MUSCLE.name
)

@Entity(
    tableName = "exercise_user_overrides",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index("exerciseId")]
)
data class ExerciseUserOverrideEntity(
    @PrimaryKey val exerciseId: Long = 0,
    val displayName: String? = null,
    val notes: String? = null,
    val customPhotoUri: String? = null,
    val defaultRestSeconds: Int? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "set_logs",
    foreignKeys = [
        ForeignKey(entity = ExerciseSessionEntity::class, parentColumns = ["id"], childColumns = ["exerciseSessionId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [androidx.room.Index("exerciseSessionId")]
)
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseSessionId: Long,
    val setNumber: Int,
    val type: String = SetType.NORMAL.name,
    val weight: Float = 0f,
    val repetitions: Int = 0,
    val completed: Boolean = false,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val rpe: Float? = null,
    val rir: Int? = null
)

class Converters {
    @androidx.room.TypeConverter
    fun fromPRType(value: PRType) = value.name

    @androidx.room.TypeConverter
    fun toPRType(value: String) = enumValueOf<PRType>(value)

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }
    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? = date?.time
}
