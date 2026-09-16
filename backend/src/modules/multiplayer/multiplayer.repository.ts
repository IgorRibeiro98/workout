import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { DbClient, PostgresService } from '../../database/postgres.service';
import type {
  MultiplayerCloseReason,
  MultiplayerEventType,
  MultiplayerMemberRole,
  MultiplayerMemberStatus,
  MultiplayerRoomStatus,
} from './multiplayer.contract';

export interface StoredRoom {
  readonly id: string;
  readonly host_uid: string;
  readonly status: MultiplayerRoomStatus;
  readonly workout_json: string;
  readonly workout_hash: string;
  readonly client_request_id: string;
  readonly next_sequence: number;
  readonly created_at: number;
  readonly updated_at: number;
  readonly expires_at: number;
  readonly closed_at: number | null;
  readonly close_reason: MultiplayerCloseReason | null;
}

export interface StoredMember {
  readonly room_id: string;
  readonly member_uid: string;
  readonly role: MultiplayerMemberRole;
  readonly status: MultiplayerMemberStatus;
  readonly invited_at: number;
  readonly joined_at: number | null;
  readonly finished_at: number | null;
  readonly left_at: number | null;
  readonly last_seen_at: number | null;
  /** Do perfil social ativo do membro. */
  readonly social_id: string;
  readonly display_name: string;
}

export interface StoredEvent {
  readonly room_id: string;
  readonly sequence: number;
  readonly event_id: string;
  readonly actor_uid: string;
  readonly actor_social_id: string;
  readonly type: MultiplayerEventType;
  readonly payload_json: string;
  readonly created_at: number;
}

export interface SocialProfileRef {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly displayName: string;
}

interface RoomRow {
  id: string;
  host_uid: string;
  status: string;
  workout_json: string;
  workout_hash: string;
  client_request_id: string;
  next_sequence: number | string;
  created_at: number | string;
  updated_at: number | string;
  expires_at: number | string;
  closed_at: number | string | null;
  close_reason: string | null;
}

interface MemberRow {
  room_id: string;
  member_uid: string;
  role: string;
  status: string;
  invited_at: number | string;
  joined_at: number | string | null;
  finished_at: number | string | null;
  left_at: number | string | null;
  last_seen_at: number | string | null;
  social_id: string;
  display_name: string;
}

interface EventRow {
  room_id: string;
  sequence: number | string;
  event_id: string;
  actor_uid: string;
  actor_social_id: string;
  type: string;
  payload_json: string;
  created_at: number | string;
}

const MEMBER_SELECT = `
  SELECT m.room_id, m.member_uid, m.role, m.status, m.invited_at, m.joined_at, m.finished_at,
         m.left_at, m.last_seen_at, p.social_id, p.display_name
  FROM multiplayer_room_members m
  JOIN social_profiles p ON p.owner_uid = m.member_uid`;

const EVENT_SELECT = `
  SELECT e.room_id, e.sequence, e.event_id, e.actor_uid, p.social_id AS actor_social_id,
         e.type, e.payload_json, e.created_at
  FROM multiplayer_room_events e
  JOIN social_profiles p ON p.owner_uid = e.actor_uid`;

/**
 * O acesso ao PostgreSQL do multiplayer (T19.5).
 *
 * Toda mutação de sala roda sob [lockRoom] — `SELECT ... FOR UPDATE` na linha da sala — dentro de
 * uma transação do chamador. É esse lock que serializa a atribuição de `sequence`, a entrada do
 * convidado contra o encerramento, e a saída contra a publicação de um evento: duas requisições
 * concorrentes sobre a mesma sala enxergam uma à outra, sempre.
 */
@Injectable()
export class MultiplayerRepository {
  constructor(private readonly db: PostgresService) {}

  private runner(client?: PoolClient): DbClient {
    return (client ?? this.db) as DbClient;
  }

  private toRoom(row: RoomRow): StoredRoom {
    return {
      id: row.id,
      host_uid: row.host_uid,
      status: row.status as MultiplayerRoomStatus,
      workout_json: row.workout_json,
      workout_hash: row.workout_hash,
      client_request_id: row.client_request_id,
      next_sequence: Number(row.next_sequence),
      created_at: Number(row.created_at),
      updated_at: Number(row.updated_at),
      expires_at: Number(row.expires_at),
      closed_at: row.closed_at != null ? Number(row.closed_at) : null,
      close_reason: (row.close_reason as MultiplayerCloseReason | null) ?? null,
    };
  }

