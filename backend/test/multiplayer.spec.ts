import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { join } from 'node:path';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { PostgresService } from '../src/database/postgres.service';
import { AccountDeletionRepository } from '../src/modules/account-deletion/account-deletion.repository';
import { ACCOUNT_UID_COLUMNS } from '../src/modules/account-deletion/account-uid-inventory';
import { MULTIPLAYER_LIMITS } from '../src/modules/multiplayer/multiplayer.contract';
import type { WorkoutTemplateShareSnapshotV1 } from '../src/modules/social/workout-share.contract';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Igor' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'João' },
  C: { token: 'token-c', uid: 'uid-c', email: 'c@example.com', name: 'Carla' },
} as const;

const T0 = Date.UTC(2026, 8, 16, 18, 0, 0);

const WORKOUT: WorkoutTemplateShareSnapshotV1 = {
  snapshotVersion: 1,
  name: 'Peito e tríceps',
  shortIdentifier: 'A',
  exercises: [
    {
      canonicalExerciseId: 'supino-reto-barra',
      sortOrder: 0,
      targetSets: 3,
      minReps: 8,
      maxReps: 12,
      restDurationSeconds: 90,
    },
    {
      canonicalExerciseId: 'triceps-pulley',
      sortOrder: 1,
      targetSets: 3,
      minReps: 10,
      maxReps: 15,
      restDurationSeconds: 60,
    },
  ],
};

const setCompleted = (eventId: string, setNumber: number, exercisePosition = 1) => ({
  eventId,
  type: 'SET_COMPLETED',
  payload: {
    canonicalExerciseId: 'supino-reto-barra',
    exercisePosition,
    setNumber,
    setCount: 3,
    completedAt: T0 + setNumber * 60_000,
  },
});

/**
 * T19.5 — a sala coordena; o servidor não executa.
 *
 * O que estes testes provam: quem pode criar e entrar (amizade, bloqueio, conta), que a sala tem
 * no máximo dois membros por construção, que cada evento tem identidade estável e ordem
 * determinística atribuída pelo servidor, que reenvio é dedupe e não duplicata, que sala encerrada
 * ou expirada recusa evento, que reconectar não duplica membership e que sair é definitivo, que
 * a exclusão de conta leva tudo e fecha a sala do outro, que nenhuma resposta carrega uid — e que
 * peso e repetição são recusados na porta.
 */
