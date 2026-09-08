/**
 * O contrato do grafo social do Spark — pedidos de amizade e amizade bilateral (T17.1).
 *
 * O espelho Kotlin é `com.example.data.social.FriendshipContract`. A descrição legível — grafo,
 * autoridade, privacidade — vive em `docs/architecture/social-domain.md`.
 *
 * ```text
 * friendCode ──lookup──▶ SocialProfilePreview ──send──▶ FriendRequest ──accept──▶ Friendship(A,B)
 * ```
 *
 * ## O que um `friendCode` permite, e o que ele nunca permite
 *
 * Ele é **descoberta controlada**, não credencial. Conhecer `SPK-7K2P9D8Q` permite, no máximo,
 * resolver um preview mínimo e mandar um pedido que o dono aceita ou recusa. Ele nunca dá acesso a
 * treino, backup, sync, e-mail, Firebase UID, medidas ou histórico — e **ser amigo tampouco**.
 * Amizade é uma relação no domínio social; ela não é chave para o domínio privado.
 *
 * ## Server-authoritative, como todo o social
 *
 * Não há Outbox, `revision`, `cursor` de sync, tombstone nem `clientMutationId` aqui. Criar ou
 * alterar uma relação exige conta e servidor: um aparelho offline não pode decidir sozinho um fato
 * que é sobre **duas** contas. O treino continua local-first, e nada disto o alcança.
 */

import type { SocialProfilePreviewDto } from './social.contract';

// --------------------------------------------------------------------------------- lookup

/**
 * O desfecho de um lookup por `friendCode`.
 *
 * `NOT_FOUND` é deliberadamente a **mesma** resposta para três situações diferentes: código
 * malformado, código que nunca existiu e código de um perfil `DISABLED`. Distinguir qualquer uma
 * delas transformaria a rota num oráculo — bastaria comparar respostas para descobrir que um
 * código existe mas está desligado, que é exatamente o que desativar deveria esconder, ou para
 * usar o servidor como validador gratuito de formato enquanto se tenta enumerar.
 */
export const FRIEND_LOOKUP_RESULTS = ['FOUND', 'SELF', 'NOT_FOUND'] as const;
export type FriendLookupResult = (typeof FRIEND_LOOKUP_RESULTS)[number];

/**
 * A relação que **já existe** entre quem consultou e quem foi encontrado.
 *
 * Ela não revela nada sobre terceiros: só descreve o próprio grafo de quem perguntou. É o que
 * permite à tela oferecer a ação certa — "Enviar solicitação", "Solicitação enviada",
 * "Responder ao pedido dele" ou "Vocês já são amigos" — em vez de oferecer sempre a mesma e
 * descobrir o estado real só depois do toque.
 */
export const FRIEND_RELATIONSHIPS = [
  'NONE',
  'FRIENDS',
  'OUTGOING_PENDING',
  'INCOMING_PENDING',
] as const;
export type FriendRelationship = (typeof FRIEND_RELATIONSHIPS)[number];

/**
 * `POST /v1/social/friends/lookup`.
 *
 * **`POST`, e não `GET` com o código na URL.** O `friendCode` não é segredo, mas é um
 * identificador social compartilhável, e a URL é a parte da requisição que vaza mais fácil: log de
 * proxy, log de acesso do Caddy, histórico de navegador, referer. O corpo não aparece em nenhum
 * dos quatro, e o `SparkLogger` já redige corpo por padrão. O custo é uma rota "de leitura" que
 * usa `POST` — e é um custo pequeno perto de uma lista de códigos válidos em texto claro no log de
 * acesso.
 *
 * Consultar **não** envia pedido nenhum: o envio é outra rota, depois de o usuário ver quem
 * apareceu (§69). É isso que impede um erro de digitação de virar convite imediato.
 */
export interface FriendLookupRequestDto {
  readonly friendCode: string;
}

