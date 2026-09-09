package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkBytesOutcome
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.CheckInComment
import com.example.domain.social.ReactionType
import com.example.domain.social.SocialReportTarget
import com.example.domain.social.UploadedCheckInMedia
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.WorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckInOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private val SUCCESS_RANGE = 200..299

/**
 * O teto de bytes que este cliente aceita receber por foto.
 *
 * O servidor recusa acima de 1,5 MB na saída (§19), então este número é folga sobre o contrato — e
 * não uma segunda política. Ele existe para que uma resposta absurda seja abortada durante a
 * leitura em vez de virar um buffer que ninguém pediu.
 */
private const val MAX_MEDIA_DOWNLOAD_BYTES = 4 * 1024 * 1024

/**
 * O check-in, o Feed e o conteúdo social sobre o transporte autenticado da T16.1 (T17.8/T17.9).
 *
 * Um cliente, um interceptor, um lugar montando `Authorization: Bearer` — o mesmo desenho dos
 * outros gateways sociais. Este arquivo é a **única** coisa do app que conhece a forma HTTP do
 * check-in; os caminhos moram no contrato ao lado.
 *
 * ## A foto não é diferente de nada disso
 *
 * Ela sobe como corpo binário e desce pelo mesmo `Authorization: Bearer` de todo o resto. Não
 * existe URL pública, não existe token de mídia, não existe CDN e não existe caminho que sirva
 * arquivo estático (§48/§49). Se um dia alguém quiser um, o custo será visível: teria de nascer um
 * segundo mecanismo de autorização aqui, e não há espaço para ele.
 */
