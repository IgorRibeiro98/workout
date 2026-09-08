package com.example.domain.social

/**
 * A fronteira do domínio social com o Spark Backend (T17.0).
 *
 * ```text
 * UI → SocialViewModel → SocialGateway → Spark Backend
 *                                            │
 *                                       autoridade
 * ```
 *
 * ## Por que gateway, e não repositório
 *
 * "Repositório" no Spark significa uma coisa concreta: um dono de dado local, com Room atrás e
 * `Flow` na frente. `WorkoutRepository`, `BackupRepository` e `SyncRepository` são todos assim, e
 * o social não é nada disso — ele não tem banco, não tem `Flow` observável, não tem Outbox e não
 * tem o que reconciliar. Chamar de repositório sugeriria um cache durável que não existe, e o
 * primeiro leitor tentaria adicioná-lo.
 *
 * É a mesma escolha do `AiCoachGateway` (T14/T16.2), pelo mesmo motivo: uma capacidade **online**
 * cuja resposta é do servidor.
 *
 * ## O que esta fronteira nunca faz
 *
 * - **não escreve no Room.** Não existe DAO, `AppDatabase` nem transação atrás dela — nem por
 *   dependência, nem por chamada. É por construção, e não por disciplina, que ativar Social não
 *   cria linha nenhuma no banco do aparelho;
 * - **não registra mutação na Outbox.** Social não é dado local-first e não converge por sync: uma
 *   entrada na Outbox seria enviada pelo protocolo de treino, que não sabe o que é um perfil;
 * - **não guarda o resultado.** Quem quiser lembrar o último perfil lido guarda em memória, ciente
 *   de que é cache e não autoridade — e o invalida ao trocar de conta;
 * - **não retenta sozinha.** Uma ação explícita do usuário produz no máximo uma requisição.
 *   Offline, a ação **não acontece** e a tela diz isso; ela não fica "pendente" em lugar nenhum.
 */
interface SocialGateway {

    /** `true` quando existe endereço de Spark Backend neste build. Sem ele, nada é oferecido. */
    val isConfigured: Boolean

    /** O perfil da conta autenticada. [SocialOutcome.NotEnabled] é o estado normal de quem nunca ativou. */
    suspend fun profile(): SocialOutcome

    /**
     * Ativa os recursos sociais para a conta autenticada.
     *
     * Idempotente no servidor: chamar duas vezes devolve o mesmo perfil, com o mesmo `socialId` e
     * o mesmo `friendCode`. Nunca é chamado por `init`, por abertura de tela ou por login — só por
     * toque explícito depois de o usuário ler o que será criado.
     */
    suspend fun activate(displayName: String): SocialOutcome

    /** Renomeia. Identidade não muda: `socialId` e `friendCode` continuam os mesmos. */
    suspend fun updateDisplayName(displayName: String): SocialOutcome

    /** Altera as configurações de privacidade informadas. `null` significa "não mexer neste campo". */
    suspend fun updatePrivacy(
        discoverability: SocialDiscoverability? = null,
        friendRequestsEnabled: Boolean? = null,
        activitySharingEnabled: Boolean? = null
    ): SocialOutcome

    /** Desativa. Não apaga Conta Spark, treino, histórico, backup nem sincronização. */
    suspend fun disable(): SocialOutcome

    /** Reativa, com a mesma identidade de antes. */
    suspend fun enable(): SocialOutcome
}

/**
 * O desfecho de uma operação social.
 *
 * Modelado com nomes, e não com `Result<SocialProfile>`: "você não está logado", "você ainda não
 * ativou", "o servidor está fora" e "este nome não serve" levam a **conselhos diferentes** na
 * tela, e um tipo genérico obrigaria a UI a reinterpretar mensagem de erro para descobrir qual
 * deles é.
 */
sealed interface SocialOutcome {

    /** O perfil atual da conta. */
    data class Success(val profile: SocialProfile) : SocialOutcome

    /**
     * A conta está autenticada e **não** tem perfil social.
     *
     * É um estado normal do produto — o padrão, na verdade —, e por isso tem nome próprio em vez
     * de virar [Failure]. Social é opcional.
     */
    data object NotEnabled : SocialOutcome

    /** A operação não foi concluída. [error] diz o que a tela deve oferecer em seguida. */
    data class Failure(val error: SocialError) : SocialOutcome
}

/**
 * Por que uma operação social não completou.
 *
 * Classes de falha, e não mensagens do servidor: mensagem de servidor não é texto de UI, e um
 * `code` interno não ajuda quem está olhando a tela.
 */
enum class SocialError {

    /** Não há endereço de Spark Backend neste build. Nenhuma requisição foi feita. */
    NOT_CONFIGURED,

    /** É preciso entrar na Conta Spark. Não é falha do social. */
    AUTH_REQUIRED,

    /** A conta não tem perfil social e a operação exigia um. */
    NOT_ENABLED,

    /** Já estava ativo — outro aparelho reativou antes. A tela recarrega em vez de mostrar erro. */
    ALREADY_ENABLED,

    /** Já estava desativado. Mesmo tratamento de [ALREADY_ENABLED]. */
    ALREADY_DISABLED,

    /** O nome social não passa nas regras. A tela aponta o campo. */
    INVALID_DISPLAY_NAME,

    /** O servidor recusou a requisição. Isso é defeito, e o texto convida a relatar. */
    REJECTED,

    /** O Spark Backend respondeu indisponível. Recuperável — tentar de novo depois resolve. */
    UNAVAILABLE,

    /** Muitas requisições para esta conta. Recuperável. */
    RATE_LIMITED,

    /** Sem internet ou servidor inalcançável. **Nada foi enviado**, e nada ficou pendente. */
    NETWORK
}