export interface FriendLookupResponseDto {
  readonly result: FriendLookupResult;
  /** Só em `FOUND`. Nunca carrega `ownerUid`, e-mail, `friendCode` ou dado de treino. */
  readonly profile?: SocialProfilePreviewDto;
  /** Só em `FOUND`. */
  readonly relationship?: FriendRelationship;
  /**
   * Só em `FOUND`: o perfil aceita pedidos **agora**.
   *
   * É uma dica de UI, e não uma permissão. O envio revalida tudo (§114/§115): entre o lookup e o
   * toque em "Enviar solicitação" o outro lado pode ter desativado o social ou desligado os
   * pedidos, e quem decide é sempre o servidor no momento do envio — nunca o preview guardado.
   */
  readonly canSendFriendRequest?: boolean;
}

// --------------------------------------------------------------------------------- pedidos

export const FRIEND_REQUEST_STATUSES = ['PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED'] as const;
export type FriendRequestStatus = (typeof FRIEND_REQUEST_STATUSES)[number];

/** De onde o pedido veio, do ponto de vista de quem está lendo a lista. */
export const FRIEND_REQUEST_DIRECTIONS = ['INCOMING', 'OUTGOING'] as const;
export type FriendRequestDirection = (typeof FRIEND_REQUEST_DIRECTIONS)[number];

/**
 * Um pedido de amizade, como o participante o vê.
 *
 * `requestId` é UUID v4 — opaco, não sequencial, e não diz quantos pedidos existem no servidor.
 * `profile` é sempre **a outra pessoa**: numa lista de recebidos é quem pediu, numa de enviados é
 * quem vai responder. O próprio dono não aparece na própria lista.
 */
export interface FriendRequestDto {
  readonly requestId: string;
  readonly profile: SocialProfilePreviewDto;
  readonly direction: FriendRequestDirection;
  /** Relógio do servidor, epoch millis UTC. */
  readonly createdAt: number;
}

/**
 * `POST /v1/social/friend-requests`.
 *
 * O alvo é o `socialId` — a identidade **pública** que o lookup devolveu —, e não o `friendCode`.
 * Depois de descoberto, o código já cumpriu a função dele; continuar carregando-o faria um
 * identificador compartilhável circular em toda mutação sem necessidade nenhuma.
 */
export interface SendFriendRequestDto {
  readonly socialId: string;
}

/**
 * O desfecho de um envio.
 *
 * Os três são **sucesso**, e isso é deliberado:
 *
 * - `REQUEST_CREATED` — nasceu um pedido pendente;
 * - `REQUEST_ALREADY_PENDING` — já havia um pedido igual. É o caso do reenvio depois de uma
 *   resposta perdida e o do toque duplo (§25/§108): a situação lógica é a mesma que o cliente
 *   queria, então tratá-la como erro faria um retry seguro parecer uma falha;
 * - `FRIENDSHIP_CREATED` — havia um pedido **inverso** pendente e a amizade foi criada na hora
 *   (§26). Ver a política abaixo.
 *
 * ## A política de pedido cruzado, e por que ela é a auto-aceitação
 *
 * ```text
 * A → B  PENDING
 * B → A  (envio)
 *        ↓
 * amizade criada, pedido de A marcado ACCEPTED — na mesma transação
 * ```
 *
 * A alternativa seria responder "já existe um pedido para você, aceite-o". Ela é defensável, e foi
 * recusada por uma razão simples: quando A pede B **e** B pede A, o consentimento bilateral já foi
 * expresso pelos dois, explicitamente. Exigir um terceiro toque não protege ninguém — protege
 * contra uma intenção que ambos acabaram de declarar — e produz a tela mais confusa do fluxo
 * ("você já pediu, agora aceite o pedido dele").
 *
 * Ela nunca cria duas amizades nem dois pedidos: a resolução acontece dentro de uma transação, e a
 * chave primária do par canônico torna a duplicata irrepresentável.
 */
export const SEND_FRIEND_REQUEST_RESULTS = [
  'REQUEST_CREATED',
  'REQUEST_ALREADY_PENDING',
  'FRIENDSHIP_CREATED',
] as const;
export type SendFriendRequestResult = (typeof SEND_FRIEND_REQUEST_RESULTS)[number];

