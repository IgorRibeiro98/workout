import { Inject, Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import { ChallengeAccessPolicy, type ChallengeLifecycleView } from './challenge.access-policy';
import type {
  AcceptChallengeResponseDto,
  CancelChallengeResponseDto,
  ChallengeDetailResponseDto,
  ChallengeInvitationListResponseDto,
  ChallengeListResponseDto,
  ChallengeParticipantScoreDto,
  ChallengePreviewDto,
  ChallengeSummaryDto,
  CreateChallengeResponseDto,
  DeclineChallengeResponseDto,
  LeaveChallengeResponseDto,
} from './challenge.contract';
import { ChallengeErrors } from './challenge.errors';
import { CHALLENGE_PARTICIPANTS, CHALLENGE_MAX_OPEN_PER_CREATOR } from './challenge.limits';
import { ChallengeRateLimiter } from './challenge.rate-limit';
import {
  ChallengeFullError,
  ChallengeRepository,
  type StoredChallenge,
  type StoredChallengeParticipant,
} from './challenge.repository';
import { ChallengeScoringService, type ScorableChallenge } from './challenge.scoring';
import {
  type ChallengeListQuery,
  type CreateChallengeRequest,
  encodeChallengeCursor,
} from './challenge.validator';
import { FriendshipErrors } from './friendship.errors';
import { FriendshipRepository, type FriendProfileRow } from './friendship.repository';
import { SocialErrors } from './social.errors';

/**
 * O caso de uso dos desafios entre amigos (T17.3).
 *
 * ## A ordem, e ela não é negociável
 *
 * ```text
 * autenticar (BearerAuthGuard)
 *      ↓
 * resolver o perfil de quem chamou   →  existe? está ACTIVE? (§112/§113)
 *      ↓
 * autorizar                          →  participo deste desafio? sou o criador?
 *      ↓
 * aplicar a regra de ciclo de vida   →  ChallengeAccessPolicy, contra o relógio do servidor
 *      ↓
 * pontuar                            →  ChallengeScoringService, sobre a fonte canônica
 *      ↓
 * responder
 * ```
 *
 * **Autorizar vem antes de pontuar**, pela mesma razão da T17.2: calcular o placar de um desafio
 * para depois descobrir que quem perguntou não participa é o desenho que, no dia de um bug,
 * responde o dado. Quando a autorização falha aqui, nada foi lido do domínio de treino.
 *
 * ## Uma resposta para várias situações
 *
 * `challengeId` inexistente, desafio de terceiros e desafio para o qual só há convite pendente
 * saem todos como `404 CHALLENGE_NOT_FOUND` (§100/§182). Conhecer o id não concede nada (§101), e
 * distinguir transformaria a rota num oráculo sobre desafios alheios.
 *
 * ## O consentimento desta fase (§terceiro princípio, §123)
 *
 * Aceitar um convite é consentir em compartilhar, **com os participantes daquele desafio**, o
 * `displayName` social, a pontuação daquele desafio, `goalReached` e a posição. E nada mais.
 *
 * Os quatro interruptores da T17.2 **não** participam disso, e este arquivo não os lê: um amigo
 * com `shareWeeklyWorkoutCount = false` ainda aparece com `7 / 12` no desafio que aceitou, porque
 * são autorizações diferentes — uma é sobre o perfil, a outra é sobre uma disputa combinada entre
 * as duas pessoas. O caminho inverso também vale: participar não altera nenhum interruptor.
 */
@Injectable()
export class ChallengeService {
  constructor(
    private readonly repository: ChallengeRepository,
    private readonly friendships: FriendshipRepository,
    private readonly policy: ChallengeAccessPolicy,
    private readonly scoring: ChallengeScoringService,
    private readonly limiter: ChallengeRateLimiter,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  // --------------------------------------------------------------------------------- criação

  /**
   * `POST /v1/social/challenges`.
   *
   * ```text
   * validar (já feito)  →  resolver cada socialId  →  revalidar amizade  →  transação
   *                              │                          │
   *                     inexistente/desativado         não é amigo
   *                              └──────────┬───────────────┘
   *                                         ▼
   *                        CHALLENGE_PARTICIPANT_NOT_AVAILABLE, e desafio nenhum (§45)
   * ```
   *
   * A amizade é revalidada **aqui**, contra a linha de `friendships` de agora (§32). O que o app
   * tem na tela não autoriza nada: entre abrir a lista de amigos e tocar em "Criar", o outro lado
   * pode ter desfeito a amizade ou desativado o Social.
   *
   * Falhar em um convidado não cria o desafio (§45). A alternativa — criar com os que deram certo
   * — produziria um desafio com dois dos três amigos que a pessoa escolheu, e ela não teria como
   * perceber: a tela mostraria um desafio criado.
   */
  create(
    principal: AuthenticatedPrincipal,
    requestId: string,
    request: CreateChallengeRequest,
  ): CreateChallengeResponseDto {
    const creator = this.requireActiveProfile(principal);

    if (!this.limiter.tryAcquireCreate(principal.uid)) {
      this.logger.warn('social.challenge.create.rate_limited', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw ChallengeErrors.rateLimited('muitos desafios criados por esta conta');
    }

    const now = this.clock.now();
    if (
      this.repository.countOpenChallengesBy(creator.ownerUid, now) >= CHALLENGE_MAX_OPEN_PER_CREATOR
    ) {
      throw ChallengeErrors.tooManyOpenChallenges();
    }

    // O criador ocupa uma vaga (§31), e o validador já garantiu o teto sobre a lista. A checagem
    // é repetida aqui porque o teto é do servidor (§166), e não da forma do corpo.
    if (request.invitedSocialIds.length + 1 > CHALLENGE_PARTICIPANTS.max) {
      throw ChallengeErrors.tooManyParticipants();
    }

    const invitations = request.invitedSocialIds.map((socialId) => ({
      invitationId: randomUUID(),
      recipientUid: this.resolveInvitableFriend(creator, socialId),
    }));

    const outcome = this.repository.create({
      challengeId: randomUUID(),
      creatorUid: creator.ownerUid,
      name: request.name,
      type: request.type,
      target: request.target,
      startDate: request.startDate,
      endDate: request.endDate,
      timeZoneId: request.timeZoneId,
      startsAt: request.startsAt,
      endsAtExclusive: request.endsAtExclusive,
      invitations,
      clientRequestId: request.clientRequestId,
      requestHash: request.requestHash,
      now,
    });

    if (outcome.kind === 'IDEMPOTENCY_CONFLICT') {
      throw ChallengeErrors.idempotencyConflict();
    }

    this.log(requestId, principal.uid, 'social.challenge.created', {
      result: outcome.kind,
      // O **tipo** e a **contagem** entram; o nome, os `socialId` e a meta não (§128/§129).
      challengeType: outcome.challenge.type,
      invitedCount: invitations.length,
    });

    return {
      result: outcome.kind === 'CREATED' ? 'CREATED' : 'ALREADY_CREATED',
      challenge: this.summaryOf(outcome.challenge, creator, now),
    };
  }

  /**
   * `socialId` → `ownerUid`, exigindo amizade ativa agora (§32/§34).
   *
   * Alvo inexistente, alvo desativado e "não somos amigos" lançam **o mesmo** erro, e ele não diz
   * qual dos convidados falhou. Dizer qual transformaria a criação de desafio num verificador de
   * existência de `socialId` — a enumeração que a T17.1 fechou.
   */
  private resolveInvitableFriend(creator: FriendProfileRow, socialId: string): string {
    const target = this.friendships.findProfileBySocialId(socialId);
    if (!target || target.status !== 'ACTIVE') {
      throw ChallengeErrors.participantNotAvailable();
    }
    if (target.ownerUid === creator.ownerUid) {
      // O criador já entra automaticamente (§42). Convidá-lo seria pedir que ele aceite o que
      // acabou de propor — e o banco recusaria (`CHECK (inviter_uid <> recipient_uid)`).
      throw ChallengeErrors.participantNotAvailable();
    }
    if (!this.friendships.areFriends(creator.ownerUid, target.ownerUid)) {
      throw ChallengeErrors.participantNotAvailable();
    }
    return target.ownerUid;
  }

  // --------------------------------------------------------------------------------- leitura

  /**
   * `GET /v1/social/challenges/:challengeId` — regras e placar.
   *
   * Só quem participa. Quem tem convite **pendente** recebe `404` aqui e vê o preview pela rota de
   * convites (§98/§99): mostrar o placar antes do aceite deixaria alguém ver o progresso dos
   * outros sem ter consentido em mostrar o próprio — exatamente a assimetria que o consentimento
   * existe para impedir.
   */
  detail(
    principal: AuthenticatedPrincipal,
    requestId: string,
    challengeId: string,
  ): ChallengeDetailResponseDto {
    const viewerProfile = this.requireActiveProfile(principal);
    const challenge = this.repository.findById(challengeId);
    if (!challenge) {
      throw ChallengeErrors.notFound();
    }

    const participation = this.repository.findParticipation(challengeId, viewerProfile.ownerUid);
    if (!participation) {
      // Não participo: a mesma resposta de um desafio inexistente (§100/§182). Nada foi pontuado
      // até aqui — nenhuma leitura do domínio de treino aconteceu.
      throw ChallengeErrors.notFound();
    }

    const now = this.clock.now();
    const participants = this.repository.listParticipants(challengeId);
    const view = this.lifecycleView(challenge, participants);
    const status = this.policy.statusOf(view, now);

    // Só participantes ativos entram no placar competitivo (§96). Quem saiu não tem a pontuação
    // exibida: ele deixou de compartilhar quando saiu.
    const active = participants.filter((participant) => participant.status === 'JOINED');
    const withdrawn = participants.length - active.length;

    // A pontuação **só** é calculada quando há disputa. Um desafio cancelado não tem vencedor
    // (§94) e um `VOID` não tem competição (§95): calcular seria trabalho para produzir um placar
    // que a tela não deve mostrar como resultado.
    const scored =
      status === 'CANCELLED' || status === 'VOID'
        ? []
        : this.scoring.leaderboard(scorableOf(challenge), active);

    const creator = participants.find((participant) => participant.role === 'CREATOR');

    this.log(requestId, principal.uid, 'social.challenge.read', {
      challengeType: challenge.type,
      status,
      participantCount: active.length,
    });

    return {
      challenge: {
        challengeId: challenge.challengeId,
        name: challenge.name,
        type: challenge.type,
        target: challenge.target,
        startDate: challenge.startDate,
        endDate: challenge.endDate,
        timeZoneId: challenge.timeZoneId,
        status,
        creator: {
          socialId: creator?.socialId ?? '',
          displayName: creator?.displayName ?? '',
        },
        participantCount: active.length,
        createdAt: challenge.createdAt,
      },
      participants: scored.map(
        (participant): ChallengeParticipantScoreDto => ({
          socialId: participant.socialId,
          displayName: participant.displayName,
          score: participant.score,
          goalReached: participant.goalReached,
          rank: participant.rank,
          role: participant.role,
          isViewer: participant.ownerUid === viewerProfile.ownerUid,
        }),
      ),
      // Só o criador recebe o número de convites pendentes (§172). Os outros participantes não
      // precisam saber quem ainda não respondeu, e ninguém recebe os **nomes** (§173).
      ...(participation.role === 'CREATOR'
        ? { pendingInvitationCount: this.repository.countPendingInvitations(challengeId) }
        : {}),
      withdrawnCount: withdrawn,
      viewer: {
        role: participation.role,
        status: participation.status,
        canCancel: this.policy.canCancel(view, participation, now),
        canLeave: this.policy.canLeave(view, participation, now),
      },
      resultMayStillChange: this.policy.resultMayStillChange(view, now),
    };
  }

  /**
   * `GET /v1/social/challenges` — os meus.
   *
   * A ordenação final é por status: `ACTIVE` primeiro, depois `UPCOMING` por data de início, e os
   * encerrados por último, mais recentes primeiro (§107). Ela acontece **aqui**, e não no `ORDER
   * BY`, porque o status depende do relógio — uma ordenação no banco teria de conhecer o instante
   * de agora e a contagem de participantes de cada linha.
   *
   * A paginação continua sendo do banco (`starts_at DESC`), então a reordenação é dentro da
   * página. Para o tamanho real destas listas — dezenas, com teto de 100 — isso é o comportamento
   * certo: ordenar globalmente por um valor derivado exigiria materializar tudo.
   */
  list(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ChallengeListQuery,
  ): ChallengeListResponseDto {
    const owner = this.requireActiveProfile(principal);
    const page = this.repository.listForParticipant(owner.ownerUid, query);
    const now = this.clock.now();

    const summaries = page.items.map((challenge) => {
      const participants = this.repository.listParticipants(challenge.challengeId);
      const creator = participants.find((participant) => participant.role === 'CREATOR');
      const active = participants.filter((participant) => participant.status === 'JOINED').length;
      return {
        challengeId: challenge.challengeId,
        name: challenge.name,
        type: challenge.type,
        target: challenge.target,
        startDate: challenge.startDate,
        endDate: challenge.endDate,
        timeZoneId: challenge.timeZoneId,
        status: this.policy.statusOf(
          {
            lifecycle: challenge.lifecycle,
            startsAt: challenge.startsAt,
            endsAtExclusive: challenge.endsAtExclusive,
            participantCount: active,
          },
          now,
        ),
        creator: {
          socialId: creator?.socialId ?? '',
          displayName: creator?.displayName ?? '',
        },
        participantCount: active,
        createdAt: challenge.createdAt,
      };
    });

    // A lista **não** é pontuada (§65 da T17.2, aplicado aqui): um placar por linha custaria uma
    // consulta de treino por participante de cada desafio, e a tela de lista não mostra placar.
    summaries.sort(
      (a, b) =>
        STATUS_ORDER[a.status] - STATUS_ORDER[b.status] ||
        (a.status === 'UPCOMING'
          ? a.startDate.localeCompare(b.startDate)
          : b.startDate.localeCompare(a.startDate)) ||
        a.challengeId.localeCompare(b.challengeId),
    );

    this.log(requestId, principal.uid, 'social.challenge.listed', { count: summaries.length });

    return {
      challenges: summaries,
      total: page.total,
      ...(page.nextCursor ? { nextCursor: encodeChallengeCursor(page.nextCursor) } : {}),
    };
  }

  /**
   * `GET /v1/social/challenge-invitations` — os convites que eu recebi.
   *
   * Cada um traz o **preview** (§98): regras, quem convidou e quantos já aceitaram. Sem placar,
   * sem progresso e sem os nomes dos outros participantes (§99/§173).
   */
  invitations(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ChallengeListQuery,
  ): ChallengeInvitationListResponseDto {
    const owner = this.requireActiveProfile(principal);
    const page = this.repository.listPendingInvitations(owner.ownerUid, query);
    const now = this.clock.now();

    const previews = page.items.map((invitation): ChallengePreviewDto => {
      const participants = this.repository.listParticipants(invitation.challengeId);
      const creator = participants.find((participant) => participant.role === 'CREATOR');
      const active = participants.filter((participant) => participant.status === 'JOINED').length;
      const view: ChallengeLifecycleView = {
        lifecycle: invitation.challenge.lifecycle,
        startsAt: invitation.challenge.startsAt,
        endsAtExclusive: invitation.challenge.endsAtExclusive,
        participantCount: active,
      };

      return {
        challenge: {
          challengeId: invitation.challenge.challengeId,
          name: invitation.challenge.name,
          type: invitation.challenge.type,
          target: invitation.challenge.target,
          startDate: invitation.challenge.startDate,
          endDate: invitation.challenge.endDate,
          timeZoneId: invitation.challenge.timeZoneId,
          status: this.policy.statusOf(view, now),
          creator: {
            socialId: creator?.socialId ?? '',
            displayName: creator?.displayName ?? '',
          },
          participantCount: active,
          createdAt: invitation.challenge.createdAt,
        },
        invitationId: invitation.invitationId,
        status: this.policy.invitationStatusOf(invitation.status, view, now),
        createdAt: invitation.createdAt,
      };
    });

    this.log(requestId, principal.uid, 'social.challenge.invitations.listed', {
      count: previews.length,
    });

    return {
      invitations: previews,
      total: page.total,
      ...(page.nextCursor ? { nextCursor: encodeChallengeCursor(page.nextCursor) } : {}),
    };
  }

  // --------------------------------------------------------------------------------- respostas

  /**
   * `POST /v1/social/challenge-invitations/:invitationId/accept` — só o destinatário (§51).
   *
   * ## A amizade é revalidada no aceite (§57/§58)
   *
   * Eram amigos quando o convite nasceu; podem não ser mais. A amizade é a permissão de
   * **convidar**, e ela precisa valer no momento em que o convite vira participação — senão um
   * convite antigo seria uma porta que sobrevive ao fim da amizade.
   *
   * ## Depois do aceite, a amizade deixa de ser autoridade (§59/§60)
   *
   * A partir daqui o consentimento é do desafio, e não da amizade. Desfazer a amizade **não**
   * remove ninguém do desafio: os dois combinaram uma disputa e ela continua valendo. O que o
   * `unfriend` revoga é o perfil social da T17.2 — são autorizações distintas.
   */
  accept(
    principal: AuthenticatedPrincipal,
    requestId: string,
    invitationId: string,
  ): AcceptChallengeResponseDto {
    const recipient = this.requireActiveProfile(principal);
    this.requireRespondQuota(principal, requestId);

    const invitation = this.repository.findInvitationById(invitationId);
    // Não existe, ou não é meu: a mesma resposta (§51/§182).
    if (!invitation || invitation.recipientUid !== recipient.ownerUid) {
      throw ChallengeErrors.invitationNotFound();
    }

    const challenge = this.repository.findById(invitation.challengeId);
    if (!challenge) {
      throw ChallengeErrors.invitationNotFound();
    }

    const now = this.clock.now();
    const view = this.lifecycleView(
      challenge,
      this.repository.listParticipants(challenge.challengeId),
    );

    if (challenge.lifecycle === 'CANCELLED') {
      throw ChallengeErrors.cancelled();
    }
    if (!this.policy.canAcceptInvitation(view, now)) {
      // *Late join* é bloqueante (§56). O convite está `EXPIRED` (§55), derivado — e não uma
      // linha que um cron precisaria ter atualizado antes deste toque.
      throw ChallengeErrors.alreadyStarted();
    }
    // A amizade **agora** (§57). Se acabou, o convite deixou de valer.
    if (!this.friendships.areFriends(recipient.ownerUid, invitation.inviterUid)) {
      throw ChallengeErrors.invitationNotFound();
    }

    let outcome;
    try {
      outcome = this.repository.acceptInvitation(
        invitationId,
        challenge.challengeId,
        recipient.ownerUid,
        CHALLENGE_PARTICIPANTS.max,
        now,
      );
    } catch (error) {
      if (error instanceof ChallengeFullError) {
        // A transação foi desfeita: o convite continua `PENDING`, e não aceito num desafio de que
        // a pessoa não participa.
        throw ChallengeErrors.tooManyParticipants();
      }
      throw error;
    }

    if (outcome.kind === 'NOT_PENDING') {
      throw ChallengeErrors.invitationNotPending();
    }

    this.log(requestId, principal.uid, 'social.challenge.invitation.accepted', {
      result: outcome.kind,
      challengeType: challenge.type,
    });

    const participants = this.repository.listParticipants(challenge.challengeId);
    const creator = participants.find((participant) => participant.role === 'CREATOR');
    return {
      result: outcome.kind === 'ACCEPTED' ? 'ACCEPTED' : 'ALREADY_PARTICIPATING',
      challenge: {
        challengeId: challenge.challengeId,
        name: challenge.name,
        type: challenge.type,
        target: challenge.target,
        startDate: challenge.startDate,
        endDate: challenge.endDate,
        timeZoneId: challenge.timeZoneId,
        status: this.policy.statusOf(this.lifecycleView(challenge, participants), now),
        creator: {
          socialId: creator?.socialId ?? '',
          displayName: creator?.displayName ?? '',
        },
        participantCount: participants.filter((p) => p.status === 'JOINED').length,
        createdAt: challenge.createdAt,
      },
    };
  }

  /** `POST /v1/social/challenge-invitations/:invitationId/decline` — só o destinatário (§52). */
  decline(
    principal: AuthenticatedPrincipal,
    requestId: string,
    invitationId: string,
  ): DeclineChallengeResponseDto {
    const recipient = this.requireActiveProfile(principal);
    this.requireRespondQuota(principal, requestId);

    const invitation = this.repository.findInvitationById(invitationId);
    if (!invitation || invitation.recipientUid !== recipient.ownerUid) {
      throw ChallengeErrors.invitationNotFound();
    }

    const now = this.clock.now();
    if (this.repository.declineInvitation(invitationId, now)) {
      this.log(requestId, principal.uid, 'social.challenge.invitation.declined', {
        result: 'DECLINED',
      });
      return { result: 'DECLINED' };
    }

    // Não mudou nada agora. Recusar de novo é sucesso (§192); recusar algo já aceito é conflito.
    const current = this.repository.findInvitationById(invitationId);
    if (current?.status === 'DECLINED') {
      this.log(requestId, principal.uid, 'social.challenge.invitation.declined', {
        result: 'ALREADY_DECLINED',
      });
      return { result: 'ALREADY_DECLINED' };
    }
    throw ChallengeErrors.invitationNotPending();
  }

  /**
   * `POST /v1/social/challenges/:challengeId/leave` — só membro (§61/§62).
   *
   * Permitido antes e **durante** o desafio (§63/§64). O efeito é imediato: a pessoa some do
   * placar competitivo (§96), e a pontuação dela deixa de ser calculada e mostrada. A linha
   * permanece como `WITHDRAWN` (§65) — sair não apaga o fato de ter participado.
   *
   * Não há rejoin (§66): `WITHDRAWN` é terminal para aquele desafio.
   */
  leave(
    principal: AuthenticatedPrincipal,
    requestId: string,
    challengeId: string,
  ): LeaveChallengeResponseDto {
    const owner = this.requireActiveProfile(principal);
    this.requireRespondQuota(principal, requestId);

    const { challenge, participation } = this.requireParticipation(owner.ownerUid, challengeId);
    if (participation.role === 'CREATOR') {
      throw ChallengeErrors.cannotLeaveAsCreator();
    }

    const now = this.clock.now();
    if (this.repository.leave(challengeId, owner.ownerUid, now)) {
      this.log(requestId, principal.uid, 'social.challenge.left', {
        result: 'LEFT',
        challengeType: challenge.type,
      });
      return { result: 'LEFT' };
    }
    // Já tinha saído: toque duplo, ou reenvio depois de resposta perdida (§193).
    this.log(requestId, principal.uid, 'social.challenge.left', { result: 'ALREADY_LEFT' });
    return { result: 'ALREADY_LEFT' };
  }

  /**
   * `POST /v1/social/challenges/:challengeId/cancel` — só o criador (§67).
   *
   * Permitido em `UPCOMING` (§68) e em `ACTIVE` (§69). O desafio acaba para todos e **não tem
   * vencedor** (§94): não há um resultado parcial que valha, porque as regras que as pessoas
   * aceitaram incluíam a janela inteira.
   *
   * Cancelar um desafio já encerrado não é permitido: o resultado já existe, e apagá-lo depois
   * seria reescrever um fato do qual outras pessoas participaram.
   */
  cancel(
    principal: AuthenticatedPrincipal,
    requestId: string,
    challengeId: string,
  ): CancelChallengeResponseDto {
    const owner = this.requireActiveProfile(principal);
    this.requireRespondQuota(principal, requestId);

    const { challenge, participation } = this.requireParticipation(owner.ownerUid, challengeId);
    if (participation.role !== 'CREATOR') {
      throw ChallengeErrors.notCreator();
    }

    const now = this.clock.now();
    if (challenge.lifecycle === 'CANCELLED') {
      // Cancelar duas vezes é sucesso (§194): o estado que o cliente queria é o que existe.
      this.log(requestId, principal.uid, 'social.challenge.cancelled', {
        result: 'ALREADY_CANCELLED',
      });
      return { result: 'ALREADY_CANCELLED' };
    }
    if (now >= challenge.endsAtExclusive) {
      throw ChallengeErrors.notFound();
    }

    if (this.repository.cancel(challengeId, now)) {
      this.log(requestId, principal.uid, 'social.challenge.cancelled', {
        result: 'CANCELLED',
        challengeType: challenge.type,
      });
      return { result: 'CANCELLED' };
    }
    // Outra escrita venceu a corrida (dois aparelhos do criador cancelando ao mesmo tempo).
    return { result: 'ALREADY_CANCELLED' };
  }

  // --------------------------------------------------------------------------------- comum

  /**
   * O perfil da conta autenticada, exigindo que ele exista e esteja ativo (§112–§114).
   *
   * Sem perfil: `SOCIAL_NOT_ENABLED` — o mesmo erro da T17.0, porque é o mesmo fato.
   * Perfil desativado: `SOCIAL_PROFILE_DISABLED`, e **nenhuma** rota de desafio responde — nem as
   * de leitura, como no grafo da T17.1.
   */
  private requireActiveProfile(principal: AuthenticatedPrincipal): FriendProfileRow {
    const profile = this.friendships.findProfileByOwnerUid(principal.uid);
    if (!profile) {
      throw SocialErrors.notEnabled();
    }
    if (profile.status !== 'ACTIVE') {
      throw FriendshipErrors.profileDisabled();
    }
    return profile;
  }

  /** O desafio, quando ele existe e **envolve quem perguntou**. Caso contrário, "não existe". */
  private requireParticipation(
    ownerUid: string,
    challengeId: string,
  ): {
    challenge: StoredChallenge;
    participation: { role: 'CREATOR' | 'MEMBER'; status: 'JOINED' | 'WITHDRAWN' };
  } {
    const challenge = this.repository.findById(challengeId);
    if (!challenge) {
      throw ChallengeErrors.notFound();
    }
    const participation = this.repository.findParticipation(challengeId, ownerUid);
    if (!participation) {
      throw ChallengeErrors.notFound();
    }
    return { challenge, participation };
  }

  private requireRespondQuota(principal: AuthenticatedPrincipal, requestId: string): void {
    if (!this.limiter.tryAcquireRespond(principal.uid)) {
      this.logger.warn('social.challenge.respond.rate_limited', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw ChallengeErrors.rateLimited('muitas ações de desafio nesta conta');
    }
  }

  private lifecycleView(
    challenge: StoredChallenge,
    participants: readonly StoredChallengeParticipant[],
  ): ChallengeLifecycleView {
    return {
      lifecycle: challenge.lifecycle,
      startsAt: challenge.startsAt,
      endsAtExclusive: challenge.endsAtExclusive,
      participantCount: participants.filter((participant) => participant.status === 'JOINED')
        .length,
    };
  }

  /** O resumo logo depois da criação: o criador é o único participante, e ele é quem chamou. */
  private summaryOf(
    challenge: StoredChallenge,
    creator: FriendProfileRow,
    now: number,
  ): ChallengeSummaryDto {
    const participants = this.repository.listParticipants(challenge.challengeId);
    const active = participants.filter((participant) => participant.status === 'JOINED').length;
    return {
      challengeId: challenge.challengeId,
      name: challenge.name,
      type: challenge.type,
      target: challenge.target,
      startDate: challenge.startDate,
      endDate: challenge.endDate,
      timeZoneId: challenge.timeZoneId,
      status: this.policy.statusOf(
        {
          lifecycle: challenge.lifecycle,
          startsAt: challenge.startsAt,
          endsAtExclusive: challenge.endsAtExclusive,
          participantCount: active,
        },
        now,
      ),
      creator: { socialId: creator.socialId, displayName: creator.displayName },
      participantCount: active,
      createdAt: challenge.createdAt,
    };
  }

  /**
   * O log dos desafios (§128–§130).
   *
   * O que entra: `requestId`, prefixo do uid, evento, desfecho, **tipo** de desafio, status e
   * contagens. O que **nunca** entra: nome do desafio, `displayName`, `socialId`, `friendCode`,
   * e-mail, uid completo, corpo — e **pontuação individual** (§130). Um placar em log seria o
   * progresso de treino de alguém em texto claro numa cópia de arquivo, e ele não é necessário
   * para operar nada.
   */
  private log(
    requestId: string,
    uid: string,
    event: string,
    fields: Record<string, unknown>,
  ): void {
    this.logger.info(event, { requestId, uidPrefix: uidPrefix(uid), ...fields });
  }
}

/** As regras que o serviço de pontuação precisa. Nada de identidade, nada de participante. */
function scorableOf(challenge: StoredChallenge): ScorableChallenge {
  return {
    type: challenge.type,
    target: challenge.target,
    startDate: challenge.startDate,
    endDate: challenge.endDate,
    timeZoneId: challenge.timeZoneId,
    startsAt: challenge.startsAt,
    endsAtExclusive: challenge.endsAtExclusive,
  };
}

/** A ordem das seções da lista (§107): o que está acontecendo, o que vem, e o que passou. */
const STATUS_ORDER = {
  ACTIVE: 0,
  UPCOMING: 1,
  ENDED: 2,
  VOID: 3,
  CANCELLED: 4,
} as const;
