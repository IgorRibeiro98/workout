package com.example.domain.social

/**
 * A fronteira do perfil social enriquecido com o Spark Backend (T17.2).
 *
 * ```text
 * UI → SocialProfileViewModel → SocialProfileGateway → Spark Backend
 *                                                           │
 *                                                      autoridade
 * ```
 *
 * ## Por que separada do [FriendGateway]
 *
 * O grafo responde "com quem eu me relaciono"; este responde "o que eu mostro, e o que eu vejo de
 * alguém". São telas diferentes, momentos diferentes e ritmos diferentes: a lista de amigos é lida
 * ao abrir a seção, e um perfil é lido **quando alguém toca em um nome** (§64). Juntá-los criaria a
 * tentação de enriquecer a lista — e enriquecer a lista significa uma requisição por linha (§65).
 *
 * ## O que esta fronteira nunca faz
 *
 * - **não escreve no Room.** Não existe `FriendProgressEntity`, DAO ou transação atrás dela (§63).
 *   Guardar o progresso de terceiros no aparelho criaria uma cópia que sobrevive à mudança de
 *   privacidade da outra pessoa — e a cópia continuaria mostrando o que ela desligou;
 * - **não registra mutação na Outbox** (§108/§138). Offline, alterar o compartilhamento **não
 *   acontece**: não fica pendente e não é reenviado. A tela diz que nada foi enviado;
 * - **não guarda o resultado.** O perfil lido vive no estado do ViewModel, em memória, e é
 *   descartado na troca de conta (§61/§109);
 * - **não dispara sincronização** (§68). Abrir o perfil de alguém não pede push nem pull de
 *   ninguém: o que o servidor sabe é o que ele já sabia;
 * - **não envia progresso.** Não existe método que mande nível, sequência ou contagem — nem por
 *   engano, porque nenhum parâmetro daqui os aceita (§85–§87).
 */
interface SocialProfileGateway {

    /** `true` quando existe endereço de Spark Backend neste build. Sem ele, nada é oferecido. */
    val isConfigured: Boolean

    /**
     * O perfil enriquecido de um amigo.
     *
     * Exige amizade **ativa** no momento da requisição, e quem verifica é o servidor. Um perfil que
     * o app leu há dez minutos não autoriza nada: entre uma leitura e outra, a outra pessoa pode
     * ter desfeito a amizade, desativado o Social ou desligado um campo.
     */
    suspend fun friendProfile(socialId: String): SocialProfileOutcome<FriendSocialProfile>

    /** Exatamente o que um amigo veria de mim agora — montado pelo mesmo caminho no servidor. */
    suspend fun profilePreview(): SocialProfileOutcome<FriendSocialProfile>

    /** O que eu compartilho, e o que o servidor consegue mostrar de cada campo. */
    suspend fun progressSharing(): SocialProfileOutcome<ProgressSharing>

    /**
     * Altera o que eu compartilho. Parcial: `null` significa "não mexa neste campo".
     *
     * [weekTimeZone] é o fuso do aparelho, e não uma escolha do usuário: ele existe para que a
     * semana do servidor seja a mesma semana da tela de consistência. Ele não é progresso — é o
     * parâmetro que permite ao servidor derivar a contagem sem inventar um fuso.
     */
    suspend fun updateProgressSharing(
        shareLevel: Boolean? = null,
        shareConsistencyStreak: Boolean? = null,
        shareWeeklyWorkoutCount: Boolean? = null,
        shareHighlightedAchievements: Boolean? = null,
        weekTimeZone: String? = null
    ): SocialProfileOutcome<ProgressSharing>
}