export interface SendFriendRequestResponseDto {
  readonly result: SendFriendRequestResult;
  /** Em `REQUEST_CREATED` e `REQUEST_ALREADY_PENDING`. */
  readonly request?: FriendRequestDto;
  /** Em `FRIENDSHIP_CREATED`. */
  readonly friend?: FriendDto;
}

/**
 * `POST /v1/social/friend-requests/:requestId/accept`.
 *
 * `ALREADY_FRIENDS` é sucesso, não erro: é a resposta de aceitar duas vezes, e a de tentar de novo
 * depois de uma resposta perdida (§109). O estado que o cliente queria é o estado que existe.
 */
export const ACCEPT_FRIEND_REQUEST_RESULTS = ['ACCEPTED', 'ALREADY_FRIENDS'] as const;
export type AcceptFriendRequestResult = (typeof ACCEPT_FRIEND_REQUEST_RESULTS)[number];

export interface AcceptFriendRequestResponseDto {
  readonly result: AcceptFriendRequestResult;
  readonly friend: FriendDto;
}

/** `POST /v1/social/friend-requests/:requestId/reject`. Recusar duas vezes é idempotente. */
export const REJECT_FRIEND_REQUEST_RESULTS = ['REJECTED', 'ALREADY_REJECTED'] as const;
export type RejectFriendRequestResult = (typeof REJECT_FRIEND_REQUEST_RESULTS)[number];

export interface RejectFriendRequestResponseDto {
  readonly result: RejectFriendRequestResult;
}

/** `POST /v1/social/friend-requests/:requestId/cancel`. Cancelar duas vezes é idempotente. */
export const CANCEL_FRIEND_REQUEST_RESULTS = ['CANCELLED', 'ALREADY_CANCELLED'] as const;
export type CancelFriendRequestResult = (typeof CANCEL_FRIEND_REQUEST_RESULTS)[number];

export interface CancelFriendRequestResponseDto {
  readonly result: CancelFriendRequestResult;
}

export interface FriendRequestListResponseDto {
  readonly requests: readonly FriendRequestDto[];
  /**
   * Quantos pedidos pendentes existem ao todo — não quantos vieram nesta página.
   *
   * Ele existe porque a seção social do Perfil mostra "1 solicitação pendente" sem abrir a lista,
   * e derivar esse número do tamanho da página faria o texto mentir na página seguinte. É um
   * `COUNT(*)` sobre índice, e não um contador desnormalizado numa coluna — um contador é uma
   * segunda verdade que diverge no primeiro erro.
   */
  readonly total: number;
  /**
   * Continuação opaca, quando há mais.
   *
   * Ausente significa "acabou". O conteúdo é detalhe do servidor e o cliente só o devolve como
   * veio — um cursor que o cliente saiba montar é um cursor que ele vai montar errado.
   */
  readonly nextCursor?: string;
}

// --------------------------------------------------------------------------------- amizade

/**
 * Um amigo, na lista de amigos.
 *
 * `friendCode` **não** está aqui, e a ausência é o contrato (§40): depois da amizade criada, quem
 * identifica é o `socialId`. Devolver o código de todos os amigos em toda listagem transformaria
 * uma lista de contatos numa lista de convites redistribuíveis, sem que ninguém tivesse escolhido
 * isso.
 *
 * Também não estão aqui, e não podem entrar sem uma projeção explícita (T17.2/T17.4): nível, XP,
 * sequência, frequência, último treino, medidas e qualquer outro dado de domínio privado.
 */
export interface FriendDto {
  readonly socialId: string;
  readonly displayName: string;
  /** Quando a amizade foi criada. Relógio do servidor, epoch millis UTC. */
  readonly friendsSince: number;
}

export interface FriendListResponseDto {
  readonly friends: readonly FriendDto[];
  /** Quantos amigos ao todo. Mesma razão do `total` dos pedidos. */
  readonly total: number;
  readonly nextCursor?: string;
}