describe('Multiplayer remoto: salas, membership e eventos (T19.5)', () => {
  let temp: TempDb;
  let app: INestApplication;
  let verifier: FakeAuthTokenVerifier;
  let clock: FakeClock;

  beforeEach(async () => {
    temp = createTempDb();
    verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    clock = new FakeClock(T0);
    app = await createTestApp(
      configFor(temp.path, {
        DELETION_TOMBSTONES_FILE_PATH: join(temp.directory, 'deletion_tombstones.tsv'),
        ACCOUNT_DELETION_HMAC_KEY: 'test-hmac-key-for-account-deletion-very-secret',
      }),
      verifier,
      undefined,
      clock,
    );
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  async function activate(token: string, displayName: string): Promise<string> {
    const res = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(token))
      .send({ displayName })
      .expect(200);
    return res.body.profile.socialId;
  }

  async function befriend(tokenA: string, tokenB: string, socialIdB: string): Promise<void> {
    const sent = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(tokenA))
      .send({ socialId: socialIdB })
      .expect(200);
    await request(server())
      .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
      .set('Authorization', auth(tokenB))
      .send()
      .expect(200);
  }

  async function friendsAB(): Promise<{ socialA: string; socialB: string }> {
    const socialA = await activate(ACCOUNTS.A.token, 'Igor');
    const socialB = await activate(ACCOUNTS.B.token, 'João');
    await befriend(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);
    return { socialA, socialB };
  }

  const createRoom = (
    token: string,
    inviteeSocialId: string,
    clientRequestId = 'req-1',
    workout: unknown = WORKOUT,
  ) =>
    request(server())
      .post('/v1/multiplayer/rooms')
      .set('Authorization', auth(token))
      .send({ clientRequestId, inviteeSocialId, workout });

  const joinRoom = (token: string, roomId: string) =>
    request(server())
      .post(`/v1/multiplayer/rooms/${roomId}/join`)
      .set('Authorization', auth(token))
      .send();

  const leaveRoom = (token: string, roomId: string) =>
    request(server())
      .post(`/v1/multiplayer/rooms/${roomId}/leave`)
      .set('Authorization', auth(token))
      .send();

  const closeRoom = (token: string, roomId: string) =>
    request(server())
      .post(`/v1/multiplayer/rooms/${roomId}/close`)
      .set('Authorization', auth(token))
      .send();

  const publish = (token: string, roomId: string, events: unknown[]) =>
    request(server())
      .post(`/v1/multiplayer/rooms/${roomId}/events`)
      .set('Authorization', auth(token))
      .send({ events });

  const poll = (token: string, roomId: string, after = 0, wait?: number) =>
    request(server())
      .get(`/v1/multiplayer/rooms/${roomId}/events`)
      .query(wait === undefined ? { after } : { after, wait })
      .set('Authorization', auth(token));

  const getRoom = (token: string, roomId: string) =>
    request(server()).get(`/v1/multiplayer/rooms/${roomId}`).set('Authorization', auth(token));

  async function activeRoom(): Promise<{ roomId: string; socialA: string; socialB: string }> {
    const { socialA, socialB } = await friendsAB();
    const created = await createRoom(ACCOUNTS.A.token, socialB).expect(201);
    await joinRoom(ACCOUNTS.B.token, created.body.roomId).expect(200);
    return { roomId: created.body.roomId, socialA, socialB };
  }

  // ================================================================ auth e criação

  it('toda rota exige token', async () => {
    await request(server()).post('/v1/multiplayer/rooms').send({}).expect(401);
    await request(server()).get('/v1/multiplayer/rooms/invitations').expect(401);
    await request(server()).get('/v1/multiplayer/rooms/x/events').expect(401);
    await request(server()).post('/v1/multiplayer/rooms/x/events').send({}).expect(401);
  });

  it('criar exige perfil social ativo, e o convidado precisa ser amigo', async () => {
    const socialB = await activate(ACCOUNTS.B.token, 'João');
    const noProfile = await createRoom(ACCOUNTS.A.token, socialB).expect(403);
    expect(noProfile.body.error.code).toBe('SOCIAL_NOT_ENABLED');

    await activate(ACCOUNTS.A.token, 'Igor');
    const notFriend = await createRoom(ACCOUNTS.A.token, socialB).expect(403);
    expect(notFriend.body.error.code).toBe('FRIENDSHIP_REQUIRED');

    // Um `socialId` inexistente responde exatamente igual (anti-enumeração).
    const ghost = await createRoom(ACCOUNTS.A.token, 'no-such-social-id').expect(403);
    expect(ghost.body.error.code).toBe(notFriend.body.error.code);
    expect(ghost.body.error.message).toBe(notFriend.body.error.message);
  });

  it('não é possível convidar a si mesmo', async () => {
    const socialA = await activate(ACCOUNTS.A.token, 'Igor');
    const res = await createRoom(ACCOUNTS.A.token, socialA).expect(400);
    expect(res.body.error.code).toBe('MULTIPLAYER_CANNOT_INVITE_SELF');
  });

  it('cria a sala WAITING com o host ACTIVE e o convidado INVITED, e o convite aparece para o convidado', async () => {
    const { socialA, socialB } = await friendsAB();
    const created = await createRoom(ACCOUNTS.A.token, socialB).expect(201);

    expect(created.body).toMatchObject({
      status: 'WAITING',
      closeReason: null,
      hostSocialId: socialA,
      me: { role: 'HOST', status: 'ACTIVE' },
      lastSequence: 0,
      workout: WORKOUT,
    });
    expect(created.body.members).toEqual([
      expect.objectContaining({
        socialId: socialB,
        role: 'GUEST',
        status: 'INVITED',
        connected: false,
      }),
      expect.objectContaining({
        socialId: socialA,
        role: 'HOST',
        status: 'ACTIVE',
        connected: true,
      }),
    ]);
    expect(created.body.expiresAt).toBe(T0 + MULTIPLAYER_LIMITS.waitingTtlMs);

    const invitations = await request(server())
      .get('/v1/multiplayer/rooms/invitations')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(invitations.body).toEqual([
      {
        roomId: created.body.roomId,
        createdAt: T0,
        expiresAt: T0 + MULTIPLAYER_LIMITS.waitingTtlMs,
        host: { socialId: socialA, displayName: 'Igor' },
        workoutName: 'Peito e tríceps',
        exerciseCount: 2,
      },
    ]);

    // O convidado enxerga a sala (para decidir se entra); um terceiro não.
    await getRoom(ACCOUNTS.B.token, created.body.roomId).expect(200);
    await activate(ACCOUNTS.C.token, 'Carla');
    await getRoom(ACCOUNTS.C.token, created.body.roomId).expect(404);
  });

  it('criar é idempotente por clientRequestId, e conflito com parâmetros divergentes', async () => {
    const { socialB } = await friendsAB();
    const first = await createRoom(ACCOUNTS.A.token, socialB, 'req-x').expect(201);
    const replay = await createRoom(ACCOUNTS.A.token, socialB, 'req-x').expect(201);
    expect(replay.body.roomId).toBe(first.body.roomId);

    const divergent = await createRoom(ACCOUNTS.A.token, socialB, 'req-x', {
      ...WORKOUT,
      name: 'Outro treino',
    }).expect(409);
    expect(divergent.body.error.code).toBe('MULTIPLAYER_CONFLICT');
  });

  it('uma sala nova do mesmo host encerra a anterior (SUPERSEDED)', async () => {
    const { socialB } = await friendsAB();
    const first = await createRoom(ACCOUNTS.A.token, socialB, 'req-1').expect(201);
    const second = await createRoom(ACCOUNTS.A.token, socialB, 'req-2').expect(201);
    expect(second.body.roomId).not.toBe(first.body.roomId);

    const old = await getRoom(ACCOUNTS.A.token, first.body.roomId).expect(200);
    expect(old.body).toMatchObject({ status: 'CLOSED', closeReason: 'SUPERSEDED' });
    const events = await poll(ACCOUNTS.A.token, first.body.roomId).expect(200);
    expect(events.body.events.map((e: { type: string }) => e.type)).toEqual(['ROOM_CLOSED']);
  });

  it('o treino da sala é validado: sem carga, sem campo desconhecido, catálogo canônico', async () => {
    const { socialB } = await friendsAB();
    const withWeight = await createRoom(ACCOUNTS.A.token, socialB, 'r1', {
      ...WORKOUT,
      exercises: [{ ...WORKOUT.exercises[0], plannedWeight: 80 }],
    }).expect(400);
    expect(withWeight.body.error.code).toBe('MULTIPLAYER_INVALID_REQUEST');

    await createRoom(ACCOUNTS.A.token, socialB, 'r2', { ...WORKOUT, exercises: [] }).expect(400);
    await createRoom(ACCOUNTS.A.token, socialB, 'r3', {
      ...WORKOUT,
      exercises: [{ ...WORKOUT.exercises[0], canonicalExerciseId: 'has space' }],
    }).expect(400);
    await createRoom(ACCOUNTS.A.token, socialB, 'r4', { ...WORKOUT, snapshotVersion: 2 }).expect(
      400,
    );
  });

  // ================================================================ join / membership

  it('o convidado entra: INVITED → ACTIVE, sala ACTIVE, MEMBER_JOINED na sequence 1, e o rejoin não duplica', async () => {
    const { socialA, socialB } = await friendsAB();
    const created = await createRoom(ACCOUNTS.A.token, socialB).expect(201);
    const roomId = created.body.roomId as string;

    clock.advance(1000);
    const joined = await joinRoom(ACCOUNTS.B.token, roomId).expect(200);
    expect(joined.body).toMatchObject({
      status: 'ACTIVE',
      me: { role: 'GUEST', status: 'ACTIVE' },
      lastSequence: 1,
      expiresAt: T0 + MULTIPLAYER_LIMITS.activeTtlMs,
    });
    expect(joined.body.members).toHaveLength(2);

    // Rejoin (reconnect): a mesma membership, nenhum evento novo.
    const again = await joinRoom(ACCOUNTS.B.token, roomId).expect(200);
    expect(again.body.lastSequence).toBe(1);
    expect(again.body.members).toHaveLength(2);

    const events = await poll(ACCOUNTS.A.token, roomId).expect(200);
    expect(events.body.events).toEqual([
      expect.objectContaining({ sequence: 1, type: 'MEMBER_JOINED', actorSocialId: socialB }),
    ]);
    expect(events.body.room.hostSocialId).toBe(socialA);
  });

  it('um terceiro não entra, não lê e não publica — a sala não existe para ele', async () => {
    const { roomId } = await activeRoom();
    await activate(ACCOUNTS.C.token, 'Carla');
    await joinRoom(ACCOUNTS.C.token, roomId).expect(404);
    await getRoom(ACCOUNTS.C.token, roomId).expect(404);
    await poll(ACCOUNTS.C.token, roomId).expect(404);
    await publish(ACCOUNTS.C.token, roomId, [setCompleted('c-evt-0001', 1)]).expect(404);
    await leaveRoom(ACCOUNTS.C.token, roomId).expect(404);
    await closeRoom(ACCOUNTS.C.token, roomId).expect(404);
  });

  it('a sala tem no máximo dois membros por schema (UNIQUE room_id + role)', async () => {
    const { roomId } = await activeRoom();
    await activate(ACCOUNTS.C.token, 'Carla');
    const db = app.get(PostgresService);
    await expect(
      db.query(
        `INSERT INTO multiplayer_room_members (room_id, member_uid, role, status, invited_at)
         VALUES ($1, $2, 'GUEST', 'INVITED', $3)`,
        [roomId, ACCOUNTS.C.uid, T0],
      ),
    ).rejects.toMatchObject({ code: '23505' });
    await expect(
      db.query(
        `INSERT INTO multiplayer_room_members (room_id, member_uid, role, status, invited_at)
         VALUES ($1, $2, 'SPECTATOR', 'INVITED', $3)`,
        [roomId, ACCOUNTS.C.uid, T0],
      ),
    ).rejects.toMatchObject({ code: '23514' });
  });

  it('bloqueio vence: a sala aberta fecha, o join responde 404 e o convite some', async () => {
    const { socialA, socialB } = await friendsAB();
    const created = await createRoom(ACCOUNTS.A.token, socialB).expect(201);
    const roomId = created.body.roomId as string;

    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ blockedSocialId: socialB })
      .expect(200);

    await joinRoom(ACCOUNTS.B.token, roomId).expect(404);
    const invitations = await request(server())
      .get('/v1/multiplayer/rooms/invitations')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(invitations.body).toEqual([]);

    const room = await getRoom(ACCOUNTS.A.token, roomId).expect(200);
    expect(room.body).toMatchObject({ status: 'CLOSED', closeReason: 'UNAVAILABLE' });
    expect(JSON.stringify(room.body)).not.toContain('BLOCK');

    // E bloquear depois de amigos numa sala ACTIVE fecha a sala também.
    await request(server())
      .delete(`/v1/social/blocks/${socialB}`)
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    await befriend(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);
    const second = await createRoom(ACCOUNTS.A.token, socialB, 'req-2').expect(201);
    await joinRoom(ACCOUNTS.B.token, second.body.roomId).expect(200);
    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ blockedSocialId: socialA })
      .expect(200);
    const closed = await getRoom(ACCOUNTS.A.token, second.body.roomId).expect(200);
    expect(closed.body.status).toBe('CLOSED');
    const denied = await publish(ACCOUNTS.A.token, second.body.roomId, [
      setCompleted('a-evt-0001', 1),
    ]).expect(409);
    expect(denied.body.error.code).toBe('MULTIPLAYER_ROOM_CLOSED');
  });

  it('o convidado recusa (leave enquanto INVITED): a sala fecha com INVITE_DECLINED', async () => {
    const { socialB } = await friendsAB();
    const created = await createRoom(ACCOUNTS.A.token, socialB).expect(201);
    await leaveRoom(ACCOUNTS.B.token, created.body.roomId).expect(200);
    const room = await getRoom(ACCOUNTS.A.token, created.body.roomId).expect(200);
    expect(room.body).toMatchObject({ status: 'CLOSED', closeReason: 'INVITE_DECLINED' });
    // Sala fechada vence sobre qualquer join (T19.5 §10).
    const denied = await joinRoom(ACCOUNTS.B.token, created.body.roomId).expect(409);
    expect(denied.body.error.code).toBe('MULTIPLAYER_ROOM_CLOSED');
  });

  // ================================================================ eventos

  it('eventos ganham sequence do servidor, em ordem de chegada, e o peer os lê na mesma ordem', async () => {
    const { roomId, socialA, socialB } = await activeRoom();

    const a1 = await publish(ACCOUNTS.A.token, roomId, [
      { eventId: 'a-started-0001', type: 'WORKOUT_STARTED', payload: { exerciseCount: 2 } },
      setCompleted('a-set-0001', 1),
    ]).expect(200);
    expect(a1.body.accepted).toEqual([
      { eventId: 'a-started-0001', sequence: 2 },
      { eventId: 'a-set-0001', sequence: 3 },
    ]);
    expect(a1.body.room.lastSequence).toBe(3);

    const b1 = await publish(ACCOUNTS.B.token, roomId, [setCompleted('b-set-0001', 1)]).expect(200);
    expect(b1.body.accepted).toEqual([{ eventId: 'b-set-0001', sequence: 4 }]);

    const a2 = await publish(ACCOUNTS.A.token, roomId, [setCompleted('a-set-0002', 2)]).expect(200);
    expect(a2.body.accepted).toEqual([{ eventId: 'a-set-0002', sequence: 5 }]);

    const fromB = await poll(ACCOUNTS.B.token, roomId, 1).expect(200);
    expect(
      fromB.body.events.map((e: { sequence: number; actorSocialId: string; type: string }) => [
        e.sequence,
        e.actorSocialId,
        e.type,
      ]),
    ).toEqual([
      [2, socialA, 'WORKOUT_STARTED'],
      [3, socialA, 'SET_COMPLETED'],
      [4, socialB, 'SET_COMPLETED'],
      [5, socialA, 'SET_COMPLETED'],
    ]);
    expect(fromB.body.cursor).toBe(5);
    expect(fromB.body.hasMore).toBe(false);
    expect(fromB.body.events[1].payload).toEqual(setCompleted('a-set-0001', 1).payload);

    // O cursor é o contrato de paginação: nada antes dele volta.
    const tail = await poll(ACCOUNTS.B.token, roomId, 4).expect(200);
    expect(tail.body.events.map((e: { sequence: number }) => e.sequence)).toEqual([5]);
    const nothing = await poll(ACCOUNTS.B.token, roomId, 5).expect(200);
    expect(nothing.body.events).toEqual([]);
    expect(nothing.body.cursor).toBe(5);
  });

  it('o mesmo eventId reenviado devolve a mesma sequence e não cria segunda linha', async () => {
    const { roomId } = await activeRoom();
    const first = await publish(ACCOUNTS.A.token, roomId, [setCompleted('a-set-0001', 1)]).expect(
      200,
    );
    const replay = await publish(ACCOUNTS.A.token, roomId, [
      setCompleted('a-set-0001', 1),
      setCompleted('a-set-0002', 2),
    ]).expect(200);
    expect(replay.body.accepted).toEqual([
      { eventId: 'a-set-0001', sequence: first.body.accepted[0].sequence },
      { eventId: 'a-set-0002', sequence: first.body.accepted[0].sequence + 1 },
    ]);

    const page = await poll(ACCOUNTS.B.token, roomId, 1).expect(200);
    expect(page.body.events.map((e: { eventId: string }) => e.eventId)).toEqual([
      'a-set-0001',
      'a-set-0002',
    ]);

    // O mesmo lote com um eventId repetido dentro dele é malformado.
    await publish(ACCOUNTS.A.token, roomId, [
      setCompleted('a-set-0003', 3),
      setCompleted('a-set-0003', 3),
    ]).expect(400);
  });

  it('peso, repetição, RPE, PR, XP e uid são recusados no payload — por nome', async () => {
    const { roomId } = await activeRoom();
    for (const [key, value] of [
      ['weight', 80],
      ['repetitions', 10],
      ['reps', 10],
      ['rpe', 8],
      ['rir', 2],
      ['notes', 'x'],
      ['pr', true],
      ['xp', 50],
      ['uid', 'uid-a'],
      ['syncId', 'abc'],
      ['anythingElse', 1],
    ] as const) {
      const res = await publish(ACCOUNTS.A.token, roomId, [
        {
          ...setCompleted('a-set-0001', 1),
          payload: { ...setCompleted('a-set-0001', 1).payload, [key]: value },
        },
      ]).expect(400);
      expect(res.body.error.code).toBe('MULTIPLAYER_INVALID_REQUEST');
    }
    // Tipos de sistema não podem ser publicados por cliente.
    await publish(ACCOUNTS.A.token, roomId, [
      { eventId: 'a-fake-joined', type: 'MEMBER_JOINED', payload: {} },
    ]).expect(400);
    await publish(ACCOUNTS.A.token, roomId, [
      { eventId: 'a-fake-closed', type: 'ROOM_CLOSED', payload: {} },
    ]).expect(400);
    // setNumber acima de setCount, eventId curto demais, lote vazio.
    await publish(ACCOUNTS.A.token, roomId, [
      {
        ...setCompleted('a-set-0009', 9),
        payload: { ...setCompleted('a-set-0009', 9).payload, setNumber: 9 },
      },
    ]).expect(400);
    await publish(ACCOUNTS.A.token, roomId, [setCompleted('abc', 1)]).expect(400);
    await publish(ACCOUNTS.A.token, roomId, []).expect(400);

    const page = await poll(ACCOUNTS.B.token, roomId, 1).expect(200);
    expect(page.body.events).toEqual([]);
  });

  it('quem ainda é INVITED não publica nem faz poll; quem saiu recebe MEMBER_LEFT e não volta', async () => {
    const { socialB } = await friendsAB();
    const created = await createRoom(ACCOUNTS.A.token, socialB).expect(201);
    const roomId = created.body.roomId as string;

    const notMember = await publish(ACCOUNTS.B.token, roomId, [
      setCompleted('b-set-0001', 1),
    ]).expect(403);
    expect(notMember.body.error.code).toBe('MULTIPLAYER_NOT_A_MEMBER');
    await poll(ACCOUNTS.B.token, roomId).expect(403);

    // O host pode publicar enquanto espera: o treino dele já começou.
    await publish(ACCOUNTS.A.token, roomId, [
      { eventId: 'a-started-0001', type: 'WORKOUT_STARTED', payload: { exerciseCount: 2 } },
    ]).expect(200);

    await joinRoom(ACCOUNTS.B.token, roomId).expect(200);
    await leaveRoom(ACCOUNTS.B.token, roomId).expect(200);
    await leaveRoom(ACCOUNTS.B.token, roomId).expect(200); // idempotente

    const left = await publish(ACCOUNTS.B.token, roomId, [setCompleted('b-set-0001', 1)]).expect(
      409,
    );
    expect(left.body.error.code).toBe('MULTIPLAYER_MEMBER_LEFT');
    await poll(ACCOUNTS.B.token, roomId).expect(409);
    await joinRoom(ACCOUNTS.B.token, roomId).expect(409);

    // O host continua: a sala fica ACTIVE com ele, e ele vê o MEMBER_LEFT em ordem.
    const page = await poll(ACCOUNTS.A.token, roomId).expect(200);
    expect(page.body.room.status).toBe('ACTIVE');
    expect(page.body.events.map((e: { type: string }) => e.type)).toEqual([
      'WORKOUT_STARTED',
      'MEMBER_JOINED',
      'MEMBER_LEFT',
    ]);
    expect(page.body.room.members).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ role: 'GUEST', status: 'LEFT', connected: false }),
      ]),
    );
  });

  it('finish é individual: MEMBER_FINISHED marca só quem terminou, e o outro continua publicando', async () => {
    const { roomId } = await activeRoom();
    await publish(ACCOUNTS.A.token, roomId, [
      setCompleted('a-set-0001', 1),
      { eventId: 'a-finished-001', type: 'MEMBER_FINISHED', payload: {} },
    ]).expect(200);

    const room = await getRoom(ACCOUNTS.B.token, roomId).expect(200);
    expect(room.body.status).toBe('ACTIVE');
    expect(room.body.members).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ role: 'HOST', status: 'FINISHED' }),
        expect.objectContaining({ role: 'GUEST', status: 'ACTIVE' }),
      ]),
    );

    await publish(ACCOUNTS.B.token, roomId, [setCompleted('b-set-0001', 1)]).expect(200);
    // Quem terminou ainda pode reenviar (dedupe) e ainda lê o peer.
    await publish(ACCOUNTS.A.token, roomId, [setCompleted('a-set-0001', 1)]).expect(200);
    await poll(ACCOUNTS.A.token, roomId).expect(200);

    // Os dois saem: a sala fecha sozinha (ALL_LEFT).
    await leaveRoom(ACCOUNTS.A.token, roomId).expect(200);
    expect((await getRoom(ACCOUNTS.B.token, roomId)).body.status).toBe('ACTIVE');
    await leaveRoom(ACCOUNTS.B.token, roomId).expect(200);
    const closed = await getRoom(ACCOUNTS.B.token, roomId).expect(200);
    expect(closed.body).toMatchObject({ status: 'CLOSED', closeReason: 'ALL_LEFT' });
  });

  it('sala encerrada pelo host recusa eventos e join; encerrar é do host e é idempotente', async () => {
    const { roomId } = await activeRoom();
    const notHost = await closeRoom(ACCOUNTS.B.token, roomId).expect(403);
    expect(notHost.body.error.code).toBe('MULTIPLAYER_NOT_HOST');

    await closeRoom(ACCOUNTS.A.token, roomId).expect(200);
    await closeRoom(ACCOUNTS.A.token, roomId).expect(200);

    const denied = await publish(ACCOUNTS.B.token, roomId, [setCompleted('b-set-0001', 1)]).expect(
      409,
    );
    expect(denied.body.error.code).toBe('MULTIPLAYER_ROOM_CLOSED');
    await joinRoom(ACCOUNTS.B.token, roomId).expect(409);

    // O poll de quem ficou devolve o estado final e o ROOM_CLOSED em ordem, sem esperar.
    const page = await poll(ACCOUNTS.B.token, roomId, 0, 5000).expect(200);
    expect(page.body.room).toMatchObject({ status: 'CLOSED', closeReason: 'HOST_CLOSED' });
    expect(page.body.events.map((e: { type: string }) => e.type)).toEqual([
      'MEMBER_JOINED',
      'ROOM_CLOSED',
    ]);
    expect(page.body.events[1].payload).toEqual({ reason: 'HOST_CLOSED' });
  });

  it('sala expirada falha de forma explícita (WAITING e ACTIVE), com o relógio do servidor', async () => {
    const { socialB } = await friendsAB();
    const waiting = await createRoom(ACCOUNTS.A.token, socialB, 'req-w').expect(201);
    clock.advance(MULTIPLAYER_LIMITS.waitingTtlMs + 1);
    const expired = await joinRoom(ACCOUNTS.B.token, waiting.body.roomId).expect(409);
    expect(expired.body.error.code).toBe('MULTIPLAYER_ROOM_EXPIRED');
    expect((await getRoom(ACCOUNTS.A.token, waiting.body.roomId)).body.status).toBe('EXPIRED');
    const invitations = await request(server())
      .get('/v1/multiplayer/rooms/invitations')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(invitations.body).toEqual([]);

    const active = await createRoom(ACCOUNTS.A.token, socialB, 'req-a').expect(201);
    await joinRoom(ACCOUNTS.B.token, active.body.roomId).expect(200);
    clock.advance(MULTIPLAYER_LIMITS.activeTtlMs + 1);
    const denied = await publish(ACCOUNTS.A.token, active.body.roomId, [
      setCompleted('a-set-0001', 1),
    ]).expect(409);
    expect(denied.body.error.code).toBe('MULTIPLAYER_ROOM_EXPIRED');
    const page = await poll(ACCOUNTS.B.token, active.body.roomId, 1).expect(200);
    expect(page.body.room).toMatchObject({ status: 'EXPIRED', closeReason: 'EXPIRED' });
    expect(page.body.events.map((e: { type: string }) => e.type)).toEqual(['ROOM_CLOSED']);
  });

  it('presença é derivada do último poll', async () => {
    const { roomId } = await activeRoom();
    clock.advance(1000);
    await poll(ACCOUNTS.B.token, roomId, 1).expect(200);
    const fresh = await getRoom(ACCOUNTS.A.token, roomId).expect(200);
    expect(fresh.body.members.find((m: { role: string }) => m.role === 'GUEST').connected).toBe(
      true,
    );

    clock.advance(MULTIPLAYER_LIMITS.presenceTtlMs + 1);
    const stale = await getRoom(ACCOUNTS.A.token, roomId).expect(200);
    expect(stale.body.members.find((m: { role: string }) => m.role === 'GUEST').connected).toBe(
      false,
    );
    // O host que acabou de perguntar continua conectado só se também fez poll.
    expect(stale.body.members.find((m: { role: string }) => m.role === 'HOST').connected).toBe(
      false,
    );
    await poll(ACCOUNTS.A.token, roomId, 1).expect(200);
    const back = await getRoom(ACCOUNTS.A.token, roomId).expect(200);
    expect(back.body.members.find((m: { role: string }) => m.role === 'HOST').connected).toBe(true);
  });

  it('long-poll: a resposta sai assim que o peer publica, e sem novidade sai no teto pedido', async () => {
    const { roomId } = await activeRoom();

    const startedAt = Date.now();
    const pending = poll(ACCOUNTS.B.token, roomId, 1, 8000).expect(200);
    await new Promise((resolve) => setTimeout(resolve, 300));
    await publish(ACCOUNTS.A.token, roomId, [setCompleted('a-set-0001', 1)]).expect(200);
    const page = await pending;
    expect(Date.now() - startedAt).toBeLessThan(6000);
    expect(page.body.events.map((e: { eventId: string }) => e.eventId)).toEqual(['a-set-0001']);

    const quietStart = Date.now();
    const quiet = await poll(ACCOUNTS.B.token, roomId, 2, 1200).expect(200);
    expect(quiet.body.events).toEqual([]);
    expect(Date.now() - quietStart).toBeGreaterThanOrEqual(1000);

    // `wait` acima do teto é rebaixado, e `after`/`wait` malformados são 400.
    await poll(ACCOUNTS.B.token, roomId, 2, 0).expect(200);
    await request(server())
      .get(`/v1/multiplayer/rooms/${roomId}/events`)
      .query({ after: 'x' })
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(400);
  });

  // ================================================================ isolamento e exclusão

  it('salas de contas diferentes não se enxergam, e a listagem de convites é por conta', async () => {
    const { socialB } = await friendsAB();
    const socialC = await activate(ACCOUNTS.C.token, 'Carla');
    await befriend(ACCOUNTS.A.token, ACCOUNTS.C.token, socialC);

    const forB = await createRoom(ACCOUNTS.A.token, socialB, 'req-b').expect(201);
    // A segunda sala do host substitui a primeira — é a regra "uma sala aberta por host".
    const forC = await createRoom(ACCOUNTS.A.token, socialC, 'req-c').expect(201);

    await getRoom(ACCOUNTS.C.token, forB.body.roomId).expect(404);
    await getRoom(ACCOUNTS.B.token, forC.body.roomId).expect(404);
    const invitationsC = await request(server())
      .get('/v1/multiplayer/rooms/invitations')
      .set('Authorization', auth(ACCOUNTS.C.token))
      .expect(200);
    expect(invitationsC.body.map((i: { roomId: string }) => i.roomId)).toEqual([forC.body.roomId]);
  });

  it('exclusão da conta do convidado fecha a sala do host e apaga todo rastro dela', async () => {
    const { roomId } = await activeRoom();
    await publish(ACCOUNTS.B.token, roomId, [setCompleted('b-set-0001', 1)]).expect(200);

    const deleted = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(deleted.body.status).toBe('DELETED');

    const room = await getRoom(ACCOUNTS.A.token, roomId).expect(200);
    expect(room.body).toMatchObject({ status: 'CLOSED', closeReason: 'UNAVAILABLE' });
    expect(room.body.members.map((m: { role: string }) => m.role)).toEqual(['HOST']);
    const page = await poll(ACCOUNTS.A.token, roomId).expect(200);
    expect(page.body.events.map((e: { type: string }) => e.type)).toEqual([]);

    const repository = app.get(AccountDeletionRepository);
    expect(await repository.listAllOwnerUidsInDatabase()).not.toContain(ACCOUNTS.B.uid);
    expect(ACCOUNT_UID_COLUMNS).toEqual(
      expect.arrayContaining([
        { table: 'multiplayer_rooms', column: 'host_uid', role: 'OWNER' },
        { table: 'multiplayer_room_members', column: 'member_uid', role: 'OWNER' },
        { table: 'multiplayer_room_events', column: 'actor_uid', role: 'OWNER' },
      ]),
    );
  });

  it('exclusão da conta do host apaga a sala inteira; o convidado não a encontra mais', async () => {
    const { roomId } = await activeRoom();
    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    await getRoom(ACCOUNTS.B.token, roomId).expect(404);
    await poll(ACCOUNTS.B.token, roomId).expect(404);
    const db = app.get(PostgresService);
    const rows = await db.query(`SELECT 1 FROM multiplayer_rooms WHERE id = $1`, [roomId]);
    expect(rows.rows).toHaveLength(0);
  });

  it('nenhuma resposta do multiplayer carrega uid, e-mail, deviceId ou syncId', async () => {
    const { roomId } = await activeRoom();
    await publish(ACCOUNTS.A.token, roomId, [
      { eventId: 'a-started-0001', type: 'WORKOUT_STARTED', payload: { exerciseCount: 2 } },
      setCompleted('a-set-0001', 1),
    ]).expect(200);

    const responses = [
      (await getRoom(ACCOUNTS.A.token, roomId)).body,
      (await poll(ACCOUNTS.B.token, roomId)).body,
      (await publish(ACCOUNTS.B.token, roomId, [setCompleted('b-set-0001', 1)])).body,
      (
        await request(server())
          .get('/v1/multiplayer/rooms/invitations')
          .set('Authorization', auth(ACCOUNTS.B.token))
      ).body,
    ];
    const forbidden = [
      'uid',
      'owneruid',
      'firebaseuid',
      'hostuid',
      'memberuid',
      'actoruid',
      'email',
      'deviceid',
      'syncid',
    ];
    const offenders: string[] = [];
    const walk = (value: unknown, path: string) => {
      if (Array.isArray(value)) value.forEach((v, i) => walk(v, `${path}[${i}]`));
      else if (value && typeof value === 'object') {
        for (const [key, v] of Object.entries(value)) {
          if (forbidden.includes(key.toLowerCase())) offenders.push(`${path}.${key}`);
          walk(v, `${path}.${key}`);
        }
      }
    };
    responses.forEach((r, i) => walk(r, `response[${i}]`));
    expect(offenders).toEqual([]);
    const text = JSON.stringify(responses);
    expect(text).not.toContain(ACCOUNTS.A.uid);
    expect(text).not.toContain(ACCOUNTS.B.uid);
    expect(text).not.toContain('@example.com');
  });
});
