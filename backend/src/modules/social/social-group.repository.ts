import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type { SocialGroupRole, SocialGroupStatus } from './social-group.contract';
import { VIEWER_BLOCKED_CTE } from './workout-checkin.access-policy';

/** Um Squad como ele mora no banco (§6). */
export interface StoredSocialGroup {
  readonly id: string;
  readonly ownerUid: string;
  readonly name: string;
  readonly status: SocialGroupStatus;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly deletedAt: number | null;
  readonly clientRequestId: string | null;
}

/** Uma participação (§12). */
export interface StoredGroupMembership {
  readonly id: string;
  readonly groupId: string;
  readonly memberUid: string;
  readonly role: SocialGroupRole;
  readonly joinedAt: number;
}

/** Um convite, como ele mora no banco (§19). `EXPIRED` é derivado na leitura (§21). */
export interface StoredGroupInvitation {
  readonly id: string;
  readonly groupId: string;
  readonly senderUid: string;
  readonly recipientUid: string;
  readonly status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED';
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly respondedAt: number | null;
  readonly clientRequestId: string | null;
}

/** Um Squad já projetado para a lista do viewer, com a contagem resolvida no `JOIN` (§121). */
export interface GroupSummaryRow {
  readonly groupId: string;
  readonly name: string;
  readonly memberCount: number;
  readonly role: SocialGroupRole;
  readonly createdAt: number;
}

/** Uma linha crua da lista de membros. A opacidade por bloqueio é decidida no serviço (§34). */
export interface GroupMemberRow {
  readonly membershipId: string;
  readonly memberUid: string;
  readonly role: SocialGroupRole;
  readonly joinedAt: number;
  readonly socialId: string;
  readonly displayName: string;
  /** `1` quando existe bloqueio entre o viewer e este membro, em qualquer direção (§34). */
  readonly blocked: number;
}

/** Uma linha da lista de convites recebidos, já com a prévia mínima de §139. */
export interface GroupInvitationRow {
  readonly invitationId: string;
  readonly groupId: string;
  readonly groupName: string;
  readonly memberCount: number;
  readonly inviterSocialId: string;
  readonly inviterDisplayName: string;
  readonly inviterUid: string;
  readonly status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED';
  readonly createdAt: number;
  readonly expiresAt: number;
  /** `1` quando existe bloqueio entre o viewer e quem convidou (§139). */
  readonly blocked: number;
}

/**
 * Uma linha do feed do Squad, já com a identidade pública do autor resolvida (§121).
 *
 * Até a T17.11 esta linha carregava um `direct`, que dizia se o viewer também alcançava o
 * check-in por amizade — era ele que decidia `canInteract`, porque interagir exigia relação direta.
 * A T17.12 removeu a coluna junto com a regra (§76): dentro do Squad, **participação ativa é a
 * autorização**, e todo item deste feed é interagível na audiência `GROUP` daquele Squad. Manter um
 * campo que ninguém mais lê seria deixar no código a pergunta que a fase inteira respondeu.
 */
export interface GroupFeedRow {
  readonly checkInId: string;
  readonly authorUid: string;
  readonly authorSocialId: string;
  readonly authorDisplayName: string;
  readonly caption: string | null;
  readonly publishedAt: number;
  readonly sharedToGroupAt: number;
}

interface GroupRow {
  readonly id: string;
  readonly owner_uid: string;
  readonly name: string;
  readonly status: SocialGroupStatus;
  readonly created_at: number;
  readonly updated_at: number;
  readonly deleted_at: number | null;
  readonly client_request_id: string | null;
}

interface MembershipRow {
  readonly id: string;
  readonly group_id: string;
  readonly member_uid: string;
  readonly role: SocialGroupRole;
  readonly joined_at: number;
}

interface InvitationRow {
  readonly id: string;
  readonly group_id: string;
  readonly sender_uid: string;
  readonly recipient_uid: string;
  readonly status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED';
  readonly created_at: number;
  readonly expires_at: number;
  readonly responded_at: number | null;
  readonly client_request_id: string | null;
}

