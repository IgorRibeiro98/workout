package com.example.data.social

/**
 * O contrato do perfil social enriquecido, do lado do Android (T17.2).
 *
 * O espelho TypeScript é `backend/src/modules/social/social-profile.contract.ts`, e a descrição
 * legível está em `docs/architecture/social-profile-contract.md`. Mudou de um lado, muda do outro —
 * e as duas versões precisam do mesmo commit.
 *
 * ## O que o app envia, e o que ele nunca envia
 *
 * ```text
 * ENVIA    shareLevel, shareConsistencyStreak, shareWeeklyWorkoutCount,
 *          shareHighlightedAchievements, weekTimeZone
 *
 * NUNCA    level, streak, weeklyWorkoutCount, xp, conquistas obtidas
 * ```
 *
 * A segunda lista não é uma disciplina deste arquivo: o servidor **recusa a requisição inteira** se
 * qualquer um daqueles campos aparecer no corpo. O app não tem como afirmar progresso sobre si
 * mesmo, e é assim que "o perfil social do Igor diz nível 14" continua significando alguma coisa.
 */
object SocialProfileContract {

    /** `GET` — o perfil enriquecido de um amigo. Exige amizade ativa; quem verifica é o servidor. */
    fun friendProfilePath(socialId: String): String =
        "v1/social/friends/${java.net.URLEncoder.encode(socialId, "UTF-8")}/profile"

    /** `GET` — exatamente o que um amigo veria de mim agora. */
    const val PROFILE_PREVIEW_PATH = "v1/social/me/profile-preview"

    /** `GET`/`PATCH` — o que eu compartilho, e o que está disponível. */
    const val PROGRESS_SHARING_PATH = "v1/social/me/progress-sharing"

    /** Os códigos do envelope de erro do servidor que o app traduz. */
    object ErrorCodes {
        const val FRIEND_PROFILE_NOT_FOUND = "FRIEND_PROFILE_NOT_FOUND"
        const val INVALID_PROGRESS_SETTINGS = "INVALID_PROGRESS_SETTINGS"
    }
}
