import { Injectable, Optional } from '@nestjs/common';
import { DbClient, PostgresService, type PoolClient } from '../../database/postgres.service';
import type {
  FriendRelationship,
  FriendRequestDirection,
  FriendRequestStatus,
} from './friendship.contract';
import type { SocialProfileStatus } from './social.contract';
import { NotificationService } from './notification.service';

/**
 * O recorte de um perfil que o grafo precisa: identidade, status e as duas flags de privacidade.
 *
 * `ownerUid` existe aqui porque o banco relaciona por ele — e **nunca** sai em DTO. A fronteira
 * onde o Firebase UID para de existir é a projeção (`toPreview`/`toFriend` no serviço).
 */
export interface FriendProfileRow {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly displayName: string;
  readonly status: SocialProfileStatus;
  readonly discoverability: string;
  readonly friendRequestsEnabled: boolean;
  readonly activitySharingEnabled: boolean;
  readonly activityTimeZoneId: string | null;
  readonly friendRankingParticipationEnabled: boolean;
}

export interface StoredFriendRequest {
  readonly requestId: string;
  readonly requesterUid: string;
  readonly recipientUid: string;
  readonly status: FriendRequestStatus;
  readonly createdAt: number;
  readonly updatedAt: number;
}

/** Um pedido com o perfil da **outra** pessoa já resolvido — o que a listagem devolve. */
export interface FriendRequestWithProfile extends StoredFriendRequest {
  readonly direction: FriendRequestDirection;
  readonly otherSocialId: string;
  readonly otherDisplayName: string;
}

export interface StoredFriend {
  readonly socialId: string;
  readonly displayName: string;
  readonly friendsSince: number;
}

export interface PageRequest {
  readonly limit: number;
  readonly cursor: ListCursor | null;
}

/** A chave de continuação de uma listagem: sempre o par (chave de ordenação, desempate). */
export interface ListCursor {
  readonly primary: string | number;
  readonly secondary: string;
}

export interface Page<T> {
  readonly items: readonly T[];
  readonly nextCursor: ListCursor | null;
  readonly total: number;
}

/** O desfecho de um envio, decidido **dentro** da transação. */
export type SendRequestOutcome =
  | { readonly kind: 'CREATED'; readonly request: StoredFriendRequest }
  | { readonly kind: 'ALREADY_PENDING'; readonly request: StoredFriendRequest }
  | { readonly kind: 'FRIENDSHIP_CREATED'; readonly friendsSince: number }
  | { readonly kind: 'ALREADY_FRIENDS' };

/** O desfecho de um aceite, decidido **dentro** da transação. */
export type AcceptOutcome =
  | { readonly kind: 'ACCEPTED'; readonly friendsSince: number }
  | { readonly kind: 'ALREADY_FRIENDS'; readonly friendsSince: number }
  | { readonly kind: 'NOT_PENDING' };

/**
 * A persistência do grafo social (T17.1).
 */
@Injectable()
export class FriendshipRepository {
  constructor(
    private readonly db: PostgresService,
    @Optional() private readonly notificationService?: NotificationService,
  ) {}

  private getRunner(client?: PoolClient): DbClient {
    return (client ?? this.db) as DbClient;
  }

  // ------------------------------------------------------------------------------- perfis

  /**
   * O perfil de um `friendCode` **canônico**, com igualdade exata.
   */
  async findProfileByFriendCode(canonicalFriendCode: string): Promise<FriendProfileRow | null> {
    return await this.selectProfile('p.friend_code = $1', canonicalFriendCode);
  }

  /** O perfil de um `socialId`. Igualdade exata sobre índice único, como acima. */
  async findProfileBySocialId(socialId: string): Promise<FriendProfileRow | null> {
    return await this.selectProfile('p.social_id = $1', socialId);
  }

  /** O perfil da própria conta autenticada. */
  async findProfileByOwnerUid(ownerUid: string): Promise<FriendProfileRow | null> {
    return await this.selectProfile('p.owner_uid = $1', ownerUid);
  }

