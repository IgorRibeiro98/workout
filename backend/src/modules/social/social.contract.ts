/**
 * O contrato do domínio social entre o Spark Android e o Spark Backend (T17.0).
 *
 * O espelho Kotlin é `com.example.data.social.SocialContract`. A descrição legível — autoridade,
 * identidades, privacidade — vive em `docs/architecture/social-domain.md`.
 *
 * ## Social é server-authoritative, e é a única parte do Spark que é
 *
 * ```text
 * TREINO   ação → domínio → Room → Outbox → backend      local-first (T16.3–T16.7)
 * SOCIAL   ação → backend → resultado → UI/cache         server-authoritative (T17)
 * ```
 *
 * Não existe protocolo de sync aqui: nem `revision`, nem `cursor`, nem `baseRevision`, nem
 * `clientMutationId`, nem tombstone. Uma ação social exige conta e servidor, e isso é intencional
 * — o que o aparelho não pode fazer offline é justamente o que ele não pode decidir sozinho.
 *
 * ## O que o cliente nunca envia
 *
 * `ownerUid`, `socialId`, `friendCode`, `status`, `createdAt` e `updatedAt` são **todos**
 * server-side. O envelope das requisições é estrito: um desses campos no corpo recusa a
 * requisição inteira, em vez de ser ignorado em silêncio. Ignorar seria pior — um cliente que
 * envia `ownerUid` acredita que ele significa alguma coisa.
 */

/** O estado do perfil social de uma conta. A ausência de perfil é `NOT_ENABLED`. */
export const SOCIAL_PROFILE_STATUSES = ['ACTIVE', 'DISABLED'] as const;
export type SocialProfileStatus = (typeof SOCIAL_PROFILE_STATUSES)[number];

/**
 * Quem pode encontrar este perfil.
 *
 * Um valor só na T17.0, e não é um enum "preparado para o futuro" por hábito: ele existe porque a
 * coluna precisa de um domínio explícito e porque a T17.1 vai ler exatamente este campo antes de
 * responder um lookup. Valores como `PUBLIC_SEARCH` não estão aqui porque busca pública não
 * existe neste servidor — declará-los descreveria um comportamento inexistente.
 */
export const SOCIAL_DISCOVERABILITY_VALUES = ['FRIEND_CODE_ONLY'] as const;
export type SocialDiscoverability = (typeof SOCIAL_DISCOVERABILITY_VALUES)[number];

export function isSocialDiscoverability(value: unknown): value is SocialDiscoverability {
  return (SOCIAL_DISCOVERABILITY_VALUES as readonly unknown[]).includes(value);
}

/**
 * As configurações de privacidade, como o dono as vê e as altera.
 *
 * Os defaults são conservadores por decisão (T17.0 §24): descoberta só por código, atividade não
 * compartilhada. Pedidos de amizade nascem habilitados porque a T17.1 é o caminho pretendido de
 * uso — e quem não quiser desliga antes.
 */
export interface SocialPrivacySettingsDto {
  readonly discoverability: SocialDiscoverability;
  readonly friendRequestsEnabled: boolean;
  readonly activitySharingEnabled: boolean;
  readonly activityTimeZoneId: string | null;
  readonly friendRankingParticipationEnabled: boolean;
  /** Relógio do servidor, epoch millis UTC. */
  readonly updatedAt: number;
}

/**
 * O perfil social **do próprio dono**.
 *
 * Contém `friendCode` porque o dono precisa lê-lo para convidar alguém. Isso não o torna parte de
 * toda projeção social: [SocialProfilePreviewDto] — a forma que outra pessoa verá a partir da
 * T17.1 — não o carrega.
 *
 * `ownerUid` **não está aqui**, e a ausência é o contrato: o Firebase UID é identidade privada de
 * infraestrutura e nunca identidade pública do usuário. Há teste que varre este arquivo e as
 * respostas reais procurando `uid`, `ownerUid`, `firebaseUid` e `email`.
 */
export interface SocialOwnerProfileDto {
  readonly socialId: string;
  readonly friendCode: string;
  readonly displayName: string;
  readonly status: SocialProfileStatus;
  readonly privacy: SocialPrivacySettingsDto;
  /** Relógio do servidor, epoch millis UTC. */
  readonly createdAt: number;
  readonly updatedAt: number;
}

/**
 * O perfil social como **outra pessoa** o verá — a partir da T17.1.
 *
 * Declarado agora, e vazio de qualquer coisa sensível, porque é mais fácil manter uma fronteira
 * que já existe do que criá-la depois que três endpoints estiverem devolvendo o DTO do dono
 * "porque já estava pronto". Nenhum endpoint da T17.0 o produz: não há lookup, busca nem
 * listagem nesta fase.
 *
 * O que ele nunca pode ganhar: `ownerUid`, `email`, `friendCode`, provedor de autenticação,
 * metadata de backup, e qualquer projeção de treino que o dono não tenha escolhido publicar.
 */
