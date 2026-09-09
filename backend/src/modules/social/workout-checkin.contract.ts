/**
 * O contrato dos check-ins de treino e do Feed social (T17.8).
 *
 * ## O que um check-in é — e o que ele deliberadamente não é
 *
 * Um `WorkoutCheckIn` é um **artefato social explícito**: ele nasce de um toque do usuário sobre
 * uma sessão que **já** está concluída, e nunca da conclusão em si (§3). Ele não é a atividade da
 * T17.4 — aquela diz "João treinou hoje" quando `activitySharingEnabled` está ligado, e é uma
 * projeção derivada de consentimento **de configuração**. Este diz "João publicou um check-in", e
 * o consentimento é **por sessão**. As duas superfícies coexistem e não se deduplicam (§81).
 *
 * ## O que a T17.9 acrescentou — e o que continua fora
 *
 * A T17.9 expande **este** agregado (T17.9 §5): uma legenda opcional, no máximo uma foto,
 * reações de um enum fechado e comentários. Não existe um segundo `SocialPost`, não existe um
 * segundo Feed, e um check-in publicado pela T17.8 continua válido exatamente como está —
 * `caption = null`, `media = null`, `reactions = {}`, `commentCount = 0`, sem backfill (T17.9 §6).
 *
 * O que continua fora, e é o que mantém a fronteira de privacidade: nome de treino (§40),
 * exercício, série, repetição, carga, duração, volume, PR, caloria e horário do treino (§41–§48).
 * O nome do template é texto que o usuário digita — publicá-lo seria publicar texto livre por uma
 * porta lateral, e a legenda existe justamente para que texto livre só nasça quando a pessoa
 * escrever um (T17.9 §8/§11). Vídeo, GIF animado, múltiplas fotos, carrossel, mention, hashtag,
 * link clicável e edição de publicação também continuam fora (T17.9 §3).
 *
 * ## O único timestamp público é o da publicação
 *
 * `publishedAt` significa **quando a pessoa publicou o check-in**, e não quando ela treinou (§49).
 * O instante do treino é dado privado e não cruza a fronteira em nenhuma forma — nem arredondado,
 * nem como "há X horas treinou".
 */

/**
 * `POST /v1/social/workout-checkins` — o corpo inteiro.
 *
 * Duas strings, e nada além do necessário (§12). O dono **sai do token** (§14): um `ownerUid`,
 * `authorUid` ou `completed` no corpo recusa a requisição inteira — ver `workout-checkin.validator.ts`.
 */
export interface CreateWorkoutCheckInRequest {
  /**
   * A identidade global da sessão canônica. Ela é referência **interna** do dono autenticado para
   * o servidor (§13) e nunca aparece em DTO de feed, perfil, notificação ou log.
   */
  readonly sessionSyncId: string;
  /** Idempotência da intenção do usuário (§30/§109). */
  readonly clientRequestId: string;
  /**
   * A legenda, quando a pessoa escreveu uma (T17.9 §7).
   *
   * Ausente e `null` significam a mesma coisa: sem legenda. Ela **nunca** é preenchida a partir
   * de `WorkoutSession.notes`, `WorkoutTemplate.notes` ou do nome do treino (T17.9 §8/§11) — a
   * legenda nasce só quando o usuário digita algo para publicar, e uma nota privada reaproveitada
   * "porque estava ali" publicaria o que a pessoa escreveu para si mesma.
   */
  readonly caption?: string | null;
  /**
   * A foto, quando existe (T17.9 §33/§42).
   *
   * Um `mediaId` devolvido por um upload **desta conta**, para **esta sessão**, ainda `PENDING`.
   * O servidor revalida os três na hora do anexo, mesmo que o app erre (T17.9 §148/§149).
   */
  readonly mediaId?: string | null;
}

/** O autor de um item do feed: identidade **pública**, e só ela (T17.0). */
export interface WorkoutCheckInAuthorDto {
  readonly socialId: string;
  readonly displayName: string;
}

/**
 * Um item do feed (§50).
 *
 * Campo a campo, este é o contrato inteiro. Não existe `sessionSyncId` (§51), nem metadado da
 * sessão de origem (§52), nem uid, e-mail ou `friendCode` (T17.0).
 */