class SparkWorkoutCheckInGateway(
    private val client: SparkBackendClient?
) : WorkoutCheckInGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    // ------------------------------------------------------------------ mídia (T17.9)

    override suspend fun uploadMedia(
        sessionSyncId: String,
        clientUploadId: String,
        bytes: ByteArray
    ): WorkoutCheckInOutcome<UploadedCheckInMedia> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        // O tipo declarado descreve o que o app **produziu** — o otimizador entrega sempre JPEG.
        // Ele não decide nada no servidor, que decodifica os bytes de verdade (§14).
        val outcome = activeClient.postBytes(
            path = WorkoutCheckInContract.uploadPath(sessionSyncId, clientUploadId),
            bytes = bytes,
            mediaType = "image/jpeg"
        )

        return interpret(outcome) { raw ->
            json.decodeFromString<UploadedMediaDto>(raw).toDomainOrNull()
        }
    }

    override suspend fun mediaBytes(mediaId: String): WorkoutCheckInOutcome<ByteArray> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        return when (
            val outcome = activeClient.getBytes(
                WorkoutCheckInContract.mediaPath(mediaId),
                MAX_MEDIA_DOWNLOAD_BYTES
            )
        ) {
            is SparkBytesOutcome.Downloaded -> WorkoutCheckInOutcome.Success(outcome.bytes)
            SparkBytesOutcome.NotConfigured ->
                WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)
            SparkBytesOutcome.SignedOut ->
                WorkoutCheckInOutcome.Failure(WorkoutCheckInError.AUTH_REQUIRED)
            SparkBytesOutcome.NetworkFailure ->
                WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)
            SparkBytesOutcome.TooLarge ->
                WorkoutCheckInOutcome.Failure(WorkoutCheckInError.MEDIA_TOO_LARGE)
            is SparkBytesOutcome.Rejected ->
                WorkoutCheckInOutcome.Failure(
                    errorOf(SparkHttpOutcome.Response(outcome.code, outcome.body))
                )
        }
    }

    // ------------------------------------------------------------------ publicação

    override suspend fun createCheckIn(
        sessionSyncId: String,
        clientRequestId: String,
        caption: String?,
        mediaId: String?
    ): WorkoutCheckInOutcome<WorkoutCheckIn> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        val body = json.encodeToString(
            CreateWorkoutCheckInRequestDto(
                sessionSyncId = sessionSyncId,
                clientRequestId = clientRequestId,
                // `explicitNulls = false`: sem legenda e sem foto, os campos simplesmente não
                // aparecem no corpo. Um `caption: null` explícito seria aceito também, mas o corpo
                // mínimo é o que descreve a intenção com menos ambiguidade.
                caption = caption,
                mediaId = mediaId
            )
        )

        return interpret(activeClient.postJson(WorkoutCheckInContract.CHECKINS_PATH, body)) { raw ->
            json.decodeFromString<WorkoutCheckInDto>(raw).toDomainOrNull()
        }
    }

    override suspend fun feed(limit: Int?): WorkoutCheckInOutcome<List<WorkoutCheckIn>> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        return interpret(activeClient.getJson(WorkoutCheckInContract.feedPath(limit))) { raw ->
            // Um item malformado descarta a leitura inteira em vez de virar um card vazio: meio
            // feed com um autor sem nome é pior do que dizer que não deu para carregar.
            json.decodeFromString<SocialFeedDto>(raw).items.map { dto ->
                dto.toDomainOrNull() ?: return@interpret null
            }
        }
    }

    override suspend fun checkIn(checkInId: String): WorkoutCheckInOutcome<WorkoutCheckIn> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        return interpret(
            activeClient.getJson(WorkoutCheckInContract.checkInPath(checkInId))
        ) { raw ->
            json.decodeFromString<WorkoutCheckInDto>(raw).toDomainOrNull()
        }
    }

    override suspend fun deleteCheckIn(checkInId: String): WorkoutCheckInOutcome<Unit> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        // `204 No Content`: não há corpo para interpretar, e exigir um faria o sucesso parecer
        // recusa.
        return interpret(activeClient.delete(WorkoutCheckInContract.checkInPath(checkInId))) { Unit }
    }

    // ------------------------------------------------------------------ reações (T17.9)

    override suspend fun putReaction(
        checkInId: String,
        type: ReactionType
    ): WorkoutCheckInOutcome<WorkoutCheckIn> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        val body = json.encodeToString(PutReactionRequestDto(type.name))
        return interpret(
            activeClient.putJson(WorkoutCheckInContract.reactionPath(checkInId), body)
        ) { raw ->
            json.decodeFromString<WorkoutCheckInDto>(raw).toDomainOrNull()
        }
    }

    override suspend fun removeReaction(
        checkInId: String
    ): WorkoutCheckInOutcome<WorkoutCheckIn> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        return interpret(
            activeClient.delete(WorkoutCheckInContract.reactionPath(checkInId))
        ) { raw ->
            json.decodeFromString<WorkoutCheckInDto>(raw).toDomainOrNull()
        }
    }

    // ------------------------------------------------------------------ comentários (T17.9)

    override suspend fun comments(
        checkInId: String,
        limit: Int?
    ): WorkoutCheckInOutcome<List<CheckInComment>> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        return interpret(
            activeClient.getJson(WorkoutCheckInContract.commentsPath(checkInId, limit))
        ) { raw ->
            json.decodeFromString<CheckInCommentsDto>(raw).items.map { dto ->
                dto.toDomainOrNull() ?: return@interpret null
            }
        }
    }

    override suspend fun createComment(
        checkInId: String,
        body: String
    ): WorkoutCheckInOutcome<CheckInComment> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        val payload = json.encodeToString(CreateCommentRequestDto(body))
        return interpret(
            activeClient.postJson(WorkoutCheckInContract.commentsPath(checkInId), payload)
        ) { raw ->
            json.decodeFromString<CheckInCommentDto>(raw).toDomainOrNull()
        }
    }

    override suspend fun deleteComment(
        checkInId: String,
        commentId: String
    ): WorkoutCheckInOutcome<Unit> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        return interpret(
            activeClient.delete(WorkoutCheckInContract.commentPath(checkInId, commentId))
        ) { Unit }
    }

    // ------------------------------------------------------------------ denúncia (T17.9)

    override suspend fun reportContent(
        target: SocialReportTarget,
        targetId: String,
        reason: String
    ): WorkoutCheckInOutcome<Unit> {
        val activeClient = client
            ?: return WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        val body = json.encodeToString(
            CreateContentReportRequestDto(
                targetType = target.name,
                targetId = targetId,
                reason = reason
            )
        )
        return interpret(activeClient.postJson(WorkoutCheckInContract.REPORTS_PATH, body)) { Unit }
    }

    // ------------------------------------------------------------------ interpretação

    private fun <T> interpret(
        outcome: SparkHttpOutcome,
        parse: (String) -> T?
    ): WorkoutCheckInOutcome<T> = when (outcome) {
        SparkHttpOutcome.NotConfigured ->
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NOT_CONFIGURED)

        SparkHttpOutcome.SignedOut ->
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.AUTH_REQUIRED)

        SparkHttpOutcome.NetworkFailure ->
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)

        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                parse(outcome.body)
            } catch (e: SerializationException) {
                null
            }
            if (parsed == null) {
                WorkoutCheckInOutcome.Failure(WorkoutCheckInError.REJECTED)
            } else {
                WorkoutCheckInOutcome.Success(parsed)
            }
        } else {
            WorkoutCheckInOutcome.Failure(errorOf(outcome))
        }
    }

    private fun errorOf(outcome: SparkHttpOutcome.Response): WorkoutCheckInError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            WorkoutCheckInContract.ErrorCodes.UNAUTHENTICATED -> WorkoutCheckInError.AUTH_REQUIRED
            WorkoutCheckInContract.ErrorCodes.AUTH_UNAVAILABLE -> WorkoutCheckInError.UNAVAILABLE
            WorkoutCheckInContract.ErrorCodes.SOCIAL_NOT_ENABLED ->
                WorkoutCheckInError.SOCIAL_NOT_ENABLED
            WorkoutCheckInContract.ErrorCodes.SESSION_NOT_FOUND ->
                WorkoutCheckInError.SESSION_NOT_FOUND
            WorkoutCheckInContract.ErrorCodes.SESSION_NOT_COMPLETED ->
                WorkoutCheckInError.SESSION_NOT_COMPLETED
            WorkoutCheckInContract.ErrorCodes.CHECKIN_WINDOW_EXPIRED ->
                WorkoutCheckInError.CHECKIN_WINDOW_EXPIRED
            WorkoutCheckInContract.ErrorCodes.CHECKIN_ALREADY_EXISTS ->
                WorkoutCheckInError.CHECKIN_ALREADY_EXISTS
            WorkoutCheckInContract.ErrorCodes.CHECKIN_REQUEST_CONFLICT ->
                WorkoutCheckInError.CHECKIN_REQUEST_CONFLICT
            WorkoutCheckInContract.ErrorCodes.CHECKIN_NOT_FOUND ->
                WorkoutCheckInError.CHECKIN_NOT_FOUND
            WorkoutCheckInContract.ErrorCodes.INVALID_CHECKIN_REQUEST ->
                WorkoutCheckInError.REJECTED
            // T17.9 — cada recusa de conteúdo vira uma classe própria, porque cada uma leva a um
            // conselho diferente na tela. Colapsá-las obrigaria a UI a reinterpretar texto de erro.
            WorkoutCheckInContract.ErrorCodes.INVALID_CONTENT ->
                WorkoutCheckInError.INVALID_CONTENT
            WorkoutCheckInContract.ErrorCodes.INVALID_IMAGE -> WorkoutCheckInError.INVALID_IMAGE
            WorkoutCheckInContract.ErrorCodes.MEDIA_TOO_LARGE ->
                WorkoutCheckInError.MEDIA_TOO_LARGE
            WorkoutCheckInContract.ErrorCodes.MEDIA_QUOTA_EXCEEDED ->
                WorkoutCheckInError.MEDIA_QUOTA_EXCEEDED
            WorkoutCheckInContract.ErrorCodes.MEDIA_NOT_FOUND ->
                WorkoutCheckInError.MEDIA_NOT_FOUND
            WorkoutCheckInContract.ErrorCodes.INVALID_REACTION -> WorkoutCheckInError.REJECTED
            WorkoutCheckInContract.ErrorCodes.COMMENT_NOT_FOUND ->
                WorkoutCheckInError.COMMENT_NOT_FOUND
            WorkoutCheckInContract.ErrorCodes.INVALID_REPORT_TARGET ->
                WorkoutCheckInError.CHECKIN_NOT_FOUND
            WorkoutCheckInContract.ErrorCodes.API_RATE_LIMITED,
            WorkoutCheckInContract.ErrorCodes.RATE_LIMITED -> WorkoutCheckInError.RATE_LIMITED
            WorkoutCheckInContract.ErrorCodes.SOCIAL_UNAVAILABLE -> WorkoutCheckInError.UNAVAILABLE
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> WorkoutCheckInError.AUTH_REQUIRED
                outcome.code == HTTP_FORBIDDEN -> WorkoutCheckInError.SOCIAL_NOT_ENABLED
                outcome.code == HTTP_NOT_FOUND -> WorkoutCheckInError.CHECKIN_NOT_FOUND
                outcome.code == HTTP_CONFLICT -> WorkoutCheckInError.CHECKIN_ALREADY_EXISTS
                outcome.code == HTTP_UNPROCESSABLE -> WorkoutCheckInError.REJECTED
                outcome.code == HTTP_TOO_MANY_REQUESTS -> WorkoutCheckInError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> WorkoutCheckInError.UNAVAILABLE
                else -> WorkoutCheckInError.REJECTED
            }
        }
    }
}