  private toMember(row: MemberRow): StoredMember {
    return {
      room_id: row.room_id,
      member_uid: row.member_uid,
      role: row.role as MultiplayerMemberRole,
      status: row.status as MultiplayerMemberStatus,
      invited_at: Number(row.invited_at),
      joined_at: row.joined_at != null ? Number(row.joined_at) : null,
      finished_at: row.finished_at != null ? Number(row.finished_at) : null,
      left_at: row.left_at != null ? Number(row.left_at) : null,
      last_seen_at: row.last_seen_at != null ? Number(row.last_seen_at) : null,
      social_id: row.social_id,
      display_name: row.display_name,
    };
  }

  private toEvent(row: EventRow): StoredEvent {
    return {
      room_id: row.room_id,
      sequence: Number(row.sequence),
      event_id: row.event_id,
      actor_uid: row.actor_uid,
      actor_social_id: row.actor_social_id,
      type: row.type as MultiplayerEventType,
      payload_json: row.payload_json,
      created_at: Number(row.created_at),
    };
  }

  // ------------------------------------------------------------------ perfis e amizade

  async findProfileByUid(ownerUid: string): Promise<SocialProfileRef | undefined> {
    const res = await this.db.query<{ owner_uid: string; social_id: string; display_name: string }>(
      `SELECT owner_uid, social_id, display_name FROM social_profiles
       WHERE owner_uid = $1 AND status = 'ACTIVE'`,
      [ownerUid],
    );
    const row = res.rows[0];
    return row
      ? { ownerUid: row.owner_uid, socialId: row.social_id, displayName: row.display_name }
      : undefined;
  }

  async findProfileBySocialId(socialId: string): Promise<SocialProfileRef | undefined> {
    const res = await this.db.query<{ owner_uid: string; social_id: string; display_name: string }>(
      `SELECT owner_uid, social_id, display_name FROM social_profiles
       WHERE social_id = $1 AND status = 'ACTIVE'`,
      [socialId],
    );
    const row = res.rows[0];
    return row
      ? { ownerUid: row.owner_uid, socialId: row.social_id, displayName: row.display_name }
      : undefined;
  }

  async isFriendshipActive(uidA: string, uidB: string): Promise<boolean> {
    const res = await this.db.query(
      `SELECT 1 FROM friendships
       WHERE (user_a_uid = $1 AND user_b_uid = $2) OR (user_a_uid = $2 AND user_b_uid = $1)
       LIMIT 1`,
      [uidA, uidB],
    );
    return res.rows.length > 0;
  }

  // ------------------------------------------------------------------ salas

  async transaction<T>(work: (client: PoolClient) => Promise<T>): Promise<T> {
    return this.db.transaction(work);
  }

  async findRoomById(roomId: string, client?: PoolClient): Promise<StoredRoom | undefined> {
    const res = await this.runner(client).query<RoomRow>(
      `SELECT * FROM multiplayer_rooms WHERE id = $1`,
      [roomId],
    );
    return res.rows[0] ? this.toRoom(res.rows[0]) : undefined;
  }

  /** A linha da sala, travada até o fim da transação. */
  async lockRoom(client: PoolClient, roomId: string): Promise<StoredRoom | undefined> {
    const res = await client.query<RoomRow>(
      `SELECT * FROM multiplayer_rooms WHERE id = $1 FOR UPDATE`,
      [roomId],
    );
    return res.rows[0] ? this.toRoom(res.rows[0]) : undefined;
  }

  async findRoomByHostAndClientRequestId(
    hostUid: string,
    clientRequestId: string,
  ): Promise<StoredRoom | undefined> {
    const res = await this.db.query<RoomRow>(
      `SELECT * FROM multiplayer_rooms WHERE host_uid = $1 AND client_request_id = $2`,
      [hostUid, clientRequestId],
    );
    return res.rows[0] ? this.toRoom(res.rows[0]) : undefined;
  }

