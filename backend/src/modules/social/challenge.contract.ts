/**
 * O contrato dos desafios entre amigos (T17.3).
 *
 * O espelho Kotlin é `com.example.data.social.ChallengeContract`. A descrição legível — autoridade,
 * consentimento, pontuação e sincronização tardia — vive em
 * `docs/architecture/challenge-domain.md`.
 *
 * ```text
 * Friendship ──convite──▶ ChallengeInvitation ──aceitar──▶ participação
 *                                                                │
 *                                        dados canônicos de treino (sync)
 *                                                                ▼
 *                                                    score · rank · goalReached
 * ```
 *
 * ## O cliente nunca envia pontuação
 *
 * Não existe — e não pode existir — campo de entrada com `score`, `progress`, `rank`, `winner`,
 * `count` ou `points`. Eles são recusados **por nome**, invalidando a requisição inteira
 * (`challenge.validator.ts`), pela mesma razão que a T17.2 recusa `level` e `weeklyWorkoutCount`:
 * um APK modificado não pode se declarar com 12 de 12.
 *
 * O que o cliente envia é **intenção** — quais regras, quais amigos, e a resposta a um convite.
 * A pontuação é derivada no servidor, na leitura, dos dados canônicos de treino que já chegaram
 * por sync.
 *
 * ## Server-authoritative, como todo o social
 *
 * Não há Outbox, `revision`, `cursor`, tombstone nem `clientMutationId` aqui. O treino continua
 * local-first — e é justamente por isso que a elegibilidade de uma sessão é o **instante em que
 * ela aconteceu**, e não o instante em que ela chegou ao servidor (§71/§72).
 */

import type { SocialProfilePreviewDto } from './social.contract';

// --------------------------------------------------------------------------------- tipos

/**
 * Os tipos de desafio desta fase.
 *
 * Dois, e não mais, porque só estes dois têm autoridade canônica **remota** clara hoje:
 *
 * ```text
 * WORKOUTS_COMPLETED   sessões COMPLETED na janela          sync_entities / WORKOUT_SESSION
 * ACTIVE_DAYS          dias de calendário com ≥1 sessão      o mesmo, agrupado por dia local
 * ```
 *
 * `TOTAL_VOLUME`, `TOTAL_SETS`, `TOTAL_REPS`, `TIME_TRAINED` e `CALORIES` exigiriam abrir o
 * payload da sessão — séries, cargas, repetições, horários —, que é exatamente o que
 * `AGGREGATE_ONLY` (`social.projection.ts`) proíbe. `XP_GAINED` e `PR_COUNT` não têm autoridade
 * remota nenhuma: `xp_transactions` e os PRs são DERIVED na matriz da T16 e nunca saem do
 * aparelho. Nenhum deles nasce sem uma tarefa própria que resolva a fonte primeiro.
 */
export const CHALLENGE_TYPES = ['WORKOUTS_COMPLETED', 'ACTIVE_DAYS'] as const;
export type ChallengeType = (typeof CHALLENGE_TYPES)[number];

/**
 * O status **derivado** de um desafio (§26/§27).
 *
 * Nenhum destes cinco é uma coluna. O banco guarda `lifecycle` (`OPEN`/`CANCELLED`) — a única
 * parte que alguém escreve — e o resto é a leitura do relógio do servidor contra a janela:
 *
 * ```text
 * lifecycle = CANCELLED                              ──▶ CANCELLED
 * now  <  startsAt                                   ──▶ UPCOMING
 * now >=  startsAt  e  participantes ativos < 2      ──▶ VOID
 * now  <  endsAtExclusive                            ──▶ ACTIVE
 * caso contrário                                     ──▶ ENDED
 * ```
 *
 * Derivar, e não persistir, é o que dispensa um cron (§27). Um desafio marcado `UPCOMING` numa
 * coluna continuaria `UPCOMING` para sempre se o processo que o viraria não rodasse — e o modo de
 * falhar seria um desafio que nunca começa, silenciosamente.
 *
 * `VOID` cobre as duas formas de "não há competição aqui": ninguém aceitou antes de começar
 * (§28), e todo mundo saiu. Ele é **monotônico** — uma vez `VOID`, sempre `VOID` —, porque entrar
 * exige aceitar, aceitar exige `now < startsAt`, e `VOID` só é avaliado depois disso.
 *
 * `ENDED` significa **a janela de elegibilidade fechou**, e não "o resultado é final" (§73/§252).
 * Um treino feito dentro da janela e sincronizado depois ainda conta — é isso que impede o
 * Spark de punir quem treinou offline.
 */
