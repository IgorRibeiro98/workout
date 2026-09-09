package com.example.data.social

/**
 * Os caminhos e os códigos de erro dos Squads (T17.11 §127).
 *
 * Os caminhos moram aqui e em mais nenhum lugar — há teste estrutural sobre isso. Uma tela que
 * montasse `"v1/social/groups"` seria um segundo dono do contrato.
 *
 * ## O que este arquivo **não** declara, e é o ponto da fase
 *
 * Não existe caminho de busca de Squad, não existe caminho público, não existe caminho de entrada
 * por código e não existe caminho de convite por link (§4/§5). A ausência não é "ainda não
 * implementamos": é o contrato. Um Squad é conhecido por quem está dentro dele e por quem recebeu
 * um convite.
 */
object SocialGroupContract {

    const val GROUPS_PATH = "v1/social/groups"

    /** Os convites **recebidos**. Fica sob `groups/` porque é sobre eles que o convite fala. */
    const val INVITATIONS_PATH = "v1/social/groups/invitations"

    /**
     * A resposta a um convite mora fora de `groups/{groupId}`.
     *
     * O motivo é de produto: quem aceita ainda **não** é membro, e uma rota aninhada no grupo daria
     * a impressão de que o `groupId` faz parte da autorização. Aqui a autorização é o convite.
     */
    const val GROUP_INVITATIONS_PATH = "v1/social/group-invitations"

    fun groupPath(groupId: String): String = "$GROUPS_PATH/${encode(groupId)}"

    fun membersPath(groupId: String): String = "${groupPath(groupId)}/members"

    fun memberPath(groupId: String, membershipId: String): String =
        "${membersPath(groupId)}/${encode(membershipId)}"

    fun groupInvitationsPath(groupId: String): String = "${groupPath(groupId)}/invitations"

    fun acceptInvitationPath(invitationId: String): String =
        "$GROUP_INVITATIONS_PATH/${encode(invitationId)}/accept"

    fun declineInvitationPath(invitationId: String): String =
        "$GROUP_INVITATIONS_PATH/${encode(invitationId)}/decline"

    fun cancelInvitationPath(invitationId: String): String =
        "$GROUP_INVITATIONS_PATH/${encode(invitationId)}/cancel"

    fun leavePath(groupId: String): String = "${groupPath(groupId)}/leave"

    fun transferOwnershipPath(groupId: String): String = "${groupPath(groupId)}/transfer-ownership"

    fun sharePath(groupId: String, checkInId: String): String =
        "${groupPath(groupId)}/checkins/${encode(checkInId)}"

    /** O feed aceita **só** `limit`. Não existe `?users=`, e o servidor recusa qualquer outro. */
    fun feedPath(groupId: String, limit: Int? = null): String {
        val base = "${groupPath(groupId)}/feed"
        return if (limit == null) base else "$base?limit=$limit"
    }

    /** Em quais Squads um check-in próprio já está (§141). */
    fun checkInGroupsPath(checkInId: String): String =
        "v1/social/workout-checkins/${encode(checkInId)}/groups"

    /**
     * Escapa um identificador de caminho.
     *
     * Os valores aqui são UUIDs do servidor, então na prática nada precisa de escape. Ele existe
     * porque "na prática" é uma garantia que some no dia em que o formato mudar, e uma barra no
     * meio de um identificador viraria outro caminho — que o servidor recusa, mas com um erro que
     * ninguém entenderia.
     */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    object ErrorCodes {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"
        const val API_RATE_LIMITED = "API_RATE_LIMITED"
        const val INVALID_GROUP_REQUEST = "INVALID_GROUP_REQUEST"
        const val INVALID_GROUP_NAME = "INVALID_GROUP_NAME"
        const val SOCIAL_NOT_ENABLED = "SOCIAL_NOT_ENABLED"
        const val GROUP_NOT_FOUND = "GROUP_NOT_FOUND"
        const val GROUP_FORBIDDEN = "GROUP_FORBIDDEN"
        const val GROUP_OWNED_LIMIT_REACHED = "GROUP_OWNED_LIMIT_REACHED"
        const val GROUP_MEMBERSHIP_LIMIT_REACHED = "GROUP_MEMBERSHIP_LIMIT_REACHED"
        const val GROUP_FULL = "GROUP_FULL"
        const val GROUP_INVITE_NOT_ALLOWED = "GROUP_INVITE_NOT_ALLOWED"
        const val GROUP_ALREADY_MEMBER = "GROUP_ALREADY_MEMBER"
        const val GROUP_INVITE_LIMIT_REACHED = "GROUP_INVITE_LIMIT_REACHED"
        const val INVITATION_NOT_AVAILABLE = "INVITATION_NOT_AVAILABLE"
        const val GROUP_OWNER_ACTION_REQUIRED = "GROUP_OWNER_ACTION_REQUIRED"
        const val GROUP_MEMBER_NOT_FOUND = "GROUP_MEMBER_NOT_FOUND"
        const val CHECKIN_NOT_FOUND = "CHECKIN_NOT_FOUND"
        const val GROUP_SHARE_LIMIT_REACHED = "GROUP_SHARE_LIMIT_REACHED"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val SOCIAL_UNAVAILABLE = "SOCIAL_UNAVAILABLE"

        /** A recusa de desativar o Social enquanto houver Squad com posse por resolver (§98/§99). */
        const val GROUP_OWNERSHIP_REQUIRES_ACTION = "GROUP_OWNERSHIP_REQUIRES_ACTION"
    }

    /**
     * Os limites, espelhando os do servidor (§8/§11/§18/§68).
     *
     * Eles existem aqui para **resposta imediata na tela** — desabilitar o botão, mostrar o
     * contador —, e nunca como autorização: quem decide é o servidor, que revalida tudo. É a mesma
     * relação da normalização de `friendCode` da T17.1.
     */
    object Limits {
        const val MIN_NAME_LENGTH = 3
        const val MAX_NAME_LENGTH = 40
        const val MAX_MEMBERS = 20
        const val MAX_OWNED_GROUPS = 5
        const val MAX_SHARES_PER_CHECKIN = 5
    }
}