  private async selectProfile(where: string, value: string): Promise<FriendProfileRow | null> {
    const res = await this.db.query<ProfileRow>(
      `SELECT p.owner_uid, p.social_id, p.display_name, p.status,
              s.discoverability, s.friend_requests_enabled, s.activity_sharing_enabled,
              s.activity_time_zone_id, s.friend_ranking_participation_enabled
       FROM social_profiles p
       JOIN social_privacy_settings s ON s.owner_uid = p.owner_uid
       WHERE ${where}`,
      [value],
    );

    const row = res.rows[0];
    return row ? toProfile(row) : null;
  }

  // ------------------------------------------------------------------------------- relação

  /** A relação que já existe entre duas contas, do ponto de vista de [viewerUid]. */
  async relationship(viewerUid: string, otherUid: string): Promise<FriendRelationship> {
    if (await this.areFriends(viewerUid, otherUid)) {
      return 'FRIENDS';
    }
    if (await this.findPendingRequest(viewerUid, otherUid)) {
      return 'OUTGOING_PENDING';
    }
    if (await this.findPendingRequest(otherUid, viewerUid)) {
      return 'INCOMING_PENDING';
    }
    return 'NONE';
  }

  async areFriends(uidA: string, uidB: string, client?: PoolClient): Promise<boolean> {
    const [a, b] = canonicalPair(uidA, uidB);
    const q = this.getRunner(client);
    const res = await q.query(
      `SELECT 1 FROM friendships WHERE user_a_uid = $1 AND user_b_uid = $2`,
      [a, b],
    );
    return res.rows.length > 0;
  }

  async findPendingRequest(
    requesterUid: string,
    recipientUid: string,
    client?: PoolClient,
  ): Promise<StoredFriendRequest | null> {
    const q = this.getRunner(client);
    const res = await q.query<RequestRow>(
      `SELECT request_id, requester_uid, recipient_uid, status, created_at, updated_at
       FROM friend_requests
       WHERE requester_uid = $1 AND recipient_uid = $2 AND status = 'PENDING'`,
      [requesterUid, recipientUid],
    );
    const row = res.rows[0];
    return row ? toRequest(row) : null;
  }

  async findRequestById(requestId: string, client?: PoolClient): Promise<StoredFriendRequest | null> {
    const q = this.getRunner(client);
    const res = await q.query<RequestRow>(
      `SELECT request_id, requester_uid, recipient_uid, status, created_at, updated_at
       FROM friend_requests WHERE request_id = $1`,
      [requestId],
    );
    const row = res.rows[0];
    return row ? toRequest(row) : null;
  }

  // ------------------------------------------------------------------------------- envio

  /**
   * Envia um pedido — ou resolve o cruzamento, na **mesma** transação.
   */
  async sendRequest(input: {
    readonly requestId: string;
    readonly requesterUid: string;
    readonly recipientUid: string;
    readonly now: number;
  }): Promise<SendRequestOutcome> {
    return await this.db.transaction(async (client): Promise<SendRequestOutcome> => {
      if (await this.areFriends(input.requesterUid, input.recipientUid, client)) {
        return { kind: 'ALREADY_FRIENDS' };
      }

      const existing = await this.findPendingRequest(input.requesterUid, input.recipientUid, client);
      if (existing) {
        return { kind: 'ALREADY_PENDING', request: existing };
      }

      const inverse = await this.findPendingRequest(input.recipientUid, input.requesterUid, client);
      if (inverse) {
        await client.query(
          `UPDATE friend_requests SET status = 'ACCEPTED', updated_at = $1
           WHERE request_id = $2 AND status = 'PENDING'`,
          [input.now, inverse.requestId],
        );
        await this.insertFriendship(input.requesterUid, input.recipientUid, input.now, client);

        if (this.notificationService) {
          await this.notificationService.enqueueFriendRequestAccepted(client, {
            requestId: inverse.requestId,
            requesterUid: input.recipientUid,
            now: input.now,
          });
          await this.notificationService.enqueueFriendRequestAccepted(client, {
            requestId: inverse.requestId,
            requesterUid: input.requesterUid,
            now: input.now,
          });
        }

        return { kind: 'FRIENDSHIP_CREATED', friendsSince: input.now };
      }

      await client.query(
        `INSERT INTO friend_requests
           (request_id, requester_uid, recipient_uid, status, created_at, updated_at)
         VALUES ($1, $2, $3, 'PENDING', $4, $5)`,
        [input.requestId, input.requesterUid, input.recipientUid, input.now, input.now],
      );

      if (this.notificationService) {
        await this.notificationService.enqueueFriendRequestReceived(client, {
          requestId: input.requestId,
          recipientUid: input.recipientUid,
          now: input.now,
        });
      }

      return {
        kind: 'CREATED',
        request: {
          requestId: input.requestId,
          requesterUid: input.requesterUid,
          recipientUid: input.recipientUid,
          status: 'PENDING',
          createdAt: input.now,
          updatedAt: input.now,
        },
      };
    });
  }

