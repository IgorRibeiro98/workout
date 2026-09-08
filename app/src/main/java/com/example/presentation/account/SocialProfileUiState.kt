package com.example.presentation.account

import com.example.domain.social.FriendSocialProfile
import com.example.domain.social.ProgressSharingAvailability
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SocialProfileError

/**
 * O estado do perfil social enriquecido na tela (T17.2).
 *
 * ## Duas telas, dois estados, e por que eles não são um `isLoading`
 *
 * "Carregando o perfil do Igor", "o Igor não compartilha nada", "sem internet", "vocês não são
 * mais amigos" e "salvando a sua configuração" levam a **cinco telas diferentes**, e um booleano
 * colapsaria as cinco. Pior: colapsar "não compartilha" com "erro" faria a tela sugerir tentar de
 * novo para um estado que é normal e definitivo até a outra pessoa mudar de ideia.
 */
sealed interface FriendProfilePhase {

    /** Nada foi pedido ainda. É o estado antes de o usuário tocar em um amigo. */
    data object Idle : FriendProfilePhase

    data object Loading : FriendProfilePhase

    /** Carregado, e há pelo menos um campo compartilhado. */
    data class Ready(val profile: FriendSocialProfile) : FriendProfilePhase

    /**
     * Carregado, e a pessoa não compartilha nada agora.
     *
     * Estado próprio, e não uma variação de [Ready] com lista vazia, porque o texto é outro:
     * "ainda não compartilha informações de progresso". A tela **nunca** diz "não treina" — ela
     * não sabe disso, e o servidor não afirmou isso.
     *
     * Ele também cobre "compartilha, mas o servidor ainda não tem o dado": para quem olha, os dois
     * são a mesma ausência, de propósito.
     */
    data class NoSharedProgress(val profile: FriendSocialProfile) : FriendProfilePhase

    /**
     * O perfil não está disponível.
     *
     * Quatro situações chegam aqui e o app **não** as distingue: vocês deixaram de ser amigos, a
     * pessoa desativou o Social, o `socialId` não existe, ou havia apenas um pedido pendente.
     * O servidor responde a mesma coisa para as quatro, e adivinhar seria reconstruir na tela a
     * informação que ele recusou dar.
     */
    data object NotAvailable : FriendProfilePhase

    /** Sem internet. A mensagem que importa é que nada foi enviado e nada ficou pendente. */
    data object Offline : FriendProfilePhase

    /** Falhou por outra razão. [reason] é classe de erro, nunca mensagem do servidor. */
    data class Error(val reason: SocialProfileError) : FriendProfilePhase
}

/** O estado da tela "Compartilhar progresso" (§105). */
sealed interface ProgressSharingPhase {

    data object Idle : ProgressSharingPhase

    data object Loading : ProgressSharingPhase

    data object Ready : ProgressSharingPhase

    /**
     * Uma alteração está em voo.
     *
     * A UI **não** faz atualização otimista (§106): o interruptor só se move depois que o servidor
     * confirmou. É a escolha simples e correta para privacidade — um interruptor que se move e
     * depois volta sozinho deixa a pessoa sem saber o que está valendo.
     */
    data object Saving : ProgressSharingPhase

    /** A conta não tem perfil social, ou ele está desativado. O conselho é ir ao Perfil. */
    data class SocialUnavailable(val disabled: Boolean) : ProgressSharingPhase

    /** Sem internet. Offline, alterar privacidade **não acontece** — e a tela diz isso. */
    data object Offline : ProgressSharingPhase

    data class Error(val reason: SocialProfileError) : ProgressSharingPhase
}

/**
 * O que a UI do perfil social precisa saber.
 *
 * Tudo aqui é **cache de leitura**, não fonte de verdade — a autoridade é o Spark Backend. Vive só
 * em memória, dentro do ViewModel, e é descartado na troca de conta: nada disso é gravado no Room,
 * no DataStore ou em arquivo (§61/§63).
 */
data class SocialProfileUiState(
    /** `false` quando não há endereço de Spark Backend neste build. */
    val isConfigured: Boolean = true,

    // ------------------------------------------------------------------ perfil de um amigo

    /** De quem é o perfil que está aberto. `null` quando nenhum está. */
    val openedSocialId: String? = null,
    val friendPhase: FriendProfilePhase = FriendProfilePhase.Idle,

    // ------------------------------------------------------------------ minhas configurações

    val sharingPhase: ProgressSharingPhase = ProgressSharingPhase.Idle,
    val settings: ProgressSharingSettings = ProgressSharingSettings(),
    val availability: ProgressSharingAvailability = ProgressSharingAvailability(),

    /**
     * A prévia do que um amigo veria de mim — a mesma resposta do servidor, pelo mesmo caminho.
     *
     * `null` enquanto ela não for pedida: ela sai de um toque em "Pré-visualizar meu perfil", e
     * não de abrir a tela, porque ela é uma pergunta a mais para o servidor.
     */
    val preview: FriendSocialProfile? = null,
    val isPreviewLoading: Boolean = false,

    /**
     * Um aviso pontual sobre a última ação.
     *
     * Separado de [ProgressSharingPhase.Error] porque ele **não** substitui a tela: os
     * interruptores continuam visíveis e utilizáveis, e o aviso some na próxima ação.
     */
    val notice: SocialProfileError? = null
) {
    /** A tela de configurações está ocupada por inteiro? */
    val isSharingBusy: Boolean
        get() = sharingPhase is ProgressSharingPhase.Loading ||
            sharingPhase is ProgressSharingPhase.Saving
}
