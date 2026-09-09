import { Inject, Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { uidPrefix } from '../auth/bearer-auth.guard';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
} from './canonical-training.source';
import { SocialRepository } from './social.repository';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { SocialContentRateLimiter } from './social-content.rate-limit';
import { SocialMediaService } from './social-media.service';
import { SocialMediaRepository } from './social-media.repository';
import {
  COMMENT_FLOOD_WINDOW_MS,
  COMMENTS_DEFAULT_LIMIT,
  COMMENTS_MAX_LIMIT,
  MAX_COMMENTS_PER_CHECKIN_PER_WINDOW,
} from './social-media.limits';
import { WorkoutCheckInAccessPolicy } from './workout-checkin.access-policy';
import {
  CHECKIN_CLOCK_SKEW_TOLERANCE_MS,
  CHECKIN_WINDOW_MS,
  FEED_DEFAULT_LIMIT,
  FEED_MAX_LIMIT,
  FEED_WINDOW_MS,
  type CheckInCommentDto,
  type CheckInCommentsDto,
  type CreateWorkoutCheckInRequest,
  type ReactionType,
  type SocialFeedDto,
  type WorkoutCheckInDto,
} from './workout-checkin.contract';
import { WorkoutCheckInErrors } from './workout-checkin.errors';
import { WorkoutCheckInRateLimiter } from './workout-checkin.rate-limit';
import {
  type FeedRow,
  type StoredWorkoutCheckIn,
  WorkoutCheckInRepository,
} from './workout-checkin.repository';

/**
 * Os check-ins de treino e o Feed social (T17.8).
 *
 * ## O que este serviço garante, e onde cada garantia mora
 *
 * ```text
 * Room (WorkoutSession COMPLETED)          ← autoridade operacional, local-first
 *        │
 *        ▼  sync T16 (o único caminho de upload de sessão)
 * sync_entities                            ← estado sincronizado
 *        │
 *        ▼  CanonicalTrainingSource        ← a MESMA fronteira da T17.2/T17.3/T17.4
 * findSessionForCheckIn(owner, syncId)
 *        │
 *        │  ação explícita do usuário + confirmação
 *        ▼
 * social_workout_checkins                  ← artefato social, server-authoritative
 *        │
 *        ▼  amizade atual ∧ ¬bloqueio ∧ perfil ativo
 * Feed dos amigos
 * ```
 *
 * **Concluir um treino não publica nada** (§3). Este serviço só é alcançado por um `POST` que o
 * usuário disparou depois de confirmar um preview — não existe gatilho no fim da sessão, no sync,
 * na abertura de tela nem em background.
 *
 * **O cliente não é autoridade sobre nada disso** (§11/§14). Ele envia dois identificadores; o
 * dono sai do token verificado, o estado da sessão sai da fonte canônica e o instante sai do
 * `Clock` do servidor (§26). Um corpo com `completed: true` ou `ownerUid` é recusado por nome, no
 * validador, antes de chegar aqui.
 *
 * **Falha aqui não altera treino nenhum** (§10). Este módulo não escreve em `sync_entities`, não
 * toca `WorkoutSession`, não gera XP, conquista, missão, streak nem ranking (§119–§121), e não
 * dispara push (§95). Ele insere uma linha e lê outras.
 */