  async countRoomsCreatedSince(hostUid: string, since: number): Promise<number> {
    const res = await this.db.query<{ count: string | number }>(
      `SELECT COUNT(*) AS count FROM multiplayer_rooms WHERE host_uid = $1 AND created_at >= $2`,
      [hostUid, since],
    );
    return Number(res.rows[0]?.count ?? 0);
  }

  async listOpenRoomIdsForHost(client: PoolClient, hostUid: string): Promise<string[]> {
    const res = await client.query<{ id: string }>(
      `SELECT id FROM multiplayer_rooms
       WHERE host_uid = $1 AND status IN ('WAITING', 'ACTIVE')
       ORDER BY created_at
       FOR UPDATE`,
      [hostUid],
    );
    return res.rows.map((r) => r.id);
  }

  async insertRoom(
    client: PoolClient,
    room: StoredRoom,
    guest: { readonly uid: string },
  ): Promise<void> {
    await client.query(
      `INSERT INTO multiplayer_rooms
         (id, host_uid, status, workout_json, workout_hash, client_request_id, next_sequence,
          created_at, updated_at, expires_at, closed_at, close_reason)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, NULL, NULL)`,
      [
        room.id,
        room.host_uid,
        room.status,
        room.workout_json,
        room.workout_hash,
        room.client_request_id,
        room.next_sequence,
        room.created_at,
        room.updated_at,
        room.expires_at,
      ],
    );
    await client.query(
      `INSERT INTO multiplayer_room_members
         (room_id, member_uid, role, status, invited_at, joined_at, last_seen_at)
       VALUES ($1, $2, 'HOST', 'ACTIVE', $3, $3, $3), ($1, $4, 'GUEST', 'INVITED', $3, NULL, NULL)`,
      [room.id, room.host_uid, room.created_at, guest.uid],
    );
  }

  async updateRoomStatus(
    client: PoolClient,
    roomId: string,
    from: readonly MultiplayerRoomStatus[],
    to: MultiplayerRoomStatus,
    now: number,
    closeReason: MultiplayerCloseReason | null = null,
  ): Promise<boolean> {
    const closing = to === 'CLOSED' || to === 'EXPIRED';
    const res = await client.query(
      `UPDATE multiplayer_rooms
       SET status = $1, updated_at = $2,
           closed_at = CASE WHEN $3::boolean THEN $2 ELSE closed_at END,
           close_reason = CASE WHEN $3::boolean THEN $4 ELSE close_reason END
       WHERE id = $5 AND status = ANY($6::text[])`,
      [to, now, closing, closeReason, roomId, from],
    );
    return (res.rowCount ?? 0) > 0;
  }

  async touchRoom(client: PoolClient, roomId: string, now: number): Promise<void> {
    await client.query(`UPDATE multiplayer_rooms SET updated_at = $1 WHERE id = $2`, [now, roomId]);
  }

  // ------------------------------------------------------------------ membros

  async listMembers(roomId: string, client?: PoolClient): Promise<StoredMember[]> {
    const res = await this.runner(client).query<MemberRow>(
      `${MEMBER_SELECT} WHERE m.room_id = $1 ORDER BY m.role`,
      [roomId],
    );
    return res.rows.map((r) => this.toMember(r));
  }

  async findMember(
    roomId: string,
    memberUid: string,
    client?: PoolClient,
  ): Promise<StoredMember | undefined> {
    const res = await this.runner(client).query<MemberRow>(
      `${MEMBER_SELECT} WHERE m.room_id = $1 AND m.member_uid = $2`,
      [roomId, memberUid],
    );
    return res.rows[0] ? this.toMember(res.rows[0]) : undefined;
  }

  /**
   * Transição de estado de membership, **condicional** ao estado esperado: é o `rowCount` que diz
   * se esta requisição foi a que mudou algo — e é assim que join, leave e finish repetidos são
   * sucesso sem serem uma segunda transição.
   */
  async transitionMember(
    client: PoolClient,
    roomId: string,
    memberUid: string,
    from: readonly MultiplayerMemberStatus[],
    to: MultiplayerMemberStatus,
    now: number,
  ): Promise<boolean> {
    const res = await client.query(
      `UPDATE multiplayer_room_members
       SET status = $1::text,
           joined_at    = CASE WHEN $1::text = 'ACTIVE'   THEN $2::bigint ELSE joined_at END,
           finished_at  = CASE WHEN $1::text = 'FINISHED' THEN $2::bigint ELSE finished_at END,
           left_at      = CASE WHEN $1::text = 'LEFT'     THEN $2::bigint ELSE left_at END,
           last_seen_at = CASE WHEN $1::text IN ('ACTIVE', 'FINISHED') THEN $2::bigint ELSE last_seen_at END
       WHERE room_id = $3 AND member_uid = $4 AND status = ANY($5::text[])`,
      [to, now, roomId, memberUid, from],
    );
    return (res.rowCount ?? 0) > 0;
  }