export const CHALLENGE_STATUSES = ['UPCOMING', 'ACTIVE', 'ENDED', 'CANCELLED', 'VOID'] as const;
export type ChallengeStatus = (typeof CHALLENGE_STATUSES)[number];

/** O papel de um participante (§38). */
export const CHALLENGE_ROLES = ['CREATOR', 'MEMBER'] as const;
export type ChallengeRole = (typeof CHALLENGE_ROLES)[number];

/** A participação (§39). `WITHDRAWN` é terminal: não há rejoin nesta fase (§66). */
export const CHALLENGE_PARTICIPANT_STATUSES = ['JOINED', 'WITHDRAWN'] as const;
export type ChallengeParticipantStatus = (typeof CHALLENGE_PARTICIPANT_STATUSES)[number];

/**
 * O status **derivado** de um convite (§36).
 *
 * `PENDING`, `ACCEPTED` e `DECLINED` são gravados. Os outros dois não:
 *
 * ```text
 * PENDING + desafio cancelado    ──▶ CANCELLED
 * PENDING + now >= startsAt      ──▶ EXPIRED     (a transição lazy de §55)
 * ```
 *
 * Derivar em vez de gravar é o que garante que "aceitar depois do início" seja **impossível** em
 * vez de "improvável": não depende de um processo ter passado por ali antes do toque.
 */
export const CHALLENGE_INVITATION_STATUSES = [
  'PENDING',
  'ACCEPTED',
  'DECLINED',
  'EXPIRED',
  'CANCELLED',
] as const;
export type ChallengeInvitationStatus = (typeof CHALLENGE_INVITATION_STATUSES)[number];

// --------------------------------------------------------------------------------- criação

/**
 * `POST /v1/social/challenges`.
 *
 * ```json
 * {
 *   "clientRequestId": "b6f1...",
 *   "name": "12 treinos",
 *   "type": "WORKOUTS_COMPLETED",
 *   "target": 12,
 *   "startDate": "2026-09-10",
 *   "endDate": "2026-10-09",
 *   "timeZoneId": "America/Sao_Paulo",
 *   "invitedSocialIds": ["...", "..."]
 * }
 * ```
 *
 * **Nunca** `creatorUid`, `participantUids`, `status`, `score` ou `startsAt` (§33). O dono sai do
 * token verificado; a janela em instantes é derivada aqui (§13); e a pontuação não é coisa que o
 * aparelho afirme.
 */
export interface CreateChallengeRequestDto {
  /**
   * O identificador da **tentativa** (§188).
   *
   * Gerado pelo Android, UUID. Ele existe para que o reenvio depois de uma resposta perdida —
   * e o toque duplo — devolvam o desafio que já foi criado, em vez de criarem um segundo com os
   * mesmos amigos convidados de novo.
   */
  readonly clientRequestId: string;
  readonly name: string;
  readonly type: ChallengeType;
  readonly target: number;
  /** Data de calendário `YYYY-MM-DD`, inclusiva. Pelo menos o dia seguinte no fuso escolhido (§15). */
  readonly startDate: string;
  /** Data de calendário `YYYY-MM-DD`, **inclusiva** — o último dia que conta. */
  readonly endDate: string;
  /** Fuso IANA do desafio (§8). Um só, para todos (§9). O Android sugere; o servidor valida (§10). */
  readonly timeZoneId: string;
  /**
   * Quem é convidado, por `socialId` (§32/§33).
   *
   * Cada um precisa ser amigo **ativo** do criador **agora**. Um `socialId` que não seja, ou um
   * perfil desativado, faz o desafio inteiro não ser criado (§45): um desafio criado pela metade,
   * com dois dos três amigos que a pessoa escolheu, é pior do que um erro que ela pode corrigir.
   */
  readonly invitedSocialIds: readonly string[];
}