/**
 * `POST /v1/social/friends/remove`.
 *
 * `POST` com o alvo no corpo em vez de `DELETE /v1/social/friends/:socialId` por duas razões: o
 * `SparkBackendClient` do Android tem `GET`/`POST`/`PATCH` e nenhum caminho precisa de um verbo
 * novo, e o identificador social fica fora da URL — logo, fora de log de proxy e de log de acesso,
 * como no lookup. O efeito é o mesmo, e a rota diz o que faz.
 */
export interface RemoveFriendDto {
  readonly socialId: string;
}

export interface RemoveFriendResponseDto {
  readonly result: 'REMOVED';
}

// --------------------------------------------------------------------------------- erros

export const FRIENDSHIP_ERROR_CODES = {
  /** Corpo, parâmetro ou valor fora do contrato — inclusive um campo server-side no corpo. */
  INVALID_FRIEND_REQUEST: 'INVALID_FRIEND_REQUEST',
  /**
   * Quem chamou tem perfil social, mas ele está `DISABLED`.
   *
   * Nenhuma rota do grafo funciona nesse estado — nem as de leitura (§50). Um perfil desativado
   * não é descobrível, não aparece na lista dos amigos dele e não movimenta pedido: as relações
   * ficam **suspensas**, preservadas, e voltam inteiras ao reativar. Isso é o oposto de apagar.
   */
  SOCIAL_PROFILE_DISABLED: 'SOCIAL_PROFILE_DISABLED',
  /**
   * O `socialId` alvo não corresponde a nenhum perfil **alcançável**.
   *
   * Inexistente e desativado dão a mesma resposta, pela mesma razão do lookup.
   */
  SOCIAL_PROFILE_NOT_FOUND: 'SOCIAL_PROFILE_NOT_FOUND',
  /** Pedido para si mesmo. Impossível também no banco (`CHECK`), não só aqui. */
  SELF_FRIEND_REQUEST: 'SELF_FRIEND_REQUEST',
  /** O destinatário desligou `friendRequestsEnabled`. A autoridade é o servidor (§114). */
  FRIEND_REQUESTS_DISABLED: 'FRIEND_REQUESTS_DISABLED',
  /** Já existe amizade entre os dois. Enviar pedido não recria nada (§34). */
  ALREADY_FRIENDS: 'ALREADY_FRIENDS',
  /** O pedido não existe — ou não existe **para quem perguntou**, que é a mesma resposta (§117). */
  FRIEND_REQUEST_NOT_FOUND: 'FRIEND_REQUEST_NOT_FOUND',
  /** O pedido existe e já é terminal. Estado terminal não volta para `PENDING` (§19). */
  FRIEND_REQUEST_NOT_PENDING: 'FRIEND_REQUEST_NOT_PENDING',
  /** Participante errado: só o destinatário aceita ou recusa (§28/§29). */
  NOT_REQUEST_RECIPIENT: 'NOT_REQUEST_RECIPIENT',
  /** Participante errado: só quem enviou cancela (§30). */
  NOT_REQUEST_SENDER: 'NOT_REQUEST_SENDER',
  /** Não há amizade para remover. */
  FRIENDSHIP_NOT_FOUND: 'FRIENDSHIP_NOT_FOUND',
  /** Teto próprio do lookup ou do envio de pedidos, por conta autenticada (§11–§14). */
  SOCIAL_RATE_LIMITED: 'SOCIAL_RATE_LIMITED',
} as const;

export type FriendshipErrorCode =
  (typeof FRIENDSHIP_ERROR_CODES)[keyof typeof FRIENDSHIP_ERROR_CODES];

/** Rotas do grafo, sob o mesmo `/v1/social` da T17.0. */
export const FRIENDS_ROUTE_PREFIX = 'social/friends';
export const FRIEND_REQUESTS_ROUTE_PREFIX = 'social/friend-requests';
