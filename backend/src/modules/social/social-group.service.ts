import { Inject, Injectable, Optional } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { uidPrefix } from '../auth/bearer-auth.guard';
import { BlockRepository } from './block.repository';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { CheckInProjector, type ProjectableCheckIn } from './checkin.projector';
import { FriendshipRepository } from './friendship.repository';
import { NotificationService } from './notification.service';
import type {
  CheckInGroupSharesDto,
  CreateGroupInvitationRequest,
  CreateSocialGroupRequest,
  SocialGroupDetailDto,
  SocialGroupFeedDto,
  SocialGroupFeedItemDto,
  SocialGroupInvitationDto,
  SocialGroupInvitationListDto,
  SocialGroupListDto,
  SocialGroupMemberDto,
  SocialGroupMembersDto,
  SocialGroupShareDto,
  SocialGroupSummaryDto,
} from './social-group.contract';
import { SocialGroupErrors } from './social-group.errors';
import {
  SOCIAL_GROUP_FEED,
  SOCIAL_GROUP_INVITATION_TTL_MS,
  SOCIAL_GROUP_LIST_PAGE,
  SOCIAL_GROUP_MAX_MEMBERS,
  SOCIAL_GROUP_MAX_MEMBERSHIPS,
  SOCIAL_GROUP_MAX_OWNED,
  SOCIAL_GROUP_MAX_PENDING_INVITATIONS,
  SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN,
} from './social-group.limits';
import { SocialGroupRateLimiter } from './social-group.rate-limit';
import {
  SocialGroupRepository,
  type GroupFeedRow,
  type StoredGroupInvitation,
  type StoredSocialGroup,
} from './social-group.repository';
import { SocialRepository } from './social.repository';
import { WorkoutCheckInRepository } from './workout-checkin.repository';

/**
 * Os Squads privados e o feed de grupo (T17.11).
 *
 * ```text
 *   OWNER cria                          amigo direto ativo
 *      │                                       │
 *      ▼                                       ▼
 *   Squad ──── convite ────────────────▶ recipient aceita ────▶ MEMBER
 *      │                                                          │
 *      │  o autor traz um check-in JÁ publicado (§51/§56)          │
 *      ▼                                                          ▼
 *   GroupCheckInShare ─────────────────────────────────▶ feed privado do Squad
 * ```
 *
 * ## As três invariantes que este serviço existe para manter
 *
 * 1. **um Squad é privado** (§5/§59/§60). Não existe busca, não existe listagem pública, não
 *    existe link de convite. Conhecer um `groupId` não concede nada: toda superfície do grupo
 *    começa por `requireMembership`, e quem não é membro recebe o mesmo `404` de "não existe";
 * 2. **participação não é amizade, nos dois sentidos** (§31/§109). Entrar num Squad não cria
 *    amizade; desfazer a amizade não remove ninguém do Squad. A amizade é revalidada **no
 *    convite e no aceite** (§25/§29) porque é ali que ela é a autorização; depois disso quem
 *    autoriza é a participação, que é um consentimento próprio;
 * 3. **bloqueio é soberano** (§33). Ele nunca destrói participação — destruí-la contaria a um
 *    terceiro que houve um bloqueio —, e sempre corta a visibilidade entre o par: na lista de
 *    membros, no feed, na mídia e no convite.
 *
 * ## O que este serviço nunca faz
 *
 * Não escreve em `social_workout_checkins`, não toca `sync_entities`, não gera XP, conquista,
 * streak nem ranking, e não dispara push exceto o convite (§90/§95). Excluir um Squad apaga
 * **arestas**, e nenhuma publicação de ninguém (§47/§48/§49/§102).
 */
@Injectable()
export class SocialGroupService {
  constructor(
    private readonly repository: SocialGroupRepository,
    private readonly socialRepository: SocialRepository,
    private readonly friendships: FriendshipRepository,
    private readonly blocks: BlockRepository,
    private readonly checkIns: WorkoutCheckInRepository,
    // T17.12 §43/§70/§72 — participação é o consentimento que autoriza a audiência de um Squad.
    // Quando ela acaba — saída, remoção, desativação do Social — ou quando o objeto da conversa
    // some — compartilhamento desfeito, Squad excluído — as interações daquela audiência vão junto,
    // na mesma transação. As tabelas continuam sendo do repositório de interações; aqui elas são
    // apenas acionadas pelo ciclo de vida que este serviço governa.
    private readonly interactions: CheckInInteractionRepository,
    private readonly projector: CheckInProjector,
    private readonly rateLimiter: SocialGroupRateLimiter,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
    /**
     * O convite é o **único** push desta fase (§90/§95).
     *
     * `@Optional` pelo mesmo motivo do resto do módulo: um teste que não se importa com push monta
     * o serviço sem ele, e um Squad sem notificação é um Squad completo.
     */
    @Optional() private readonly notifications?: NotificationService,
  ) {}

  // ================================================================== criação e leitura

