import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { DbClient, PostgresService } from '../../database/postgres.service';
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

/**
 * O alcance de uma varredura de expiração de convites — ver `expirePendingInvitations`.
 *
 * Sempre explícito: quem varre precisa dizer **o que** está prestes a ler ou escrever, e não
 * carimbar a tabela inteira de passagem.
 */
export type InvitationExpiryScope =
  | { readonly recipientUid: string }
  | { readonly groupId: string }
  | { readonly invitationId: string };

/**
 * Um convite, como ele mora no banco (§19).
 */
export interface StoredGroupInvitation {
  readonly id: string;
  readonly groupId: string;
  readonly senderUid: string;
  readonly recipientUid: string;
  readonly status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED' | 'EXPIRED';
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
  readonly status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED' | 'EXPIRED';
  readonly createdAt: number;
  readonly expiresAt: number;
  /** `1` quando existe bloqueio entre o viewer e quem convidou (§139). */
  readonly blocked: number;
}

/**
 * Uma linha do feed do Squad, já com a identidade pública do autor resolvida (§121).
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
  readonly created_at: string | number;
  readonly updated_at: string | number;
  readonly deleted_at: string | number | null;
  readonly client_request_id: string | null;
}

interface MembershipRow {
  readonly id: string;
  readonly group_id: string;
  readonly member_uid: string;
  readonly role: SocialGroupRole;
  readonly joined_at: string | number;
}

interface InvitationRow {
  readonly id: string;
  readonly group_id: string;
  readonly sender_uid: string;
  readonly recipient_uid: string;
  readonly status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED' | 'EXPIRED';
  readonly created_at: string | number;
  readonly expires_at: string | number;
  readonly responded_at: string | number | null;
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
    createdAt: Number(row.created_at),
    updatedAt: Number(row.updated_at),
    deletedAt: row.deleted_at != null ? Number(row.deleted_at) : null,
    clientRequestId: row.client_request_id,
  };
}

function toMembership(row: MembershipRow): StoredGroupMembership {
  return {
    id: row.id,
    groupId: row.group_id,
    memberUid: row.member_uid,
    role: row.role,
    joinedAt: Number(row.joined_at),
  };
}

function toInvitation(row: InvitationRow): StoredGroupInvitation {
  return {
    id: row.id,
    groupId: row.group_id,
    senderUid: row.sender_uid,
    recipientUid: row.recipient_uid,
    status: row.status,
    createdAt: Number(row.created_at),
    expiresAt: Number(row.expires_at),
    respondedAt: row.responded_at != null ? Number(row.responded_at) : null,
    clientRequestId: row.client_request_id,
  };
}

/**
 * O acesso ao banco dos Squads (T17.11 §120/§121).
 */
@Injectable()
export class SocialGroupRepository {
  constructor(private readonly db: PostgresService) {}

  private getRunner(client?: PoolClient): DbClient {
    return (client ?? this.db) as DbClient;
  }

  /**
   * Uma transação do agregado.
   */
  async transaction<T>(work: (client: PoolClient) => Promise<T>): Promise<T> {
    return this.db.transaction(work);
  }

  // ------------------------------------------------------------------ Squad

