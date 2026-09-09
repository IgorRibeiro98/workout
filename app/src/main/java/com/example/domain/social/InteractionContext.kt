package com.example.domain.social

/**
 * A audiência de uma interação — reação ou comentário — sobre um check-in (T17.12 §4/§7/§29).
 *
 * ## Por que uma interação precisa de audiência
 *
 * Um mesmo [WorkoutCheckIn] alcança várias superfícies ao mesmo tempo: o Feed de amigos e cada
 * Squad em que ele foi compartilhado. Até a T17.11 a reação e o comentário eram do **check-in**, e
 * não do lugar onde a conversa aconteceu — então um comentário escrito dentro do Squad X aparecia
 * no Squad Y e no Feed de amigos, para gente que não fazia parte daquela conversa. A T17.11 evitou
 * o vazamento tornando o acesso só-por-Squad **somente leitura** (§70/§71); a T17.12 resolve a
 * causa: a interação passa a pertencer a uma audiência explícita.
 *
 * ```text
 * WorkoutCheckIn
 *  ├── Feed de amigos → interações FRIEND
 *  ├── Squad A        → interações GROUP(A)
 *  └── Squad B        → interações GROUP(B)
 * ```
 *
 * A publicação continua sendo **uma só** — nada é duplicado. A mesma pessoa pode ter 🔥 no Feed de
 * amigos e 💪 no Squad A ao mesmo tempo, e as duas são independentes.
 *
 * ## Ela é da tela, e nunca global
 *
 * Este tipo pertence à **publicação sendo vista naquela tela**, e não a um estado de aplicação:
 * um "contexto atual" em singleton faria a tela de detalhe aberta a partir do Squad A herdar o
 * contexto de quem abriu o Squad B um instante antes, e a interação nasceria na audiência errada
 * sem nenhum sintoma visível. Por isso ele viaja como parâmetro — da rota de navegação para a
 * ViewModel, e da ViewModel para o gateway.
 *
 * ## Ela é uma **proposta**, e não uma concessão (§7/§9/§67/§69)
 *
 * Quem decide se este aparelho realmente alcança aquela publicação por aquele Squad é o servidor,
 * que revalida compartilhamento, participação ativa e bloqueio a cada requisição. Um contexto que
 * não confere é recusado com `404` — ele **nunca** "cai" para [Friend], porque um comentário
 * escrito para um Squad que virasse comentário no Feed de amigos publicaria a conversa para quem
 * não estava nela. Por isso o app nunca lê um contexto que veio do servidor: ele monta o contexto
 * a partir de **onde o usuário estava** quando tocou.
 *
 * `PUBLIC`, `FOLLOWERS`, `CUSTOM`, `MULTI_GROUP` e mensagem direta continuam fora de escopo (§4).
 */
sealed interface InteractionContext {

    /**
     * A chave desta audiência para mapas de estado da tela.
     *
     * Ela existe porque um cache — comentários, reações em voo, contagens — indexado só pelo
     * `checkInId` mostraria os comentários do Squad X dentro do Squad Y assim que a mesma
     * publicação fosse aberta nos dois. A chave de qualquer estado por publicação é o par
     * `(checkInId, audiência)`, e [key] é a metade que faltava.
     */
    val key: String

    /** O valor que vai no protocolo. O servidor aceita `FRIEND` e `GROUP`, e mais nada (§29). */
    val wireType: String

    /** O Squad, quando a audiência é um. `null` no Feed de amigos — as duas não coexistem (§27). */
    val groupId: String?

    /** O Feed de amigos. É a audiência de toda publicação alcançada por relação direta. */
    data object Friend : InteractionContext {
        override val key: String = "FRIEND"
        override val wireType: String = "FRIEND"
        override val groupId: String? = null
    }

    /**
     * Um Squad específico.
     *
     * [groupId] é o identificador opaco do servidor, o mesmo da rota do Squad — e ele **não
     * concede acesso** (T17.11 §59): quem não é membro recebe o mesmo `404` de "não existe".
     */
    data class Group(override val groupId: String) : InteractionContext {
        override val key: String = "GROUP:$groupId"
        override val wireType: String = "GROUP"
    }
}

/**
 * A chave de um estado de tela por publicação **e** audiência (T17.12 §61).
 *
 * Todo mapa/conjunto que guarde algo por check-in — reação em voo, comentários carregados, envio
 * pendente — usa isto como chave. Indexar só pelo `checkInId` é o defeito que a T17.12 lista como
 * bloqueante: a mesma publicação aberta em dois Squads compartilharia a conversa dos dois.
 */
fun interactionKey(checkInId: String, context: InteractionContext): String =
    "${context.key}|$checkInId"