  // ------------------------------------------------------------------------------- respostas

  /**
   * Aceita um pedido: marca `ACCEPTED` **e** cria a amizade, ou não faz nenhuma das duas.
   */
  async acceptRequest(requestId: string, now: number): Promise<AcceptOutcome> {
    return await this.db.transaction(async (client): Promise<AcceptOutcome> => {
      const request = await this.findRequestById(requestId, client);
      if (!request) {
        return { kind: 'NOT_PENDING' };
      }

      const res = await client.query(
        `UPDATE friend_requests SET status = 'ACCEPTED', updated_at = $1
         WHERE request_id = $2 AND status = 'PENDING'`,
        [now, requestId],
      );

      if ((res.rowCount ?? 0) === 0) {
        const since = await this.friendshipCreatedAt(request.requesterUid, request.recipientUid, client);
        if (request.status === 'ACCEPTED' && since !== null) {
          return { kind: 'ALREADY_FRIENDS', friendsSince: since };
        }
        return { kind: 'NOT_PENDING' };
      }

      await this.insertFriendship(request.requesterUid, request.recipientUid, now, client);

      if (this.notificationService) {
        await this.notificationService.enqueueFriendRequestAccepted(client, {
          requestId,
          requesterUid: request.requesterUid,
          now,
        });
      }

      const since = (await this.friendshipCreatedAt(request.requesterUid, request.recipientUid, client)) ?? now;
      return { kind: 'ACCEPTED', friendsSince: since };
    });
  }

  /**
   * Muda o status de um pedido pendente. `true` quando **esta** chamada foi a que mudou.
   */
  async resolveRequest(requestId: string, status: FriendRequestStatus, now: number): Promise<boolean> {
    const res = await this.db.query(
      `UPDATE friend_requests SET status = $1, updated_at = $2
       WHERE request_id = $3 AND status = 'PENDING'`,
      [status, now, requestId],
    );
    return (res.rowCount ?? 0) > 0;
  }

  // ------------------------------------------------------------------------------- amizade

  /**
   * Cria a amizade na forma canônica.
   */
  private async insertFriendship(uidA: string, uidB: string, now: number, client?: PoolClient): Promise<void> {
    const [a, b] = canonicalPair(uidA, uidB);
    const q = this.getRunner(client);
    await q.query(
      `INSERT INTO friendships (user_a_uid, user_b_uid, created_at) VALUES ($1, $2, $3)
       ON CONFLICT (user_a_uid, user_b_uid) DO NOTHING`,
      [a, b, now],
    );
  }

  async friendshipCreatedAt(uidA: string, uidB: string, client?: PoolClient): Promise<number | null> {
    const [a, b] = canonicalPair(uidA, uidB);
    const q = this.getRunner(client);
    const res = await q.query<{ created_at: string | number }>(
      `SELECT created_at FROM friendships WHERE user_a_uid = $1 AND user_b_uid = $2`,
      [a, b],
    );
    const row = res.rows[0];
    return row ? Number(row.created_at) : null;
  }

