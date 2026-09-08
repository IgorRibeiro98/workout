package com.example.data.social

import com.example.domain.social.FriendSocialProfile
import com.example.domain.social.ProgressSharing
import com.example.domain.social.ProgressSharingAvailability
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SharedProgress
import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialProfileError
import com.example.domain.social.SocialProfileGateway
import com.example.domain.social.SocialProfileOutcome
import kotlinx.coroutines.CompletableDeferred

/**
 * O perfil social enriquecido do Spark Backend, em memória (T17.2).
 *
 * Não é um mock de chamadas: é um **servidor de mentira com estado**, que reproduz as regras que o
 * servidor de verdade aplica — preferências por conta, privacidade filtrando na saída, campos
 * indisponíveis que **não viram zero**, e isolamento entre contas. É isso que permite ao teste de
 * troca de conta e ao de "ligado sem dado" afirmarem alguma coisa: contra um mock que devolve
 * objetos fixos, os dois passariam sem provar nada.
 *
 * Vive em `test/`, e só em `test/`.
 */
class FakeSocialProfileGateway(
    override val isConfigured: Boolean = true
) : SocialProfileGateway {

    /** A conta autenticada, do ponto de vista deste "servidor". Trocá-la é trocar de conta. */
    var currentUid: String? = null

    /** O que a próxima operação deve responder, quando não for para funcionar. */
    var failWith: SocialProfileError? = null

    /**
     * Quando presente, a próxima operação **espera** aqui.
     *
     * É o que permite observar o estado no meio do voo — em particular, provar que trocar de conta
     * descarta a resposta que estava a caminho.
     */
    var gate: CompletableDeferred<Unit>? = null

    /** Quantas requisições saíram. É o contador que prova "uma por toque, e nenhuma sozinha". */
    var requestCount: Int = 0
        private set

    /** As preferências de cada conta, como o servidor as guarda. */
    private val settingsByUid = mutableMapOf<String, ProgressSharingSettings>()

    /** O que **cada conta** consegue afirmar. O teste monta a disponibilidade que quer provar. */
    private val availabilityByUid = mutableMapOf<String, ProgressSharingAvailability>()

    /** O progresso canônico de cada conta, antes de qualquer privacidade. */
    private val progressByUid = mutableMapOf<String, SharedProgress>()

    /** `socialId → uid`, para responder o perfil de um amigo. */
    private val uidBySocialId = mutableMapOf<String, String>()
    private val displayNameByUid = mutableMapOf<String, String>()

    /** As amizades, como pares não ordenados. Sem amizade não há perfil. */
    private val friendships = mutableSetOf<Set<String>>()

    // ------------------------------------------------------------------ montagem do cenário

    fun register(
        uid: String,
        socialId: String,
        displayName: String,
        progress: SharedProgress = SharedProgress(),
        availability: ProgressSharingAvailability = ProgressSharingAvailability(),
        settings: ProgressSharingSettings = ProgressSharingSettings()
    ) {
        uidBySocialId[socialId] = uid
        displayNameByUid[uid] = displayName
        progressByUid[uid] = progress
        availabilityByUid[uid] = availability
        settingsByUid[uid] = settings
    }

    fun makeFriends(uidA: String, uidB: String) {
        friendships += setOf(uidA, uidB)
    }

    fun unfriend(uidA: String, uidB: String) {
        friendships -= setOf(uidA, uidB)
    }

    fun settingsOf(uid: String): ProgressSharingSettings =
        settingsByUid[uid] ?: ProgressSharingSettings()

    // ------------------------------------------------------------------ o "servidor"

    override suspend fun friendProfile(
        socialId: String
    ): SocialProfileOutcome<FriendSocialProfile> = respond {
        val viewer = currentUid ?: return@respond fail(SocialProfileError.AUTH_REQUIRED)
        val targetUid = uidBySocialId[socialId]
            // Inexistente, desativado e "não somos amigos" respondem a mesma coisa — como no
            // servidor de verdade.
            ?: return@respond fail(SocialProfileError.PROFILE_UNAVAILABLE)
        if (setOf(viewer, targetUid) !in friendships) {
            return@respond fail(SocialProfileError.PROFILE_UNAVAILABLE)
        }
        SocialProfileOutcome.Success(profileOf(targetUid, socialId))
    }

    override suspend fun profilePreview(): SocialProfileOutcome<FriendSocialProfile> = respond {
        val uid = currentUid ?: return@respond fail(SocialProfileError.AUTH_REQUIRED)
        val socialId = uidBySocialId.entries.firstOrNull { it.value == uid }?.key
            ?: return@respond fail(SocialProfileError.SOCIAL_NOT_ENABLED)
        // A prévia passa pelo **mesmo** filtro do perfil do amigo. Um caminho separado aqui faria
        // o dublê esconder exatamente o defeito que este teste existe para pegar.
        SocialProfileOutcome.Success(profileOf(uid, socialId))
    }

    override suspend fun progressSharing(): SocialProfileOutcome<ProgressSharing> = respond {
        val uid = currentUid ?: return@respond fail(SocialProfileError.AUTH_REQUIRED)
        SocialProfileOutcome.Success(sharingOf(uid))
    }

    override suspend fun updateProgressSharing(
        shareLevel: Boolean?,
        shareConsistencyStreak: Boolean?,
        shareWeeklyWorkoutCount: Boolean?,
        shareHighlightedAchievements: Boolean?,
        weekTimeZone: String?
    ): SocialProfileOutcome<ProgressSharing> = respond {
        val uid = currentUid ?: return@respond fail(SocialProfileError.AUTH_REQUIRED)
        val current = settingsOf(uid)
        // Semântica de PATCH: o que não veio não muda. E `updatedAt` é do "servidor".
        settingsByUid[uid] = current.copy(
            shareLevel = shareLevel ?: current.shareLevel,
            shareConsistencyStreak = shareConsistencyStreak ?: current.shareConsistencyStreak,
            shareWeeklyWorkoutCount = shareWeeklyWorkoutCount ?: current.shareWeeklyWorkoutCount,
            shareHighlightedAchievements =
                shareHighlightedAchievements ?: current.shareHighlightedAchievements,
            weekTimeZone = weekTimeZone ?: current.weekTimeZone,
            updatedAt = current.updatedAt + 1
        )
        SocialProfileOutcome.Success(sharingOf(uid))
    }

    // ------------------------------------------------------------------ regras do dublê

    /**
     * A privacidade aplicada na saída, com a disponibilidade por cima.
     *
     * As duas condições valem, como no servidor: o campo só sai se o dono ligou **e** se há dado.
     * Ligado sem dado devolve ausência — nunca zero.
     */
    private fun profileOf(uid: String, socialId: String): FriendSocialProfile {
        val settings = settingsOf(uid)
        val availability = availabilityByUid[uid] ?: ProgressSharingAvailability()
        val progress = progressByUid[uid] ?: SharedProgress()

        fun <T> visible(shared: Boolean, field: SocialFieldAvailability, value: T?): T? =
            if (shared && field == SocialFieldAvailability.AVAILABLE) value else null

        return FriendSocialProfile(
            socialId = socialId,
            displayName = displayNameByUid[uid].orEmpty(),
            sharedProgress = SharedProgress(
                level = visible(settings.shareLevel, availability.level, progress.level),
                consistencyStreak = visible(
                    settings.shareConsistencyStreak,
                    availability.consistencyStreak,
                    progress.consistencyStreak
                ),
                weeklyWorkoutCount = visible(
                    settings.shareWeeklyWorkoutCount,
                    availability.weeklyWorkoutCount,
                    progress.weeklyWorkoutCount
                ),
                highlightedAchievementIds = visible(
                    settings.shareHighlightedAchievements,
                    availability.highlightedAchievements,
                    progress.highlightedAchievementIds
                ).orEmpty()
            )
        )
    }

    private fun sharingOf(uid: String) = ProgressSharing(
        settings = settingsOf(uid),
        availability = availabilityByUid[uid] ?: ProgressSharingAvailability()
    )

    private suspend fun <T> respond(
        block: suspend () -> SocialProfileOutcome<T>
    ): SocialProfileOutcome<T> {
        requestCount++
        gate?.await()
        failWith?.let { return SocialProfileOutcome.Failure(it) }
        return block()
    }

    private fun fail(error: SocialProfileError): SocialProfileOutcome<Nothing> =
        SocialProfileOutcome.Failure(error)
}