  /**
   * `POST /v1/social/groups` (§16/§17/§18).
   *
   * A ordem é deliberada: rate limit, perfil ativo, idempotência, teto, e só então a escrita. Um
   * retry de resposta perdida não gasta o teto de criação nem consulta a contagem de Squads.
   *
   * §17 — o criador vira `OWNER` e a participação nasce **na mesma transação**. Um Squad sem
   * participação seria um grupo que nem o dono consegue abrir, e ele não se corrige sozinho.
   */
  createGroup(
    callerUid: string,
    requestId: string,
    request: CreateSocialGroupRequest,
  ): SocialGroupSummaryDto {
    if (!this.rateLimiter.tryAcquireCreate(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);

    // §146 — dois toques em "Criar Squad" produzem um Squad. Devolver o existente é o que faz o
    // retry de resposta perdida convergir em vez de deixar um grupo fantasma na lista.
    const existing = this.repository.findGroupByClientRequest(callerUid, request.clientRequestId);
    if (existing && existing.status === 'ACTIVE') {
      return this.summaryOf(existing, 'OWNER');
    }

    if (this.repository.countOwnedActiveGroups(callerUid) >= SOCIAL_GROUP_MAX_OWNED) {
      throw SocialGroupErrors.ownedLimitReached(SOCIAL_GROUP_MAX_OWNED);
    }
    if (this.repository.countActiveMemberships(callerUid) >= SOCIAL_GROUP_MAX_MEMBERSHIPS) {
      throw SocialGroupErrors.membershipLimitReached(SOCIAL_GROUP_MAX_MEMBERSHIPS);
    }

    const now = this.clock.now();
    const group: StoredSocialGroup = {
      id: randomUUID(),
      ownerUid: callerUid,
      name: request.name,
      status: 'ACTIVE',
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      clientRequestId: request.clientRequestId,
    };

    this.repository.transaction(() => {
      this.repository.createGroup(group);
      // §15 — o dono **é** membro. Não existe posse fora da participação: uma coluna `owner_uid`
      // sem linha correspondente produziria um dono que não aparece na lista de membros e que a
      // contagem não conta.
      this.repository.createMembership({
        id: randomUUID(),
        groupId: group.id,
        memberUid: callerUid,
        role: 'OWNER',
        joinedAt: now,
      });
    });

    // §123/§124 — o evento e o prefixo do uid. **Nunca** o nome do Squad, nunca `displayName`,
    // nunca o uid completo.
    this.logger.info('social.group.created', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });

    return { groupId: group.id, name: group.name, memberCount: 1, role: 'OWNER', createdAt: now };
  }

  /** `GET /v1/social/groups` (§131/§132). Só as participações **ativas** do próprio viewer. */
  listGroups(callerUid: string): SocialGroupListDto {
    this.requireActiveProfile(callerUid);
    const rows = this.repository.listGroupsForMember(callerUid, SOCIAL_GROUP_LIST_PAGE.maxLimit);
    return {
      items: rows.map<SocialGroupSummaryDto>((row) => ({
        groupId: row.groupId,
        name: row.name,
        memberCount: row.memberCount,
        role: row.role,
        createdAt: row.createdAt,
      })),
    };
  }

  /** `GET /v1/social/groups/{groupId}` (§135). Exige participação — ter o `groupId` não basta. */
  getGroup(callerUid: string, groupId: string): SocialGroupDetailDto {
    this.requireActiveProfile(callerUid);
    const { group, membership } = this.requireMembership(callerUid, groupId);

    return {
      groupId: group.id,
      name: group.name,
      memberCount: this.repository.countMembers(group.id),
      role: membership.role,
      createdAt: group.createdAt,
      // Só o dono convida (§22), então só ele tem uso para o número — e mostrá-lo aos demais
      // contaria quantas pessoas foram chamadas e ainda não responderam, que é informação sobre
      // terceiros que ninguém pediu para compartilhar.
      pendingInvitationCount:
        membership.role === 'OWNER' ? this.repository.countPendingInvitations(group.id) : null,
    };
  }

  /**
   * `GET /v1/social/groups/{groupId}/members` (§34/§35/§36/§136).
   *
   * A projeção por bloqueio acontece **aqui**, e não na tela: um membro em bloqueio com o viewer
   * vira uma entrada opaca — `membershipId`, papel, e "Participante indisponível". Sem `socialId`,
   * sem `displayName`, sem navegação de perfil.
   *
   * O `membershipId` continua presente, e é o que preserva a integridade administrativa (§36/§37):
   * o dono consegue remover alguém que o bloqueou sem que a tela receba a identidade daquela
   * pessoa. Ele é um identificador opaco válido só dentro deste grupo — nunca um uid (§37).
   */
  listMembers(callerUid: string, groupId: string): SocialGroupMembersDto {
    this.requireActiveProfile(callerUid);
    this.requireMembership(callerUid, groupId);

    const rows = this.repository.listMembers(groupId, callerUid);
    return {
      items: rows.map<SocialGroupMemberDto>((row) => {
        const blocked = row.blocked === 1;
        return {
          membershipId: row.membershipId,
          role: row.role,
          joinedAt: row.joinedAt,
          available: !blocked,
          socialId: blocked ? null : row.socialId,
          displayName: blocked ? null : row.displayName,
          isCurrentUser: row.memberUid === callerUid,
        };
      }),
    };
  }

  // ================================================================== convites

