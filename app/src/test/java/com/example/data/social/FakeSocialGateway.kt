package com.example.data.social

import com.example.domain.social.SocialDiscoverability
import com.example.domain.social.SocialError
import com.example.domain.social.SocialGateway
import com.example.domain.social.SocialOutcome
import com.example.domain.social.SocialPrivacySettings
import com.example.domain.social.SocialProfile
import com.example.domain.social.SocialProfileStatus
import kotlinx.coroutines.CompletableDeferred

/**
 * O Spark Backend social, em memória (T17.0).
 *
 * Ele não é um mock de chamadas: é um **servidor de mentira com estado**, que reproduz as regras
 * que a T17.0 estabeleceu no servidor de verdade — identidade gerada por ele, ativação
 * idempotente, identidade preservada na reativação, e isolamento por conta. É isso que permite ao
 * teste de troca de conta e ao de reativação afirmarem alguma coisa: contra um mock que devolve
 * objetos fixos, os dois passariam sem provar nada.
 *
 * Vive em `test/`, e só em `test/`.
 */
class FakeSocialGateway(
    override val isConfigured: Boolean = true
) : SocialGateway {

    /** A conta autenticada, do ponto de vista deste "servidor". Trocá-la é trocar de conta. */
    var currentUid: String? = null

    /** O que a próxima operação deve responder, quando não for para funcionar. */
    var failWith: SocialError? = null

    /** Perfis por conta — a prova de que a conta A não recebe o perfil da conta B. */
    private val profiles = mutableMapOf<String, SocialProfile>()

    /** Quantas vezes cada operação foi chamada. `activate` é a que mais importa: ela cria. */
    var profileCalls = 0
        private set
    var activateCalls = 0
        private set
    var updateNameCalls = 0
        private set
    var privacyCalls = 0
        private set
    var disableCalls = 0
        private set
    var enableCalls = 0
        private set

    /**
     * Quando presente, a próxima operação **espera** aqui.
     *
     * É o que permite observar o estado "no meio do voo" — em particular, provar que trocar de
     * conta durante uma requisição descarta a resposta que chegar depois.
     */
    var gate: CompletableDeferred<Unit>? = null

    private var nextSocialId = 1
    private var nextFriendCode = 1

    fun seed(uid: String, profile: SocialProfile) {
        profiles[uid] = profile
    }

    fun stored(uid: String): SocialProfile? = profiles[uid]

    override suspend fun profile(): SocialOutcome {
        profileCalls += 1
        return respond { uid ->
            profiles[uid]?.let { SocialOutcome.Success(it) } ?: SocialOutcome.NotEnabled
        }
    }

    override suspend fun activate(displayName: String): SocialOutcome {
        activateCalls += 1
        return respond { uid ->
            // Idempotente, como o servidor: a segunda ativação devolve o perfil que já existe,
            // com a mesma identidade e o mesmo nome.
            val existing = profiles[uid]
            if (existing != null) return@respond SocialOutcome.Success(existing)

            val created = SocialProfile(
                socialId = "social-${nextSocialId++}",
                friendCode = "SPK-CODE${nextFriendCode++}",
                displayName = displayName,
                status = SocialProfileStatus.ACTIVE,
                privacy = SocialPrivacySettings(),
                createdAt = 1_000L,
                updatedAt = 1_000L
            )
            profiles[uid] = created
            SocialOutcome.Success(created)
        }
    }

    override suspend fun updateDisplayName(displayName: String): SocialOutcome {
        updateNameCalls += 1
        return mutate { it.copy(displayName = displayName, updatedAt = it.updatedAt + 1) }
    }

    override suspend fun updatePrivacy(
        discoverability: SocialDiscoverability?,
        friendRequestsEnabled: Boolean?,
        activitySharingEnabled: Boolean?
    ): SocialOutcome {
        privacyCalls += 1
        return mutate { profile ->
            profile.copy(
                privacy = profile.privacy.copy(
                    discoverability = discoverability ?: profile.privacy.discoverability,
                    friendRequestsEnabled = friendRequestsEnabled
                        ?: profile.privacy.friendRequestsEnabled,
                    activitySharingEnabled = activitySharingEnabled
                        ?: profile.privacy.activitySharingEnabled
                )
            )
        }
    }

    override suspend fun disable(): SocialOutcome {
        disableCalls += 1
        return mutate { profile ->
            if (profile.status == SocialProfileStatus.DISABLED) {
                return@mutate null
            }
            // Identidade preservada: só o status muda.
            profile.copy(status = SocialProfileStatus.DISABLED)
        }
    }

    override suspend fun enable(): SocialOutcome {
        enableCalls += 1
        return mutate { profile ->
            if (profile.status == SocialProfileStatus.ACTIVE) {
                return@mutate null
            }
            profile.copy(status = SocialProfileStatus.ACTIVE)
        }
    }

    /** `null` do bloco significa "estado já era esse" — o conflito explícito do servidor. */
    private suspend fun mutate(change: (SocialProfile) -> SocialProfile?): SocialOutcome =
        respond { uid ->
            val current = profiles[uid] ?: return@respond SocialOutcome.NotEnabled
            val updated = change(current)
                ?: return@respond SocialOutcome.Failure(
                    if (current.status == SocialProfileStatus.ACTIVE) {
                        SocialError.ALREADY_ENABLED
                    } else {
                        SocialError.ALREADY_DISABLED
                    }
                )
            profiles[uid] = updated
            SocialOutcome.Success(updated)
        }

    private suspend fun respond(block: (String) -> SocialOutcome): SocialOutcome {
        gate?.await()
        if (!isConfigured) return SocialOutcome.Failure(SocialError.NOT_CONFIGURED)
        failWith?.let { return SocialOutcome.Failure(it) }
        val uid = currentUid ?: return SocialOutcome.Failure(SocialError.AUTH_REQUIRED)
        return block(uid)
    }
}