export interface SocialProfilePreviewDto {
  readonly socialId: string;
  readonly displayName: string;
}

/** O ator de uma atividade social (T17.4). */
export interface SocialActivityActorDto {
  readonly socialId: string;
  readonly displayName: string;
}

/** O item de atividade dos amigos — somente TRAINING_DAY na T17.4. */
export interface SocialActivityItemDto {
  readonly type: 'TRAINING_DAY';
  readonly actor: SocialActivityActorDto;
  readonly daysAgo: number;
}

/** A resposta do feed recente de atividade dos amigos (GET /v1/social/activity). */
export interface SocialActivityResponse {
  readonly items: readonly SocialActivityItemDto[];
}

/** A linha de um participante no ranking entre amigos (T17.4). */
export interface FriendRankingEntryDto {
  readonly socialId: string;
  readonly displayName: string;
  readonly score: number;
  readonly rank: number;
  readonly isCurrentUser: boolean;
}

/** A resposta do ranking contextual de 7 dias entre amigos (GET /v1/social/rankings/last-7-days). */
export interface FriendRankingResponse {
  readonly type: 'WORKOUTS_COMPLETED_LAST_7_DAYS';
  readonly participantCount: number;
  readonly entries: readonly FriendRankingEntryDto[];
}

/**
 * `GET /v1/social/me`.
 *
 * `{ enabled: false }` para uma conta autenticada sem perfil, e não `404`: "você não ativou os
 * recursos sociais" é um estado normal e esperado do produto, não um erro. Usar 404 como fluxo
 * normal obrigaria o cliente a tratar uma falha como se fosse sucesso — e a confundi-la com as
 * outras razões pelas quais um 404 acontece.
 */
export type SocialMeResponse =
  | { readonly enabled: false }
  | { readonly enabled: true; readonly profile: SocialOwnerProfileDto };

/** A resposta de toda rota que devolve o perfil do dono. */
export interface SocialProfileResponse {
  readonly profile: SocialOwnerProfileDto;
}

export const SOCIAL_ERROR_CODES = {
  /** Corpo, campo ou valor fora do contrato — inclusive um campo server-side no corpo. */
  INVALID_SOCIAL_REQUEST: 'INVALID_SOCIAL_REQUEST',
  /** O nome social não passa nas regras de forma. Separado para a UI apontar o campo. */
  INVALID_DISPLAY_NAME: 'INVALID_DISPLAY_NAME',
  /** A conta não tem perfil social. Erro real para quem tenta alterar; não é o caso do `GET`. */
  SOCIAL_NOT_ENABLED: 'SOCIAL_NOT_ENABLED',
  /** `enable` sobre um perfil que já está ativo. */
  SOCIAL_ALREADY_ENABLED: 'SOCIAL_ALREADY_ENABLED',
  /** `disable` sobre um perfil que já está desativado. */
  SOCIAL_ALREADY_DISABLED: 'SOCIAL_ALREADY_DISABLED',
  /** O servidor não conseguiu concluir agora (ex.: geração de código esgotou as tentativas). */
  SOCIAL_UNAVAILABLE: 'SOCIAL_UNAVAILABLE',
  /** Usuário não optou por participar do ranking (reciprocidade, T17.4). */
  RANKING_NOT_ENABLED: 'RANKING_NOT_ENABLED',
  /** Fuso horário da atividade inválido ou ausente quando necessário (T17.4). */
  INVALID_ACTIVITY_TIMEZONE: 'INVALID_ACTIVITY_TIMEZONE',
  /** Atividade indisponível para exibição (T17.4). */
  ACTIVITY_NOT_AVAILABLE: 'ACTIVITY_NOT_AVAILABLE',
  /**
   * `disable` recusado porque a conta é dona de Squads com outras pessoas (T17.11 §98/§99).
   *
   * A mensagem carrega **quantos** Squads precisam de ação, e nunca quem está neles. O caminho de
   * saída é do usuário: transferir a posse para alguém que ele escolha, ou excluir o Squad.
   */
  GROUP_OWNERSHIP_REQUIRES_ACTION: 'GROUP_OWNERSHIP_REQUIRES_ACTION',
} as const;

export type SocialErrorCode = (typeof SOCIAL_ERROR_CODES)[keyof typeof SOCIAL_ERROR_CODES];

/** Prefixo das rotas sociais. Com o versionamento por URI, o caminho real é `/v1/social/...`. */
export const SOCIAL_ROUTE_PREFIX = 'social';
export const SOCIAL_ACTIVITY_ROUTE = 'activity';
export const FRIEND_RANKING_LAST_7_DAYS_ROUTE = 'rankings/last-7-days';