  /**
   * Desfaz a amizade. `true` quando havia uma.
   */
  async removeFriendship(uidA: string, uidB: string): Promise<boolean> {
    const [a, b] = canonicalPair(uidA, uidB);
    const res = await this.db.query(
      `DELETE FROM friendships WHERE user_a_uid = $1 AND user_b_uid = $2`,
      [a, b],
    );
    return (res.rowCount ?? 0) > 0;
  }

  // ------------------------------------------------------------------------------- listagens

  /**
   * Os amigos de uma conta, em ordem estável e paginada.
   */
  async listFriends(ownerUid: string, page: PageRequest): Promise<Page<StoredFriend>> {
    let query: string;
    let params: unknown[];

    if (page.cursor) {
      query = `
        ${FRIENDS_SELECT}
        AND (p.display_name > $2 OR (p.display_name = $2 AND p.social_id > $3))
        ORDER BY p.display_name ASC, p.social_id ASC
        LIMIT $4
      `;
      params = [ownerUid, String(page.cursor.primary), page.cursor.secondary, page.limit + 1];
    } else {
      query = `
        ${FRIENDS_SELECT}
        ORDER BY p.display_name ASC, p.social_id ASC
        LIMIT $2
      `;
      params = [ownerUid, page.limit + 1];
    }

    const res = await this.db.query<FriendRow>(query, params);

    const totalRes = await this.db.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total FROM (${FRIENDS_SELECT}) AS count_subquery`,
      [ownerUid],
    );
    const total = Number(totalRes.rows[0]?.total ?? 0);

    return paginate(
      res.rows.map((row) => ({
        socialId: row.social_id,
        displayName: row.display_name,
        friendsSince: Number(row.created_at),
      })),
      page.limit,
      total,
      (friend) => ({ primary: friend.displayName, secondary: friend.socialId }),
    );
  }

  /**
   * Os pedidos pendentes de uma conta, mais recentes primeiro.
   */
  async listRequests(
    ownerUid: string,
    direction: FriendRequestDirection,
    page: PageRequest,
  ): Promise<Page<FriendRequestWithProfile>> {
    const [own, other] =
      direction === 'INCOMING'
        ? ['fr.recipient_uid', 'fr.requester_uid']
        : ['fr.requester_uid', 'fr.recipient_uid'];

    const select = requestsSelect(own, other);
    let query: string;
    let params: unknown[];

    if (page.cursor) {
      query = `
        ${select}
        AND (fr.created_at < $2 OR (fr.created_at = $2 AND fr.request_id < $3))
        ORDER BY fr.created_at DESC, fr.request_id DESC
        LIMIT $4
      `;
      params = [ownerUid, Number(page.cursor.primary), page.cursor.secondary, page.limit + 1];
    } else {
      query = `
        ${select}
        ORDER BY fr.created_at DESC, fr.request_id DESC
        LIMIT $2
      `;
      params = [ownerUid, page.limit + 1];
    }

    const res = await this.db.query<RequestWithProfileRow>(query, params);

    const totalRes = await this.db.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total FROM (${select}) AS count_subquery`,
      [ownerUid],
    );
    const total = Number(totalRes.rows[0]?.total ?? 0);