// --------------------------------------------------------------------------------- leitura

/** As regras do desafio, como qualquer participante as vê. Imutáveis depois da criação (§47/§49). */
export interface ChallengeSummaryDto {
  readonly challengeId: string;
  readonly name: string;
  readonly type: ChallengeType;
  readonly target: number;
  readonly startDate: string;
  readonly endDate: string;
  readonly timeZoneId: string;
  /** Derivado (§26/§27). Ver [CHALLENGE_STATUSES]. */
  readonly status: ChallengeStatus;
  /** Quem criou — preview mínimo, nunca uid. */
  readonly creator: SocialProfilePreviewDto;
  /** Quantos participantes ativos (`JOINED`) existem agora. */
  readonly participantCount: number;
  /** Relógio do servidor, epoch millis UTC. */
  readonly createdAt: number;
}

/**
 * Uma linha do placar.
 *
 * `score` é **derivado no servidor**, na leitura, dos dados canônicos de treino. Ele nunca chega
 * do cliente e nunca é gravado.
 *
 * O que esta estrutura deliberadamente **não** carrega (§125–§127): `sessionId`, `syncId` de
 * sessão, `exerciseId`, nome de treino, horário de qualquer sessão, séries, cargas, repetições,
 * notas, peso corporal, medidas, Firebase UID, e-mail e `friendCode`. Participar de um desafio dá
 * acesso ao **placar**, e não aos dados que o produziram (§quarto princípio).
 */
export interface ChallengeParticipantScoreDto {
  readonly socialId: string;
  readonly displayName: string;
  /** Quantos pontos, pelas regras do tipo. Pode ultrapassar o `target` (§86). */
  readonly score: number;
  /** `score >= target`. Separado de "vencedor" de propósito (§93): vários podem bater a meta. */
  readonly goalReached: boolean;
  /**
   * Posição, em *competition ranking* (§89): `1, 1, 3`.
   *
   * Empate **permanece** empate. Não há desempate por ordem de chegada ao servidor (§90) nem por
   * `createdAt` da sessão (§91): o primeiro puniria quem sincronizou depois, e o segundo inventaria
   * um critério que ninguém combinou.
   */
  readonly rank: number;
  /** Papel no desafio. Só para a UI distinguir quem criou. */
  readonly role: ChallengeRole;
  /** É a linha de quem está olhando (§170). */
  readonly isViewer: boolean;
}

/**
 * `GET /v1/social/challenges/:challengeId` — as regras mais o placar.
 *
 * Só participantes ativos e o criador recebem isto. Quem tem convite **pendente** recebe o preview
 * ([ChallengePreviewDto]), sem placar (§99): ver o progresso de alguém antes de consentir em
 * mostrar o próprio é exatamente a assimetria que o consentimento existe para impedir.
 */
export interface ChallengeDetailResponseDto {
  readonly challenge: ChallengeSummaryDto;
  /** Ordenado por `score` decrescente. Participantes `WITHDRAWN` não aparecem (§96). */
  readonly participants: readonly ChallengeParticipantScoreDto[];
  /**
   * Quantos convites ainda estão pendentes.
   *
   * Só para o criador (§172). Os outros participantes recebem o campo ausente: eles não precisam
   * saber quem ainda não respondeu, e os **nomes** não são revelados a ninguém (§173).
   */
  readonly pendingInvitationCount?: number;
  /** Quantos saíram. Metadado opcional de §96 — sem nomes. */
  readonly withdrawnCount: number;
  /**
   * O papel de quem está olhando, e o que ele pode fazer.
   *
   * Derivado no servidor: a UI não decide quem cancela e quem sai (§174/§175). Um app
   * desatualizado que oferecesse o botão errado receberia o erro tipado correspondente.
   */
  readonly viewer: ChallengeViewerDto;
  /**
   * O resultado ainda pode mudar por sincronização tardia (§74/§179).
   *
   * `true` sempre que a janela já fechou (`ENDED`): o servidor **não sabe** se todos os
   * participantes já sincronizaram tudo o que fizeram no período, e afirmar um resultado final
   * seria afirmar o que ele não tem como verificar (§75/§253). A tela usa isto para dizer a
   * verdade em vez de prometer irrevogabilidade.
   */
  readonly resultMayStillChange: boolean;
}