@Injectable()
export class WorkoutCheckInService {
  constructor(
    private readonly repository: WorkoutCheckInRepository,
    private readonly socialRepository: SocialRepository,
    private readonly rateLimiter: WorkoutCheckInRateLimiter,
    // T17.9 — as três superfícies novas. Elas entram no **mesmo** serviço de propósito: o
    // agregado é um só (§5), e um `ReactionService` ao lado precisaria da mesma política de
    // acesso, do mesmo perfil ativo e do mesmo repositório de check-in — o que produziria a
    // segunda cópia da autorização que §130 chama de bloqueante.
    private readonly interactions: CheckInInteractionRepository,
    private readonly mediaRepository: SocialMediaRepository,
    private readonly media: SocialMediaService,
    private readonly accessPolicy: WorkoutCheckInAccessPolicy,
    private readonly contentRateLimiter: SocialContentRateLimiter,
    @Inject(CANONICAL_TRAINING_SOURCE)
    private readonly canonicalTraining: CanonicalTrainingSource,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  /**
   * `POST /v1/social/workout-checkins`.
   *
   * A ordem é deliberada: **autorizar antes de olhar a sessão**, e resolver idempotência antes de
   * consultar a fonte canônica. Um retry de resposta perdida não precisa reler o domínio de
   * treino, e um pedido de quem não tem perfil social não deveria conseguir descobrir nada sobre
   * sessões pela latência da resposta.
   */
  createCheckIn(
    callerUid: string,
    requestId: string,
    request: CreateWorkoutCheckInRequest,
  ): WorkoutCheckInDto {
    if (!this.rateLimiter.tryAcquireCreate(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }

    const profile = this.requireActiveProfile(callerUid);
    const { sessionSyncId, clientRequestId, caption, mediaId } = request;

    // §32 — a mesma intenção não pode significar duas coisas. Reusar o `clientRequestId` para
    // outra sessão descreve um cliente confuso, e atender seria concordar com a confusão.
    const byRequest = this.repository.findByAuthorAndClientRequest(callerUid, clientRequestId);
    if (byRequest && byRequest.sourceSessionSyncId !== sessionSyncId) {
      throw WorkoutCheckInErrors.requestConflict();
    }

    // §31/§33/§34 — mesma sessão devolve o **mesmo** check-in, com o mesmo `clientRequestId` ou
    // com outro. É o que faz o toque duplo, o retry de resposta perdida e o "compartilhar" a
    // partir do Histórico convergirem em uma publicação só.
    const bySession = this.repository.findByAuthorAndSession(callerUid, sessionSyncId);
    if (bySession) {
      if (bySession.status === 'PUBLISHED') {
        // §31/§33 — o retry devolve o post **como ele está**, com legenda, foto, reações e
        // comentários que já existirem. Devolver a forma "recém-criada" faria um retry parecer
        // ter apagado o que aconteceu desde a primeira publicação.
        return this.projectSingle(callerUid, bySession.id, profile.socialId, profile.displayName);
      }
      // §29 — um treino, no máximo um check-in. Excluir é definitivo para aquela sessão: a linha
      // continua, a `UNIQUE` continua valendo, e republicar não é oferecido.
      throw WorkoutCheckInErrors.alreadyExists();
    }

    const now = this.clock.now();
    this.assertSessionEligible(callerUid, sessionSyncId, now);

    const created: StoredWorkoutCheckIn = {
      id: randomUUID(),
      authorUid: callerUid,
      sourceSessionSyncId: sessionSyncId,
      clientRequestId,
      status: 'PUBLISHED',
      caption: caption ?? null,
      createdAt: now,
      deletedAt: null,
    };

    const published = this.insertOrResolveRace(created);

    // §33/§34/§35/§148 — o anexo vem **depois** da criação, e é condicional no banco: mesma conta,
    // mesma sessão, mídia ainda `PENDING`. O servidor revalida os três mesmo que o Android erre.
    //
    // Se o anexo falha, a publicação é desfeita em vez de nascer sem a foto que a pessoa escolheu:
    // §43 é explícito em que a decisão de publicar sem foto precisa ser **do usuário**, e um
    // servidor que publicasse em silêncio a tomaria por ele. O soft delete usa o mesmo caminho da
    // exclusão normal, então a `UNIQUE` por sessão continua valendo — mas isso tornaria aquela
    // sessão impublicável para sempre, e por isso a linha é **removida** de verdade aqui: ela
    // existiu por milissegundos, ninguém a viu, e nenhuma outra publicação depende dela.
    if (mediaId) {
      const attached = this.media.attachToCheckIn(mediaId, callerUid, sessionSyncId, published.id);
      if (!attached) {
        if (published.id === created.id) {
          this.repository.hardDelete(published.id);
        }
        throw WorkoutCheckInErrors.mediaNotFound();
      }
    }

    // Nunca `sessionSyncId`, nunca `displayName`, nunca o uid completo (§124).
    // §160/§161 — evento, prefixo de uid, status e **se** havia legenda/foto. Nunca o texto da
    // legenda, nunca `sessionSyncId`, nunca `displayName`, nunca o uid completo.
    this.logger.info('social.checkin.created', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      status: 'PUBLISHED',
      hasCaption: published.caption !== null,
      hasMedia: Boolean(mediaId),
    });

    return this.projectSingle(callerUid, published.id, profile.socialId, profile.displayName);
  }

  /**
   * `DELETE /v1/social/workout-checkins/{checkInId}`.
   *
   * Só o autor (§65), e a resposta para "não existe" e "não é seu" é a **mesma** — um `404` que
   * não confirma a existência do id para quem não é dono.
   *
   * Idempotente (§66): repetir converge, porque a escrita é condicional em `status = 'PUBLISHED'`
   * e o segundo `DELETE` simplesmente não muda nada.
   *
   * Não toca a sessão de treino (§67). Nem aqui, nem em lugar nenhum: este módulo não conhece
   * `sync_entities` a não ser através da fonte canônica, que é somente leitura.
   */
  deleteCheckIn(callerUid: string, requestId: string, checkInId: string): void {
    const existing = this.repository.findById(checkInId);
    if (!existing || existing.authorUid !== callerUid) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }

    // §99/§100 — a visibilidade de tudo cai junto, e cai **agora**: o soft delete do check-in já
    // faz o Feed, o detalhe, as reações e os comentários pararem de responder, porque todos
    // passam pela mesma política, que exige `status = 'PUBLISHED'`. A mídia precisa de uma
    // linha própria porque ela tem um arquivo atrás — marcá-la `DELETED` revoga o acesso na hora
    // e entrega o arquivo ao cleaner, sem que este `DELETE` espere I/O de sistema de arquivos.
    const now = this.clock.now();
    const changed = this.sqliteTransaction(() => {
      const result = this.repository.softDelete(checkInId, callerUid, now);
      if (result) {
        this.media.revokeForCheckIn(checkInId, now);
      }
      return result;
    });

    this.logger.info('social.checkin.deleted', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      status: changed ? 'DELETED' : 'ALREADY_DELETED',
    });
  }

  /**
   * `GET /v1/social/feed`.
   *
   * O corpo da autorização está na consulta do repositório (§78). O que este método decide é o
   * recorte: janela de 30 dias a partir do relógio do servidor, e o teto de itens.
   *
   * Pedir mais que [FEED_MAX_LIMIT] **não** é erro — é atendido até o teto (§74). Recusar faria um
   * cliente novo quebrar contra um servidor antigo por causa de um número.
   */
  getFeed(callerUid: string, requestId: string, limit?: number): SocialFeedDto {
    this.requireActiveProfile(callerUid);

    const now = this.clock.now();
    const boundedLimit = Math.min(Math.max(limit ?? FEED_DEFAULT_LIMIT, 1), FEED_MAX_LIMIT);
    const rows = this.repository.findFeed(callerUid, now - FEED_WINDOW_MS, boundedLimit);

    this.logger.info('social.feed.read', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      returnedCount: rows.length,
    });

    return { items: this.enrich(callerUid, rows) };
  }

  /**
   * `GET /v1/social/workout-checkins/{checkInId}` — o detalhe (T17.9 §118).
   *
   * A mesma política, o mesmo DTO e o mesmo enriquecimento do Feed. Existe como rota própria
   * porque a tela de detalhe precisa poder recarregar **um** post depois de reagir ou comentar,
   * sem baixar o Feed inteiro — e não porque o detalhe seja um segundo tipo de publicação.
   */
  getCheckIn(callerUid: string, checkInId: string): WorkoutCheckInDto {
    this.requireActiveProfile(callerUid);
    const visible = this.accessPolicy.findVisibleCheckIn(callerUid, checkInId);
    if (!visible) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }
    return this.enrich(callerUid, [visible as FeedRow])[0];
  }

  // ================================================================== reações (§61–§73)

  /**
   * `PUT /v1/social/workout-checkins/{id}/reaction` (§66).
   *
   * Adicionar e **trocar** são a mesma operação (§64): a chave primária `(checkin_id,
   * reactor_uid)` faz o `ON CONFLICT` atualizar a linha existente, então 🔥 → 💪 nunca vira duas
   * reações. Repetir a mesma reação é sucesso e não muda nada — é o retry de resposta perdida.
   *
   * §67/§68 — quem pode reagir é quem consegue **ver o post agora**. "Já reagiu antes" não é
   * permissão: depois de um `unfriend` ou de um bloqueio, a próxima requisição é recusada, porque
   * a pergunta é feita contra as tabelas e não contra o histórico da pessoa.
   *
   * §71/§72/§73 — não dá XP, não gera push e não entra na Activity da T17.4.
   */
  putReaction(
    callerUid: string,
    requestId: string,
    checkInId: string,
    type: ReactionType,
  ): WorkoutCheckInDto {
    if (!this.contentRateLimiter.tryAcquireReaction(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    const profile = this.requireActiveProfile(callerUid);
    const visible = this.accessPolicy.findVisibleCheckIn(callerUid, checkInId);
    if (!visible) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }

    this.interactions.putReaction(checkInId, callerUid, type, this.clock.now());

    // §160 — o tipo é vocabulário fechado do servidor, então registrá-lo não vaza conteúdo de
    // ninguém. O uid completo e o `socialId` continuam fora.
    this.logger.info('social.reaction.changed', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      type,
      operation: 'PUT',
    });

    void profile;
    return this.enrich(callerUid, [visible as FeedRow])[0];
  }

  /**
   * `DELETE /v1/social/workout-checkins/{id}/reaction` (§65).
   *
   * Idempotente: remover o que já não existe é sucesso. Exige visibilidade pela mesma razão do
   * `PUT` — e porque devolver o post atualizado exige poder lê-lo.
   */
  removeReaction(callerUid: string, requestId: string, checkInId: string): WorkoutCheckInDto {
    if (!this.contentRateLimiter.tryAcquireReaction(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);
    const visible = this.accessPolicy.findVisibleCheckIn(callerUid, checkInId);
    if (!visible) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }

    this.interactions.removeReaction(checkInId, callerUid);

    this.logger.info('social.reaction.changed', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      operation: 'DELETE',
    });

    return this.enrich(callerUid, [visible as FeedRow])[0];
  }

  // ================================================================== comentários (§74–§98)

  /**
   * `GET /v1/social/workout-checkins/{id}/comments` (§89/§90/§91).
   *
   * Bounded sempre, ordenado por `createdAt ASC` com desempate por `commentId` (§91) — é uma
   * conversa, e conversa se lê de cima para baixo.
   *
   * `canDelete` é decidido aqui (§93/§94/§95): o autor do comentário e o autor do check-in podem;
   * um terceiro, não. A tela usa o booleano para desenhar o menu, e o servidor recusa de qualquer
   * forma — esconder um botão nunca foi controle de acesso.
   */
  listComments(callerUid: string, checkInId: string, limit?: number): CheckInCommentsDto {
    this.requireActiveProfile(callerUid);
    const visible = this.accessPolicy.findVisibleCheckIn(callerUid, checkInId);
    if (!visible) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }

    const bounded = Math.min(Math.max(limit ?? COMMENTS_DEFAULT_LIMIT, 1), COMMENTS_MAX_LIMIT);
    const rows = this.interactions.listComments(callerUid, checkInId, bounded);

    return {
      items: rows.map<CheckInCommentDto>((row) => ({
        commentId: row.commentId,
        author: { socialId: row.authorSocialId, displayName: row.authorDisplayName },
        body: row.body,
        createdAt: row.createdAt,
        isCurrentUser: row.authorUid === callerUid,
        canDelete: row.authorUid === callerUid || visible.authorUid === callerUid,
      })),
    };
  }

  /**
   * `POST /v1/social/workout-checkins/{id}/comments` (§81/§82).
   *
   * §82 — comentar exige conseguir ver o post **naquele instante**. Não-amigo, par bloqueado,
   * autor com Social desativado e post excluído são todos recusados com a mesma resposta.
   *
   * §122 — o retorno é o comentário **já persistido**, com o identificador que o servidor gerou.
   * O Android insere na lista a partir daí, e não otimisticamente: um comentário que aparece e
   * some é pior do que um que demora um instante para aparecer, porque quem escreveu acredita que
   * a outra pessoa leu.
   */
  createComment(
    callerUid: string,
    requestId: string,
    checkInId: string,
    body: string,
  ): CheckInCommentDto {
    if (!this.contentRateLimiter.tryAcquireComment(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    const profile = this.requireActiveProfile(callerUid);
    const visible = this.accessPolicy.findVisibleCheckIn(callerUid, checkInId);
    if (!visible) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }

    const now = this.clock.now();
    // §158 — o teto por publicação existe além do teto global: 30 comentários espalhados por dez
    // posts é conversa; 30 no mesmo post é enxurrada na publicação de uma pessoa só.
    const recent = this.interactions.countRecentCommentsBy(
      checkInId,
      callerUid,
      now - COMMENT_FLOOD_WINDOW_MS,
    );
    if (recent >= MAX_COMMENTS_PER_CHECKIN_PER_WINDOW) {
      throw WorkoutCheckInErrors.rateLimited();
    }

    const commentId = randomUUID();
    this.interactions.createComment(commentId, checkInId, callerUid, body, now);

    // §161 — **nunca** o corpo do comentário. Só o evento, o prefixo do uid e o comprimento, que
    // é metadata técnica e não conteúdo.
    this.logger.info('social.comment.created', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      bodyLength: body.length,
    });

    return {
      commentId,
      author: { socialId: profile.socialId, displayName: profile.displayName },
      body,
      createdAt: now,
      isCurrentUser: true,
      // O autor sempre pode apagar o próprio comentário (§93).
      canDelete: true,
    };
  }

  /**
   * `DELETE /v1/social/workout-checkins/{checkInId}/comments/{commentId}` (§93–§98).
   *
   * Duas autoridades, e só duas: o **autor do comentário** (§93) e o **autor do check-in**, que
   * modera a própria publicação (§94). Um terceiro amigo recebe `404` — a mesma resposta de
   * "não existe" —, porque confirmar que o comentário existe já diria a ele algo sobre um post
   * que ele não administra (§95).
   *
   * Idempotente (§97): a escrita é condicional em `deleted_at IS NULL`, então o segundo `DELETE`
   * converge em vez de virar `404`. E ela não toca o check-in (§98).
   */
  deleteComment(callerUid: string, requestId: string, checkInId: string, commentId: string): void {
    this.requireActiveProfile(callerUid);
    const visible = this.accessPolicy.findVisibleCheckIn(callerUid, checkInId);
    if (!visible) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }

    const comment = this.interactions.findComment(commentId);
    if (!comment || comment.checkInId !== checkInId) {
      throw WorkoutCheckInErrors.commentNotFound();
    }
    if (comment.authorUid !== callerUid && visible.authorUid !== callerUid) {
      throw WorkoutCheckInErrors.commentNotFound();
    }

    const changed = this.interactions.softDeleteComment(commentId, this.clock.now());

    this.logger.info('social.comment.deleted', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      status: changed ? 'DELETED' : 'ALREADY_DELETED',
      // Quem apagou: o autor do comentário ou o dono do post. Metadata de moderação, sem
      // identidade — os dois prefixos de uid já seriam mais do que a revisão precisa aqui.
      actor: comment.authorUid === callerUid ? 'COMMENT_AUTHOR' : 'POST_AUTHOR',
    });
  }

  // ------------------------------------------------------------------ internas

  /**
   * Perfil social **ativo** é a porta de entrada dos dois lados (§60).
   *
   * Desativado não publica e não lê. O que a desativação **não** faz é apagar as publicações que
   * já existem (§61): elas ficam invisíveis para os outros pela cláusula `p.status = 'ACTIVE'` do
   * feed, e voltam inteiras ao reativar.
   */
  private requireActiveProfile(callerUid: string) {
    const account = this.socialRepository.find(callerUid);
    if (!account || account.profile.status !== 'ACTIVE') {
      throw WorkoutCheckInErrors.socialNotEnabled();
    }
    return account.profile;
  }

  /**
   * A elegibilidade da sessão (§17/§24/§28).
   *
   * ```text
   * owner correto ∧ WORKOUT_SESSION ∧ deleted = 0 ∧ status = COMPLETED ∧ dentro de 48h
   * ```
   *
   * Os quatro primeiros vêm da fonte canônica — a mesma que responde perfil, desafio e atividade
   * —, e não de um `SELECT` escrito aqui (§15). O quinto vem do `Clock` do servidor (§26): o
   * aparelho pode esconder o botão por conveniência, mas quem decide é este método (§27).
   */
  private assertSessionEligible(callerUid: string, sessionSyncId: string, now: number): void {
    const session = this.canonicalTraining.findSessionForCheckIn(callerUid, sessionSyncId);

    // Inexistente, de outra conta, com tombstone e ainda não sincronizada: a mesma resposta para
    // as quatro (§18/§116). O cliente que sabe ter a sessão localmente interpreta este `404` como
    // "o servidor ainda não a conhece" e roda um ciclo de sync — mas essa conclusão é dele, e o
    // servidor não a confirma.
    if (!session || session.deleted) {
      throw WorkoutCheckInErrors.sessionNotFound();
    }

    if (session.status !== 'COMPLETED') {
      throw WorkoutCheckInErrors.sessionNotCompleted();
    }

    const finishedAt = session.finishedAt;
    // Sem instante canônico não há como afirmar que o treino é recente, e "publicar mesmo assim"
    // seria decidir a favor da publicação num caso que ninguém consegue verificar.
    if (finishedAt === null) {
      throw WorkoutCheckInErrors.windowExpired();
    }
    if (finishedAt > now + CHECKIN_CLOCK_SKEW_TOLERANCE_MS) {
      throw WorkoutCheckInErrors.windowExpired();
    }
    if (now - finishedAt > CHECKIN_WINDOW_MS) {
      throw WorkoutCheckInErrors.windowExpired();
    }
  }

  /**
   * A corrida entre duas requisições simultâneas da mesma conta pela mesma sessão.
   *
   * As duas passam pelas leituras acima e chegam aqui; a `UNIQUE` do banco recusa a segunda. Isso
   * é o desenho — a garantia de "um treino, um check-in" é do banco, e não da ordem em que dois
   * `SELECT` aconteceram.
   *
   * Ao perder a corrida devolvemos **a linha vencedora**, e não o objeto que tentamos inserir: o
   * `id` que não entrou no banco não é um check-in, e responder com ele daria ao cliente um
   * identificador que nenhum `DELETE` encontraria.
   *
   * A decisão lê a **propriedade** `code` do erro, e não `instanceof`: sob Jest, um erro nativo do
   * `better-sqlite3` pode atravessar realms e falhar um `instanceof` que deveria valer.
   */
  private insertOrResolveRace(item: StoredWorkoutCheckIn): StoredWorkoutCheckIn {
    try {
      this.repository.create(item);
      return item;
    } catch (error) {
      const code = (error as { code?: unknown }).code;
      if (typeof code !== 'string' || !code.startsWith('SQLITE_CONSTRAINT')) {
        throw error;
      }

      const winner = this.repository.findByAuthorAndSession(
        item.authorUid,
        item.sourceSessionSyncId,
      );
      if (winner && winner.status === 'PUBLISHED') {
        return winner;
      }
      throw WorkoutCheckInErrors.unavailable('não foi possível publicar o check-in agora');
    }
  }

  /**
   * O enriquecimento de uma **página** do Feed (T17.9 §58/§131/§132).
   *
   * ## Por que quatro consultas, e não vinte e uma
   *
   * A tentação é resolver cada card sozinho: para cada check-in, buscar a foto, contar as reações,
   * descobrir a do viewer e contar os comentários. Um feed de 20 itens viraria 80 consultas, e o
   * custo cresceria com o tamanho da página — que é exatamente o N+1 que §131 proíbe.
   *
   * Aqui a página inteira vai junto em cada agregação: uma consulta de mídia, uma de contagem de
   * reações, uma da reação do viewer e uma de contagem de comentários. Quatro, independentemente
   * de a página ter 1 ou 50 itens.
   *
   * ## As contagens são **do viewer**, não do post (§69/§92)
   *
   * `countReactionsForCheckIns` e `countCommentsForCheckIns` recebem o `viewerUid` e filtram por
   * ele. Não existe caminho aqui que produza um número global: o cenário de §69 — A reage ao post
   * de C, A bloqueou B, B lê o post de C — precisa que a participação de A não transpareça nem
   * como número, e um `COUNT(*)` sem viewer vazaria exatamente isso.
   */
  private enrich(viewerUid: string, rows: readonly FeedRow[]): WorkoutCheckInDto[] {
    if (rows.length === 0) {
      return [];
    }
    const ids = rows.map((row) => row.checkInId);

    const media = new Map(
      this.mediaRepository
        .findAttachedForCheckIns(ids)
        .map((item) => [
          item.checkInId,
          { mediaId: item.mediaId, width: item.width, height: item.height },
        ]),
    );

    const reactionTotals = new Map<string, Record<string, number>>();
    for (const row of this.interactions.countReactionsForCheckIns(viewerUid, ids)) {
      const bucket = reactionTotals.get(row.checkInId) ?? {};
      bucket[row.type] = row.total;
      reactionTotals.set(row.checkInId, bucket);
    }

    const viewerReactions = this.interactions.findViewerReactions(viewerUid, ids);
    const commentCounts = this.interactions.countCommentsForCheckIns(viewerUid, ids);

    return rows.map((row) => ({
      type: 'WORKOUT_CHECK_IN' as const,
      checkInId: row.checkInId,
      author: { socialId: row.authorSocialId, displayName: row.authorDisplayName },
      publishedAt: row.publishedAt,
      caption: row.caption,
      media: media.get(row.checkInId) ?? null,
      reactions: reactionTotals.get(row.checkInId) ?? {},
      currentUserReaction: viewerReactions.get(row.checkInId) ?? null,
      commentCount: commentCounts.get(row.checkInId) ?? 0,
      isCurrentUser: row.authorUid === viewerUid,
    }));
  }

  /**
   * O DTO de uma publicação recém-criada.
   *
   * Passa pela **mesma** política e pelo **mesmo** enriquecimento do Feed, e não por um atalho que
   * monta o objeto a partir da linha inserida. Assim a resposta do `POST` é exatamente o que a
   * próxima leitura do Feed devolveria — incluindo a foto que acabou de ser anexada — em vez de
   * uma segunda maneira de descrever o mesmo post, que divergiria no primeiro campo novo.
   */
  private projectSingle(
    callerUid: string,
    checkInId: string,
    socialId: string,
    displayName: string,
  ): WorkoutCheckInDto {
    const visible = this.accessPolicy.findVisibleCheckIn(callerUid, checkInId);
    if (visible) {
      return this.enrich(callerUid, [visible as FeedRow])[0];
    }
    // Inalcançável em condições normais: o autor sempre enxerga a própria publicação recém-criada.
    // O caminho existe para o instante entre a criação e uma desativação concorrente do Social —
    // e responder o post do jeito mais simples é melhor do que um erro sobre algo que funcionou.
    const stored = this.repository.findById(checkInId);
    return {
      type: 'WORKOUT_CHECK_IN',
      checkInId,
      author: { socialId, displayName },
      publishedAt: stored?.createdAt ?? this.clock.now(),
      caption: stored?.caption ?? null,
      media: null,
      reactions: {},
      currentUserReaction: null,
      commentCount: 0,
      isCurrentUser: true,
    };
  }

  /** A transação do agregado, delegada a quem é dono da conexão. */
  private sqliteTransaction<T>(work: () => T): T {
    return this.repository.transaction(work);
  }
}
