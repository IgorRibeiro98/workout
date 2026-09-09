package com.example.data.social

import com.example.domain.social.CheckInComment
import com.example.domain.social.CheckInMedia
import com.example.domain.social.ReactionType
import com.example.domain.social.SocialCheckInAuthor
import com.example.domain.social.UploadedCheckInMedia
import com.example.domain.social.WorkoutCheckIn
import kotlinx.serialization.Serializable

/**
 * Os DTOs do check-in, do Feed e do conteúdo social (T17.8/T17.9).
 *
 * ## O que estes tipos deliberadamente não declaram
 *
 * Nenhum campo de identidade privada (`ownerUid`, `firebaseUid`, e-mail, `friendCode`), nenhuma
 * referência à sessão de origem em uma **resposta** (`sessionSyncId`) e nenhum dado de treino. O
 * servidor também não os envia — mas declará-los aqui seria o primeiro passo para alguém decidir
 * que "seria útil se o servidor mandasse", e a segunda tela a depender disso tornaria a remoção
 * cara.
 *
 * ## A foto entra por identificador, nunca por URL
 *
 * [WorkoutCheckInMediaDto] tem `mediaId` e dimensões. Não existe `photoUrl`, `imageUrl` nem
 * `mediaUrl` em lugar nenhum deste arquivo, e não existe campo de bytes: um `data:image/...;base64`
 * no Feed seria o servidor mandando a foto de todo mundo em toda leitura (T17.9 §133/§134), e uma
 * URL pública transformaria "amigos" em "qualquer um com o endereço" (§48).
 *
 * O corpo da criação carrega até quatro campos. `completed` não existe, e não pode existir: o
 * servidor recusa esse campo por nome, porque quem responde se a sessão está concluída é a fonte
 * canônica dele.
 */
@Serializable
internal data class CreateWorkoutCheckInRequestDto(
    val sessionSyncId: String,
    val clientRequestId: String,
    /** Ausente quando não há legenda — `explicitNulls = false` no `Json` cuida disso (§7). */
    val caption: String? = null,
    /** Ausente quando não há foto (§42). */
    val mediaId: String? = null
)

@Serializable
internal data class WorkoutCheckInAuthorDto(
    val socialId: String = "",
    val displayName: String = ""
) {
    fun toDomain(): SocialCheckInAuthor =
        SocialCheckInAuthor(socialId = socialId, displayName = displayName)
}

@Serializable
internal data class WorkoutCheckInMediaDto(
    val mediaId: String = "",
    val width: Int = 0,
    val height: Int = 0
) {
    fun toDomainOrNull(): CheckInMedia? =
        if (mediaId.isBlank()) null else CheckInMedia(mediaId, width, height)
}

@Serializable
internal data class WorkoutCheckInDto(
    val type: String = "",
    val checkInId: String = "",
    val author: WorkoutCheckInAuthorDto = WorkoutCheckInAuthorDto(),
    val publishedAt: Long = 0L,
    val caption: String? = null,
    val media: WorkoutCheckInMediaDto? = null,
    /**
     * `Map<String, Int>` e não `Map<ReactionType, Int>`: um servidor mais novo pode acrescentar um
     * tipo, e um enum estrito faria a desserialização inteira falhar — o Feed sumiria da tela por
     * causa de uma reação que este app ainda não desenha. Os desconhecidos são descartados na
     * conversão para o domínio.
     */
    val reactions: Map<String, Int> = emptyMap(),
    val currentUserReaction: String? = null,
    val commentCount: Int = 0,
    val isCurrentUser: Boolean = false,
    /**
     * Se **este** usuário pode reagir e comentar (T17.11 §70/§72/§144).
     *
     * O default é `true` de propósito: um servidor anterior à T17.11 não envia o campo, e toda
     * publicação que ele devolve chega por relação direta — no Feed de amigos a amizade é a própria
     * condição de aparecer. Um default `false` faria um app novo esconder as ações de interação
     * contra um servidor antigo, que é uma regressão silenciosa e difícil de rastrear.
     */
    val canInteract: Boolean = true
) {
    /** `null` quando a resposta não descreve um check-in íntegro — o gateway trata como recusa. */
    fun toDomainOrNull(): WorkoutCheckIn? {
        if (checkInId.isBlank() || author.socialId.isBlank()) return null
        return WorkoutCheckIn(
            checkInId = checkInId,
            author = author.toDomain(),
            publishedAt = publishedAt,
            caption = caption,
            media = media?.toDomainOrNull(),
            reactions = reactions.mapNotNull { (key, value) ->
                ReactionType.fromWire(key)?.let { it to value }
            }.toMap(),
            currentUserReaction = ReactionType.fromWire(currentUserReaction),
            commentCount = commentCount,
            isCurrentUser = isCurrentUser,
            canInteract = canInteract
        )
    }
}

@Serializable
internal data class SocialFeedDto(
    val items: List<WorkoutCheckInDto> = emptyList()
)

@Serializable
internal data class UploadedMediaDto(
    val mediaId: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val byteSize: Int = 0,
    val expiresAt: Long = 0L
) {
    fun toDomainOrNull(): UploadedCheckInMedia? =
        if (mediaId.isBlank()) {
            null
        } else {
            UploadedCheckInMedia(
                mediaId = mediaId,
                width = width,
                height = height,
                byteSize = byteSize
            )
        }
}

@Serializable
internal data class PutReactionRequestDto(val type: String)

@Serializable
internal data class CreateCommentRequestDto(val body: String)

@Serializable
internal data class CheckInCommentDto(
    val commentId: String = "",
    val author: WorkoutCheckInAuthorDto = WorkoutCheckInAuthorDto(),
    val body: String = "",
    val createdAt: Long = 0L,
    val isCurrentUser: Boolean = false,
    /** Decidido pelo servidor (§93/§94/§95). A tela desenha o menu; ela não recalcula a regra. */
    val canDelete: Boolean = false
) {
    fun toDomainOrNull(): CheckInComment? {
        if (commentId.isBlank() || author.socialId.isBlank()) return null
        return CheckInComment(
            commentId = commentId,
            author = author.toDomain(),
            body = body,
            createdAt = createdAt,
            isCurrentUser = isCurrentUser,
            canDelete = canDelete
        )
    }
}

@Serializable
internal data class CheckInCommentsDto(
    val items: List<CheckInCommentDto> = emptyList()
)

/**
 * O corpo da denúncia de conteúdo (T17.9 §101/§103).
 *
 * Três campos, e nenhum deles diz **quem** está sendo denunciado. Um `reportedUid` aqui deixaria o
 * cliente escolher a conta alvo apontando para conteúdo que nem é dela — o servidor recusa o campo
 * por nome, e §195 chama isso de bloqueante.
 */
@Serializable
internal data class CreateContentReportRequestDto(
    val targetType: String,
    val targetId: String,
    val reason: String
)