    return paginate(
      res.rows.map((row) => ({
        requestId: row.request_id,
        requesterUid: row.requester_uid,
        recipientUid: row.recipient_uid,
        status: row.status as FriendRequestStatus,
        createdAt: Number(row.created_at),
        updatedAt: Number(row.updated_at),
        direction,
        otherSocialId: row.other_social_id,
        otherDisplayName: row.other_display_name,
      })),
      page.limit,
      total,
      (request) => ({ primary: request.createdAt, secondary: request.requestId }),
    );
  }

  /**
   * Retorna todos os amigos diretos cujo perfil está ACTIVE, acompanhados de suas preferências
   * de privacidade (T17.4). Usado nas projeções de atividade e ranking contextual.
   */
  async findActiveFriends(ownerUid: string): Promise<readonly FriendProfileRow[]> {
    const res = await this.db.query<ProfileRow>(
      `SELECT p.owner_uid, p.social_id, p.display_name, p.status,
              s.discoverability, s.friend_requests_enabled, s.activity_sharing_enabled,
              s.activity_time_zone_id, s.friend_ranking_participation_enabled
       FROM friendships f
       JOIN social_profiles p
         ON p.owner_uid = CASE WHEN f.user_a_uid = $1 THEN f.user_b_uid ELSE f.user_a_uid END
       JOIN social_privacy_settings s
         ON s.owner_uid = p.owner_uid
       WHERE (f.user_a_uid = $1 OR f.user_b_uid = $1)
         AND p.status = 'ACTIVE'
       ORDER BY p.display_name ASC, p.social_id ASC`,
      [ownerUid],
    );

    return res.rows.map(toProfile);
  }
}

/**
 * O par canônico: menor uid primeiro.
 */
export function canonicalPair(uidA: string, uidB: string): [string, string] {
  return uidA < uidB ? [uidA, uidB] : [uidB, uidA];
}

const FRIENDS_SELECT = `
  SELECT p.social_id, p.display_name, f.created_at
  FROM friendships f
  JOIN social_profiles p
    ON p.owner_uid = CASE WHEN f.user_a_uid = $1 THEN f.user_b_uid ELSE f.user_a_uid END
  WHERE (f.user_a_uid = $1 OR f.user_b_uid = $1)
    AND p.status = 'ACTIVE'
`;

function requestsSelect(ownColumn: string, otherColumn: string): string {
  return `
  SELECT fr.request_id, fr.requester_uid, fr.recipient_uid, fr.status, fr.created_at,
         fr.updated_at, p.social_id AS other_social_id, p.display_name AS other_display_name
  FROM friend_requests fr
  JOIN social_profiles p ON p.owner_uid = ${otherColumn}
  WHERE ${ownColumn} = $1
    AND fr.status = 'PENDING'
    AND p.status = 'ACTIVE'
`;
}

function paginate<T>(
  rows: T[],
  limit: number,
  total: number,
  cursorOf: (item: T) => ListCursor,
): Page<T> {
  const hasMore = rows.length > limit;
  const items = hasMore ? rows.slice(0, limit) : rows;
  return {
    items,
    total,
    nextCursor: hasMore && items.length > 0 ? cursorOf(items[items.length - 1]) : null,
  };
}

interface ProfileRow {
  owner_uid: string;
  social_id: string;
  display_name: string;
  status: string;
  discoverability: string;
  friend_requests_enabled: boolean | number;
  activity_sharing_enabled: boolean | number;
  activity_time_zone_id: string | null;
  friend_ranking_participation_enabled: boolean | number;
}

interface RequestRow {
  request_id: string;
  requester_uid: string;
  recipient_uid: string;
  status: string;
  created_at: string | number;
  updated_at: string | number;
}

interface RequestWithProfileRow extends RequestRow {
  other_social_id: string;
  other_display_name: string;
}

interface FriendRow {
  social_id: string;
  display_name: string;
  created_at: string | number;
}

function toProfile(row: ProfileRow): FriendProfileRow {
  return {
    ownerUid: row.owner_uid,
    socialId: row.social_id,
    displayName: row.display_name,
    status: row.status as SocialProfileStatus,
    discoverability: row.discoverability,
    friendRequestsEnabled: Boolean(row.friend_requests_enabled),
    activitySharingEnabled: Boolean(row.activity_sharing_enabled),
    activityTimeZoneId: row.activity_time_zone_id,
    friendRankingParticipationEnabled: Boolean(row.friend_ranking_participation_enabled),
  };
}

function toRequest(row: RequestRow): StoredFriendRequest {
  return {
    requestId: row.request_id,
    requesterUid: row.requester_uid,
    recipientUid: row.recipient_uid,
    status: row.status as FriendRequestStatus,
    createdAt: Number(row.created_at),
    updatedAt: Number(row.updated_at),
  };
}