  /**
   * `POST /v1/social/groups/{groupId}/invitations` (§22–§28).
   *
   * ```text
   * quem convida:  somente o OWNER                                        §22
   * quem pode ser convidado:
   *   perfil ACTIVE ∧ amigo direto atual do OWNER ∧ ¬bloqueio             §23
   * nunca:  socialId de desconhecido · displayName · e-mail · friendCode  §24
   * ```
   *
   * §25 — o backend **revalida a amizade**. O seletor do Android filtra a lista de amigos por
   * conveniência, e isso não é controle de acesso: um APK modificado que enviasse o `socialId` de
   * um estranho encontra esta verificação.
   *
   * §26/§27 — convidar a si mesmo é impossível, e convidar quem já é membro devolve
   * `GROUP_ALREADY_MEMBER` sem criar participação duplicada.
   */
  invite(
    callerUid: string,
    requestId: string,
    groupId: string,
    request: CreateGroupInvitationRequest,
  ): SocialGroupInvitationDto {
    if (!this.rateLimiter.tryAcquireInvite(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);
    const { group, membership } = this.requireMembership(callerUid, groupId);
    if (membership.role !== 'OWNER') {
      throw SocialGroupErrors.forbidden('apenas o dono do squad pode convidar');
    }

    // §146 — a mesma intenção produz o mesmo convite.
    const byRequest = this.repository.findInvitationByClientRequest(
      groupId,
      request.clientRequestId,
    );
    if (byRequest) {
      return this.invitationDtoOf(byRequest, group, callerUid);
    }

    const target = this.friendships.findProfileBySocialId(request.socialId);
    // §23/§24/§26 — inexistente, desativado, não-amigo, bloqueado e o próprio requisitante: a mesma
    // resposta para os cinco. Distinguir transformaria a rota num verificador de `socialId`.
    if (
      !target ||
      target.status !== 'ACTIVE' ||
      target.ownerUid === callerUid ||
      !this.friendships.areFriends(callerUid, target.ownerUid) ||
      this.blocks.isBlockedBidirectional(callerUid, target.ownerUid)
    ) {
      throw SocialGroupErrors.inviteNotAllowed();
    }

    if (this.repository.findActiveMembership(groupId, target.ownerUid)) {
      throw SocialGroupErrors.alreadyMember();
    }
    if (this.repository.countMembers(groupId) >= SOCIAL_GROUP_MAX_MEMBERS) {
      throw SocialGroupErrors.full(SOCIAL_GROUP_MAX_MEMBERS);
    }
    // §28 — um convite pendente por (Squad, destinatário). Devolver o que já existe é a resposta
    // segura: criar o segundo deixaria dois itens na lista de quem recebeu, e aceitar um deixaria
    // o outro pendente para sempre.
    const pending = this.repository.findPendingInvitation(groupId, target.ownerUid);
    if (pending) {
      return this.invitationDtoOf(pending, group, callerUid);
    }
    if (this.repository.countPendingInvitations(groupId) >= SOCIAL_GROUP_MAX_PENDING_INVITATIONS) {
      throw SocialGroupErrors.inviteLimitReached(SOCIAL_GROUP_MAX_PENDING_INVITATIONS);
    }

    const now = this.clock.now();
    const invitation: StoredGroupInvitation = {
      id: randomUUID(),
      groupId,
      senderUid: callerUid,
      recipientUid: target.ownerUid,
      status: 'PENDING',
      createdAt: now,
      expiresAt: now + SOCIAL_GROUP_INVITATION_TTL_MS,
      respondedAt: null,
      clientRequestId: request.clientRequestId,
    };

    // §90 — o convite e o evento de push nascem na **mesma** transação. Fora dela, uma falha entre
    // as duas escritas produziria um convite sem aviso ou um aviso sem convite; o segundo é pior,
    // porque leva alguém a abrir o app para procurar algo que não existe.
    this.repository.transaction(() => {
      this.repository.createInvitation(invitation);
      this.notifications?.enqueueGroupInvitationReceived({
        invitationId: invitation.id,
        recipientUid: invitation.recipientUid,
        expiresAt: invitation.expiresAt,
      });
    });

    this.logger.info('social.group.invitation.created', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });

    return this.invitationDtoOf(invitation, group, callerUid);
  }

  /** `GET /v1/social/groups/invitations` (§138/§139). Só os pendentes, e só os do próprio viewer. */
  listInvitations(callerUid: string): SocialGroupInvitationListDto {
    this.requireActiveProfile(callerUid);
    const now = this.clock.now();
    const rows = this.repository.listInvitationsForRecipient(
      callerUid,
      SOCIAL_GROUP_LIST_PAGE.maxLimit,
    );

    return {
      items: rows
        // §21 — a expiração é **derivada**. Um convite vencido some da lista sem que nada precise
        // ter passado por ali para marcá-lo.
        .filter((row) => now < row.expiresAt)
        .map<SocialGroupInvitationDto>((row) => ({
          invitationId: row.invitationId,
          groupId: row.groupId,
          groupName: row.groupName,
          memberCount: row.memberCount,
          // §139 — se houver bloqueio, a identidade de quem convidou não aparece. Na prática o
          // bloqueio já cancelou o convite (§105); isto cobre a corrida entre as duas escritas.
          inviterSocialId: row.blocked === 1 ? null : row.inviterSocialId,
          inviterDisplayName: row.blocked === 1 ? null : row.inviterDisplayName,
          status: 'PENDING',
          createdAt: row.createdAt,
          expiresAt: row.expiresAt,
        })),
    };
  }

  /**
   * `POST /v1/social/group-invitations/{invitationId}/accept` (§29/§30/§31).
   *
   * Sete revalidações, **no instante do aceite**, e cada uma cobre uma coisa que pode ter mudado
   * desde o envio:
   *
   * ```text
   * o convite é meu · está PENDING · não expirou     §29
   * o Squad está ACTIVE                              §29
   * meu perfil está ACTIVE                           §29
   * o Squad tem vaga                                 §29
   * a amizade com quem convidou ainda existe         §29/§30
   * não há bloqueio em nenhuma direção               §29
   * ```
   *
   * §30 explica a mais importante: é permitido que A convide B, que os dois deixem de ser amigos,
   * e que B tente aceitar. A resposta é `INVITATION_NOT_AVAILABLE` — indistinguível de "esse
   * convite não existe" —, porque o convite foi emitido sob uma autorização que já não vale.
   *
   * §31 é o outro lado da mesma moeda: **depois** do aceite a participação é um consentimento
   * próprio, e desfazer a amizade não remove ninguém. É o mesmo princípio de consentimento
   * específico dos desafios da T17.3.
   */
  acceptInvitation(
    callerUid: string,
    requestId: string,
    invitationId: string,
  ): SocialGroupSummaryDto {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);

