package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.SocialGroup
import com.example.domain.social.SocialGroupDetail
import com.example.domain.social.SocialGroupError
import com.example.domain.social.SocialGroupFeedItem
import com.example.domain.social.SocialGroupGateway
import com.example.domain.social.SocialGroupInvitation
import com.example.domain.social.SocialGroupMember
import com.example.domain.social.SocialGroupOutcome
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
 * Os Squads sobre o transporte autenticado da T16.1 (T17.11).
 *
 * Um cliente, um interceptor, um lugar montando `Authorization: Bearer` — o mesmo desenho dos
 * outros gateways sociais. Este arquivo é a **única** coisa do app que conhece a forma HTTP dos
 * Squads; os caminhos moram no contrato ao lado.
 *
 * ## Nenhuma requisição daqui descobre um Squad
 *
 * Não existe método de busca, e o contrato não tem caminho para um (§4/§5). Toda leitura é sobre os
 * grupos de que o próprio usuário participa ou sobre os convites que ele recebeu — e o servidor
 * deriva as duas coisas do token, sem parâmetro que o cliente possa ampliar.
 */
class SparkSocialGroupGateway(
    private val client: SparkBackendClient?
) : SocialGroupGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    // ------------------------------------------------------------------ squads

    override suspend fun createGroup(
        name: String,
        clientRequestId: String
    ): SocialGroupOutcome<SocialGroup> {
        val activeClient = client ?: return notConfigured()
        val body = json.encodeToString(CreateSocialGroupRequestDto(name, clientRequestId))

        return interpret(activeClient.postJson(SocialGroupContract.GROUPS_PATH, body)) { raw ->
            json.decodeFromString<SocialGroupSummaryDto>(raw).toDomainOrNull()
        }
    }

    override suspend fun groups(): SocialGroupOutcome<List<SocialGroup>> {
        val activeClient = client ?: return notConfigured()

        return interpret(activeClient.getJson(SocialGroupContract.GROUPS_PATH)) { raw ->
            // Um item malformado descarta a leitura inteira em vez de virar um card vazio: meia
            // lista com um Squad sem nome é pior do que dizer que não deu para carregar.
            json.decodeFromString<SocialGroupListDto>(raw).items.map { dto ->
                dto.toDomainOrNull() ?: return@interpret null
            }
        }
    }

    override suspend fun group(groupId: String): SocialGroupOutcome<SocialGroupDetail> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.getJson(SocialGroupContract.groupPath(groupId))
        ) { raw -> json.decodeFromString<SocialGroupDetailDto>(raw).toDomainOrNull() }
    }

    override suspend fun members(groupId: String): SocialGroupOutcome<List<SocialGroupMember>> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.getJson(SocialGroupContract.membersPath(groupId))
        ) { raw ->
            json.decodeFromString<SocialGroupMembersDto>(raw).items.map { dto ->
                dto.toDomainOrNull() ?: return@interpret null
            }
        }
    }

    override suspend fun deleteGroup(groupId: String): SocialGroupOutcome<Unit> {
        val activeClient = client ?: return notConfigured()

        // `204 No Content`: não há corpo para interpretar, e exigir um faria o sucesso parecer
        // recusa.
        return interpret(activeClient.delete(SocialGroupContract.groupPath(groupId))) { Unit }
    }

    // ------------------------------------------------------------------ convites

    override suspend fun invite(
        groupId: String,
        socialId: String,
        clientRequestId: String
    ): SocialGroupOutcome<SocialGroupInvitation> {
        val activeClient = client ?: return notConfigured()
        val body = json.encodeToString(CreateGroupInvitationRequestDto(socialId, clientRequestId))

        return interpret(
            activeClient.postJson(SocialGroupContract.groupInvitationsPath(groupId), body)
        ) { raw -> json.decodeFromString<SocialGroupInvitationDto>(raw).toDomainOrNull() }
    }

    override suspend fun invitations(): SocialGroupOutcome<List<SocialGroupInvitation>> {
        val activeClient = client ?: return notConfigured()

        return interpret(activeClient.getJson(SocialGroupContract.INVITATIONS_PATH)) { raw ->
            json.decodeFromString<SocialGroupInvitationListDto>(raw).items.map { dto ->
                dto.toDomainOrNull() ?: return@interpret null
            }
        }
    }

    override suspend fun acceptInvitation(
        invitationId: String
    ): SocialGroupOutcome<SocialGroup> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.postJson(SocialGroupContract.acceptInvitationPath(invitationId), "{}")
        ) { raw -> json.decodeFromString<SocialGroupSummaryDto>(raw).toDomainOrNull() }
    }

    override suspend fun declineInvitation(invitationId: String): SocialGroupOutcome<Unit> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.postJson(SocialGroupContract.declineInvitationPath(invitationId), "{}")
        ) { Unit }
    }

    override suspend fun cancelInvitation(invitationId: String): SocialGroupOutcome<Unit> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.postJson(SocialGroupContract.cancelInvitationPath(invitationId), "{}")
        ) { Unit }
    }

    // ------------------------------------------------------------------ composição

    override suspend fun leaveGroup(groupId: String): SocialGroupOutcome<Unit> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.postJson(SocialGroupContract.leavePath(groupId), "{}")
        ) { Unit }
    }

    override suspend fun removeMember(
        groupId: String,
        membershipId: String
    ): SocialGroupOutcome<Unit> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.delete(SocialGroupContract.memberPath(groupId, membershipId))
        ) { Unit }
    }

    override suspend fun transferOwnership(
        groupId: String,
        membershipId: String
    ): SocialGroupOutcome<Unit> {
        val activeClient = client ?: return notConfigured()
        val body = json.encodeToString(TransferGroupOwnershipRequestDto(membershipId))

        return interpret(
            activeClient.postJson(SocialGroupContract.transferOwnershipPath(groupId), body)
        ) { Unit }
    }

    // ------------------------------------------------------------------ feed

    override suspend fun shareCheckIn(
        groupId: String,
        checkInId: String
    ): SocialGroupOutcome<SocialGroupFeedItem> {
        val activeClient = client ?: return notConfigured()

        // Sem corpo: os dois identificadores estão no caminho, e não há nada a propor. Um corpo
        // aqui seria espaço para o cliente tentar propor autoria, data ou audiência — as três
        // decididas no servidor (§83).
        return interpret(
            activeClient.postJson(SocialGroupContract.sharePath(groupId, checkInId), "{}")
        ) { raw -> json.decodeFromString<SocialGroupShareDto>(raw).item.toDomainOrNull() }
    }

    override suspend fun unshareCheckIn(
        groupId: String,
        checkInId: String
    ): SocialGroupOutcome<Unit> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.delete(SocialGroupContract.sharePath(groupId, checkInId))
        ) { Unit }
    }

    override suspend fun feed(
        groupId: String,
        limit: Int?
    ): SocialGroupOutcome<List<SocialGroupFeedItem>> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.getJson(SocialGroupContract.feedPath(groupId, limit))
        ) { raw ->
            json.decodeFromString<SocialGroupFeedDto>(raw).items.map { dto ->
                dto.toDomainOrNull() ?: return@interpret null
            }
        }
    }

    override suspend fun groupsForCheckIn(checkInId: String): SocialGroupOutcome<List<String>> {
        val activeClient = client ?: return notConfigured()

        return interpret(
            activeClient.getJson(SocialGroupContract.checkInGroupsPath(checkInId))
        ) { raw -> json.decodeFromString<CheckInGroupSharesDto>(raw).groupIds }
    }

    // ------------------------------------------------------------------ interpretação

    private fun <T> notConfigured(): SocialGroupOutcome<T> =
        SocialGroupOutcome.Failure(SocialGroupError.NOT_CONFIGURED)

    private fun <T> interpret(
        outcome: SparkHttpOutcome,
        parse: (String) -> T?
    ): SocialGroupOutcome<T> = when (outcome) {
        SparkHttpOutcome.NotConfigured ->
            SocialGroupOutcome.Failure(SocialGroupError.NOT_CONFIGURED)

        SparkHttpOutcome.SignedOut ->
            SocialGroupOutcome.Failure(SocialGroupError.AUTH_REQUIRED)

        SparkHttpOutcome.NetworkFailure ->
            SocialGroupOutcome.Failure(SocialGroupError.NETWORK)

        is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
            val parsed = try {
                parse(outcome.body)
            } catch (e: SerializationException) {
                null
            }
            if (parsed == null) {
                SocialGroupOutcome.Failure(SocialGroupError.REJECTED)
            } else {
                SocialGroupOutcome.Success(parsed)
            }
        } else {
            SocialGroupOutcome.Failure(errorOf(outcome))
        }
    }

    /**
     * O código do envelope decide, e o status HTTP é o plano B.
     *
     * A ordem importa: o mesmo `404` significa "squad não encontrado", "convite indisponível" e
     * "check-in não encontrado", e cada um leva a um texto diferente na tela. Ler o `code` é o que
     * permite distingui-los sem a UI reinterpretar mensagem de erro.
     */
    private fun errorOf(outcome: SparkHttpOutcome.Response): SocialGroupError {
        val code = runCatching {
            json.decodeFromString<ErrorEnvelopeDto>(outcome.body).error.code
        }.getOrNull()

        return when (code) {
            SocialGroupContract.ErrorCodes.UNAUTHENTICATED -> SocialGroupError.AUTH_REQUIRED
            SocialGroupContract.ErrorCodes.AUTH_UNAVAILABLE -> SocialGroupError.UNAVAILABLE
            SocialGroupContract.ErrorCodes.SOCIAL_NOT_ENABLED ->
                SocialGroupError.SOCIAL_NOT_ENABLED
            SocialGroupContract.ErrorCodes.GROUP_NOT_FOUND -> SocialGroupError.GROUP_NOT_FOUND
            SocialGroupContract.ErrorCodes.GROUP_FORBIDDEN -> SocialGroupError.FORBIDDEN
            SocialGroupContract.ErrorCodes.GROUP_OWNED_LIMIT_REACHED ->
                SocialGroupError.OWNED_LIMIT_REACHED
            SocialGroupContract.ErrorCodes.GROUP_MEMBERSHIP_LIMIT_REACHED ->
                SocialGroupError.MEMBERSHIP_LIMIT_REACHED
            SocialGroupContract.ErrorCodes.GROUP_FULL -> SocialGroupError.GROUP_FULL
            SocialGroupContract.ErrorCodes.GROUP_INVITE_NOT_ALLOWED ->
                SocialGroupError.INVITE_NOT_ALLOWED
            SocialGroupContract.ErrorCodes.GROUP_ALREADY_MEMBER -> SocialGroupError.ALREADY_MEMBER
            SocialGroupContract.ErrorCodes.GROUP_INVITE_LIMIT_REACHED ->
                SocialGroupError.INVITE_LIMIT_REACHED
            SocialGroupContract.ErrorCodes.INVITATION_NOT_AVAILABLE ->
                SocialGroupError.INVITATION_NOT_AVAILABLE
            SocialGroupContract.ErrorCodes.GROUP_OWNER_ACTION_REQUIRED ->
                SocialGroupError.OWNER_ACTION_REQUIRED
            SocialGroupContract.ErrorCodes.GROUP_MEMBER_NOT_FOUND ->
                SocialGroupError.MEMBER_NOT_FOUND
            SocialGroupContract.ErrorCodes.CHECKIN_NOT_FOUND -> SocialGroupError.CHECKIN_NOT_FOUND
            SocialGroupContract.ErrorCodes.GROUP_SHARE_LIMIT_REACHED ->
                SocialGroupError.SHARE_LIMIT_REACHED
            SocialGroupContract.ErrorCodes.INVALID_GROUP_NAME -> SocialGroupError.INVALID_NAME
            SocialGroupContract.ErrorCodes.INVALID_GROUP_REQUEST -> SocialGroupError.REJECTED
            SocialGroupContract.ErrorCodes.API_RATE_LIMITED,
            SocialGroupContract.ErrorCodes.RATE_LIMITED -> SocialGroupError.RATE_LIMITED
            SocialGroupContract.ErrorCodes.SOCIAL_UNAVAILABLE -> SocialGroupError.UNAVAILABLE
            else -> when {
                outcome.code == HTTP_UNAUTHORIZED -> SocialGroupError.AUTH_REQUIRED
                outcome.code == HTTP_FORBIDDEN -> SocialGroupError.FORBIDDEN
                outcome.code == HTTP_NOT_FOUND -> SocialGroupError.GROUP_NOT_FOUND
                outcome.code == HTTP_CONFLICT -> SocialGroupError.OWNER_ACTION_REQUIRED
                outcome.code == HTTP_UNPROCESSABLE -> SocialGroupError.REJECTED
                outcome.code == HTTP_TOO_MANY_REQUESTS -> SocialGroupError.RATE_LIMITED
                outcome.code >= HTTP_SERVER_ERROR -> SocialGroupError.UNAVAILABLE
                else -> SocialGroupError.REJECTED
            }
        }
    }
}