  async createGroup(group: StoredSocialGroup, client?: PoolClient): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(
      `INSERT INTO social_groups (
         id, owner_uid, name, status, created_at, updated_at, deleted_at, client_request_id
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [
        group.id,
        group.ownerUid,
        group.name,
        group.status,
        group.createdAt,
        group.updatedAt,
        group.deletedAt,
        group.clientRequestId,
      ],
    );
  }

  async findGroup(groupId: string, client?: PoolClient): Promise<StoredSocialGroup | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<GroupRow>(
      `SELECT ${GROUP_COLUMNS} FROM social_groups WHERE id = $1 LIMIT 1`,
      [groupId],
    );
    const row = res.rows[0];
    return row ? toGroup(row) : null;
  }

  /** §146 — a mesma intenção do usuário produz o mesmo Squad. */
  async findGroupByClientRequest(
    ownerUid: string,
    clientRequestId: string,
    client?: PoolClient,
  ): Promise<StoredSocialGroup | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<GroupRow>(
      `SELECT ${GROUP_COLUMNS} FROM social_groups
        WHERE owner_uid = $1 AND client_request_id = $2 LIMIT 1`,
      [ownerUid, clientRequestId],
    );
    const row = res.rows[0];
    return row ? toGroup(row) : null;
  }

  /** §18 — quantos Squads **ativos** esta conta criou. */
  async countOwnedActiveGroups(ownerUid: string, client?: PoolClient): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ n: string | number }>(
      `SELECT COUNT(*) AS n FROM social_groups WHERE owner_uid = $1 AND status = 'ACTIVE'`,
      [ownerUid],
    );
    return Number(res.rows[0]?.n ?? 0);
  }

  /** §18 — em quantos Squads **ativos** esta conta participa. */
  async countActiveMemberships(memberUid: string, client?: PoolClient): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ n: string | number }>(
      `SELECT COUNT(*) AS n
         FROM social_group_memberships m
         JOIN social_groups g ON g.id = m.group_id
        WHERE m.member_uid = $1 AND g.status = 'ACTIVE'`,
      [memberUid],
    );
    return Number(res.rows[0]?.n ?? 0);
  }

  /**
   * Exclusão do Squad (§46/§48).
   */
  async markGroupDeleted(
    groupId: string,
    ownerUid: string,
    now: number,
    client?: PoolClient,
  ): Promise<boolean> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `UPDATE social_groups
          SET status = 'DELETED', deleted_at = $1, updated_at = $2
        WHERE id = $3 AND owner_uid = $4 AND status = 'ACTIVE'`,
      [now, now, groupId, ownerUid],
    );
    return (res.rowCount ?? 0) > 0;
  }

  /**
   * O contexto de grupo, e **só** ele (§48/§49).
   */
  async purgeGroupContext(groupId: string, now: number, client?: PoolClient): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(`DELETE FROM social_group_checkin_shares WHERE group_id = $1`, [groupId]);
    await runner.query(
      `UPDATE social_group_invitations
          SET status = 'CANCELLED', responded_at = $1
        WHERE group_id = $2 AND status = 'PENDING'`,
      [now, groupId],
    );
    await runner.query(`DELETE FROM social_group_memberships WHERE group_id = $1`, [groupId]);
  }

  /**
   * Os Squads de que o viewer participa (§131/§132).
   */
  async listGroupsForMember(
    memberUid: string,
    limit: number,
    client?: PoolClient,
  ): Promise<GroupSummaryRow[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{
      groupId: string;
      name: string;
      role: string;
      createdAt: string | number;
      memberCount: string | number;
    }>(
      `SELECT g.id         AS "groupId",
              g.name       AS name,
              m.role       AS role,
              g.created_at AS "createdAt",
              (SELECT COUNT(*) FROM social_group_memberships mc WHERE mc.group_id = g.id)
                           AS "memberCount"
         FROM social_group_memberships m
         JOIN social_groups g ON g.id = m.group_id
        WHERE m.member_uid = $1 AND g.status = 'ACTIVE'
        ORDER BY g.created_at DESC, g.id DESC
        LIMIT $2`,
      [memberUid, limit],
    );

    return res.rows.map((row) => ({
      groupId: row.groupId,
      name: row.name,
      role: row.role as SocialGroupRole,
      createdAt: Number(row.createdAt),
      memberCount: Number(row.memberCount),
    }));
  }

  // ------------------------------------------------------------------ participação

  async createMembership(membership: StoredGroupMembership, client?: PoolClient): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(
      `INSERT INTO social_group_memberships (id, group_id, member_uid, role, joined_at)
       VALUES ($1, $2, $3, $4, $5)`,
      [
        membership.id,
        membership.groupId,
        membership.memberUid,
        membership.role,
        membership.joinedAt,
      ],
    );
  }

  /**
   * A participação do viewer neste Squad — a autorização de **toda** superfície do grupo (§59).
   */
  async findActiveMembership(
    groupId: string,
    memberUid: string,
    client?: PoolClient,
  ): Promise<StoredGroupMembership | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<MembershipRow>(
      `SELECT m.id, m.group_id, m.member_uid, m.role, m.joined_at
         FROM social_group_memberships m
         JOIN social_groups g ON g.id = m.group_id
        WHERE m.group_id = $1 AND m.member_uid = $2 AND g.status = 'ACTIVE'
        LIMIT 1`,
      [groupId, memberUid],
    );
    const row = res.rows[0];
    return row ? toMembership(row) : null;
  }

  async findMembershipById(
    groupId: string,
    membershipId: string,
    client?: PoolClient,
  ): Promise<StoredGroupMembership | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<MembershipRow>(
      `SELECT ${MEMBERSHIP_COLUMNS} FROM social_group_memberships
        WHERE group_id = $1 AND id = $2 LIMIT 1`,
      [groupId, membershipId],
    );
    const row = res.rows[0];
    return row ? toMembership(row) : null;
  }

  async countMembers(groupId: string, client?: PoolClient): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ n: string | number }>(
      `SELECT COUNT(*) AS n FROM social_group_memberships WHERE group_id = $1`,
      [groupId],
    );
    return Number(res.rows[0]?.n ?? 0);
  }

  /**
   * A lista de membros, com o bloqueio já resolvido pelo banco (§34/§121).
   */
  async listMembers(
    groupId: string,
    viewerUid: string,
    client?: PoolClient,
  ): Promise<GroupMemberRow[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{
      membershipId: string;
      memberUid: string;
      role: string;
      joinedAt: string | number;
      socialId: string;
      displayName: string;
      blocked: string | number;
    }>(
      `SELECT m.id           AS "membershipId",
              m.member_uid   AS "memberUid",
              m.role         AS role,
              m.joined_at    AS "joinedAt",
              p.social_id    AS "socialId",
              p.display_name AS "displayName",
              CASE WHEN EXISTS (
                SELECT 1 FROM social_blocks b
                 WHERE (b.blocker_uid = $2 AND b.blocked_uid = m.member_uid)
                    OR (b.blocker_uid = m.member_uid AND b.blocked_uid = $2)
              ) THEN 1 ELSE 0 END AS blocked
         FROM social_group_memberships m
         JOIN social_profiles p ON p.owner_uid = m.member_uid
        WHERE m.group_id = $1
        ORDER BY CASE WHEN m.role = 'OWNER' THEN 0 ELSE 1 END, m.joined_at ASC, m.id ASC`,
      [groupId, viewerUid],
    );

    return res.rows.map((row) => ({
      membershipId: row.membershipId,
      memberUid: row.memberUid,
      role: row.role as SocialGroupRole,
      joinedAt: Number(row.joinedAt),
      socialId: row.socialId,
      displayName: row.displayName,
      blocked: Number(row.blocked),
    }));
  }

  /** Sai do Squad (§38). Idempotente: o `changes` diz se havia o que remover. */
  async deleteMembership(
    groupId: string,
    memberUid: string,
    client?: PoolClient,
  ): Promise<boolean> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `DELETE FROM social_group_memberships WHERE group_id = $1 AND member_uid = $2`,
      [groupId, memberUid],
    );
    return (res.rowCount ?? 0) > 0;
  }

  /**
   * Os compartilhamentos de uma pessoa **naquele** Squad (§62/§63/§64).
   */
  async deleteSharesByAuthorInGroup(
    groupId: string,
    authorUid: string,
    client?: PoolClient,
  ): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `DELETE FROM social_group_checkin_shares WHERE group_id = $1 AND author_uid = $2`,
      [groupId, authorUid],
    );
    return res.rowCount ?? 0;
  }

  /**
   * Quais check-ins desta pessoa estão neste Squad (T17.12 §71/§143).
   */
  async listSharedCheckInIdsByAuthorInGroup(
    groupId: string,
    authorUid: string,
    client?: PoolClient,
  ): Promise<string[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ checkInId: string }>(
      `SELECT checkin_id AS "checkInId"
         FROM social_group_checkin_shares
        WHERE group_id = $1 AND author_uid = $2`,
      [groupId, authorUid],
    );
    return res.rows.map((row) => row.checkInId);
  }

  /** A transferência de posse (§40/§150). Duas escritas, sempre dentro da mesma transação. */
  async updateMembershipRole(
    membershipId: string,
    role: SocialGroupRole,
    client?: PoolClient,
  ): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(`UPDATE social_group_memberships SET role = $1 WHERE id = $2`, [
      role,
      membershipId,
    ]);
  }

  async updateGroupOwner(
    groupId: string,
    ownerUid: string,
    now: number,
    client?: PoolClient,
  ): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(`UPDATE social_groups SET owner_uid = $1, updated_at = $2 WHERE id = $3`, [
      ownerUid,
      now,
      groupId,
    ]);
  }

  // ------------------------------------------------------------------ convites

  async createInvitation(invitation: StoredGroupInvitation, client?: PoolClient): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(
      `INSERT INTO social_group_invitations (
         id, group_id, sender_uid, recipient_uid, status, created_at, expires_at,
         responded_at, client_request_id
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
      [
        invitation.id,
        invitation.groupId,
        invitation.senderUid,
        invitation.recipientUid,
        invitation.status,
        invitation.createdAt,
        invitation.expiresAt,
        invitation.respondedAt,
        invitation.clientRequestId,
      ],
    );
  }