export interface ChallengeViewerDto {
  readonly role: ChallengeRole;
  readonly status: ChallengeParticipantStatus;
  /** Só o criador, e só enquanto o desafio não é terminal (§67/§68/§69). */
  readonly canCancel: boolean;
  /** Só membro `JOINED`; o criador cancela em vez de sair (§62). */
  readonly canLeave: boolean;
}

/**
 * O que o convidado vê **antes** de aceitar (§98).
 *
 * Regras, quem convidou e quantos já aceitaram — o suficiente para decidir. **Sem placar, sem
 * nomes de participantes e sem progresso de ninguém** (§99).
 */
export interface ChallengePreviewDto {
  readonly challenge: ChallengeSummaryDto;
  readonly invitationId: string;
  readonly status: ChallengeInvitationStatus;
  readonly createdAt: number;
}

export interface ChallengeListResponseDto {
  /** Ordenados: `ACTIVE`, depois `UPCOMING` por data de início, depois os encerrados (§107). */
  readonly challenges: readonly ChallengeSummaryDto[];
  readonly total: number;
  readonly nextCursor?: string;
}

export interface ChallengeInvitationListResponseDto {
  readonly invitations: readonly ChallengePreviewDto[];
  readonly total: number;
  readonly nextCursor?: string;
}

// --------------------------------------------------------------------------------- mutações

/**
 * O desfecho de uma criação.
 *
 * `ALREADY_CREATED` é **sucesso**: é o reenvio depois de resposta perdida e o toque duplo (§187),
 * com o mesmo `clientRequestId` e o mesmo conteúdo. O estado que o cliente queria é o que existe.
 */
export const CREATE_CHALLENGE_RESULTS = ['CREATED', 'ALREADY_CREATED'] as const;
export type CreateChallengeResult = (typeof CREATE_CHALLENGE_RESULTS)[number];

export interface CreateChallengeResponseDto {
  readonly result: CreateChallengeResult;
  readonly challenge: ChallengeSummaryDto;
}

/** `POST /v1/social/challenge-invitations/:invitationId/accept`. Aceitar duas vezes é sucesso. */
export const ACCEPT_CHALLENGE_RESULTS = ['ACCEPTED', 'ALREADY_PARTICIPATING'] as const;
export type AcceptChallengeResult = (typeof ACCEPT_CHALLENGE_RESULTS)[number];

export interface AcceptChallengeResponseDto {
  readonly result: AcceptChallengeResult;
  readonly challenge: ChallengeSummaryDto;
}

/** `POST /v1/social/challenge-invitations/:invitationId/decline`. Idempotente (§192). */
export const DECLINE_CHALLENGE_RESULTS = ['DECLINED', 'ALREADY_DECLINED'] as const;
export type DeclineChallengeResult = (typeof DECLINE_CHALLENGE_RESULTS)[number];

export interface DeclineChallengeResponseDto {
  readonly result: DeclineChallengeResult;
}

/** `POST /v1/social/challenges/:challengeId/leave`. Idempotente (§193). */
export const LEAVE_CHALLENGE_RESULTS = ['LEFT', 'ALREADY_LEFT'] as const;
export type LeaveChallengeResult = (typeof LEAVE_CHALLENGE_RESULTS)[number];

export interface LeaveChallengeResponseDto {
  readonly result: LeaveChallengeResult;
}

/** `POST /v1/social/challenges/:challengeId/cancel`. Idempotente (§194). */
export const CANCEL_CHALLENGE_RESULTS = ['CANCELLED', 'ALREADY_CANCELLED'] as const;
export type CancelChallengeResult = (typeof CANCEL_CHALLENGE_RESULTS)[number];

export interface CancelChallengeResponseDto {
  readonly result: CancelChallengeResult;
}

// --------------------------------------------------------------------------------- erros

