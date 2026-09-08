package com.example.presentation.account

import com.example.domain.social.SocialError
import com.example.domain.social.SocialProfile

/**
 * O estado dos recursos sociais na tela (T17.0).
 *
 * ## Por que não um `isLoading`
 *
 * "Carregando o perfil", "ativando", "salvando o nome" e "desativando" são esperas diferentes, e
 * quem espera precisa saber qual. Mais importante: "entre na conta", "ative os recursos sociais",
 * "sem internet" e "este nome não serve" são **conselhos opostos**, e um booleano colapsaria os
 * quatro em "carregando/não carregando" — deixando a tela adivinhar o resto a partir de um campo
 * de erro solto.
 */
sealed interface SocialPhase {

    /** Não há endereço de Spark Backend neste build. Nada a oferecer, e dizer isso é honesto. */
    data object NotConfigured : SocialPhase

    /** Sem Conta Spark. Social exige uma; o resto do Spark, não. */
    data object SignedOut : SocialPhase

    /** Lendo o perfil no servidor. Leitura pura — não cria nada. */
    data object Loading : SocialPhase

    /**
     * Autenticado, e os recursos sociais **não** foram ativados.
     *
     * É o estado padrão de toda conta: login não cria perfil social.
     */
    data object NotEnabled : SocialPhase

    /** A ativação está em andamento, a partir de um toque explícito. */
    data object Activating : SocialPhase

    /** Perfil carregado. [profile] é a cópia lida do servidor, nunca uma autoridade local. */
    data class Active(val profile: SocialProfile) : SocialPhase

    /** Salvando nome ou privacidade. O perfil anterior continua visível embaixo. */
    data class Saving(val profile: SocialProfile) : SocialPhase

    /** Desativando. */
    data class Disabling(val profile: SocialProfile) : SocialPhase

    /**
     * Sem internet ou servidor inalcançável.
     *
     * Estado próprio, e não um [Error] genérico: aqui a mensagem certa é "nada foi enviado", que
     * é a diferença entre o usuário achar que renomeou e saber que não renomeou.
     */
    data class Offline(val profile: SocialProfile? = null) : SocialPhase

    /** Falhou por outra razão. [reason] é classe de erro, nunca mensagem do servidor. */
    data class Error(val reason: SocialError, val profile: SocialProfile? = null) : SocialPhase
}

/**
 * O que a UI social precisa saber.
 *
 * [profile] é **cache de leitura**, não fonte de verdade — a autoridade é o Spark Backend. Ele
 * vive só aqui, em memória, e é invalidado na troca de conta: nada disso é gravado no Room, no
 * DataStore ou em arquivo. Ver `SocialViewModel`.
 */
data class SocialUiState(
    val phase: SocialPhase = SocialPhase.NotConfigured,
    /** `true` enquanto a folha de ativação (nome + consentimento) está aberta. */
    val isActivationSheetOpen: Boolean = false,
    /** `true` enquanto a folha de edição de nome está aberta. */
    val isEditingName: Boolean = false,
    /** `true` enquanto a confirmação de desativação está aberta. */
    val isConfirmingDisable: Boolean = false,
    /**
     * A sugestão de nome vinda da conta Google.
     *
     * É **sugestão**, e só: depois da ativação, o perfil social é a autoridade do próprio nome, e
     * trocar de foto ou de nome no Google não reescreve nada aqui.
     */
    val suggestedDisplayName: String? = null,
    /** O que o usuário digitou no campo de nome. Estado de formulário, não de domínio. */
    val displayNameInput: String = "",
    /** `true` quando o nome digitado já tem forma aceitável para ser enviado. */
    val isDisplayNameAcceptable: Boolean = false
) {

    /** O perfil conhecido, em qualquer fase que o tenha. */
    val profile: SocialProfile?
        get() = when (val current = phase) {
            is SocialPhase.Active -> current.profile
            is SocialPhase.Saving -> current.profile
            is SocialPhase.Disabling -> current.profile
            is SocialPhase.Offline -> current.profile
            is SocialPhase.Error -> current.profile
            else -> null
        }

    /** Uma operação em andamento: enquanto isso, novos toques são ignorados. */
    val isBusy: Boolean
        get() = phase is SocialPhase.Loading ||
            phase is SocialPhase.Activating ||
            phase is SocialPhase.Saving ||
            phase is SocialPhase.Disabling
}