export interface WorkoutCheckInDto {
  readonly type: 'WORKOUT_CHECK_IN';
  readonly checkInId: string;
  readonly author: WorkoutCheckInAuthorDto;
  /** Quando o **check-in** foi publicado. Nunca quando o treino aconteceu (§49). */
  readonly publishedAt: number;
  /** `null` em toda publicação da T17.8, e em toda publicação sem legenda (T17.9 §60). */
  readonly caption: string | null;
  /** `null` quando não há foto (T17.9 §42). */
  readonly media: WorkoutCheckInMediaDto | null;
  /**
   * Contagem por tipo, já filtrada **para este viewer** (T17.9 §69/§70).
   *
   * Só os tipos com contagem maior que zero aparecem. Não existe lista de quem reagiu: ela
   * exporia participação social de terceiros e cobraria uma consulta por item.
   */
  readonly reactions: Readonly<Partial<Record<ReactionType, number>>>;
  /** A reação do próprio viewer, quando ele tem uma (T17.9 §70). */
  readonly currentUserReaction: ReactionType | null;
  /** Comentários **visíveis para este viewer** (T17.9 §92). Nunca a contagem global. */
  readonly commentCount: number;
  readonly isCurrentUser: boolean;
  /**
   * Se **este** viewer pode reagir e comentar nesta publicação (T17.11 §70/§72/§144).
   *
   * `true` quando ele alcança a publicação por relação direta — é o autor, ou é amigo dele.
   * `false` quando o único caminho até ela é um Squad compartilhado: a T17.11 **não** amplia a
   * autorização de interação para relação puramente de grupo (§70), porque um mesmo check-in em
   * dois Squads e no Feed de amigos passaria a ter uma conversa com três audiências sobrepostas —
   * e resolver isso exige comentários cientes de audiência, que esta fase não introduz em silêncio
   * (§71).
   *
   * A tela usa o booleano para não desenhar o que não funciona (§144), e o servidor recusa de
   * qualquer forma: esconder um botão nunca foi controle de acesso (§72). Toda publicação do Feed
   * de amigos vem com `true` — lá a relação direta é a própria condição de aparecer.
   */
  readonly canInteract: boolean;
}

/**
 * A foto de um check-in, como o Feed a publica (T17.9 §58/§59/§133/§134).
 *
 * `mediaId` e as dimensões, e **nada mais**. Não existe URL pública, não existe caminho de
 * sistema de arquivos e não existe `data:image/webp;base64` — o Android sabe montar a requisição
 * autenticada para `GET /v1/social/media/{mediaId}`, e um link que funcionasse sem token
 * transformaria "amigos" em "qualquer um com o endereço".
 *
 * As dimensões vão junto porque a tela precisa reservar o espaço certo antes de a imagem chegar;
 * sem elas o Feed pularia a cada foto carregada.
 */
export interface WorkoutCheckInMediaDto {
  readonly mediaId: string;
  readonly width: number;
  readonly height: number;
}

/**
 * Os tipos de reação (T17.9 §61/§62).
 *
 * Enum **fechado**, e é o ponto: o cliente não envia emoji. Aceitar um caractere arbitrário seria
 * aceitar conteúdo livre de terceiros na publicação de alguém — sem sanitização, sem limite e sem
 * alvo de denúncia —, o que é exatamente o que esta fase evita ao ter três valores nomeados.
 * A escolha de qual emoji desenhar (🔥 💪 👏) é da tela.
 */
export const REACTION_TYPES = ['FIRE', 'MUSCLE', 'CLAP'] as const;
export type ReactionType = (typeof REACTION_TYPES)[number];

/** `PUT /v1/social/workout-checkins/{id}/reaction` — o corpo inteiro (T17.9 §66). */
export interface PutReactionRequest {
  readonly type: ReactionType;
}

/** `POST /v1/social/workout-checkins/{id}/comments` — o corpo inteiro (T17.9 §81). */
export interface CreateCommentRequest {
  readonly body: string;
}

/**
 * Um comentário (T17.9 §87/§88).
 *
 * `author` é identidade **pública**: `socialId` e `displayName`. Nunca uid, e-mail ou
 * `friendCode` — §88 chama isso de bloqueante, e a varredura de resposta real cobre.
 *
 * `canDelete` é decidido **no servidor** (§93/§94/§95): o autor do comentário e o autor do
 * check-in podem apagar; um terceiro, não. A tela desenha o item de menu a partir deste booleano
 * em vez de recalcular a regra — e o servidor recusa de qualquer forma, porque esconder um botão
 * não é controle de acesso.
 */
export interface CheckInCommentDto {
  readonly commentId: string;
  readonly author: WorkoutCheckInAuthorDto;
  readonly body: string;
  readonly createdAt: number;
  readonly isCurrentUser: boolean;
  readonly canDelete: boolean;
}

/** `GET /v1/social/workout-checkins/{id}/comments` (T17.9 §89/§90/§91). */
export interface CheckInCommentsDto {
  readonly items: readonly CheckInCommentDto[];
}

/**
 * `POST /v1/social/checkin-media` — a resposta do upload (T17.9 §33).
 *
 * O `mediaId` é o único identificador que circula. Ele **não concede acesso** (§51): quem não
 * pode ver o check-in não baixa os bytes, mesmo conhecendo o UUID.
 */
export interface UploadedMediaDto {
  readonly mediaId: string;
  readonly width: number;
  readonly height: number;
  readonly byteSize: number;
  /** Quando a mídia ainda não anexada expira e é limpa (§38/§39). */
  readonly expiresAt: number;
}

/** `GET /v1/social/feed`. Sem cursor histórico: a janela é bounded por desenho (§73–§75). */
export interface SocialFeedDto {
  readonly items: readonly WorkoutCheckInDto[];
}

/** A janela em que uma sessão concluída ainda pode virar check-in (§24). */
export const CHECKIN_WINDOW_MS = 48 * 60 * 60 * 1000;