export const CHALLENGE_ERROR_CODES = {
  /** Corpo, parâmetro ou valor fora do contrato — inclusive um campo de pontuação. */
  INVALID_CHALLENGE_REQUEST: 'INVALID_CHALLENGE_REQUEST',
  /** Tipo que este servidor não conhece, ou que ainda não tem fonte canônica. */
  INVALID_CHALLENGE_TYPE: 'INVALID_CHALLENGE_TYPE',
  /** Meta fora dos limites — inclusive a meta impossível de `ACTIVE_DAYS` (§21). */
  INVALID_CHALLENGE_TARGET: 'INVALID_CHALLENGE_TARGET',
  /** Datas fora de forma, invertidas, curtas/longas demais, ou começando hoje/no passado (§15). */
  INVALID_CHALLENGE_PERIOD: 'INVALID_CHALLENGE_PERIOD',
  /** O identificador não é um fuso IANA que este runtime conhece (§10). */
  INVALID_CHALLENGE_TIMEZONE: 'INVALID_CHALLENGE_TIMEZONE',
  /** Mais convidados do que cabe (§30), ou o desafio já está cheio. */
  TOO_MANY_PARTICIPANTS: 'TOO_MANY_PARTICIPANTS',
  /**
   * Um `socialId` convidado não é amigo ativo do criador **agora** (§32).
   *
   * Alvo inexistente, alvo desativado e "não somos amigos" respondem **a mesma coisa**, pela mesma
   * razão do lookup da T17.1: distinguir transformaria a criação num oráculo sobre contas alheias.
   */
  CHALLENGE_PARTICIPANT_NOT_AVAILABLE: 'CHALLENGE_PARTICIPANT_NOT_AVAILABLE',
  /** O criador atingiu o teto de desafios abertos (§111). */
  TOO_MANY_OPEN_CHALLENGES: 'TOO_MANY_OPEN_CHALLENGES',
  /**
   * O desafio não existe — **ou** não existe para quem perguntou (§100/§182).
   *
   * Uma conta C que adivinhe um `challengeId` recebe exatamente isto, indistinguível de um id
   * inventado. Saber que um desafio existe já é mais do que ela deveria aprender.
   */
  CHALLENGE_NOT_FOUND: 'CHALLENGE_NOT_FOUND',
  /** O convite não existe, ou não é de quem perguntou. Mesma resposta, mesma razão. */
  CHALLENGE_INVITATION_NOT_FOUND: 'CHALLENGE_INVITATION_NOT_FOUND',
  /** O convite existe, é seu, e já foi respondido. Estado terminal não volta para `PENDING`. */
  CHALLENGE_INVITATION_NOT_PENDING: 'CHALLENGE_INVITATION_NOT_PENDING',
  /** O desafio já começou: aceitar tarde é bloqueado (§54/§56), e o convite está expirado (§55). */
  CHALLENGE_ALREADY_STARTED: 'CHALLENGE_ALREADY_STARTED',
  /** O desafio foi cancelado pelo criador. */
  CHALLENGE_CANCELLED: 'CHALLENGE_CANCELLED',
  /** Só o criador cancela (§67). */
  NOT_CHALLENGE_CREATOR: 'NOT_CHALLENGE_CREATOR',
  /** O criador não sai; ele cancela (§62). */
  CANNOT_LEAVE_AS_CREATOR: 'CANNOT_LEAVE_AS_CREATOR',
  /**
   * Mesmo `clientRequestId`, conteúdo diferente (§190).
   *
   * Não é um retry — é um cliente reusando um identificador de tentativa para outro pedido, e
   * atendê-lo criaria um desafio que o usuário não veria como novo.
   */
  CHALLENGE_IDEMPOTENCY_CONFLICT: 'CHALLENGE_IDEMPOTENCY_CONFLICT',
  /** Teto próprio da criação ou das respostas, por conta autenticada (§108/§109). */
  CHALLENGE_RATE_LIMITED: 'CHALLENGE_RATE_LIMITED',
} as const;

export type ChallengeErrorCode = (typeof CHALLENGE_ERROR_CODES)[keyof typeof CHALLENGE_ERROR_CODES];

/** Rotas dos desafios, sob o mesmo `/v1/social` da T17.0. */
export const CHALLENGES_ROUTE_PREFIX = 'social/challenges';
export const CHALLENGE_INVITATIONS_ROUTE_PREFIX = 'social/challenge-invitations';