  async findInvitation(
    invitationId: string,
    client?: PoolClient,
  ): Promise<StoredGroupInvitation | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<InvitationRow>(
      `SELECT ${INVITATION_COLUMNS} FROM social_group_invitations WHERE id = $1 LIMIT 1`,
      [invitationId],
    );
    const row = res.rows[0];
    return row ? toInvitation(row) : null;
  }

  async findInvitationByClientRequest(
    groupId: string,
    clientRequestId: string,
    client?: PoolClient,
  ): Promise<StoredGroupInvitation | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<InvitationRow>(
      `SELECT ${INVITATION_COLUMNS} FROM social_group_invitations
        WHERE group_id = $1 AND client_request_id = $2 LIMIT 1`,
      [groupId, clientRequestId],
    );
    const row = res.rows[0];
    return row ? toInvitation(row) : null;
  }

  async findPendingInvitation(
    groupId: string,
    recipientUid: string,
    client?: PoolClient,
  ): Promise<StoredGroupInvitation | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<InvitationRow>(
      `SELECT ${INVITATION_COLUMNS} FROM social_group_invitations
        WHERE group_id = $1 AND recipient_uid = $2 AND status = 'PENDING' LIMIT 1`,
      [groupId, recipientUid],
    );
    const row = res.rows[0];
    return row ? toInvitation(row) : null;
  }

  /**
   * Marca como `EXPIRED` os convites pendentes vencidos **do escopo pedido** (T17.13.1 §27).
   *
   * A materialização continua obrigatória: `idx_social_group_invitations_pending` é um índice
   * único parcial sobre `status = 'PENDING'`, e ele não sabe que horas são — um convite vencido
   * que continue `PENDING` ocupa a vaga daquele par (Squad, destinatário) para sempre e ainda
   * conta na quota. É o beco sem saída que a T17.13.1 corrigiu.
   *
   * O que mudou é o **alcance**: a varredura era a tabela inteira a cada operação sensível a
   * `PENDING`, inclusive nas leituras. Agora cada chamada varre só o que ela mesma vai olhar —
   * os convites de um destinatário, de um Squad ou um convite específico. O estado materializado
   * é o mesmo; o custo deixa de crescer com o tamanho da tabela.
   */
  async expirePendingInvitations(
    now: number,
    scope: InvitationExpiryScope,
    client?: PoolClient,
  ): Promise<number> {
    const runner = this.getRunner(client);
    const [column, value] =
      'recipientUid' in scope
        ? ['recipient_uid', scope.recipientUid]
        : 'groupId' in scope
          ? ['group_id', scope.groupId]
          : ['id', scope.invitationId];
    const res = await runner.query(
      `UPDATE social_group_invitations SET status = 'EXPIRED'
        WHERE status = 'PENDING' AND expires_at <= $1 AND ${column} = $2`,
      [now, value],
    );
    return res.rowCount ?? 0;
  }

  async countPendingInvitations(groupId: string, client?: PoolClient): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ n: string | number }>(
      `SELECT COUNT(*) AS n FROM social_group_invitations
        WHERE group_id = $1 AND status = 'PENDING'`,
      [groupId],
    );
    return Number(res.rows[0]?.n ?? 0);
  }

  /**
   * Responde a um convite (§29).
   */
  async resolveInvitation(
    invitationId: string,
    status: 'ACCEPTED' | 'DECLINED' | 'CANCELLED',
    now: number,
    client?: PoolClient,
  ): Promise<boolean> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `UPDATE social_group_invitations
          SET status = $1, responded_at = $2
        WHERE id = $3 AND status = 'PENDING'`,
      [status, now, invitationId],
    );
    return (res.rowCount ?? 0) > 0;
  }

  /**
   * Cancela os convites pendentes entre um par, nas duas direções (§105).
   */
  async cancelPendingInvitationsBetween(
    uidA: string,
    uidB: string,
    now: number,
    client?: PoolClient,
  ): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `UPDATE social_group_invitations
          SET status = 'CANCELLED', responded_at = $1
        WHERE status = 'PENDING'
          AND ((sender_uid = $2 AND recipient_uid = $3)
            OR (sender_uid = $4 AND recipient_uid = $5))`,
      [now, uidA, uidB, uidB, uidA],
    );
    return res.rowCount ?? 0;
  }

  /** Cancela todo convite pendente **de** ou **para** esta conta (§96). */
  async cancelAllPendingInvitationsFor(
    uid: string,
    now: number,
    client?: PoolClient,
  ): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `UPDATE social_group_invitations
          SET status = 'CANCELLED', responded_at = $1
        WHERE status = 'PENDING' AND (sender_uid = $2 OR recipient_uid = $3)`,
      [now, uid, uid],
    );
    return res.rowCount ?? 0;
  }

  /**
   * Os convites recebidos, com a prévia mínima de §138/§139.
   */
  async listInvitationsForRecipient(
    recipientUid: string,
    limit: number,
    now: number,
    client?: PoolClient,
  ): Promise<GroupInvitationRow[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{
      invitationId: string;
      groupId: string;
      groupName: string;
      inviterUid: string;
      inviterSocialId: string;
      inviterDisplayName: string;
      status: string;
      createdAt: string | number;
      expiresAt: string | number;
      memberCount: string | number;
      blocked: string | number;
    }>(
      `SELECT i.id           AS "invitationId",
              i.group_id     AS "groupId",
              g.name         AS "groupName",
              i.sender_uid   AS "inviterUid",
              sp.social_id   AS "inviterSocialId",
              sp.display_name AS "inviterDisplayName",
              i.status       AS status,
              i.created_at   AS "createdAt",
              i.expires_at   AS "expiresAt",
              (SELECT COUNT(*) FROM social_group_memberships mc WHERE mc.group_id = g.id)
                             AS "memberCount",
              CASE WHEN EXISTS (
                SELECT 1 FROM social_blocks b
                 WHERE (b.blocker_uid = $1 AND b.blocked_uid = i.sender_uid)
                    OR (b.blocker_uid = i.sender_uid AND b.blocked_uid = $1)
              ) THEN 1 ELSE 0 END AS blocked
         FROM social_group_invitations i
         JOIN social_groups g    ON g.id = i.group_id AND g.status = 'ACTIVE'
         JOIN social_profiles sp ON sp.owner_uid = i.sender_uid AND sp.status = 'ACTIVE'
        WHERE i.recipient_uid = $1
          AND i.status = 'PENDING'
          AND i.expires_at > $3
        ORDER BY i.created_at DESC, i.id DESC
        LIMIT $2`,
      [recipientUid, limit, now],
    );

    return res.rows.map((row) => ({
      invitationId: row.invitationId,
      groupId: row.groupId,
      groupName: row.groupName,
      inviterUid: row.inviterUid,
      inviterSocialId: row.inviterSocialId,
      inviterDisplayName: row.inviterDisplayName,
      status: row.status as 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED' | 'EXPIRED',
      createdAt: Number(row.createdAt),
      expiresAt: Number(row.expiresAt),
      memberCount: Number(row.memberCount),
      blocked: Number(row.blocked),
    }));
  }

  // ------------------------------------------------------------------ compartilhamento

  async createShare(
    input: {
      id: string;
      groupId: string;
      checkInId: string;
      authorUid: string;
      createdAt: number;
    },
    client?: PoolClient,
  ): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(
      `INSERT INTO social_group_checkin_shares (id, group_id, checkin_id, author_uid, created_at)
       VALUES ($1, $2, $3, $4, $5)`,
      [input.id, input.groupId, input.checkInId, input.authorUid, input.createdAt],
    );
  }

  async findShare(
    groupId: string,
    checkInId: string,
    client?: PoolClient,
  ): Promise<{ id: string; authorUid: string; createdAt: number } | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ id: string; authorUid: string; createdAt: string | number }>(
      `SELECT id, author_uid AS "authorUid", created_at AS "createdAt"
         FROM social_group_checkin_shares
        WHERE group_id = $1 AND checkin_id = $2 LIMIT 1`,
      [groupId, checkInId],
    );
    const row = res.rows[0];
    return row ? { id: row.id, authorUid: row.authorUid, createdAt: Number(row.createdAt) } : null;
  }

  /** §130 — só o autor remove o próprio compartilhamento. Idempotente. */
  async deleteShare(
    groupId: string,
    checkInId: string,
    authorUid: string,
    client?: PoolClient,
  ): Promise<boolean> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `DELETE FROM social_group_checkin_shares
        WHERE group_id = $1 AND checkin_id = $2 AND author_uid = $3`,
      [groupId, checkInId, authorUid],
    );
    return (res.rowCount ?? 0) > 0;
  }

  /** §68 — em quantos Squads este check-in já está. */
  async countSharesForCheckIn(checkInId: string, client?: PoolClient): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ n: string | number }>(
      `SELECT COUNT(*) AS n FROM social_group_checkin_shares WHERE checkin_id = $1`,
      [checkInId],
    );
    return Number(res.rows[0]?.n ?? 0);
  }

  /** §141 — em quais Squads **ativos** este check-in já está. Alimenta o seletor da tela. */
  async listGroupIdsForCheckIn(checkInId: string, client?: PoolClient): Promise<string[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ groupId: string }>(
      `SELECT s.group_id AS "groupId"
         FROM social_group_checkin_shares s
         JOIN social_groups g ON g.id = s.group_id AND g.status = 'ACTIVE'
        WHERE s.checkin_id = $1`,
      [checkInId],
    );
    return res.rows.map((row) => row.groupId);
  }

  /**
   * O feed de um Squad (§59–§66/§84/§86).
   */
  async findGroupFeed(
    viewerUid: string,
    groupId: string,
    sharedSinceMs: number,
    limit: number,
    client?: PoolClient,
  ): Promise<GroupFeedRow[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{
      checkInId: string;
      authorUid: string;
      authorSocialId: string;
      authorDisplayName: string;
      caption: string | null;
      publishedAt: string | number;
      sharedToGroupAt: string | number;
    }>(
      `WITH ${VIEWER_BLOCKED_CTE}
       SELECT c.id            AS "checkInId",
              c.author_uid    AS "authorUid",
              p.social_id     AS "authorSocialId",
              p.display_name  AS "authorDisplayName",
              c.caption       AS caption,
              c.created_at    AS "publishedAt",
              s.created_at    AS "sharedToGroupAt"
         FROM social_group_checkin_shares s
         JOIN social_groups g             ON g.id = s.group_id AND g.status = 'ACTIVE'
         JOIN social_workout_checkins c   ON c.id = s.checkin_id
         JOIN social_profiles p           ON p.owner_uid = c.author_uid
         JOIN social_group_memberships am ON am.group_id = s.group_id
                                         AND am.member_uid = c.author_uid
        WHERE s.group_id = $2
          AND s.created_at >= $3
          AND c.status = 'PUBLISHED'
          AND p.status = 'ACTIVE'
          AND c.author_uid NOT IN (SELECT uid FROM viewer_blocked)
          AND EXISTS (SELECT 1 FROM social_group_memberships vm
                       WHERE vm.group_id = $2 AND vm.member_uid = $1)
          AND EXISTS (SELECT 1 FROM social_profiles vp
                       WHERE vp.owner_uid = $1 AND vp.status = 'ACTIVE')
        ORDER BY s.created_at DESC, s.id DESC
        LIMIT $4`,
      [viewerUid, groupId, sharedSinceMs, limit],
    );

    return res.rows.map((row) => ({
      checkInId: row.checkInId,
      authorUid: row.authorUid,
      authorSocialId: row.authorSocialId,
      authorDisplayName: row.authorDisplayName,
      caption: row.caption,
      publishedAt: Number(row.publishedAt),
      sharedToGroupAt: Number(row.sharedToGroupAt),
    }));
  }

  // ------------------------------------------------------------------ ciclo de vida da conta

  /**
   * Os Squads em que esta conta é dona e que ainda têm **outras** pessoas (§98/§99).
   */
  async listOwnedActiveGroupsWithOthers(ownerUid: string, client?: PoolClient): Promise<string[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ groupId: string }>(
      `SELECT g.id AS "groupId"
         FROM social_groups g
        WHERE g.owner_uid = $1 AND g.status = 'ACTIVE'
          AND (SELECT COUNT(*) FROM social_group_memberships m WHERE m.group_id = g.id) > 1`,
      [ownerUid],
    );
    return res.rows.map((row) => row.groupId);
  }

  /** Os Squads ativos criados por esta conta — usado na exclusão de conta (§100/§101). */
  async listOwnedActiveGroups(ownerUid: string, client?: PoolClient): Promise<string[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ groupId: string }>(
      `SELECT id AS "groupId" FROM social_groups WHERE owner_uid = $1 AND status = 'ACTIVE'`,
      [ownerUid],
    );
    return res.rows.map((row) => row.groupId);
  }

  /** As participações **como MEMBER** desta conta em Squads ativos (§97). */
  async listActiveMemberGroups(memberUid: string, client?: PoolClient): Promise<string[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ groupId: string }>(
      `SELECT m.group_id AS "groupId"
         FROM social_group_memberships m
         JOIN social_groups g ON g.id = m.group_id
        WHERE m.member_uid = $1 AND m.role = 'MEMBER' AND g.status = 'ACTIVE'`,
      [memberUid],
    );
    return res.rows.map((row) => row.groupId);
  }
}

