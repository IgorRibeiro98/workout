import { Inject, Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { uidPrefix } from '../auth/bearer-auth.guard';
import { BlockRepository } from './block.repository';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
} from './canonical-training.source';
import { SocialGroupRepository } from './social-group.repository';
import { SocialRepository } from './social.repository';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { CheckInProjector } from './checkin.projector';
import { SocialContentRateLimiter } from './social-content.rate-limit';
import { SocialMediaService } from './social-media.service';
import { SocialMediaRepository } from './social-media.repository';
import {
  COMMENT_FLOOD_WINDOW_MS,
  COMMENTS_DEFAULT_LIMIT,
  COMMENTS_MAX_LIMIT,
  MAX_COMMENTS_PER_CHECKIN_PER_WINDOW,
} from './social-media.limits';
import { WorkoutCheckInAccessPolicy, type VisibleCheckIn } from './workout-checkin.access-policy';
import {
  WorkoutCheckInContextResolver,
  type InteractionAudience,
} from './workout-checkin-context.resolver';
import {
  CHECKIN_CLOCK_SKEW_TOLERANCE_MS,
  CHECKIN_WINDOW_MS,
  FEED_DEFAULT_LIMIT,
  FEED_MAX_LIMIT,
  FEED_WINDOW_MS,
  type CheckInCommentDto,
  type CheckInCommentsDto,
  type CreateWorkoutCheckInRequest,
  type InteractionContextRequest,
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
    // T17.11 §50 — a montagem do card saiu daqui e virou um provider. Ela passou a ter **duas**
    // superfícies: o Feed de amigos e o feed de um Squad. Um card montado por dois caminhos
    // divergiria no próximo campo novo, e "não existe um segundo Feed authority" deixaria de ser
    // verdade sem que ninguém percebesse.
    private readonly projector: CheckInProjector,
    private readonly media: SocialMediaService,
    private readonly accessPolicy: WorkoutCheckInAccessPolicy,
    // T17.12 §32 — o **único** lugar que transforma "o contexto que a tela propôs" em "a audiência
    // que o servidor confirmou". Reagir, comentar e listar comentários passam todos por ele, e é
    // isso que impede que um `groupId` do cliente conceda alguma coisa sozinho (§7/§179).
    private readonly contextResolver: WorkoutCheckInContextResolver,
    // T17.12 §54 — a moderação do dono do Squad precisa saber quem é dono de qual Squad, e o
    // bloqueio precisa continuar soberano (§127). Os dois são leitura, e nenhum deles é uma segunda
    // política: `SocialGroupRepository` e `BlockRepository` continuam sendo os donos dessas
    // perguntas, aqui apenas consultados.
    private readonly groups: SocialGroupRepository,
    private readonly blocks: BlockRepository,
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
  getCheckIn(
    callerUid: string,
    checkInId: string,
    context?: InteractionContextRequest,
  ): WorkoutCheckInDto {
    this.requireActiveProfile(callerUid);

    // T17.12 §35/§66 — quando a tela informa de onde veio, o detalhe é lido **naquela audiência**:
    // as contagens são as daquele Squad e a interação é liberada para qualquer membro ativo (§76).
    // O `groupId` não concede nada por si: o resolvedor confirma Squad ativo, compartilhamento,
    // participação dos dois lados e ausência de bloqueio antes de devolver a audiência (§7/§9).
    if (context) {
      const { audience, checkIn } = this.contextResolver.resolve(callerUid, checkInId, context);
      return this.projectInAudience(callerUid, checkIn, audience);
    }

    // Sem contexto, a rota responde o que respondia na T17.11: quem alcança a publicação só por um
    // Squad continua podendo **ler** e não interagir (§70 daquela fase). Não é uma restrição nova —
    // é a única resposta honesta quando ninguém disse de qual Squad a tela está falando, e as
    // contagens de uma audiência escolhida por sorteio seriam piores do que nenhuma.
    const accessible = this.accessPolicy.findAccessibleCheckIn(callerUid, checkInId);
    if (!accessible) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }
    return this.projector.project(
      callerUid,
      [
        {
          checkInId: accessible.checkInId,
          authorUid: accessible.authorUid,
          authorSocialId: accessible.authorSocialId,
          authorDisplayName: accessible.authorDisplayName,
          caption: accessible.caption,
          publishedAt: accessible.publishedAt,
          canInteract: accessible.canInteract,
        },
      ],
      { type: 'FRIEND' },
    )[0];
  }

  // ================================================================== reações (§61–§73)

  /**
   * `PUT /v1/social/workout-checkins/{id}/reaction` (§66).
   *
   * Adicionar e **trocar** são a mesma operação (§64): o `ON CONFLICT` atualiza a linha daquela
   * audiência, então 🔥 → 💪 nunca vira duas reações **ali** — e não toca a reação que a pessoa
   * tenha em outra audiência, que é uma interação independente (T17.12 §11/§13). O alvo é um dos
   * dois índices únicos parciais da 0020, e não mais uma chave primária: a unicidade passou a ser
   * por audiência. Repetir a mesma reação é sucesso e não muda nada — é o retry de resposta perdida.
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
    context?: InteractionContextRequest,
  ): WorkoutCheckInDto {
    if (!this.contentRateLimiter.tryAcquireReaction(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);
    // T17.12 §7/§9 — a autorização é da audiência, não do post: `FRIEND` continua exigindo relação
    // direta, e `GROUP(X)` exige Squad ativo, check-in compartilhado ali, participação ativa do
    // requisitante **e** do autor, e ausência de bloqueio. Um contexto inválido é `404`, nunca um
    // rebaixamento silencioso para `FRIEND` (§69).
    const { audience, checkIn } = this.contextResolver.resolve(callerUid, checkInId, context);

    this.interactions.putReaction(checkInId, callerUid, type, audience, this.clock.now());

    // §172/§173 — o tipo e a audiência são vocabulário fechado do servidor, então registrá-los não
    // vaza conteúdo de ninguém. O `groupId`, o uid completo e o `socialId` continuam fora.
    this.logger.info('social.reaction.changed', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      type,
      audience: audience.type,
      operation: 'PUT',
    });

    return this.projectInAudience(callerUid, checkIn, audience);
  }

  /**
   * `DELETE /v1/social/workout-checkins/{id}/reaction` (§65).
   *
   * Idempotente: remover o que já não existe é sucesso. Exige visibilidade pela mesma razão do
   * `PUT` — e porque devolver o post atualizado exige poder lê-lo.
   */
  removeReaction(
    callerUid: string,
    requestId: string,
    checkInId: string,
    context?: InteractionContextRequest,
  ): WorkoutCheckInDto {
    if (!this.contentRateLimiter.tryAcquireReaction(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);
    // §16 — remover é sempre **dentro de uma audiência**. Sem isso, desfazer a reação dentro do
    // Squad apagaria a que a pessoa deixou no Feed de amigos, que é outra conversa inteira.
    const { audience, checkIn } = this.contextResolver.resolve(callerUid, checkInId, context);

    this.interactions.removeReaction(checkInId, callerUid, audience);

    this.logger.info('social.reaction.changed', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      audience: audience.type,
      operation: 'DELETE',
    });

    return this.projectInAudience(callerUid, checkIn, audience);
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
  listComments(
    callerUid: string,
    checkInId: string,
    limit?: number,
    context?: InteractionContextRequest,
  ): CheckInCommentsDto {
    this.requireActiveProfile(callerUid);
    const { audience, checkIn } = this.contextResolver.resolve(callerUid, checkInId, context);

    const bounded = Math.min(Math.max(limit ?? COMMENTS_DEFAULT_LIMIT, 1), COMMENTS_MAX_LIMIT);
    // §65/§66 — a lista é de **uma** audiência. O comentário do Squad X não aparece no Squad Y nem
    // no Feed de amigos, e o do Feed de amigos não aparece em Squad nenhum (§20/§21/§22).
    const rows = this.interactions.listComments(callerUid, checkInId, audience, bounded);

    // §54 — o dono do Squad pode moderar os comentários **daquela** audiência. A pergunta é feita
    // uma vez por lista, e não por comentário: o papel é o mesmo para todas as linhas.
    const moderatesAudience = this.moderatesGroupAudience(callerUid, audience);

    return {
      items: rows.map<CheckInCommentDto>((row) => ({
        commentId: row.commentId,
        author: { socialId: row.authorSocialId, displayName: row.authorDisplayName },
        body: row.body,
        createdAt: row.createdAt,
        isCurrentUser: row.authorUid === callerUid,
        // §125 — três autoridades, nesta ordem: o autor do comentário, o autor do check-in e o
        // dono do Squad quando a audiência é a dele. Toda linha aqui já passou pelo filtro de
        // bloqueio da consulta, então o dono nunca recebe `canDelete` sobre algo que ele não vê
        // (§126/§127).
        canDelete:
          row.authorUid === callerUid || checkIn.authorUid === callerUid || moderatesAudience,
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
    context?: InteractionContextRequest,
  ): CheckInCommentDto {
    if (!this.contentRateLimiter.tryAcquireComment(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    const profile = this.requireActiveProfile(callerUid);
    const { audience } = this.contextResolver.resolve(callerUid, checkInId, context);

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
    this.interactions.createComment(commentId, checkInId, callerUid, body, audience, now);

    // §172/§173 — **nunca** o corpo do comentário, nunca o nome do Squad, nunca o `groupId`. Só o
    // evento, o prefixo do uid, o comprimento e a audiência — os dois últimos são metadata técnica,
    // e a audiência é vocabulário fechado do servidor.
    this.logger.info(
      audience.type === 'GROUP' ? 'social.group.comment.created' : 'social.comment.created',
      {
        requestId,
        uidPrefix: uidPrefix(callerUid),
        bodyLength: body.length,
        audience: audience.type,
      },
    );

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
   * Três autoridades desde a T17.12 (§125), nesta ordem: o **autor do comentário** (§93), o
   * **autor do check-in**, que modera a própria publicação (§94), e o **dono do Squad** — mas só
   * quando a audiência do comentário é `GROUP` daquele Squad (§54). Qualquer outra pessoa recebe
   * `404` — a mesma resposta de "não existe" —, porque confirmar que o comentário existe já diria
   * a ela algo sobre uma conversa que ela não administra (§95/§123).
   *
   * Idempotente (§97): a escrita é condicional em `deleted_at IS NULL`, então o segundo `DELETE`
   * converge em vez de virar `404`. E ela não toca o check-in (§98).
   */
  deleteComment(callerUid: string, requestId: string, checkInId: string, commentId: string): void {
    this.requireActiveProfile(callerUid);

    // §122 — a rota não recebe contexto, e não precisa: a audiência é uma propriedade **do
    // comentário**, e é o servidor que a lê. Aceitar um contexto aqui deixaria a tela dizer em que
    // audiência ela acha que está, o que é exatamente o que não pode decidir moderação.
    const comment = this.interactions.findComment(commentId);
    if (!comment || comment.checkInId !== checkInId) {
      throw WorkoutCheckInErrors.commentNotFound();
    }

    const checkIn = this.repository.findById(checkInId);
    const isCommentAuthor = comment.authorUid === callerUid;
    const isPostAuthor = checkIn?.authorUid === callerUid && checkIn.status === 'PUBLISHED';
    // §54/§55/§56 — o dono do Squad modera **a interação daquele contexto**, e nada além dela: ele
    // não alcança comentário `FRIEND` (nem que ele e o autor sejam amigos), não alcança outro Squad
    // e não toca o check-in. §126/§127 — o bloqueio continua soberano: um comentário que o bloqueio
    // já esconde dele não vira visível para ser moderado.
    const moderatesAsGroupOwner =
      !isCommentAuthor &&
      !isPostAuthor &&
      comment.audienceType === 'GROUP' &&
      comment.groupId !== null &&
      this.moderatesGroupAudience(callerUid, { type: 'GROUP', groupId: comment.groupId }) &&
      !this.blocks.isBlockedBidirectional(callerUid, comment.authorUid);

    if (!isCommentAuthor && !isPostAuthor && !moderatesAsGroupOwner) {
      // §123 — um membro comum não modera comentário alheio, e recebe a mesma resposta de "não
      // existe": confirmar o comentário já diria a ele algo sobre uma conversa que ele não
      // administra.
      throw WorkoutCheckInErrors.commentNotFound();
    }

    const changed = this.interactions.softDeleteComment(commentId, this.clock.now());

    this.logger.info(
      moderatesAsGroupOwner ? 'social.group.comment.moderated' : 'social.comment.deleted',
      {
        requestId,
        uidPrefix: uidPrefix(callerUid),
        status: changed ? 'DELETED' : 'ALREADY_DELETED',
        // §120/§121 — quem apagou, como papel. Metadata de moderação, sem identidade e sem o corpo
        // do comentário: os prefixos de uid dos dois lados já seriam mais do que a revisão precisa.
        actor: isCommentAuthor ? 'COMMENT_AUTHOR' : isPostAuthor ? 'POST_AUTHOR' : 'GROUP_OWNER',
      },
    );
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
   * O enriquecimento de uma **página** do Feed de amigos (T17.9 §58/§131/§132).
   *
   * Desde a T17.11 ele delega ao [CheckInProjector] (§50). O código era idêntico ao que o feed de
   * um Squad precisaria, e duas cópias é o desenho em que, no dia de um campo novo, uma superfície
   * o ganha e a outra não — que é exatamente o "segundo Feed authority" que a T17.11 proíbe.
   *
   * `canInteract: true` para toda linha, e não é uma simplificação: no Feed de amigos a relação
   * direta é a **própria condição de aparecer** — a CTE `eligible_authors` só admite o próprio
   * viewer e os amigos atuais dele. Um item deste feed que não autorizasse interação seria uma
   * contradição com a consulta que o produziu.
   */
  private enrich(viewerUid: string, rows: readonly FeedRow[]): WorkoutCheckInDto[] {
    return this.projector.project(
      viewerUid,
      rows.map((row) => ({
        checkInId: row.checkInId,
        authorUid: row.authorUid,
        authorSocialId: row.authorSocialId,
        authorDisplayName: row.authorDisplayName,
        caption: row.caption,
        publishedAt: row.publishedAt,
        canInteract: true,
      })),
      // T17.12 §33/§38/§64 — o Feed de amigos é a audiência `FRIEND`, e as contagens dele contam
      // **só** o que aconteceu ali. Uma reação deixada dentro de um Squad não entra neste número.
      { type: 'FRIEND' },
    );
  }

  /**
   * Um check-in projetado **dentro da audiência que acabou de ser autorizada** (T17.12 §37/§63).
   *
   * A resposta de reagir, remover reação e abrir o detalhe com contexto passa por aqui, e é sempre
   * o mesmo card do feed daquela audiência — nunca uma segunda maneira de descrever a publicação,
   * que divergiria no primeiro campo novo.
   *
   * `canInteract: true` não é otimismo: chegar até aqui significa que o resolvedor já confirmou a
   * audiência contra as tabelas. Se ele não tivesse confirmado, teria lançado `404` antes.
   */
  private projectInAudience(
    viewerUid: string,
    checkIn: VisibleCheckIn,
    audience: InteractionAudience,
  ): WorkoutCheckInDto {
    return this.projector.project(
      viewerUid,
      [
        {
          checkInId: checkIn.checkInId,
          authorUid: checkIn.authorUid,
          authorSocialId: checkIn.authorSocialId,
          authorDisplayName: checkIn.authorDisplayName,
          caption: checkIn.caption,
          publishedAt: checkIn.publishedAt,
          canInteract: true,
        },
      ],
      audience,
    )[0];
  }

  /**
   * O viewer é dono do Squad desta audiência? (T17.12 §54/§55/§150.)
   *
   * `false` para toda audiência `FRIEND`, sempre — e é o ponto de §55: privilégio de grupo não
   * atravessa para o Feed de amigos, nem quando o dono e o autor do comentário são amigos.
   */
  private moderatesGroupAudience(viewerUid: string, audience: InteractionAudience): boolean {
    if (audience.type !== 'GROUP') {
      return false;
    }
    return this.groups.findActiveMembership(audience.groupId, viewerUid)?.role === 'OWNER';
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
      // O autor sempre pode interagir com a própria publicação.
      canInteract: true,
    };
  }

  /** A transação do agregado, delegada a quem é dono da conexão. */
  private sqliteTransaction<T>(work: () => T): T {
    return this.repository.transaction(work);
  }
}