  async touchPresence(roomId: string, memberUid: string, now: number): Promise<void> {
    await this.db.query(
      `UPDATE multiplayer_room_members SET last_seen_at = $1
       WHERE room_id = $2 AND member_uid = $3 AND status IN ('ACTIVE', 'FINISHED')`,
      [now, roomId, memberUid],
    );
  }

  /** Os convites pendentes de uma conta: salas WAITING, ainda no prazo, onde ela é INVITED. */
  async listInvitations(
    memberUid: string,
    now: number,
  ): Promise<Array<{ room: StoredRoom; host: SocialProfileRef }>> {
    const res = await this.db.query<
      RoomRow & { host_social_id: string; host_display_name: string }
    >(
      `SELECT r.*, p.social_id AS host_social_id, p.display_name AS host_display_name
       FROM multiplayer_room_members m
       JOIN multiplayer_rooms r ON r.id = m.room_id
       JOIN social_profiles p ON p.owner_uid = r.host_uid AND p.status = 'ACTIVE'
       WHERE m.member_uid = $1 AND m.status = 'INVITED'
         AND r.status = 'WAITING' AND r.expires_at > $2
       ORDER BY r.created_at DESC
       LIMIT 20`,
      [memberUid, now],
    );
    return res.rows.map((row) => ({
      room: this.toRoom(row),
      host: {
        ownerUid: row.host_uid,
        socialId: row.host_social_id,
        displayName: row.host_display_name,
      },
    }));
  }

  // ------------------------------------------------------------------ eventos

  async findEventByEventId(
    client: PoolClient,
    roomId: string,
    eventId: string,
  ): Promise<StoredEvent | undefined> {
    const res = await client.query<EventRow>(
      `${EVENT_SELECT} WHERE e.room_id = $1 AND e.event_id = $2`,
      [roomId, eventId],
    );
    return res.rows[0] ? this.toEvent(res.rows[0]) : undefined;
  }

  /**
   * Anexa um evento com a **próxima** sequence da sala. Só é correto sob [lockRoom]: `sequence`
   * vem da linha travada, e a linha é atualizada aqui mesmo, então dois anexos concorrentes na
   * mesma sala nunca leem o mesmo `next_sequence`.
   */
  async appendEvent(
    client: PoolClient,
    room: { readonly id: string; readonly next_sequence: number },
    event: {
      readonly eventId: string;
      readonly actorUid: string;
      readonly type: MultiplayerEventType;
      readonly payload: Record<string, unknown>;
    },
    now: number,
  ): Promise<number> {
    const sequence = room.next_sequence;
    await client.query(
      `INSERT INTO multiplayer_room_events
         (room_id, sequence, event_id, actor_uid, type, payload_json, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [
        room.id,
        sequence,
        event.eventId,
        event.actorUid,
        event.type,
        JSON.stringify(event.payload),
        now,
      ],
    );
    await client.query(
      `UPDATE multiplayer_rooms SET next_sequence = $1, updated_at = $2 WHERE id = $3`,
      [sequence + 1, now, room.id],
    );
    (room as { next_sequence: number }).next_sequence = sequence + 1;
    return sequence;
  }

  async listEventsAfter(
    roomId: string,
    after: number,
    limit: number,
    client?: PoolClient,
  ): Promise<StoredEvent[]> {
    const res = await this.runner(client).query<EventRow>(
      `${EVENT_SELECT} WHERE e.room_id = $1 AND e.sequence > $2 ORDER BY e.sequence LIMIT $3`,
      [roomId, after, limit],
    );
    return res.rows.map((r) => this.toEvent(r));
  }
}