const GROUP_COLUMNS = `id, owner_uid, name, status, created_at, updated_at, deleted_at,
                       client_request_id`;

const MEMBERSHIP_COLUMNS = `id, group_id, member_uid, role, joined_at`;

const INVITATION_COLUMNS = `id, group_id, sender_uid, recipient_uid, status, created_at,
                            expires_at, responded_at, client_request_id`;

function toGroup(row: GroupRow): StoredSocialGroup {
  return {
    id: row.id,
    ownerUid: row.owner_uid,
    name: row.name,
    status: row.status,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    deletedAt: row.deleted_at,
    clientRequestId: row.client_request_id,
  };
}

function toMembership(row: MembershipRow): StoredGroupMembership {
  return {
    id: row.id,
    groupId: row.group_id,
    memberUid: row.member_uid,
    role: row.role,
    joinedAt: row.joined_at,
  };
}

function toInvitation(row: InvitationRow): StoredGroupInvitation {
  return {
    id: row.id,
    groupId: row.group_id,
    senderUid: row.sender_uid,
    recipientUid: row.recipient_uid,
    status: row.status,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    respondedAt: row.responded_at,
    clientRequestId: row.client_request_id,
  };
}

/**
 * O acesso ao banco dos Squads (T17.11 §120/§121).
 *
 * ## O que este repositório garante, e o que ele deliberadamente não decide
 *
 * Ele **não** decide autorização de produto — quem pode convidar, quem pode remover, se o Squad
 * está cheio. Isso é do serviço. O que ele garante é que toda leitura que atravessa a fronteira de
 * uma pessoa para outra já venha filtrada por bloqueio e por participação **em SQL** — a mesma
 * escolha da T17.9 (§78 da T17.8): carregar tudo e filtrar em JavaScript é o desenho que, no dia
 * de um bug no filtro, **já leu** o dado de quem não devia.
 *
 * ## Nenhuma consulta aqui é N+1 (§121)
 *
 * A lista de Squads traz `memberCount` por subconsulta agregada, a lista de membros e a de
 * convites resolvem perfil e bloqueio no `JOIN`, e o feed resolve autor, bloqueio e relação direta
 * em uma consulta só. Nada aqui cresce com o tamanho da página em número de idas ao banco.
 */