    const invitation = this.repository.findInvitation(invitationId);
    const now = this.clock.now();
    if (
      !invitation ||
      invitation.recipientUid !== callerUid ||
      invitation.status !== 'PENDING' ||
      now >= invitation.expiresAt
    ) {
      throw SocialGroupErrors.invitationNotAvailable();
    }

    const group = this.repository.findGroup(invitation.groupId);
    if (!group || group.status !== 'ACTIVE') {
      throw SocialGroupErrors.invitationNotAvailable();
    }

    // §29/§30 — a amizade com quem convidou, e o bloqueio, revalidados agora.
    if (
      !this.friendships.areFriends(callerUid, invitation.senderUid) ||
      this.blocks.isBlockedBidirectional(callerUid, invitation.senderUid)
    ) {
      throw SocialGroupErrors.invitationNotAvailable();
    }

    // Já membro: o aceite converge em vez de virar erro. Acontece quando a resposta se perde e o
    // aparelho repete o toque.
    const already = this.repository.findActiveMembership(group.id, callerUid);
    if (already) {
      this.repository.resolveInvitation(invitationId, 'ACCEPTED', now);
      return this.summaryOf(group, already.role);
    }

    if (this.repository.countMembers(group.id) >= SOCIAL_GROUP_MAX_MEMBERS) {
      throw SocialGroupErrors.full(SOCIAL_GROUP_MAX_MEMBERS);
    }
    if (this.repository.countActiveMemberships(callerUid) >= SOCIAL_GROUP_MAX_MEMBERSHIPS) {
      throw SocialGroupErrors.membershipLimitReached(SOCIAL_GROUP_MAX_MEMBERSHIPS);
    }

    // A transição do convite e a criação da participação são uma coisa só. Fora de uma transação,
    // uma falha no meio deixaria um convite consumido sem participação — e não haveria como
    // aceitar de novo.
    const created = this.repository.transaction(() => {
      if (!this.repository.resolveInvitation(invitationId, 'ACCEPTED', now)) {
        return false;
      }
      this.repository.createMembership({
        id: randomUUID(),
        groupId: group.id,
        memberUid: callerUid,
        role: 'MEMBER',
        joinedAt: now,
      });
      return true;
    });

    if (!created) {
      // Outra requisição consumiu o convite entre a leitura e a escrita.
      throw SocialGroupErrors.invitationNotAvailable();
    }

