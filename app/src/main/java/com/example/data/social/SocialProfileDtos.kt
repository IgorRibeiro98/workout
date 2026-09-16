package com.example.data.social

import com.example.domain.social.FriendSocialProfile
import com.example.domain.social.ProgressSharing
import com.example.domain.social.ProgressSharingAvailability
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SharedProgress
import com.example.domain.social.SocialConsistencyParameters
import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialWeeklyGoal
import kotlinx.serialization.Serializable

/**
 * Os contratos de serialização do perfil social enriquecido (T17.2).
 *
 * ## Campo ausente é ausente — e é por isso que tudo é nulável
 *
 * O servidor **omite** o que não pode mostrar: um campo escondido pela privacidade e um campo que
 * ele não consegue afirmar chegam do mesmo jeito, sem existir no JSON. Um default numérico aqui
 * (`val level: Int = 0`) transformaria a ausência em "nível 0" e faria a tela afirmar sobre a vida
 * de outra pessoa exatamente o que a T17.2 existe para não afirmar.
 *
 * ## O que estes DTOs não podem ganhar
 *
 * `ownerUid`, `firebaseUid`, `email`, `friendCode`, qualquer flag de privacidade de outra pessoa e
 * qualquer dado de treino bruto — sessão, série, carga, nota, horário, medida, payload de sync ou
 * de backup. Nada disso chega do servidor, e declarar o campo aqui seria a primeira metade de
 * passar a receber.
 */

/** O progresso já filtrado que um amigo recebe. Todo campo é opcional. */
@Serializable
data class SharedProgressDto(
    val level: Int? = null,
    val consistencyStreak: Int? = null,
    val weeklyWorkoutCount: Int? = null,
    val highlightedAchievementIds: List<String>? = null
)

/** `GET /v1/social/friends/{socialId}/profile` e `GET /v1/social/me/profile-preview`. */
@Serializable
data class FriendSocialProfileDto(
    val socialId: String,
    val displayName: String,
    val sharedProgress: SharedProgressDto = SharedProgressDto()
)

@Serializable
data class FriendSocialProfileResponseDto(
    val profile: FriendSocialProfileDto
)

/** As preferências do dono. */
@Serializable
data class ProgressSharingSettingsDto(
    val shareLevel: Boolean = false,
    val shareConsistencyStreak: Boolean = false,
    val shareWeeklyWorkoutCount: Boolean = false,
    val shareHighlightedAchievements: Boolean = false,
    val weekTimeZone: String? = null,
    val consistency: ConsistencyParametersDto? = null,
    val updatedAt: Long = 0L
)

/**
 * Os parâmetros de consistência (T19.2A): configuração, nunca progresso.
 *
 * Meta por semana e início do acompanhamento — o que `ConsistencyCalculator` lê além das sessões.
 * O servidor recebe isto e **deriva** a sequência dos treinos sincronizados; nenhum campo aqui
 * carrega sequência, nível, XP ou conquista, e um que carregasse seria recusado por nome.
 */
@Serializable
data class ConsistencyParametersDto(
    val trackingStartedAtEpochDay: Long,
    val weeklyGoals: List<WeeklyGoalDto> = emptyList()
)

@Serializable
data class WeeklyGoalDto(
    val weekStartEpochDay: Long,
    val goal: Int
)

/** A disponibilidade de cada campo, como o dono a vê. */
@Serializable
data class ProgressSharingAvailabilityDto(
    val level: String? = null,
    val consistencyStreak: String? = null,
    val weeklyWorkoutCount: String? = null,
    val highlightedAchievements: String? = null
)

@Serializable
data class ProgressSharingResponseDto(
    val settings: ProgressSharingSettingsDto,
    val availability: ProgressSharingAvailabilityDto = ProgressSharingAvailabilityDto()
)

