package com.example.domain.social

/**
 * Um [SocialGroupGateway] que não faz nada (T17.11).
 *
 * Existe pelo mesmo motivo do `StubWorkoutCheckInGateway`: um teste que se importa com **uma**
 * operação não deveria ter de implementar as quinze. Cada `override` daqui devolve o resultado
 * neutro, e o teste sobrescreve só o que ele afirma.
 *
 * Os padrões são **sucesso vazio**, e não falha: um teste que esqueceu de configurar uma resposta
 * vê uma lista vazia — um estado normal do produto — em vez de um erro que ele passaria a
 * investigar sem que houvesse nada errado.
 */
open class StubSocialGroupGateway : SocialGroupGateway {

    override val isConfigured: Boolean = true

    override suspend fun createGroup(
        name: String,
        clientRequestId: String
    ): SocialGroupOutcome<SocialGroup> =
        SocialGroupOutcome.Failure(SocialGroupError.REJECTED)

    override suspend fun groups(): SocialGroupOutcome<List<SocialGroup>> =
        SocialGroupOutcome.Success(emptyList())

    override suspend fun group(groupId: String): SocialGroupOutcome<SocialGroupDetail> =
        SocialGroupOutcome.Failure(SocialGroupError.GROUP_NOT_FOUND)

    override suspend fun members(groupId: String): SocialGroupOutcome<List<SocialGroupMember>> =
        SocialGroupOutcome.Success(emptyList())

    override suspend fun deleteGroup(groupId: String): SocialGroupOutcome<Unit> =
        SocialGroupOutcome.Success(Unit)

    override suspend fun invite(
        groupId: String,
        socialId: String,
        clientRequestId: String
    ): SocialGroupOutcome<SocialGroupInvitation> =
        SocialGroupOutcome.Failure(SocialGroupError.INVITE_NOT_ALLOWED)

    override suspend fun invitations(): SocialGroupOutcome<List<SocialGroupInvitation>> =
        SocialGroupOutcome.Success(emptyList())

    override suspend fun acceptInvitation(
        invitationId: String
    ): SocialGroupOutcome<SocialGroup> =
        SocialGroupOutcome.Failure(SocialGroupError.INVITATION_NOT_AVAILABLE)

    override suspend fun declineInvitation(invitationId: String): SocialGroupOutcome<Unit> =
        SocialGroupOutcome.Success(Unit)

    override suspend fun cancelInvitation(invitationId: String): SocialGroupOutcome<Unit> =
        SocialGroupOutcome.Success(Unit)

    override suspend fun leaveGroup(groupId: String): SocialGroupOutcome<Unit> =
        SocialGroupOutcome.Success(Unit)

    override suspend fun removeMember(
        groupId: String,
        membershipId: String
    ): SocialGroupOutcome<Unit> = SocialGroupOutcome.Success(Unit)

    override suspend fun transferOwnership(
        groupId: String,
        membershipId: String
    ): SocialGroupOutcome<Unit> = SocialGroupOutcome.Success(Unit)

    override suspend fun shareCheckIn(
        groupId: String,
        checkInId: String
    ): SocialGroupOutcome<SocialGroupFeedItem> =
        SocialGroupOutcome.Failure(SocialGroupError.CHECKIN_NOT_FOUND)

    override suspend fun unshareCheckIn(
        groupId: String,
        checkInId: String
    ): SocialGroupOutcome<Unit> = SocialGroupOutcome.Success(Unit)

    override suspend fun feed(
        groupId: String,
        limit: Int?
    ): SocialGroupOutcome<List<SocialGroupFeedItem>> = SocialGroupOutcome.Success(emptyList())

    override suspend fun groupsForCheckIn(checkInId: String): SocialGroupOutcome<List<String>> =
        SocialGroupOutcome.Success(emptyList())
}