/**
 * Serializa as decisões de **teto** de um Squad (T18.3.2).
 *
 * `pg_advisory_xact_lock`, a mesma convenção de `lockRelationshipPair` (par social) e do Account
 * Mutation Fence: dois argumentos hasheados, adquirido dentro da transação, liberado sozinho no
 * COMMIT/ROLLBACK.
 *
 * Existe porque "contar e inserir" só é um limite se as duas coisas forem uma. Sem o lock, vinte
 * aceites simultâneos liam `memberCount` antes de qualquer inserção e **todos** passavam: o Squad
 * terminava acima de `SOCIAL_GROUP_MAX_MEMBERS` sem que nenhuma requisição tivesse feito nada
 * errado. A contagem precisa ser relida **depois** do lock, dentro da mesma transação da escrita.
 */
export async function lockSocialGroup(client: PoolClient, groupId: string): Promise<void> {
  await client.query('SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))', [
    'social_group',
    groupId,
  ]);
}

/**
 * Serializa os tetos que são **por conta** — Squads criados e participações ativas.
 *
 * Ordem de aquisição: este lock vem **antes** de [lockSocialGroup] em qualquer caminho que precise
 * dos dois (aceitar um convite confere os dois tetos). Uma ordem fixa é o que impede que dois
 * caminhos que tomam os mesmos dois locks em sentidos opostos se travem mutuamente.
 */
export async function lockSocialGroupMember(client: PoolClient, memberUid: string): Promise<void> {
  await client.query('SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))', [
    'social_group_member',
    memberUid,
  ]);
}