    this.logger.info('social.group.invitation.accepted', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });

    return this.summaryOf(group, 'MEMBER');
  }

  /** `POST /v1/social/group-invitations/{invitationId}/decline`. Só o destinatário. Idempotente. */
  declineInvitation(callerUid: string, requestId: string, invitationId: string): void {
    this.respondToInvitation(callerUid, requestId, invitationId, 'DECLINED');
  }

  /**
   * `POST /v1/social/group-invitations/{invitationId}/cancel`. Só quem enviou.
   *
   * `sender` e `recipient` recebem a **mesma** resposta para um convite que não é deles: o `404`
   * de `INVITATION_NOT_AVAILABLE`. Um erro que distinguisse "não é seu" de "não existe" faria de
   * um `invitationId` de terceiro um oráculo.
   */
  cancelInvitation(callerUid: string, requestId: string, invitationId: string): void {
    this.respondToInvitation(callerUid, requestId, invitationId, 'CANCELLED');
  }

  // ================================================================== composição

  /**
   * `POST /v1/social/groups/{groupId}/leave` (§38/§39/§44/§62).
   *
   * O dono **não** sai por aqui (§39): ele transfere a posse ou exclui o Squad. Deixá-lo sair
   * produziria um grupo sem dono — e escolher um substituto por ordenação seria entregar um grupo
   * de gente real a quem não pediu (§98).
   *
   * §44 — sair não mexe em amizade nenhuma. §62 — os compartilhamentos daquela pessoa **naquele**
   * Squad são removidos junto, na mesma transação: sem isso, o conteúdo de quem saiu continuaria no
   * feed, e um `rejoin` futuro ressuscitaria posts antigos (§64).
   */
  leaveGroup(callerUid: string, requestId: string, groupId: string): void {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);

    const group = this.repository.findGroup(groupId);
    const membership =
      group?.status === 'ACTIVE' ? this.repository.findActiveMembership(groupId, callerUid) : null;

    // Idempotente (§38): sair de onde já não se está é sucesso. O `404` fica para quem nunca
    // participou de um Squad que existe — mas as duas respostas são indistinguíveis por desenho,
    // e por isso a saída silenciosa é a correta aqui.
    if (!group || group.status !== 'ACTIVE' || !membership) {
      return;
    }
    if (membership.role === 'OWNER') {
      throw SocialGroupErrors.ownerActionRequired(
        'transfira a posse ou exclua o squad antes de sair',
      );
    }

    const now = this.clock.now();
    this.repository.transaction(() => {
      this.purgeMemberFootprint(groupId, callerUid, now);
      this.repository.deleteMembership(groupId, callerUid);
    });

    this.logger.info('social.group.member.left', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });
  }

  /**
   * `DELETE /v1/social/groups/{groupId}/members/{membershipId}` (§42/§43/§44/§63).
   *
   * Só o dono, e nunca a si mesmo (§43) — para sair ele transfere ou exclui. O alvo é o
   * `membershipId` justamente para que o dono possa remover alguém com quem tem bloqueio, sem que
   * a tela precise ter recebido a identidade daquela pessoa (§36).
   *
   * Idempotente (§42). Não altera amizade (§44). Remove os compartilhamentos daquela pessoa neste
   * Squad, como a saída (§63/§64).
   */
  removeMember(callerUid: string, requestId: string, groupId: string, membershipId: string): void {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);
    const { membership } = this.requireMembership(callerUid, groupId);
    if (membership.role !== 'OWNER') {
      throw SocialGroupErrors.forbidden('apenas o dono do squad pode remover participantes');
    }

    const target = this.repository.findMembershipById(groupId, membershipId);
    if (!target) {
      // Idempotente: remover quem já saiu é sucesso.
      return;
    }
    if (target.memberUid === callerUid) {
      throw SocialGroupErrors.ownerActionRequired(
        'o dono não pode remover a si mesmo; transfira a posse ou exclua o squad',
      );
    }

    const now = this.clock.now();
    this.repository.transaction(() => {
      // §144 — remover alguém tem exatamente o mesmo efeito que a saída.
      this.purgeMemberFootprint(groupId, target.memberUid, now);
      this.repository.deleteMembership(groupId, target.memberUid);
    });

    this.logger.info('social.group.member.removed', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });
  }

  /**
   * `POST /v1/social/groups/{groupId}/transfer-ownership` (§40/§41/§150).
   *
   * Duas escritas — o antigo dono vira `MEMBER`, o alvo vira `OWNER` — dentro de **uma**
   * transação, sobre um índice único parcial que torna dois `OWNER` impossíveis no banco. §150 é
   * explícito: nunca 0 dono, nunca 2. Aqui o estado inválido não é improvável; ele é
   * irrepresentável.
   *
   * §41 — o alvo é escolhido pelo `membershipId` da lista de membros. Um `socialId` no corpo
   * permitiria transferir para uma identidade que a tela nunca mostrou.
   *
   * O bloqueio **não** impede a transferência: um dono que precisa sair do produto precisa poder
   * entregar o grupo, e a lista de membros já dá a ele uma entrada opaca para escolher (§36). O
   * que o bloqueio continua fazendo é esconder o conteúdo entre o par — o que segue valendo depois
   * da troca de papéis, porque a política é avaliada por leitura.
   */
  transferOwnership(
    callerUid: string,
    requestId: string,
    groupId: string,
    membershipId: string,
  ): void {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);
    const { group, membership } = this.requireMembership(callerUid, groupId);
    if (membership.role !== 'OWNER') {
      throw SocialGroupErrors.forbidden('apenas o dono do squad pode transferir a posse');
    }

    const target = this.repository.findMembershipById(groupId, membershipId);
    if (!target || target.memberUid === callerUid) {
      throw SocialGroupErrors.memberNotFound();
    }

    const now = this.clock.now();
    this.repository.transaction(() => {
      // A ordem importa: o índice único parcial permite **um** `OWNER` por grupo, então rebaixar
      // antes de promover é o que evita que a transação inteira falhe contra ela mesma.
      this.repository.updateMembershipRole(membership.id, 'MEMBER');
      this.repository.updateMembershipRole(target.id, 'OWNER');
      this.repository.updateGroupOwner(group.id, target.memberUid, now);
    });

    this.logger.info('social.group.ownership.transferred', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });
  }

  /**
   * `DELETE /v1/social/groups/{groupId}` (§46/§47/§48/§49).
   *
   * Só o dono. Idempotente: a escrita é condicional em `status = 'ACTIVE'`, e repetir converge.
   *
   * O que ele apaga é **contexto de grupo** (§48): participações, convites pendentes e as arestas
   * de compartilhamento. O que ele nunca alcança (§47/§49/§102): `WorkoutSession`,
   * `WorkoutTemplate`, cópias importadas de `WorkoutShare`, o check-in original de ninguém e a
   * mídia dele. Um check-in que estava aqui continua no Feed de amigos do autor, conforme a
   * política original dele — só o vínculo desaparece.
   */
  deleteGroup(callerUid: string, requestId: string, groupId: string): void {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);

    const group = this.repository.findGroup(groupId);
    if (!group || group.status !== 'ACTIVE') {
      return; // idempotente
    }
    // Anti-enumeração: quem não é membro nem sabe que o Squad existe; quem é membro mas não é dono
    // recebe `403`, porque esconder que a ação é do dono só faria a tela mostrar um botão morto.
    const membership = this.repository.findActiveMembership(groupId, callerUid);
    if (!membership) {
      throw SocialGroupErrors.notFound();
    }
    if (membership.role !== 'OWNER') {
      throw SocialGroupErrors.forbidden('apenas o dono do squad pode excluí-lo');
    }

    const now = this.clock.now();
    this.repository.transaction(() => {
      this.repository.markGroupDeleted(groupId, callerUid, now);
      this.repository.purgeGroupContext(groupId, now);
      // T17.12 §72/§146 — a audiência `GROUP(este Squad)` deixa de existir junto com o Squad. O
      // `ON DELETE CASCADE` da FK não cobre isto: a exclusão é soft (§46), a linha de
      // `social_groups` continua lá e nenhum cascade dispara. O que continua intocado é tudo o
      // resto — `FRIEND`, os outros Squads e o check-in de quem quer que seja.
      this.interactions.purgeGroupInteractions(groupId, now);
    });

    this.logger.info('social.group.deleted', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });
  }

  // ================================================================== feed

  /**
   * `POST /v1/social/groups/{groupId}/checkins/{checkInId}` (§51–§58/§68).
   *
   * ```text
   * o check-in é meu ∧ está PUBLISHED        §51/§56
   * eu sou membro ativo deste Squad          §57
   * ainda cabe (≤ 5 Squads por check-in)     §68
   * ```
   *
   * §52 — **nada é automático**. Entrar num Squad não faz check-ins futuros aparecerem lá, e
   * publicar um check-in não o traz para grupo nenhum. Este método é o único caminho, e ele só é
   * alcançado por um toque explícito.
   *
   * §56 — só o autor. B não reposta o check-in de A: a autoria é verificada contra
   * `social_workout_checkins.author_uid`, e a resposta para "não é seu" é a mesma de "não existe".
   *
   * §58 — a existência de um membro bloqueado **não** veta o compartilhamento para os outros 18. A
   * projeção do feed filtra aquele par, e usar o bloqueio de um como veto para todos daria a uma
   * pessoa o poder de silenciar o grupo inteiro.
   *
   * §55/§170 — idempotente pela chave natural `(group_id, checkin_id)`: compartilhar duas vezes
   * devolve o mesmo vínculo, com a **data original**. Nenhum `clientRequestId` é necessário aqui,
   * e ele seria mais fraco: a chave natural vale também entre dispositivos diferentes.
   */
  shareCheckIn(
    callerUid: string,
    requestId: string,
    groupId: string,
    checkInId: string,
  ): SocialGroupShareDto {
    if (!this.rateLimiter.tryAcquireShare(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);
    this.requireMembership(callerUid, groupId);

    const checkIn = this.checkIns.findById(checkInId);
    if (!checkIn || checkIn.authorUid !== callerUid || checkIn.status !== 'PUBLISHED') {
      throw SocialGroupErrors.checkInNotFound();
    }

    const existing = this.repository.findShare(groupId, checkInId);
    if (!existing) {
      if (this.repository.countSharesForCheckIn(checkInId) >= SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN) {
        throw SocialGroupErrors.shareLimitReached(SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN);
      }
      const now = this.clock.now();
      try {
        this.repository.createShare({
          id: randomUUID(),
          groupId,
          checkInId,
          authorUid: callerUid,
          createdAt: now,
        });
      } catch (error) {
        // A corrida entre duas requisições simultâneas: a `UNIQUE` recusa a segunda, e a resposta
        // certa é o vínculo que a primeira criou. A decisão lê a **propriedade** `code`, e não
        // `instanceof`: sob Jest, um erro nativo do `better-sqlite3` pode atravessar realms e
        // falhar um `instanceof` que deveria valer.
        const code = (error as { code?: unknown }).code;
        if (typeof code !== 'string' || !code.startsWith('SQLITE_CONSTRAINT')) {
          throw error;
        }
        if (!this.repository.findShare(groupId, checkInId)) {
          throw SocialGroupErrors.unavailable('não foi possível compartilhar agora');
        }
      }

      this.logger.info('social.group.checkin_shared', {
        requestId,
        uidPrefix: uidPrefix(callerUid),
      });
    }

    const share = this.repository.findShare(groupId, checkInId);
    if (!share) {
      throw SocialGroupErrors.unavailable('não foi possível compartilhar agora');
    }

    // Projetado pelo **mesmo** caminho do feed: a resposta do `POST` é exatamente o que a próxima
    // leitura devolveria, em vez de uma segunda maneira de descrever o mesmo card.
    const [item] = this.projectFeedRows(callerUid, groupId, [
      {
        checkInId,
        authorUid: callerUid,
        authorSocialId: '',
        authorDisplayName: '',
        caption: checkIn.caption,
        publishedAt: checkIn.createdAt,
        sharedToGroupAt: share.createdAt,
      },
    ]);

    return {
      item,
      sharedGroupCount: this.repository.countSharesForCheckIn(checkInId),
    };
  }

  /**
   * `DELETE /v1/social/groups/{groupId}/checkins/{checkInId}` (§128/§129/§130).
   *
   * Desfazer o compartilhamento **não apaga o check-in** (§128): a publicação continua no Feed de
   * amigos do autor, com legenda, foto, reações e comentários intactos. Some a aresta, e só ela.
   *
   * §129/§130 — só o **autor** remove. O dono do Squad não modera conteúdo por exclusão nesta
   * fase: quem tem problema com uma publicação usa Bloqueio e Denúncia, que já existem no domínio
   * e não exigem dar a alguém o poder de apagar o que outra pessoa escreveu.
   *
   * Idempotente: remover o que já não existe é sucesso.
   */
  unshareCheckIn(callerUid: string, requestId: string, groupId: string, checkInId: string): void {
    if (!this.rateLimiter.tryAcquireShare(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);

    const now = this.clock.now();
    // T17.12 §70/§71/§145 — tirar o check-in do Squad revoga a audiência `GROUP` dele **na hora**,
    // e isso já valeria sem esta limpeza: a política exige o compartilhamento a cada leitura. A
    // limpeza existe para não deixar uma conversa inalcançável crescendo para sempre no banco, e
    // ela é `(este Squad, este check-in)` — `FRIEND` e os outros Squads não são tocados.
    const removed = this.repository.transaction(() => {
      const deleted = this.repository.deleteShare(groupId, checkInId, callerUid);
      if (deleted) {
        this.interactions.purgeGroupInteractionsForShare(groupId, checkInId, now);
      }
      return deleted;
    });

    if (removed) {
      this.logger.info('social.group.checkin_unshared', {
        requestId,
        uidPrefix: uidPrefix(callerUid),
      });
    }
  }

  /**
   * `GET /v1/social/groups/{groupId}/feed` (§59/§60/§61/§86).
   *
   * A autorização de leitura tem duas camadas, e é de propósito: `requireMembership` responde
   * `404` para quem não é membro, e a própria consulta traz um `EXISTS` de participação que não
   * tem como devolver linha para quem não é (§121 do repositório). A segunda existe porque a
   * primeira depende de alguém ter escrito a chamada na ordem certa.
   */
  getFeed(
    callerUid: string,
    requestId: string,
    groupId: string,
    limit?: number,
  ): SocialGroupFeedDto {
    this.requireActiveProfile(callerUid);
    this.requireMembership(callerUid, groupId);

    const now = this.clock.now();
    const bounded = Math.min(
      Math.max(limit ?? SOCIAL_GROUP_FEED.defaultLimit, 1),
      SOCIAL_GROUP_FEED.maxLimit,
    );
    const rows = this.repository.findGroupFeed(
      callerUid,
      groupId,
      now - SOCIAL_GROUP_FEED.windowMs,
      bounded,
    );

    this.logger.info('social.group.feed.read', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      returnedCount: rows.length,
    });

    return { items: this.projectFeedRows(callerUid, groupId, rows) };
  }

  /** §141 — em quais Squads este check-in **próprio** já está. Alimenta o seletor da tela. */
  listGroupsForCheckIn(callerUid: string, checkInId: string): CheckInGroupSharesDto {
    this.requireActiveProfile(callerUid);
    const checkIn = this.checkIns.findById(checkInId);
    if (!checkIn || checkIn.authorUid !== callerUid || checkIn.status !== 'PUBLISHED') {
      throw SocialGroupErrors.checkInNotFound();
    }
    return { groupIds: this.repository.listGroupIdsForCheckIn(checkInId) };
  }

  // ================================================================== ciclo de vida da conta

  /**
   * A política de desativação do Social (§96–§99).
   *
   * ```text
   * dono de Squad com outras pessoas  ──▶ recusa: GROUP_OWNERSHIP_REQUIRES_ACTION
   * dono de Squad vazio (só ele)      ──▶ o Squad é excluído
   * participante                      ──▶ sai dos Squads (§97)
   * convites pendentes (dos dois lados) ──▶ cancelados
   * ```
   *
   * §98 é a decisão que merece explicação. A alternativa — escolher um novo dono automaticamente —
   * exigiria uma ordenação, e qualquer ordenação entrega um grupo de pessoas reais a alguém que
   * não pediu por isso. Recusar é mais explícito e mais seguro: a pessoa transfere a posse para
   * quem ela escolher, ou exclui o Squad, e só então desativa.
   *
   * Devolve **contagens**, e nunca dados de membro (§99).
   *
   * Chamado de dentro da transação de `SocialService.disable`, como `applySocialDisable` dos
   * desafios: desativar o perfil e resolver os grupos acontecem juntos, ou não acontecem.
   */
  applySocialDisable(
    ownerUid: string,
    now: number,
  ): {
    groupsDeleted: number;
    groupsLeft: number;
    invitationsCancelled: number;
  } {
    const blocking = this.repository.listOwnedActiveGroupsWithOthers(ownerUid);
    if (blocking.length > 0) {
      throw SocialGroupErrors.ownerActionRequired(
        `transfira a posse ou exclua ${blocking.length} squad(s) antes de desativar o social`,
      );
    }

    let groupsDeleted = 0;
    for (const groupId of this.repository.listOwnedActiveGroups(ownerUid)) {
      // Só sobram os Squads em que a pessoa está sozinha — a checagem acima garantiu isso.
      this.repository.markGroupDeleted(groupId, ownerUid, now);
      this.repository.purgeGroupContext(groupId, now);
      this.interactions.purgeGroupInteractions(groupId, now);
      groupsDeleted += 1;
    }

    let groupsLeft = 0;
    for (const groupId of this.repository.listActiveMemberGroups(ownerUid)) {
      // T17.12 §75 — desativar o Social encerra as participações, e o conteúdo `GROUP` ligado a
      // elas termina junto. É a mesma regra da saída (§43): a participação era o consentimento.
      this.purgeMemberFootprint(groupId, ownerUid, now);
      this.repository.deleteMembership(groupId, ownerUid);
      groupsLeft += 1;
    }

    const invitationsCancelled = this.repository.cancelAllPendingInvitationsFor(ownerUid, now);

    return { groupsDeleted, groupsLeft, invitationsCancelled };
  }

  /**
   * A pergunta que a desativação faz **antes** de tentar (§99).
   *
   * Existe separada de [applySocialDisable] para que a rota possa recusar com uma contagem sem
   * abrir transação nenhuma.
   */
  countGroupsRequiringOwnerAction(ownerUid: string): number {
    return this.repository.listOwnedActiveGroupsWithOthers(ownerUid).length;
  }

  // ------------------------------------------------------------------ internas

  /**
   * Perfil social **ativo** é a porta de entrada de tudo (§16/§96).
   *
   * Desativado não cria, não convida, não aceita, não compartilha e não lê.
   */
  private requireActiveProfile(callerUid: string) {
    const account = this.socialRepository.find(callerUid);
    if (!account || account.profile.status !== 'ACTIVE') {
      throw SocialGroupErrors.socialNotEnabled();
    }
    return account.profile;
  }

  /**
   * A participação é a autorização de **toda** superfície do Squad (§59/§60).
   *
   * Inexistente, excluído e "existe mas você não é membro" produzem o mesmo `404`. É isto que faz
   * um `groupId` vazado não valer nada.
   */
  private requireMembership(callerUid: string, groupId: string) {
    const group = this.repository.findGroup(groupId);
    if (!group || group.status !== 'ACTIVE') {
      throw SocialGroupErrors.notFound();
    }
    const membership = this.repository.findActiveMembership(groupId, callerUid);
    if (!membership) {
      throw SocialGroupErrors.notFound();
    }
    return { group, membership };
  }

  /**
   * Tudo o que uma pessoa deixa para trás **naquele** Squad (T17.12 §43/§44/§71/§143/§144).
   *
   * Três coisas, e cada uma cobre um lado do mesmo fim de participação:
   *
   * ```text
   * as conversas nos posts que ela trouxe   ← porque o compartilhamento vai embora junto (§70/§71)
   * os próprios compartilhamentos dela      ← T17.11 §62/§63
   * as reações e comentários dela aqui      ← porque a participação era o consentimento (§45)
   * ```
   *
   * A primeira é a que faltava e a ordem dela importa: depois de apagar as arestas não há como
   * saber quais eram. Sem ela, o comentário que **outra pessoa** deixou no post de quem saiu ficaria
   * vivo e inalcançável — e voltaria à tona no dia em que a pessoa reentrasse e compartilhasse o
   * mesmo check-in (§46/§143). "O compartilhamento acabou" precisa ter um efeito só, e não dois
   * conforme o caminho que o desfez.
   *
   * Sempre dentro da transação de quem chama: metade disto aplicado é conteúdo visível de alguém
   * que já não é membro.
   */
  private purgeMemberFootprint(groupId: string, memberUid: string, now: number): void {
    for (const checkInId of this.repository.listSharedCheckInIdsByAuthorInGroup(
      groupId,
      memberUid,
    )) {
      this.interactions.purgeGroupInteractionsForShare(groupId, checkInId, now);
    }
    this.repository.deleteSharesByAuthorInGroup(groupId, memberUid);
    this.interactions.purgeGroupInteractionsByActor(groupId, memberUid, now);
  }

  private respondToInvitation(
    callerUid: string,
    requestId: string,
    invitationId: string,
    outcome: 'DECLINED' | 'CANCELLED',
  ): void {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    this.requireActiveProfile(callerUid);

    const invitation = this.repository.findInvitation(invitationId);
    const authorized =
      invitation &&
      (outcome === 'DECLINED'
        ? invitation.recipientUid === callerUid
        : invitation.senderUid === callerUid);
    if (!authorized) {
      throw SocialGroupErrors.invitationNotAvailable();
    }

    // Idempotente: responder de novo converge, porque a escrita é condicional em `PENDING`.
    this.repository.resolveInvitation(invitationId, outcome, this.clock.now());

    this.logger.info('social.group.invitation.resolved', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      outcome,
    });
  }

  private summaryOf(group: StoredSocialGroup, role: 'OWNER' | 'MEMBER'): SocialGroupSummaryDto {
    return {
      groupId: group.id,
      name: group.name,
      memberCount: this.repository.countMembers(group.id),
      role,
      createdAt: group.createdAt,
    };
  }

  private invitationDtoOf(
    invitation: StoredGroupInvitation,
    group: StoredSocialGroup,
    inviterUid: string,
  ): SocialGroupInvitationDto {
    const inviter = this.socialRepository.find(inviterUid);
    return {
      invitationId: invitation.id,
      groupId: group.id,
      groupName: group.name,
      memberCount: this.repository.countMembers(group.id),
      inviterSocialId: inviter?.profile.socialId ?? null,
      inviterDisplayName: inviter?.profile.displayName ?? null,
      status:
        this.clock.now() >= invitation.expiresAt && invitation.status === 'PENDING'
          ? 'EXPIRED'
          : invitation.status,
      createdAt: invitation.createdAt,
      expiresAt: invitation.expiresAt,
    };
  }

  /**
   * As linhas do feed do grupo, pelo **mesmo** projetor do Feed de amigos (§50).
   *
   * ## O que a T17.12 mudou aqui
   *
   * `canInteract` era `direct` — o viewer precisava ser o autor ou amigo dele. Agora é `true` para
   * toda linha (T17.12 §76): dentro do Squad, **participação ativa é a autorização**. Não é uma
   * flexibilização: a consulta que produziu estas linhas já exigiu Squad ativo, compartilhamento
   * explícito, participação do viewer e do autor, e ausência de bloqueio — as mesmas condições que
   * o resolvedor de contexto revalida na hora de reagir ou comentar.
   *
   * E as contagens passam a ser as de `GROUP(groupId)` (§37/§63): 🔥3 aqui significa três pessoas
   * **deste Squad**, e a reação que alguém deixou no Feed de amigos sobre o mesmo check-in não entra
   * nesse número (§38/§64).
   */
  private projectFeedRows(
    viewerUid: string,
    groupId: string,
    rows: readonly GroupFeedRow[],
  ): SocialGroupFeedItemDto[] {
    if (rows.length === 0) {
      return [];
    }

    // Quando a linha vem da própria criação do compartilhamento, a identidade do autor é a do
    // requisitante e não passou pelo `JOIN`. Resolvê-la aqui evita uma segunda consulta.
    const selfProfile = this.socialRepository.find(viewerUid)?.profile;
    const projectable: ProjectableCheckIn[] = rows.map((row) => ({
      checkInId: row.checkInId,
      authorUid: row.authorUid,
      authorSocialId:
        row.authorSocialId || (row.authorUid === viewerUid ? (selfProfile?.socialId ?? '') : ''),
      authorDisplayName:
        row.authorDisplayName ||
        (row.authorUid === viewerUid ? (selfProfile?.displayName ?? '') : ''),
      caption: row.caption,
      publishedAt: row.publishedAt,
      canInteract: true,
    }));

    const projected = this.projector.project(viewerUid, projectable, { type: 'GROUP', groupId });
    return projected.map((checkIn, index) => ({
      checkIn,
      sharedToGroupAt: rows[index].sharedToGroupAt,
    }));
  }
}
