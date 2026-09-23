package com.example.data.social

import com.example.domain.social.SharedCustomExerciseSnapshot
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedProgramSnapshot
import com.example.domain.social.SharedProgramTemplateSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareContent
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareItem
import com.example.domain.social.WorkoutShareKind
import com.example.domain.social.WorkoutShareOtherUser
import com.example.domain.social.WorkoutShareStatus
import com.example.domain.workout.template.WeekdaySchedule
import kotlinx.serialization.Serializable

/**
 * O corpo de `POST /v1/social/workout-shares`.
 *
 * Exatamente um dos dois snapshots vai preenchido — o campo presente é o discriminador do tipo
 * (T19.3). O gateway serializa com `explicitNulls = false`, então o ausente não vira `null` no
 * JSON: o servidor recusa o corpo com os dois, ou com nenhum.
 */
@Serializable
data class CreateWorkoutShareRequestDto(
    val recipientSocialId: String,
    val clientRequestId: String,
    val snapshot: SharedWorkoutSnapshotDto? = null,
    val programSnapshot: SharedProgramSnapshotDto? = null
) {
    companion object {
        fun of(
            recipientSocialId: String,
            clientRequestId: String,
            content: WorkoutShareContent
        ): CreateWorkoutShareRequestDto = when (content) {
            is WorkoutShareContent.Workout -> CreateWorkoutShareRequestDto(
                recipientSocialId = recipientSocialId,
                clientRequestId = clientRequestId,
                snapshot = SharedWorkoutSnapshotDto.fromDomain(content.snapshot)
            )
            is WorkoutShareContent.Program -> CreateWorkoutShareRequestDto(
                recipientSocialId = recipientSocialId,
                clientRequestId = clientRequestId,
                programSnapshot = SharedProgramSnapshotDto.fromDomain(content.snapshot)
            )
        }
    }
}

/**
 * O snapshot de um treino, como ele viaja.
 *
 * `customExercises` só existe em V2 e **só é escrito quando há algum**: `null` some do JSON
 * (`explicitNulls = false`), e uma oferta V1 continua com exatamente as chaves que sempre teve.
 */
@Serializable
data class SharedWorkoutSnapshotDto(
    val snapshotVersion: Int = 1,
    val name: String,
    val shortIdentifier: String? = null,
    val customExercises: List<SharedCustomExerciseSnapshotDto>? = null,
    val exercises: List<SharedExerciseSnapshotDto> = emptyList()
) {
    fun toDomain(): SharedWorkoutSnapshot = SharedWorkoutSnapshot(
        snapshotVersion = snapshotVersion,
        name = name,
        shortIdentifier = shortIdentifier,
        customExercises = customExercises.orEmpty().map { it.toDomain() },
        exercises = exercises.map { it.toDomain() }
    )

    companion object {
        fun fromDomain(domain: SharedWorkoutSnapshot): SharedWorkoutSnapshotDto =
            SharedWorkoutSnapshotDto(
                snapshotVersion = domain.snapshotVersion,
                name = domain.name,
                shortIdentifier = domain.shortIdentifier,
                customExercises = domain.customExercises
                    .takeIf { it.isNotEmpty() }
                    ?.map { SharedCustomExerciseSnapshotDto.fromDomain(it) },
                exercises = domain.exercises.map { SharedExerciseSnapshotDto.fromDomain(it) }
            )
    }
}

@Serializable
data class SharedCustomExerciseSnapshotDto(
    val ref: String,
    val name: String,
    val primaryMuscle: String? = null,
    val equipment: String? = null,
    val description: String? = null
) {
    fun toDomain(): SharedCustomExerciseSnapshot = SharedCustomExerciseSnapshot(
        ref = ref,
        name = name,
        primaryMuscle = primaryMuscle,
        equipment = equipment,
        description = description
    )

    companion object {
        fun fromDomain(domain: SharedCustomExerciseSnapshot): SharedCustomExerciseSnapshotDto =
            SharedCustomExerciseSnapshotDto(
                ref = domain.ref,
                name = domain.name,
                primaryMuscle = domain.primaryMuscle,
                equipment = domain.equipment,
                description = domain.description
            )
    }
}

@Serializable
data class SharedProgramSnapshotDto(
    val snapshotVersion: Int = 1,
    val name: String,
    val description: String? = null,
    val customExercises: List<SharedCustomExerciseSnapshotDto>? = null,
    val templates: List<SharedProgramTemplateSnapshotDto> = emptyList()
) {
    fun toDomain(): SharedProgramSnapshot = SharedProgramSnapshot(
        snapshotVersion = snapshotVersion,
        name = name,
        description = description,
        customExercises = customExercises.orEmpty().map { it.toDomain() },
        templates = templates.map { it.toDomain() }
    )

    companion object {
        fun fromDomain(domain: SharedProgramSnapshot): SharedProgramSnapshotDto =
            SharedProgramSnapshotDto(
                snapshotVersion = domain.snapshotVersion,
                name = domain.name,
                description = domain.description,
                customExercises = domain.customExercises
                    .takeIf { it.isNotEmpty() }
                    ?.map { SharedCustomExerciseSnapshotDto.fromDomain(it) },
                templates = domain.templates.map { SharedProgramTemplateSnapshotDto.fromDomain(it) }
            )
    }
}

