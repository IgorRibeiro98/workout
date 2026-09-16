import { Inject, Injectable } from '@nestjs/common';
import { createHash, randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { CLOCK, Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { BlockRepository } from '../social/block.repository';
import type { WorkoutTemplateShareSnapshotV1 } from '../social/workout-share.contract';
import {
  MULTIPLAYER_LIMITS,
  type CreateMultiplayerRoomRequest,
  type MultiplayerCloseReason,
  type MultiplayerEventDto,
  type MultiplayerEventsPageDto,
  type MultiplayerInvitationDto,
  type MultiplayerMemberDto,
  type MultiplayerRoomDto,
  type PublishMultiplayerEventsRequest,
  type PublishMultiplayerEventsResponse,
} from './multiplayer.contract';
import { MultiplayerErrors } from './multiplayer.errors';
import {
  MultiplayerRepository,
  type SocialProfileRef,
  type StoredEvent,
  type StoredMember,
  type StoredRoom,
} from './multiplayer.repository';

const ONE_DAY_MS = 24 * 60 * 60 * 1000;
const PG_UNIQUE_VIOLATION = '23505';

/**
 * Os casos de uso do multiplayer remoto (T19.5).
 *
 * ## O que este serviço decide — e o que ele nunca decide
 *
 * Ele decide quem pode estar numa sala, em que ordem os eventos aconteceram, quem está conectado e
 * quando a sala acaba. Ele **não** decide nada sobre o treino de ninguém: não há `WorkoutSession`
 * aqui, não há série, não há peso, não há PR, não há XP. Um evento é um fato relatado por um
 * aparelho sobre a própria execução, gravado na ordem em que chegou; o outro aparelho lê e decide
 * sozinho o que mostrar. Se o servidor cair, os dois treinos continuam — este é o critério de
 * correção da T19.5.
 *
 * ## Uma sala aberta por host
 *
 * Criar uma sala nova encerra a anterior do mesmo host (`SUPERSEDED`), em vez de recusar: um host
 * que fechou o app com uma sala WAITING não pode ficar preso até ela expirar.
 *
 * ## Expiração preguiçosa
 *
 * Não há cron. Uma sala WAITING/ACTIVE cujo `expires_at` já passou vira `EXPIRED` no primeiro
 * acesso — leitura, join, evento ou poll — e a transição gera um `ROOM_CLOSED` para quem estiver
 * lendo o log.
 */
@Injectable()
export class MultiplayerService {
  constructor(
    private readonly repository: MultiplayerRepository,
    private readonly blockRepository: BlockRepository,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  /** A espera entre duas checagens do long-poll. Propriedade, e não parâmetro: o Nest não injeta funções. */
  private readonly sleep = (ms: number): Promise<void> =>
    new Promise((resolve) => setTimeout(resolve, ms));

  // ------------------------------------------------------------------ criação

  async createRoom(
    hostUid: string,
    request: CreateMultiplayerRoomRequest,
  ): Promise<MultiplayerRoomDto> {
    const now = this.clock.now();

    const host = await this.repository.findProfileByUid(hostUid);
    if (!host) throw MultiplayerErrors.socialNotEnabled();

    // Convidado inexistente e convidado que não é amigo respondem **igual**: distinguir faria
    // desta rota um oráculo de existência sobre `socialId` (T17.7, mesma regra).
    const invitee = await this.repository.findProfileBySocialId(request.inviteeSocialId);
    if (!invitee) throw MultiplayerErrors.friendshipRequired();
    if (invitee.ownerUid === hostUid) throw MultiplayerErrors.cannotInviteSelf();
    if (await this.blockRepository.isBlockedBidirectional(hostUid, invitee.ownerUid)) {
      throw MultiplayerErrors.friendshipRequired();
    }
    if (!(await this.repository.isFriendshipActive(hostUid, invitee.ownerUid))) {
      throw MultiplayerErrors.friendshipRequired();
    }

    const workoutJson = JSON.stringify(request.workout);
    const workoutHash = createHash('sha256').update(workoutJson).digest('hex');

    // Idempotência por `clientRequestId`: mesma chave + mesmo convidado + mesmo treino é replay;
    // qualquer divergência é conflito, nunca o resultado antigo com outro conteúdo.
    const existing = await this.repository.findRoomByHostAndClientRequestId(
      hostUid,
      request.clientRequestId,
    );
    if (existing) {
      return this.replayCreate(existing, hostUid, invitee, workoutHash, now);
    }

    const daily = await this.repository.countRoomsCreatedSince(hostUid, now - ONE_DAY_MS);
    if (daily >= MULTIPLAYER_LIMITS.maxRoomsPerDay) throw MultiplayerErrors.rateLimited();

    const room: StoredRoom = {
      id: randomUUID(),
      host_uid: hostUid,
      status: 'WAITING',
      workout_json: workoutJson,
      workout_hash: workoutHash,
      client_request_id: request.clientRequestId,
      next_sequence: 1,
      created_at: now,
      updated_at: now,
      expires_at: now + MULTIPLAYER_LIMITS.waitingTtlMs,
      closed_at: null,
      close_reason: null,
    };

    let superseded = 0;
    try {
      await this.repository.transaction(async (client) => {
        const open = await this.repository.listOpenRoomIdsForHost(client, hostUid);
        for (const roomId of open) {
          const locked = await this.repository.lockRoom(client, roomId);
          if (locked) {
            await this.closeRoomLocked(client, locked, 'SUPERSEDED', hostUid, now);
            superseded += 1;
          }
        }
        await this.repository.insertRoom(client, room, { uid: invitee.ownerUid });
      });
    } catch (error) {
      // Dois toques que passaram os dois pela leitura acima: a `UNIQUE (host_uid,
      // client_request_id)` decide, e o segundo recebe o resultado do primeiro.
      if ((error as { code?: string }).code === PG_UNIQUE_VIOLATION) {
        const winner = await this.repository.findRoomByHostAndClientRequestId(
          hostUid,
          request.clientRequestId,
        );
        if (winner) return this.replayCreate(winner, hostUid, invitee, workoutHash, now);
      }
      throw error;
    }

    this.logger.info('multiplayer.room.created', {
      roomId: room.id,
      exerciseCount: request.workout.exercises.length,
      superseded,
    });

    return this.toRoomDto(room, await this.repository.listMembers(room.id), hostUid, now);
  }

  private async replayCreate(
    existing: StoredRoom,
    hostUid: string,
    invitee: SocialProfileRef,
    workoutHash: string,
    now: number,
  ): Promise<MultiplayerRoomDto> {
    const members = await this.repository.listMembers(existing.id);
    const guest = members.find((m) => m.role === 'GUEST');
    if (existing.workout_hash !== workoutHash || guest?.member_uid !== invitee.ownerUid) {
      throw MultiplayerErrors.conflict('clientRequestId já utilizado com parâmetros divergentes.');
    }
    return this.toRoomDto(await this.expireIfDue(existing, now), members, hostUid, now);
  }

  // ------------------------------------------------------------------ leitura

  async getRoom(uid: string, roomId: string): Promise<MultiplayerRoomDto> {
    const now = this.clock.now();
    const { room, members } = await this.requireVisibleRoom(uid, roomId, now);
    return this.toRoomDto(room, members, uid, now);
  }

  async listInvitations(uid: string): Promise<MultiplayerInvitationDto[]> {
    const now = this.clock.now();
    const blocked = await this.blockRepository.findBlockedUidsBidirectional(uid);
    const invitations = await this.repository.listInvitations(uid, now);
    return invitations
      .filter(({ room }) => !blocked.has(room.host_uid))
      .map(({ room, host }) => {
        const workout = JSON.parse(room.workout_json) as WorkoutTemplateShareSnapshotV1;
        return {
          roomId: room.id,
          createdAt: room.created_at,
          expiresAt: room.expires_at,
          host: { socialId: host.socialId, displayName: host.displayName },
          workoutName: workout.name,
          exerciseCount: workout.exercises.length,
        };
      });
  }

  // ------------------------------------------------------------------ membership

  async join(uid: string, roomId: string): Promise<MultiplayerRoomDto> {
    const now = this.clock.now();
    const outcome = await this.repository.transaction(async (client) => {
      const room = await this.lockVisibleRoom(client, uid, roomId, now);
      const member = (await this.repository.findMember(roomId, uid, client))!;

      // Bloqueio vence (T19.5 §10): a resposta é a mesma de "sala não existe".
      if (await this.blockRepository.isBlockedBidirectional(room.host_uid, uid)) {
        throw MultiplayerErrors.roomNotFound();
      }
      if (room.status === 'CLOSED') throw MultiplayerErrors.roomClosed();
      if (room.status === 'EXPIRED') throw MultiplayerErrors.roomExpired();

      switch (member.status) {
        case 'LEFT':
          // Sair é decisão explícita; reconectar não a desfaz.
          throw MultiplayerErrors.memberLeft();
        case 'ACTIVE':
        case 'FINISHED':
          // Rejoin: a membership já existe e continua sendo uma só.
          await client.query(
            `UPDATE multiplayer_room_members SET last_seen_at = $1 WHERE room_id = $2 AND member_uid = $3`,
            [now, roomId, uid],
          );
          return { room, joined: false };
        case 'INVITED': {
          await this.repository.transitionMember(client, roomId, uid, ['INVITED'], 'ACTIVE', now);
          if (room.status === 'WAITING') {
            await client.query(
              `UPDATE multiplayer_rooms SET status = 'ACTIVE', updated_at = $1, expires_at = $2 WHERE id = $3`,
              [now, room.created_at + MULTIPLAYER_LIMITS.activeTtlMs, roomId],
            );
          }
          await this.repository.appendEvent(
            client,
            room,
            {
              eventId: `sys:joined:${randomUUID()}`,
              actorUid: uid,
              type: 'MEMBER_JOINED',
              payload: {},
            },
            now,
          );
          return { room, joined: true };
        }
      }
    });

    if (outcome.joined) this.logger.info('multiplayer.room.joined', { roomId });
    return this.getRoom(uid, roomId);
  }

  async leave(uid: string, roomId: string): Promise<{ success: boolean }> {
    const now = this.clock.now();
    const left = await this.repository.transaction(async (client) => {
      const room = await this.lockVisibleRoom(client, uid, roomId, now);
      const member = (await this.repository.findMember(roomId, uid, client))!;
      if (member.status === 'LEFT') return false;

      await this.repository.transitionMember(
        client,
        roomId,
        uid,
        ['INVITED', 'ACTIVE', 'FINISHED'],
        'LEFT',
        now,
      );
      if (room.status === 'CLOSED' || room.status === 'EXPIRED') return true;

      await this.repository.appendEvent(
        client,
        room,
        { eventId: `sys:left:${randomUUID()}`, actorUid: uid, type: 'MEMBER_LEFT', payload: {} },
        now,
      );

      const reason = this.closeReasonAfterLeave(
        room,
        member,
        await this.repository.listMembers(roomId, client),
      );
      if (reason) await this.closeRoomLocked(client, room, reason, uid, now);
      return true;
    });

    if (left) this.logger.info('multiplayer.room.left', { roomId });
    return { success: true };
  }

  async close(uid: string, roomId: string): Promise<{ success: boolean }> {
    const now = this.clock.now();
    await this.repository.transaction(async (client) => {
      const room = await this.lockVisibleRoom(client, uid, roomId, now);
      if (room.host_uid !== uid) throw MultiplayerErrors.notHost();
      if (room.status === 'CLOSED' || room.status === 'EXPIRED') return;
      await this.closeRoomLocked(client, room, 'HOST_CLOSED', uid, now);
    });
    this.logger.info('multiplayer.room.closed', { roomId, reason: 'HOST_CLOSED' });
    return { success: true };
  }

  // ------------------------------------------------------------------ eventos

  async publishEvents(
    uid: string,
    roomId: string,
    request: PublishMultiplayerEventsRequest,
  ): Promise<PublishMultiplayerEventsResponse> {
    const now = this.clock.now();
    const accepted = await this.repository.transaction(async (client) => {
      const room = await this.lockVisibleRoom(client, uid, roomId, now);
      const member = (await this.repository.findMember(roomId, uid, client))!;

      if (room.status === 'CLOSED') throw MultiplayerErrors.roomClosed();
      if (room.status === 'EXPIRED') throw MultiplayerErrors.roomExpired();
      if (member.status === 'INVITED') throw MultiplayerErrors.notAMember();
      if (member.status === 'LEFT') throw MultiplayerErrors.memberLeft();

      const result: Array<{ eventId: string; sequence: number }> = [];
      let finished = false;
      for (const event of request.events) {
        // Mesmo `eventId` → a mesma sequence de antes. Nunca uma segunda linha.
        const existing = await this.repository.findEventByEventId(client, roomId, event.eventId);
        if (existing) {
          result.push({ eventId: event.eventId, sequence: existing.sequence });
          continue;
        }
        if (room.next_sequence > MULTIPLAYER_LIMITS.maxEventsPerRoom) {
          throw MultiplayerErrors.eventLimit();
        }
        const sequence = await this.repository.appendEvent(
          client,
          room,
          { eventId: event.eventId, actorUid: uid, type: event.type, payload: event.payload },
          now,
        );
        result.push({ eventId: event.eventId, sequence });
        if (event.type === 'MEMBER_FINISHED') finished = true;
      }

      if (finished) {
        await this.repository.transitionMember(client, roomId, uid, ['ACTIVE'], 'FINISHED', now);
      }
      await client.query(
        `UPDATE multiplayer_room_members SET last_seen_at = $1 WHERE room_id = $2 AND member_uid = $3`,
        [now, roomId, uid],
      );
      return result;
    });

    this.logger.info('multiplayer.events.published', {
      roomId,
      received: request.events.length,
      appended: accepted.length,
    });

    const room = await this.repository.findRoomById(roomId);
    const members = await this.repository.listMembers(roomId);
    return { accepted, room: this.toRoomDto(room!, members, uid, now) };
  }

  /**
   * Os eventos depois de `after`, esperando até `waitMs` por um novo (long-polling).
   *
   * O poll é também a presença: `last_seen_at` do membro é o instante em que ele perguntou. Entre
   * uma checagem e outra nenhuma conexão do pool fica presa — cada iteração é uma consulta curta.
   */
  async pollEvents(
    uid: string,
    roomId: string,
    after: number,
    waitMs: number,
  ): Promise<MultiplayerEventsPageDto> {
    const startedAt = this.clock.now();
    const initial = await this.requireVisibleRoom(uid, roomId, startedAt);
    const me = initial.members.find((m) => m.member_uid === uid)!;
    if (me.status === 'INVITED') throw MultiplayerErrors.notAMember();
    if (me.status === 'LEFT') throw MultiplayerErrors.memberLeft();
    await this.repository.touchPresence(roomId, uid, startedAt);

    // O prazo da espera é relógio de parede real, e não o `Clock` do domínio: o `Clock` decide
    // expiração e presença (e pode estar congelado num teste); quanto tempo esta resposta pode
    // ficar aberta é uma pergunta sobre a conexão HTTP, não sobre o domínio.
    const deadline = Date.now() + waitMs;
    let room = initial.room;
    let members = initial.members;
    for (;;) {
      const events = await this.repository.listEventsAfter(
        roomId,
        after,
        MULTIPLAYER_LIMITS.maxEventsPerPage + 1,
      );
      const isFinal = room.status === 'CLOSED' || room.status === 'EXPIRED';
      const remaining = deadline - Date.now();
      if (events.length > 0 || isFinal || remaining <= 0) {
        const page = events.slice(0, MULTIPLAYER_LIMITS.maxEventsPerPage);
        return {
          room: this.toRoomDto(room, members, uid, this.clock.now()),
          events: page.map((e) => this.toEventDto(e)),
          cursor: page.length > 0 ? page[page.length - 1].sequence : after,
          hasMore: events.length > MULTIPLAYER_LIMITS.maxEventsPerPage,
        };
      }
      await this.sleep(Math.min(MULTIPLAYER_LIMITS.longPollIntervalMs, remaining));
      const refreshed = await this.repository.findRoomById(roomId);
      if (!refreshed) throw MultiplayerErrors.roomNotFound();
      room = await this.expireIfDue(refreshed, this.clock.now());
      members = await this.repository.listMembers(roomId);
    }
  }

  // ------------------------------------------------------------------ internos

  /** A sala, para quem é membro (convidado inclusive). Qualquer outra pessoa recebe 404. */
  private async requireVisibleRoom(
    uid: string,
    roomId: string,
    now: number,
  ): Promise<{ room: StoredRoom; members: StoredMember[] }> {
    const room = await this.repository.findRoomById(roomId);
    if (!room) throw MultiplayerErrors.roomNotFound();
    const members = await this.repository.listMembers(roomId);
    if (!members.some((m) => m.member_uid === uid)) throw MultiplayerErrors.roomNotFound();
    return { room: await this.expireIfDue(room, now), members };
  }

  private async lockVisibleRoom(
    client: PoolClient,
    uid: string,
    roomId: string,
    now: number,
  ): Promise<StoredRoom> {
    const room = await this.repository.lockRoom(client, roomId);
    if (!room) throw MultiplayerErrors.roomNotFound();
    const member = await this.repository.findMember(roomId, uid, client);
    if (!member) throw MultiplayerErrors.roomNotFound();
    if (this.isDue(room, now)) {
      await this.closeRoomLocked(client, room, 'EXPIRED', room.host_uid, now);
      return { ...room, status: 'EXPIRED', close_reason: 'EXPIRED', closed_at: now };
    }
    return room;
  }

  private isDue(room: StoredRoom, now: number): boolean {
    return (room.status === 'WAITING' || room.status === 'ACTIVE') && now >= room.expires_at;
  }

  /** Expiração preguiçosa fora de transação: abre a própria, sob lock, e relê. */
  private async expireIfDue(room: StoredRoom, now: number): Promise<StoredRoom> {
    if (!this.isDue(room, now)) return room;
    await this.repository.transaction(async (client) => {
      const locked = await this.repository.lockRoom(client, room.id);
      if (locked && this.isDue(locked, now)) {
        await this.closeRoomLocked(client, locked, 'EXPIRED', locked.host_uid, now);
      }
    });
    return (await this.repository.findRoomById(room.id)) ?? room;
  }

  /** Encerra a sala travada e registra o `ROOM_CLOSED` na mesma transação. */
  private async closeRoomLocked(
    client: PoolClient,
    room: StoredRoom,
    reason: MultiplayerCloseReason,
    actorUid: string,
    now: number,
  ): Promise<void> {
    const status = reason === 'EXPIRED' ? 'EXPIRED' : 'CLOSED';
    const changed = await this.repository.updateRoomStatus(
      client,
      room.id,
      ['WAITING', 'ACTIVE'],
      status,
      now,
      reason,
    );
    if (!changed) return;
    await this.repository.appendEvent(
      client,
      room,
      {
        eventId: `sys:closed:${randomUUID()}`,
        actorUid,
        type: 'ROOM_CLOSED',
        payload: { reason },
      },
      now,
    );
  }

  private closeReasonAfterLeave(
    room: StoredRoom,
    leaver: StoredMember,
    members: StoredMember[],
  ): MultiplayerCloseReason | null {
    if (room.status === 'WAITING') {
      return leaver.role === 'HOST' ? 'HOST_LEFT_WAITING' : 'INVITE_DECLINED';
    }
    const remaining = members.filter(
      (m) =>
        m.member_uid !== leaver.member_uid && (m.status === 'ACTIVE' || m.status === 'FINISHED'),
    );
    return remaining.length === 0 ? 'ALL_LEFT' : null;
  }

  private toRoomDto(
    room: StoredRoom,
    members: StoredMember[],
    viewerUid: string,
    now: number,
  ): MultiplayerRoomDto {
    const me = members.find((m) => m.member_uid === viewerUid);
    if (!me) throw MultiplayerErrors.roomNotFound();
    const host = members.find((m) => m.role === 'HOST');
    return {
      roomId: room.id,
      status: room.status,
      closeReason: room.close_reason,
      createdAt: room.created_at,
      updatedAt: room.updated_at,
      expiresAt: room.expires_at,
      hostSocialId: host?.social_id ?? '',
      me: { role: me.role, status: me.status },
      members: members.map((m) => this.toMemberDto(m, now)),
      workout: JSON.parse(room.workout_json) as WorkoutTemplateShareSnapshotV1,
      lastSequence: room.next_sequence - 1,
    };
  }

  private toMemberDto(member: StoredMember, now: number): MultiplayerMemberDto {
    const present = member.status === 'ACTIVE' || member.status === 'FINISHED';
    return {
      socialId: member.social_id,
      displayName: member.display_name,
      role: member.role,
      status: member.status,
      connected:
        present &&
        member.last_seen_at !== null &&
        now - member.last_seen_at < MULTIPLAYER_LIMITS.presenceTtlMs,
      lastSeenAt: member.last_seen_at,
      joinedAt: member.joined_at,
    };
  }

  private toEventDto(event: StoredEvent): MultiplayerEventDto {
    return {
      eventId: event.event_id,
      sequence: event.sequence,
      actorSocialId: event.actor_social_id,
      type: event.type,
      payload: JSON.parse(event.payload_json) as Record<string, unknown>,
      createdAt: event.created_at,
    };
  }
}
