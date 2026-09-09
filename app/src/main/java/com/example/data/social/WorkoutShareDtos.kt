package com.example.data.social

import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareItem
import com.example.domain.social.WorkoutShareOtherUser
import com.example.domain.social.WorkoutShareStatus
import kotlinx.serialization.Serializable

@Serializable
data class CreateWorkoutShareRequestDto(
    val recipientSocialId: String,
    val clientRequestId: String,
    val snapshot: SharedWorkoutSnapshotDto
)

@Serializable
data class SharedWorkoutSnapshotDto(
    val snapshotVersion: Int = 1,
    val name: String,
    val shortIdentifier: String? = null,
    val exercises: List<SharedExerciseSnapshotDto> = emptyList()
) {
    fun toDomain(): SharedWorkoutSnapshot = SharedWorkoutSnapshot(
        snapshotVersion = snapshotVersion,
        name = name,
        shortIdentifier = shortIdentifier,
        exercises = exercises.map { it.toDomain() }
    )

    companion object {
        fun fromDomain(domain: SharedWorkoutSnapshot): SharedWorkoutSnapshotDto =
            SharedWorkoutSnapshotDto(
                snapshotVersion = domain.snapshotVersion,
                name = domain.name,
                shortIdentifier = domain.shortIdentifier,
                exercises = domain.exercises.map { SharedExerciseSnapshotDto.fromDomain(it) }
            )
    }
}

@Serializable
data class SharedExerciseSnapshotDto(
    val canonicalExerciseId: String,
    val sortOrder: Int,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restDurationSeconds: Int
) {
    fun toDomain(): SharedExerciseSnapshot = SharedExerciseSnapshot(
        canonicalExerciseId = canonicalExerciseId,
        sortOrder = sortOrder,
        targetSets = targetSets,
        minReps = minReps,
        maxReps = maxReps,
        restDurationSeconds = restDurationSeconds
    )

    companion object {
        fun fromDomain(domain: SharedExerciseSnapshot): SharedExerciseSnapshotDto =
            SharedExerciseSnapshotDto(
                canonicalExerciseId = domain.canonicalExerciseId,
                sortOrder = domain.sortOrder,
                targetSets = domain.targetSets,
                minReps = domain.minReps,
                maxReps = domain.maxReps,
                restDurationSeconds = domain.restDurationSeconds
            )
    }
}

@Serializable
data class WorkoutShareOtherUserDto(
    val socialId: String,
    val displayName: String
) {
    fun toDomain(): WorkoutShareOtherUser = WorkoutShareOtherUser(
        socialId = socialId,
        displayName = displayName
    )
}

@Serializable
data class WorkoutShareItemDto(
    val shareId: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val templateName: String,
    val exerciseCount: Int,
    val otherUser: WorkoutShareOtherUserDto
) {
    fun toDomain(): WorkoutShareItem = WorkoutShareItem(
        shareId = shareId,
        status = runCatching { WorkoutShareStatus.valueOf(status) }.getOrDefault(WorkoutShareStatus.PENDING),
        createdAt = createdAt,
        expiresAt = expiresAt,
        templateName = templateName,
        exerciseCount = exerciseCount,
        otherUser = otherUser.toDomain()
    )
}

@Serializable
data class WorkoutShareDetailDto(
    val shareId: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val sender: WorkoutShareOtherUserDto,
    val recipient: WorkoutShareOtherUserDto,
    val snapshot: SharedWorkoutSnapshotDto? = null
) {
    fun toDomain(): WorkoutShareDetail = WorkoutShareDetail(
        shareId = shareId,
        status = runCatching { WorkoutShareStatus.valueOf(status) }.getOrDefault(WorkoutShareStatus.PENDING),
        createdAt = createdAt,
        expiresAt = expiresAt,
        sender = sender.toDomain(),
        recipient = recipient.toDomain(),
        snapshot = snapshot?.toDomain()
    )
}

@Serializable
data class WorkoutShareActionResponseDto(
    val success: Boolean = true
)
