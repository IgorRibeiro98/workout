package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "personal_records",
    foreignKeys = [
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [androidx.room.Index("exerciseId")]
)
data class PersonalRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val date: Long,
    val prType: PRType,
    val value: Float // weight, reps, volume, or 1rm
)

enum class PRType {
    MAX_WEIGHT,
    MAX_REPS_AT_WEIGHT,
    MAX_VOLUME,
    ONE_REP_MAX
}