/**
 * Um treino dentro de um programa compartilhado, como viaja (T19.3, T19.8).
 *
 * `scheduledDays` é a forma atual: nomes canônicos de `java.time.DayOfWeek`, sem repetição.
 * `dayOfWeek` é a forma **anterior à T19.8** — um dia só, como rótulo — que uma oferta criada
 * antes da atualização (ou por um aparelho ainda não atualizado) ainda traz. O app lê as duas e
 * escreve só a nova; o servidor aceita as duas por isso mesmo. Um valor desconhecido em qualquer
 * das duas vira "sem dia", nunca um dia inventado.
 */
@Serializable
data class SharedProgramTemplateSnapshotDto(
    val name: String,
    val shortIdentifier: String? = null,
    val orderInProgram: Int,
    val scheduledDays: List<String>? = null,
    val dayOfWeek: String? = null,
    val exercises: List<SharedExerciseSnapshotDto> = emptyList()
) {
    fun toDomain(): SharedProgramTemplateSnapshot = SharedProgramTemplateSnapshot(
        name = name,
        shortIdentifier = shortIdentifier,
        orderInProgram = orderInProgram,
        scheduledDays = scheduledDays
            ?.let { names -> WeekdaySchedule.parseCanonical(names) ?: emptyList() }
            ?: listOfNotNull(WeekdaySchedule.fromLegacyLabel(dayOfWeek)),
        exercises = exercises.map { it.toDomain() }
    )

    companion object {
        fun fromDomain(domain: SharedProgramTemplateSnapshot): SharedProgramTemplateSnapshotDto =
            SharedProgramTemplateSnapshotDto(
                name = domain.name,
                shortIdentifier = domain.shortIdentifier,
                orderInProgram = domain.orderInProgram,
                scheduledDays = WeekdaySchedule.names(domain.scheduledDays),
                exercises = domain.exercises.map { SharedExerciseSnapshotDto.fromDomain(it) }
            )
    }
}

/**
 * Um exercício, como ele viaja: id do catálogo **ou** referência CUSTOM escopada ao snapshot.
 *
 * Os dois são anuláveis no DTO porque o JSON traz exatamente um; o servidor recusa o corpo com os
 * dois, ou com nenhum, e é ele a autoridade sobre a forma. Aqui a decodificação precisa apenas
 * conseguir ler as duas formas.
 */
@Serializable
data class SharedExerciseSnapshotDto(
    val canonicalExerciseId: String? = null,
    val customExerciseRef: String? = null,
    val sortOrder: Int,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restDurationSeconds: Int
) {
    fun toDomain(): SharedExerciseSnapshot = SharedExerciseSnapshot(
        canonicalExerciseId = canonicalExerciseId,
        customExerciseRef = customExerciseRef,
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
                customExerciseRef = domain.customExerciseRef,
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

/**
 * `shareType` ausente é um servidor anterior à T19.3, que só conhecia treino. Um valor que esta
 * versão não conhece é lido como treino **sem conteúdo**: a lista o mostra, e o detalhe diz que
 * ele não pode ser importado aqui — em vez de decodificar um tipo novo como se fosse um treino.
 */
private fun kindOf(shareType: String): WorkoutShareKind? =
    runCatching { WorkoutShareKind.valueOf(shareType) }.getOrNull()

@Serializable
data class WorkoutShareItemDto(
    val shareId: String,
    val shareType: String = WorkoutShareKind.WORKOUT_TEMPLATE.name,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val templateName: String,
    val templateCount: Int = 1,
    val exerciseCount: Int,
    val otherUser: WorkoutShareOtherUserDto
) {
    fun toDomain(): WorkoutShareItem = WorkoutShareItem(
        shareId = shareId,
        kind = kindOf(shareType) ?: WorkoutShareKind.WORKOUT_TEMPLATE,
        status = runCatching { WorkoutShareStatus.valueOf(status) }.getOrDefault(WorkoutShareStatus.PENDING),
        createdAt = createdAt,
        expiresAt = expiresAt,
        templateName = templateName,
        templateCount = templateCount,
        exerciseCount = exerciseCount,
        otherUser = otherUser.toDomain()
    )
}

@Serializable
data class WorkoutShareDetailDto(
    val shareId: String,
    val shareType: String = WorkoutShareKind.WORKOUT_TEMPLATE.name,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val sender: WorkoutShareOtherUserDto,
    val recipient: WorkoutShareOtherUserDto,
    val snapshot: SharedWorkoutSnapshotDto? = null,
    val programSnapshot: SharedProgramSnapshotDto? = null
) {
    fun toDomain(): WorkoutShareDetail {
        val kind = kindOf(shareType)
        // O conteúdo é lido pelo **tipo declarado**, nunca pela forma do JSON: um snapshot que não
        // corresponde ao tipo é conteúdo ausente, não uma adivinhação.
        val content: WorkoutShareContent? = when (kind) {
            WorkoutShareKind.WORKOUT_TEMPLATE -> snapshot?.let { WorkoutShareContent.Workout(it.toDomain()) }
            WorkoutShareKind.WORKOUT_PROGRAM -> programSnapshot?.let { WorkoutShareContent.Program(it.toDomain()) }
            null -> null
        }
        return WorkoutShareDetail(
            shareId = shareId,
            kind = kind ?: WorkoutShareKind.WORKOUT_TEMPLATE,
            status = runCatching { WorkoutShareStatus.valueOf(status) }.getOrDefault(WorkoutShareStatus.PENDING),
            createdAt = createdAt,
            expiresAt = expiresAt,
            sender = sender.toDomain(),
            recipient = recipient.toDomain(),
            content = content
        )
    }
}

@Serializable
data class WorkoutShareActionResponseDto(
    val success: Boolean = true
)