@Injectable()
export class SocialGroupRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /**
   * Uma transação do agregado.
   *
   * Exposta aqui porque o dono da conexão é o repositório, e o serviço não conhece
   * `SqliteService` — a mesma separação que o resto do módulo mantém. Os casos que a exigem são
   * criar (Squad + participação do dono, §17), aceitar (convite + participação) e transferir a
   * posse (§40/§150).
   */
  transaction<T>(work: () => T): T {
    return this.sqlite.connection.transaction(work)();
  }

  // ------------------------------------------------------------------ Squad

  createGroup(group: StoredSocialGroup): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_groups (
           id, owner_uid, name, status, created_at, updated_at, deleted_at, client_request_id
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        group.id,
        group.ownerUid,
        group.name,
        group.status,
        group.createdAt,
        group.updatedAt,
        group.deletedAt,
        group.clientRequestId,
      );
  }

  findGroup(groupId: string): StoredSocialGroup | null {
    const row = this.sqlite.connection
      .prepare(`SELECT ${GROUP_COLUMNS} FROM social_groups WHERE id = ? LIMIT 1`)
      .get(groupId) as GroupRow | undefined;
    return row ? toGroup(row) : null;
  }

  /** §146 — a mesma intenção do usuário produz o mesmo Squad. */
  findGroupByClientRequest(ownerUid: string, clientRequestId: string): StoredSocialGroup | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT ${GROUP_COLUMNS} FROM social_groups
          WHERE owner_uid = ? AND client_request_id = ? LIMIT 1`,
      )
      .get(ownerUid, clientRequestId) as GroupRow | undefined;
    return row ? toGroup(row) : null;
  }

  /** §18 — quantos Squads **ativos** esta conta criou. */
  countOwnedActiveGroups(ownerUid: string): number {
    const row = this.sqlite.connection
      .prepare(`SELECT COUNT(*) AS n FROM social_groups WHERE owner_uid = ? AND status = 'ACTIVE'`)
      .get(ownerUid) as { n: number };
    return row.n;
  }

  /** §18 — em quantos Squads **ativos** esta conta participa. */
  countActiveMemberships(memberUid: string): number {
    const row = this.sqlite.connection
      .prepare(
        `SELECT COUNT(*) AS n
           FROM social_group_memberships m
           JOIN social_groups g ON g.id = m.group_id
          WHERE m.member_uid = ? AND g.status = 'ACTIVE'`,
      )
      .get(memberUid) as { n: number };
    return row.n;
  }

  /**
   * Exclusão do Squad (§46/§48).
   *
   * Soft delete e escrita **condicional** em `status = 'ACTIVE'`: dois toques rápidos produzem uma
   * transição e um no-op, e a decisão sobrevive ao processo morrer. O `changes` é a resposta; o
   * serviço não precisa reler para saber o que aconteceu.
   *
   * O que este `UPDATE` **não** toca: nenhuma linha de `social_workout_checkins`, nenhuma sessão,
   * nenhum template (§47/§102). Quem apaga as arestas do grupo é [purgeGroupContext], e apagar uma
   * aresta nunca alcança a publicação do outro lado dela.
   */
  markGroupDeleted(groupId: string, ownerUid: string, now: number): boolean {
    const result = this.sqlite.connection
      .prepare(
        `UPDATE social_groups
            SET status = 'DELETED', deleted_at = ?, updated_at = ?
          WHERE id = ? AND owner_uid = ? AND status = 'ACTIVE'`,
      )
      .run(now, now, groupId, ownerUid);
    return result.changes > 0;
  }

  /**
   * O contexto de grupo, e **só** ele (§48/§49).
   *
   * ```text
   * apaga:  memberships · convites pendentes · arestas de compartilhamento
   * nunca:  WorkoutCheckIn · WorkoutSession · WorkoutTemplate · WorkoutShare · mídia
   * ```
   *
   * O check-in de A que estava neste Squad continua existindo e continua no Feed de amigos de A
   * conforme a política original dele (§49). Só o vínculo desaparece — e é por isso que a tabela de
   * compartilhamento é uma aresta, e não um post.
   *
   * Os convites são **cancelados**, e não apagados: a linha continua sendo a prova de que aquele
   * identificador existiu, e um `POST /accept` posterior encontra `CANCELLED` em vez de "não
   * existe", o que é a mesma resposta na rota (§29) por um caminho mais honesto no banco.
   */
  purgeGroupContext(groupId: string, now: number): void {
    const db = this.sqlite.connection;
    db.prepare(`DELETE FROM social_group_checkin_shares WHERE group_id = ?`).run(groupId);
    db.prepare(
      `UPDATE social_group_invitations
          SET status = 'CANCELLED', responded_at = ?
        WHERE group_id = ? AND status = 'PENDING'`,
    ).run(now, groupId);
    db.prepare(`DELETE FROM social_group_memberships WHERE group_id = ?`).run(groupId);
  }

  /**
   * Os Squads de que o viewer participa (§131/§132).
   *
   * `memberCount` sai de uma subconsulta agregada, e não de uma segunda ida ao banco por item
   * (§121). Ele conta **todo mundo**, inclusive quem o viewer bloqueou (§35): contagem não é
   * identidade individual, e um número que mudasse por bloqueio contaria a existência do bloqueio
   * para quem comparasse duas telas.
   */
  listGroupsForMember(memberUid: string, limit: number): GroupSummaryRow[] {
    return this.sqlite.connection
      .prepare(
        `SELECT g.id         AS groupId,
                g.name       AS name,
                m.role       AS role,
                g.created_at AS createdAt,
                (SELECT COUNT(*) FROM social_group_memberships mc WHERE mc.group_id = g.id)
                             AS memberCount
           FROM social_group_memberships m
           JOIN social_groups g ON g.id = m.group_id
          WHERE m.member_uid = ? AND g.status = 'ACTIVE'
          ORDER BY g.created_at DESC, g.id DESC
          LIMIT ?`,
      )
      .all(memberUid, limit) as GroupSummaryRow[];
  }

  // ------------------------------------------------------------------ participação

  createMembership(membership: StoredGroupMembership): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_group_memberships (id, group_id, member_uid, role, joined_at)
         VALUES (?, ?, ?, ?, ?)`,
      )
      .run(
        membership.id,
        membership.groupId,
        membership.memberUid,
        membership.role,
        membership.joinedAt,
      );
  }

  /**
   * A participação do viewer neste Squad — a autorização de **toda** superfície do grupo (§59).
   *
   * `null` para "não existe", "foi excluído" e "você não é membro": os três levam ao mesmo `404`
   * (§60), e distinguir transformaria a rota num oráculo de existência.
   */
  findActiveMembership(groupId: string, memberUid: string): StoredGroupMembership | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT m.id, m.group_id, m.member_uid, m.role, m.joined_at
           FROM social_group_memberships m
           JOIN social_groups g ON g.id = m.group_id
          WHERE m.group_id = ? AND m.member_uid = ? AND g.status = 'ACTIVE'
          LIMIT 1`,
      )
      .get(groupId, memberUid) as MembershipRow | undefined;
    return row ? toMembership(row) : null;
  }

  findMembershipById(groupId: string, membershipId: string): StoredGroupMembership | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT ${MEMBERSHIP_COLUMNS} FROM social_group_memberships
          WHERE group_id = ? AND id = ? LIMIT 1`,
      )
      .get(groupId, membershipId) as MembershipRow | undefined;
    return row ? toMembership(row) : null;
  }

  countMembers(groupId: string): number {
    const row = this.sqlite.connection
      .prepare(`SELECT COUNT(*) AS n FROM social_group_memberships WHERE group_id = ?`)
      .get(groupId) as { n: number };
    return row.n;
  }

  /**
   * A lista de membros, com o bloqueio já resolvido pelo banco (§34/§121).
   *
   * O `EXISTS` de bloqueio corre **por linha na mesma consulta**, e não como um segundo `SELECT`
   * por membro. Com o teto de 20 participantes (§122) isso é barato por construção.
   *
   * A ordenação põe o dono primeiro, depois por entrada — é a ordem que a tela desenha, e ela é
   * determinística com desempate por `membershipId` para que duas leituras nunca troquem itens de
   * lugar.
   */
  listMembers(groupId: string, viewerUid: string): GroupMemberRow[] {
    return this.sqlite.connection
      .prepare(
        `SELECT m.id           AS membershipId,
                m.member_uid   AS memberUid,
                m.role         AS role,
                m.joined_at    AS joinedAt,
                p.social_id    AS socialId,
                p.display_name AS displayName,
                CASE WHEN EXISTS (
                  SELECT 1 FROM social_blocks b
                   WHERE (b.blocker_uid = :viewer AND b.blocked_uid = m.member_uid)
                      OR (b.blocker_uid = m.member_uid AND b.blocked_uid = :viewer)
                ) THEN 1 ELSE 0 END AS blocked
           FROM social_group_memberships m
           JOIN social_profiles p ON p.owner_uid = m.member_uid
          WHERE m.group_id = :groupId
          ORDER BY CASE WHEN m.role = 'OWNER' THEN 0 ELSE 1 END, m.joined_at ASC, m.id ASC`,
      )
      .all({ groupId, viewer: viewerUid }) as GroupMemberRow[];
  }

  /** Sai do Squad (§38). Idempotente: o `changes` diz se havia o que remover. */
  deleteMembership(groupId: string, memberUid: string): boolean {
    const result = this.sqlite.connection
      .prepare(`DELETE FROM social_group_memberships WHERE group_id = ? AND member_uid = ?`)
      .run(groupId, memberUid);
    return result.changes > 0;
  }

  /**
   * Os compartilhamentos de uma pessoa **naquele** Squad (§62/§63/§64).
   *
   * Apagados quando ela sai ou é removida. É isto que garante §64: um `rejoin` futuro não
   * ressuscita conteúdo antigo, porque não há o que ressuscitar. Nenhum check-in é tocado — só a
   * aresta.
   */
  deleteSharesByAuthorInGroup(groupId: string, authorUid: string): number {
    const result = this.sqlite.connection
      .prepare(`DELETE FROM social_group_checkin_shares WHERE group_id = ? AND author_uid = ?`)
      .run(groupId, authorUid);
    return result.changes;
  }

  /**
   * Quais check-ins desta pessoa estão neste Squad (T17.12 §71/§143).
   *
   * Lido **antes** de [deleteSharesByAuthorInGroup], porque depois dela não há como saber quais
   * eram: some a aresta, e com ela o objeto da conversa que acontecia em cima dela. Quem sai leva
   * junto não só o que escreveu (§43) como também o que os outros escreveram nas publicações
   * **dele** naquele Squad — é o mesmo evento de "o compartilhamento acabou" que `unshare` já
   * tratava (§70), e ele não pode ter dois efeitos diferentes conforme o caminho.
   */
  listSharedCheckInIdsByAuthorInGroup(groupId: string, authorUid: string): string[] {
    const rows = this.sqlite.connection
      .prepare(
        `SELECT checkin_id AS checkInId
           FROM social_group_checkin_shares
          WHERE group_id = ? AND author_uid = ?`,
      )
      .all(groupId, authorUid) as Array<{ checkInId: string }>;
    return rows.map((row) => row.checkInId);
  }

  /** A transferência de posse (§40/§150). Duas escritas, sempre dentro da mesma transação. */
  updateMembershipRole(membershipId: string, role: SocialGroupRole): void {
    this.sqlite.connection
      .prepare(`UPDATE social_group_memberships SET role = ? WHERE id = ?`)
      .run(role, membershipId);
  }

  updateGroupOwner(groupId: string, ownerUid: string, now: number): void {
    this.sqlite.connection
      .prepare(`UPDATE social_groups SET owner_uid = ?, updated_at = ? WHERE id = ?`)
      .run(ownerUid, now, groupId);
  }

  // ------------------------------------------------------------------ convites

  createInvitation(invitation: StoredGroupInvitation): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_group_invitations (
           id, group_id, sender_uid, recipient_uid, status, created_at, expires_at,
           responded_at, client_request_id
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        invitation.id,
        invitation.groupId,
        invitation.senderUid,
        invitation.recipientUid,
        invitation.status,
        invitation.createdAt,
        invitation.expiresAt,
        invitation.respondedAt,
        invitation.clientRequestId,
      );
  }

  findInvitation(invitationId: string): StoredGroupInvitation | null {
    const row = this.sqlite.connection
      .prepare(`SELECT ${INVITATION_COLUMNS} FROM social_group_invitations WHERE id = ? LIMIT 1`)
      .get(invitationId) as InvitationRow | undefined;
    return row ? toInvitation(row) : null;
  }

  findInvitationByClientRequest(
    groupId: string,
    clientRequestId: string,
  ): StoredGroupInvitation | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT ${INVITATION_COLUMNS} FROM social_group_invitations
          WHERE group_id = ? AND client_request_id = ? LIMIT 1`,
      )
      .get(groupId, clientRequestId) as InvitationRow | undefined;
    return row ? toInvitation(row) : null;
  }

  findPendingInvitation(groupId: string, recipientUid: string): StoredGroupInvitation | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT ${INVITATION_COLUMNS} FROM social_group_invitations
          WHERE group_id = ? AND recipient_uid = ? AND status = 'PENDING' LIMIT 1`,
      )
      .get(groupId, recipientUid) as InvitationRow | undefined;
    return row ? toInvitation(row) : null;
  }

  countPendingInvitations(groupId: string): number {
    const row = this.sqlite.connection
      .prepare(
        `SELECT COUNT(*) AS n FROM social_group_invitations
          WHERE group_id = ? AND status = 'PENDING'`,
      )
      .get(groupId) as { n: number };
    return row.n;
  }

  /**
   * Responde a um convite (§29).
   *
   * Escrita **condicional** em `status = 'PENDING'`: dois aceites simultâneos produzem uma
   * transição e um no-op, e o segundo lê `false` em vez de criar uma segunda participação.
   */
  resolveInvitation(
    invitationId: string,
    status: 'ACCEPTED' | 'DECLINED' | 'CANCELLED',
    now: number,
  ): boolean {
    const result = this.sqlite.connection
      .prepare(
        `UPDATE social_group_invitations
            SET status = ?, responded_at = ?
          WHERE id = ? AND status = 'PENDING'`,
      )
      .run(status, now, invitationId);
    return result.changes > 0;
  }

  /**
   * Cancela os convites pendentes entre um par, nas duas direções (§105).
   *
   * Chamado pelo Bloqueio. Cancelar em vez de apagar mantém a linha como prova de que o
   * identificador existiu — e um push ainda na fila para aquele convite passa a ser irrelevante na
   * revalidação do dispatcher (§106).
   */
  cancelPendingInvitationsBetween(uidA: string, uidB: string, now: number): number {
    const result = this.sqlite.connection
      .prepare(
        `UPDATE social_group_invitations
            SET status = 'CANCELLED', responded_at = ?
          WHERE status = 'PENDING'
            AND ((sender_uid = ? AND recipient_uid = ?)
              OR (sender_uid = ? AND recipient_uid = ?))`,
      )
      .run(now, uidA, uidB, uidB, uidA);
    return result.changes;
  }

  /** Cancela todo convite pendente **de** ou **para** esta conta (§96). */
  cancelAllPendingInvitationsFor(uid: string, now: number): number {
    const result = this.sqlite.connection
      .prepare(
        `UPDATE social_group_invitations
            SET status = 'CANCELLED', responded_at = ?
          WHERE status = 'PENDING' AND (sender_uid = ? OR recipient_uid = ?)`,
      )
      .run(now, uid, uid);
    return result.changes;
  }

  /**
   * Os convites recebidos, com a prévia mínima de §138/§139.
   *
   * Nome do Squad, contagem de membros e quem convidou — e nada mais. A **lista de membros** não
   * vem antes do aceite: quem ainda não entrou não é audiência do grupo.
   *
   * O bloqueio é resolvido no `JOIN` e devolvido como flag; o serviço decide o que fazer com ela.
   * Na prática o convite já teria sido cancelado pelo bloqueio (§105) — a flag cobre a corrida
   * entre as duas escritas, para que nenhuma identidade escape por ela.
   */
  listInvitationsForRecipient(recipientUid: string, limit: number): GroupInvitationRow[] {
    return this.sqlite.connection
      .prepare(
        `SELECT i.id           AS invitationId,
                i.group_id     AS groupId,
                g.name         AS groupName,
                i.sender_uid   AS inviterUid,
                sp.social_id   AS inviterSocialId,
                sp.display_name AS inviterDisplayName,
                i.status       AS status,
                i.created_at   AS createdAt,
                i.expires_at   AS expiresAt,
                (SELECT COUNT(*) FROM social_group_memberships mc WHERE mc.group_id = g.id)
                               AS memberCount,
                CASE WHEN EXISTS (
                  SELECT 1 FROM social_blocks b
                   WHERE (b.blocker_uid = :viewer AND b.blocked_uid = i.sender_uid)
                      OR (b.blocker_uid = i.sender_uid AND b.blocked_uid = :viewer)
                ) THEN 1 ELSE 0 END AS blocked
           FROM social_group_invitations i
           JOIN social_groups g    ON g.id = i.group_id AND g.status = 'ACTIVE'
           JOIN social_profiles sp ON sp.owner_uid = i.sender_uid AND sp.status = 'ACTIVE'
          WHERE i.recipient_uid = :viewer
            AND i.status = 'PENDING'
          ORDER BY i.created_at DESC, i.id DESC
          LIMIT :limit`,
      )
      .all({ viewer: recipientUid, limit }) as GroupInvitationRow[];
  }

  // ------------------------------------------------------------------ compartilhamento

  createShare(input: {
    id: string;
    groupId: string;
    checkInId: string;
    authorUid: string;
    createdAt: number;
  }): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_group_checkin_shares (id, group_id, checkin_id, author_uid, created_at)
         VALUES (?, ?, ?, ?, ?)`,
      )
      .run(input.id, input.groupId, input.checkInId, input.authorUid, input.createdAt);
  }

  findShare(
    groupId: string,
    checkInId: string,
  ): { id: string; authorUid: string; createdAt: number } | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT id, author_uid AS authorUid, created_at AS createdAt
           FROM social_group_checkin_shares
          WHERE group_id = ? AND checkin_id = ? LIMIT 1`,
      )
      .get(groupId, checkInId) as { id: string; authorUid: string; createdAt: number } | undefined;
    return row ?? null;
  }

  /** §130 — só o autor remove o próprio compartilhamento. Idempotente. */
  deleteShare(groupId: string, checkInId: string, authorUid: string): boolean {
    const result = this.sqlite.connection
      .prepare(
        `DELETE FROM social_group_checkin_shares
          WHERE group_id = ? AND checkin_id = ? AND author_uid = ?`,
      )
      .run(groupId, checkInId, authorUid);
    return result.changes > 0;
  }

  /** §68 — em quantos Squads este check-in já está. */
  countSharesForCheckIn(checkInId: string): number {
    const row = this.sqlite.connection
      .prepare(`SELECT COUNT(*) AS n FROM social_group_checkin_shares WHERE checkin_id = ?`)
      .get(checkInId) as { n: number };
    return row.n;
  }

  /** §141 — em quais Squads **ativos** este check-in já está. Alimenta o seletor da tela. */
  listGroupIdsForCheckIn(checkInId: string): string[] {
    const rows = this.sqlite.connection
      .prepare(
        `SELECT s.group_id AS groupId
           FROM social_group_checkin_shares s
           JOIN social_groups g ON g.id = s.group_id AND g.status = 'ACTIVE'
          WHERE s.checkin_id = ?`,
      )
      .all(checkInId) as Array<{ groupId: string }>;
    return rows.map((row) => row.groupId);
  }

  /**
   * O feed de um Squad (§59–§66/§84/§86).
   *
   * ## A autorização está **na consulta**, e não depois dela
   *
   * ```text
   * viewer é membro ativo do Squad          (EXISTS, sem o qual a consulta devolve zero linhas)
   * ∧ o check-in foi explicitamente compartilhado aqui
   * ∧ o check-in está PUBLISHED             (§61)
   * ∧ o autor ainda é membro ativo          (§61/§62/§63)
   * ∧ o autor tem perfil ACTIVE             (§61)
   * ∧ ¬bloqueio entre viewer e autor        (§33/§65)
   * ```
   *
   * O `EXISTS` de participação do viewer está dentro da consulta **além** da checagem que o
   * serviço faz antes: um `404` que dependesse só do serviço viraria vazamento no dia em que
   * alguém escrevesse um segundo caminho até aqui. Com ele, a consulta não tem como devolver
   * linha para quem não é membro — nem por engano.
   *
   * ## Bounded por construção (§86/§87)
   *
   * Janela de 30 dias sobre a data do **compartilhamento** no `WHERE`, teto de itens no `LIMIT`, e
   * nenhum cursor histórico. `ORDER BY s.created_at DESC, s.id DESC` — desempate determinístico,
   * para que dois compartilhamentos no mesmo milissegundo tenham sempre a mesma ordem.
   */
  findGroupFeed(
    viewerUid: string,
    groupId: string,
    sharedSinceMs: number,
    limit: number,
  ): GroupFeedRow[] {
    return this.sqlite.connection
      .prepare(
        `WITH ${VIEWER_BLOCKED_CTE}
         SELECT c.id            AS checkInId,
                c.author_uid    AS authorUid,
                p.social_id     AS authorSocialId,
                p.display_name  AS authorDisplayName,
                c.caption       AS caption,
                c.created_at    AS publishedAt,
                s.created_at    AS sharedToGroupAt
           FROM social_group_checkin_shares s
           JOIN social_groups g             ON g.id = s.group_id AND g.status = 'ACTIVE'
           JOIN social_workout_checkins c   ON c.id = s.checkin_id
           JOIN social_profiles p           ON p.owner_uid = c.author_uid
           JOIN social_group_memberships am ON am.group_id = s.group_id
                                           AND am.member_uid = c.author_uid
          WHERE s.group_id = :groupId
            AND s.created_at >= :sharedSince
            AND c.status = 'PUBLISHED'
            AND p.status = 'ACTIVE'
            AND c.author_uid NOT IN (SELECT uid FROM viewer_blocked)
            AND EXISTS (SELECT 1 FROM social_group_memberships vm
                         WHERE vm.group_id = :groupId AND vm.member_uid = :viewer)
            AND EXISTS (SELECT 1 FROM social_profiles vp
                         WHERE vp.owner_uid = :viewer AND vp.status = 'ACTIVE')
          ORDER BY s.created_at DESC, s.id DESC
          LIMIT :limit`,
      )
      .all({
        viewer: viewerUid,
        groupId,
        sharedSince: sharedSinceMs,
        limit,
      }) as GroupFeedRow[];
  }

  // ------------------------------------------------------------------ ciclo de vida da conta

  /**
   * Os Squads em que esta conta é dona e que ainda têm **outras** pessoas (§98/§99).
   *
   * A desativação do Social usa isto para decidir entre "posso resolver sozinho" e "preciso que a
   * pessoa escolha". Devolve `groupId` e nada mais: §99 é explícito em que a resposta carrega uma
   * **contagem**, e nunca dados dos membros.
   */
  listOwnedActiveGroupsWithOthers(ownerUid: string): string[] {
    const rows = this.sqlite.connection
      .prepare(
        `SELECT g.id AS groupId
           FROM social_groups g
          WHERE g.owner_uid = ? AND g.status = 'ACTIVE'
            AND (SELECT COUNT(*) FROM social_group_memberships m WHERE m.group_id = g.id) > 1`,
      )
      .all(ownerUid) as Array<{ groupId: string }>;
    return rows.map((row) => row.groupId);
  }

  /** Os Squads ativos criados por esta conta — usado na exclusão de conta (§100/§101). */
  listOwnedActiveGroups(ownerUid: string): string[] {
    const rows = this.sqlite.connection
      .prepare(`SELECT id AS groupId FROM social_groups WHERE owner_uid = ? AND status = 'ACTIVE'`)
      .all(ownerUid) as Array<{ groupId: string }>;
    return rows.map((row) => row.groupId);
  }

  /** As participações **como MEMBER** desta conta em Squads ativos (§97). */
  listActiveMemberGroups(memberUid: string): string[] {
    const rows = this.sqlite.connection
      .prepare(
        `SELECT m.group_id AS groupId
           FROM social_group_memberships m
           JOIN social_groups g ON g.id = m.group_id
          WHERE m.member_uid = ? AND m.role = 'MEMBER' AND g.status = 'ACTIVE'`,
      )
      .all(memberUid) as Array<{ groupId: string }>;
    return rows.map((row) => row.groupId);
  }
}