/** A janela de leitura do feed (§73). */
export const FEED_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;

/** Quantos itens o feed devolve quando o cliente não pede um número (§74). */
export const FEED_DEFAULT_LIMIT = 20;

/** O teto absoluto, aplicado mesmo quando o cliente pede mais (§74). */
export const FEED_MAX_LIMIT = 50;

/**
 * Tolerância para relógio adiantado do aparelho.
 *
 * Uma sessão cujo instante canônico está no futuro além disto é recusada: ela não descreve um
 * treino recente, descreve um relógio errado. O cliente **não** decide elegibilidade (§27) — este
 * número existe para não punir a defasagem normal entre dois relógios.
 */
export const CHECKIN_CLOCK_SKEW_TOLERANCE_MS = 5 * 60 * 1000;

/** Quantos comentários uma página devolve quando o cliente não pede um número (T17.9 §90). */
export const COMMENTS_DEFAULT_PAGE = 30;

/** O teto absoluto de comentários por página (T17.9 §90). */
export const COMMENTS_MAX_PAGE = 100;

export const WORKOUT_CHECKIN_ERRORS = {
  /** Corpo fora do contrato — inclusive um campo server-side (`ownerUid`, `completed`, ...). */
  INVALID_CHECKIN_REQUEST: 'INVALID_CHECKIN_REQUEST',
  /** A conta não tem perfil social ativo. */
  SOCIAL_NOT_ENABLED: 'SOCIAL_NOT_ENABLED',
  /**
   * Anti-enumeração (§116): inexistente, de outra conta, com tombstone e **ainda não
   * sincronizada** respondem exatamente isto. O servidor não distingue os quatro, porque
   * distinguir contaria a quem perguntou o que existe na conta dos outros.
   */
  SESSION_NOT_FOUND: 'SESSION_NOT_FOUND',
  /** A sessão é desta conta e existe, mas não está `COMPLETED` (§17). */
  SESSION_NOT_COMPLETED: 'SESSION_NOT_COMPLETED',
  /** O treino é antigo demais para virar check-in (§28). */
  CHECKIN_WINDOW_EXPIRED: 'CHECKIN_WINDOW_EXPIRED',
  /**
   * Já existe um check-in desta conta para esta sessão, e ele foi **excluído** (§29).
   *
   * Um treino gera no máximo um check-in, e excluir é definitivo para aquela sessão: a linha
   * permanece, a `UNIQUE` continua valendo, e republicar não é oferecido. O caminho idempotente
   * de §33 — outro `clientRequestId`, mesma sessão, check-in **vivo** — devolve o existente e
   * nunca chega aqui.
   */
  CHECKIN_ALREADY_EXISTS: 'CHECKIN_ALREADY_EXISTS',
  /** Mesmo `clientRequestId` para **outra** sessão (§32). */
  CHECKIN_REQUEST_CONFLICT: 'CHECKIN_REQUEST_CONFLICT',
  /** O check-in não existe ou não é desta conta — a mesma resposta para os dois (§65). */
  CHECKIN_NOT_FOUND: 'CHECKIN_NOT_FOUND',
  RATE_LIMITED: 'RATE_LIMITED',
  SOCIAL_UNAVAILABLE: 'SOCIAL_UNAVAILABLE',

  // --- T17.9 -------------------------------------------------------------------------

  /** A legenda ou o comentário não passaram na sanitização (§9/§77). */
  INVALID_CONTENT: 'INVALID_CONTENT',
  /** Os bytes enviados não são uma imagem que este servidor aceita (§13/§14/§20). */
  INVALID_IMAGE: 'INVALID_IMAGE',
  /** O upload é maior que o teto (§18). */
  MEDIA_TOO_LARGE: 'MEDIA_TOO_LARGE',
  /** A conta atingiu a quota de disco (§29). */
  MEDIA_QUOTA_EXCEEDED: 'MEDIA_QUOTA_EXCEEDED',
  /**
   * A mídia não existe, não é desta conta, não é desta sessão ou já foi anexada (§34/§35/§149).
   *
   * Uma resposta só para os quatro, pelo mesmo motivo de sempre: distinguir contaria a quem
   * perguntou o que existe na conta dos outros.
   */
  MEDIA_NOT_FOUND: 'MEDIA_NOT_FOUND',
  /** Reação com tipo fora do enum fechado (§62). */
  INVALID_REACTION: 'INVALID_REACTION',
  /** O comentário não existe, ou quem pediu não pode vê-lo/apagá-lo (§95). */
  COMMENT_NOT_FOUND: 'COMMENT_NOT_FOUND',
  /** Denúncia sem alvo visível, de si mesmo, ou com alvo que o servidor não resolve (§104–§106). */
  INVALID_REPORT_TARGET: 'INVALID_REPORT_TARGET',
} as const;

export type WorkoutCheckInErrorCode =
  (typeof WORKOUT_CHECKIN_ERRORS)[keyof typeof WORKOUT_CHECKIN_ERRORS];