/**
 * `PATCH /v1/social/me/progress-sharing`.
 *
 * Só preferência. Um campo `level`, `streak` ou `weeklyWorkoutCount` aqui faria o servidor recusar
 * a requisição inteira — e ele deve mesmo recusar: progresso não é coisa que o aparelho afirme.
 *
 * `explicitNulls = false` no `Json` do gateway é o que dá semântica de `PATCH` ao nulo: o campo que
 * o app não quer mudar simplesmente não vai no corpo, em vez de ir como `null` e ser interpretado
 * como "desligue".
 */
@Serializable
data class UpdateProgressSharingRequestDto(
    val shareLevel: Boolean? = null,
    val shareConsistencyStreak: Boolean? = null,
    val shareWeeklyWorkoutCount: Boolean? = null,
    val shareHighlightedAchievements: Boolean? = null,
    val weekTimeZone: String? = null,
    val consistency: ConsistencyParametersDto? = null
)

fun SocialConsistencyParameters.toDto(): ConsistencyParametersDto = ConsistencyParametersDto(
    trackingStartedAtEpochDay = trackingStartedAtEpochDay,
    weeklyGoals = weeklyGoals.map { WeeklyGoalDto(weekStartEpochDay = it.weekStartEpochDay, goal = it.goal) }
)

fun ConsistencyParametersDto.toDomain(): SocialConsistencyParameters = SocialConsistencyParameters(
    trackingStartedAtEpochDay = trackingStartedAtEpochDay,
    weeklyGoals = weeklyGoals.map { SocialWeeklyGoal(weekStartEpochDay = it.weekStartEpochDay, goal = it.goal) }
)

/**
 * O DTO vira domínio aqui, e um valor desconhecido é recusado.
 *
 * `null` em vez de um default: um perfil sem `socialId` ou sem nome é resposta que este APK não
 * sabe ler, e mostrá-lo pela metade seria pior do que dizer que não deu para carregar.
 */
fun FriendSocialProfileDto.toDomain(): FriendSocialProfile? {
    if (socialId.isBlank() || displayName.isBlank()) return null
    return FriendSocialProfile(
        socialId = socialId,
        displayName = displayName,
        sharedProgress = sharedProgress.toDomain()
    )
}

fun SharedProgressDto.toDomain(): SharedProgress = SharedProgress(
    level = level,
    consistencyStreak = consistencyStreak,
    weeklyWorkoutCount = weeklyWorkoutCount,
    // Uma lista ausente e uma lista vazia são a mesma coisa para quem olha: não há destaque.
    highlightedAchievementIds = highlightedAchievementIds.orEmpty()
)

fun ProgressSharingResponseDto.toDomain(): ProgressSharing = ProgressSharing(
    settings = ProgressSharingSettings(
        shareLevel = settings.shareLevel,
        shareConsistencyStreak = settings.shareConsistencyStreak,
        shareWeeklyWorkoutCount = settings.shareWeeklyWorkoutCount,
        shareHighlightedAchievements = settings.shareHighlightedAchievements,
        weekTimeZone = settings.weekTimeZone,
        consistency = settings.consistency?.toDomain(),
        updatedAt = settings.updatedAt
    ),
    availability = ProgressSharingAvailability(
        level = parseAvailability(availability.level),
        consistencyStreak = parseAvailability(availability.consistencyStreak),
        weeklyWorkoutCount = parseAvailability(availability.weeklyWorkoutCount),
        highlightedAchievements = parseAvailability(availability.highlightedAchievements)
    )
)

/**
 * Um valor de disponibilidade que este APK não conhece vira [SocialFieldAvailability.UNAVAILABLE].
 *
 * O default é o conservador de propósito: um servidor mais novo que invente um estado novo não
 * pode fazer a tela dizer "Disponível" por chute. "Ainda não disponível" é a frase que erra menos
 * quando não se sabe.
 */
private fun parseAvailability(raw: String?): SocialFieldAvailability =
    SocialFieldAvailability.entries.firstOrNull { it.name == raw }
        ?: SocialFieldAvailability.UNAVAILABLE
