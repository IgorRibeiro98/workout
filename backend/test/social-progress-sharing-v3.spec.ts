import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PostgresService } from '../src/database/postgres.service';
import { projectWorkoutSummary } from '../src/modules/social/social-workout-summary';
import { trainingTotals, workoutMetrics } from '../src/modules/social/social-training-metrics';
import {
  PROGRESS_SHARING_FLAGS,
  SOCIAL_PROGRESS_SHARING_DEFAULTS,
  type ProgressSharingFlags,
} from '../src/modules/social/social-progress.repository';
import type { SocialWorkoutFacts } from '../src/modules/social/social-workout-facts.source';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';

const TOKEN_A = 'token-da-conta-a';
const UID_A = 'uid-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_B = 'uid-da-conta-b';
const TOKEN_C = 'token-da-conta-c';
const UID_C = 'uid-da-conta-c';

/** Terça-feira, 15h em São Paulo — a semana canônica vai de segunda 07/09 a domingo 13/09. */
const NOW = Date.parse('2026-09-08T18:00:00Z');
const TZ = 'America/Sao_Paulo';

/** Marcadores de conteúdo privado: nenhum deles pode aparecer em resposta social nenhuma. */
const PRIVATE = {
  sessionNote: 'NOTA-PRIVADA-DA-SESSAO',
  exerciseNote: 'NOTA-PRIVADA-DO-EXERCICIO',
  machineLabel: 'MAQUINA-SECRETA-7',
  replacementReason: 'MOTIVO-PRIVADO-DA-TROCA',
  templateSyncId: 'template-sync-id-secreto',
  customExerciseSyncId: 'custom-exercise-sync-id-secreto',
};

const set = (
  weight: number,
  repetitions: number,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> => ({
  setNumber: 1,
  type: 'NORMAL',
  weight,
  repetitions,
  completed: true,
  startedAt: null,
  finishedAt: null,
  rpe: 8.5,
  rir: 2,
  durationSeconds: null,
  ...overrides,
});

const exercise = (
  name: string,
  sets: Record<string, unknown>[],
  overrides: Record<string, unknown> = {},
): Record<string, unknown> => ({
  plannedOrder: 0,
  executionOrder: 0,
  exerciseNameSnapshot: name,
  plannedExercise: null,
  actualExercise: null,
  machineLabelSnapshot: PRIVATE.machineLabel,
  primaryMuscleSnapshot: null,
  restDurationSecondsSnapshot: 90,
  startedAt: null,
  finishedAt: null,
  notes: PRIVATE.exerciseNote,
  replacementReason: PRIVATE.replacementReason,
  sets,
  ...overrides,
});

/**
 * "Superiores A", segunda 07/09, 19:57 → 20:52 em São Paulo: 55 minutos (§59).
 *
 * Supino: aquecimento (não conta) + 3 × 80 kg × 10 + uma série não concluída (não conta).
 * Remada: 3 × 60 kg × 12. Prancha: uma série de 60 s (conta como série, volume zero). Barra fixa
 * (exercício CUSTOM): peso corporal × 8 (conta como série, volume zero). Leg press: planejado e
 * pulado (não aparece).
 *
 * Séries de trabalho: 3 + 3 + 1 + 1 = 8. Volume: 2400 + 2160 = 4560 kg. Exercícios: 4.
 */
const UPPER_A = {
  startedAt: Date.parse('2026-09-07T22:57:00Z'),
  finishedAt: Date.parse('2026-09-07T23:52:00Z'),
  payload: (syncId: string) =>
    sessionPayload(syncId, {
      templateSyncId: PRIVATE.templateSyncId,
      templateNameSnapshot: 'Superiores A',
      startedAt: Date.parse('2026-09-07T22:57:00Z'),
      finishedAt: Date.parse('2026-09-07T23:52:00Z'),
      notes: PRIVATE.sessionNote,
      exercises: [
        exercise(
          'Supino reto',
          [
            set(20, 15, { type: 'WARMUP' }),
            set(80, 10),
            set(80, 10),
            set(80, 10),
            set(80, 8, { completed: false }),
          ],
          { primaryMuscleSnapshot: 'CHEST' },
        ),
        exercise('Remada curvada', [set(60, 12), set(60, 12), set(60, 12)], {
          primaryMuscleSnapshot: 'BACK',
        }),
        exercise('Prancha', [set(0, 0, { durationSeconds: 60 })]),
        exercise('Barra fixa na porta', [set(0, 8)], {
          actualExercise: { kind: 'CUSTOM', id: PRIVATE.customExerciseSyncId },
        }),
        exercise('Leg press', [set(120, 10, { completed: false })]),
      ],
    }),
};

/** Terça 07:00 em São Paulo, 30 min: 3 × 100 kg × 5 = 1500 kg, 3 séries. */
const MORNING = {
  startedAt: Date.parse('2026-09-08T10:00:00Z'),
  payload: (syncId: string) =>
    sessionPayload(syncId, {
      templateNameSnapshot: 'Pernas',
      startedAt: Date.parse('2026-09-08T10:00:00Z'),
      finishedAt: Date.parse('2026-09-08T10:30:00Z'),
      exercises: [exercise('Agachamento', [set(100, 5), set(100, 5), set(100, 5)])],
    }),
};

/** Sábado da semana anterior: conta no total, e não na semana. */
const LAST_WEEK = {
  payload: (syncId: string) =>
    sessionPayload(syncId, {
      startedAt: Date.parse('2026-09-05T12:00:00Z'),
      finishedAt: Date.parse('2026-09-05T12:45:00Z'),
      exercises: [exercise('Rosca', [set(50, 10), set(50, 10)])],
    }),
};

const ALL_ON: ProgressSharingFlags = Object.fromEntries(
  PROGRESS_SHARING_FLAGS.map(([flag]) => [flag, true]),
) as ProgressSharingFlags;

/**
 * Compartilhar Progresso V3 (T19.H3 §20–§47): estatísticas agregadas no perfil e o resumo de
 * treino no check-in — derivados da `WORKOUT_SESSION` canônica e filtrados no servidor.
 *
 * O que estes testes protegem, acima de tudo:
 *
 * 1. **nada nasce ligado** — nem para conta nova, nem para conta que já existia (0008);
 * 2. **o valor sai do servidor** — o cliente só manda preferência, e um valor é recusado por nome;
 * 3. **campo desligado não existe no JSON** — não é escondido depois, e desligar vale para trás;
 * 4. **nada privado atravessa** — nota, `machineLabel`, motivo de troca, RPE, RIR, identificador;
 * 5. **o Feed continua bounded** — o número de consultas não cresce com o número de publicações.
 */
describe('Compartilhar Progresso V3 (T19.H3)', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    jest.spyOn(Date, 'now').mockReturnValue(NOW);
    app = await createTestApp(
      configFor(temp.path),
      new FakeAuthTokenVerifier()
        .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
        .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' })
        .accept(TOKEN_C, { uid: UID_C, email: 'c@example.com' }),
      undefined,
      new FakeClock(NOW),
    );
  });

  afterEach(async () => {
    jest.restoreAllMocks();
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  const activate = async (token: string, displayName: string): Promise<string> => {
    const res = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(token))
      .send({ displayName })
      .expect(200);
    return res.body.profile.socialId as string;
  };

  const befriend = async (tokenOne: string, tokenTwo: string, socialIdTwo: string) => {
    const sent = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(tokenOne))
      .send({ socialId: socialIdTwo })
      .expect(200);
    await request(server())
      .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
      .set('Authorization', auth(tokenTwo))
      .expect(200);
  };

  const push = async (token: string, payload: (syncId: string) => Record<string, unknown>) => {
    const syncId = uuid();
    const res = await request(server())
      .post('/v1/sync/push')
      .set('Authorization', auth(token))
      .set('Content-Type', 'application/json')
      .send(
        pushBody([
          { entityType: 'WORKOUT_SESSION', entitySyncId: syncId, payload: payload(syncId) },
        ]),
      )
      .expect(200);
    expect(res.body.results[0].status).toBe('APPLIED');
    return syncId;
  };

  const checkIn = async (token: string, sessionSyncId: string): Promise<string> => {
    const res = await request(server())
      .post('/v1/social/workout-checkins')
      .set('Authorization', auth(token))
      .send({ sessionSyncId, clientRequestId: uuid() })
      .expect(201);
    return res.body.checkInId as string;
  };

  const patchSharing = (token: string, body: object) =>
    request(server())
      .patch('/v1/social/me/progress-sharing')
      .set('Authorization', auth(token))
      .send(body);

  const sharing = (token: string) =>
    request(server()).get('/v1/social/me/progress-sharing').set('Authorization', auth(token));

  const feed = (token: string) =>
    request(server()).get('/v1/social/feed').set('Authorization', auth(token)).expect(200);

  const friendProfile = (token: string, socialId: string) =>
    request(server())
      .get(`/v1/social/friends/${socialId}/profile`)
      .set('Authorization', auth(token));

  /** A (Ana) e B (Bruno) amigos; A com os três treinos sincronizados e um check-in do "Superiores A". */
  const scenario = async () => {
    const socialIdA = await activate(TOKEN_A, 'Ana');
    const socialIdB = await activate(TOKEN_B, 'Bruno');
    await befriend(TOKEN_A, TOKEN_B, socialIdB);
    await patchSharing(TOKEN_A, { weekTimeZone: TZ }).expect(200);
    const upperA = await push(TOKEN_A, UPPER_A.payload);
    await push(TOKEN_A, MORNING.payload);
    await push(TOKEN_A, LAST_WEEK.payload);
    const checkInId = await checkIn(TOKEN_A, upperA);
    return { socialIdA, socialIdB, checkInId, upperA };
  };

  const summaryInFeedOf = async (token: string, checkInId: string) => {
    const res = await feed(token);
    const item = (res.body.items as Array<Record<string, unknown>>).find(
      (candidate) => candidate.checkInId === checkInId,
    );
    expect(item).toBeDefined();
    return item?.workoutSummary as Record<string, unknown> | undefined;
  };

  // ------------------------------------------------------------------ defaults (§24/§27)

  describe('nada nasce ligado', () => {
    it('conta nova: os onze interruptores novos chegam false, e nada novo é publicado', async () => {
      const { socialIdA, checkInId } = await scenario();

      const owner = await sharing(TOKEN_A).expect(200);
      for (const [flag] of PROGRESS_SHARING_FLAGS) {
        expect({ flag, value: owner.body.settings[flag] }).toEqual({ flag, value: false });
      }

      const profile = await friendProfile(TOKEN_B, socialIdA).expect(200);
      expect(profile.body.profile.sharedProgress).toEqual({});

      const item = (await feed(TOKEN_B)).body.items[0];
      expect(item.checkInId).toBe(checkInId);
      expect('workoutSummary' in item).toBe(false);
    });

    it('conta que já existia antes da 0008: as colunas novas recebem FALSE do DEFAULT', async () => {
      await activate(TOKEN_A, 'Ana');
      const postgres = app.get(PostgresService);
      // O que a linha de uma conta antiga tinha: só as colunas da T17.2, uma delas ligada.
      await postgres.query(`DELETE FROM social_progress_settings WHERE owner_uid = $1`, [UID_A]);
      await postgres.query(
        `INSERT INTO social_progress_settings (owner_uid, share_level, updated_at) VALUES ($1, TRUE, 1)`,
        [UID_A],
      );

      const owner = await sharing(TOKEN_A).expect(200);
      expect(owner.body.settings.shareLevel).toBe(true);
      for (const [flag] of PROGRESS_SHARING_FLAGS.slice(4)) {
        expect({ flag, value: owner.body.settings[flag] }).toEqual({ flag, value: false });
      }
      expect(SOCIAL_PROGRESS_SHARING_DEFAULTS.shareWorkoutWeights).toBe(false);
    });
  });

  // ------------------------------------------------------------------ PATCH (§21/§46)

  describe('o cliente manda preferência, nunca valor', () => {
    it.each([
      ['weeklyVolumeKg', 5000],
      ['weeklyTrainingMinutes', 90],
      ['weeklyCompletedSets', 12],
      ['totalWorkouts', 200],
      ['workoutSummary', { totalVolumeKg: 1 }],
      ['exercises', []],
    ])('recusa %s por nome', async (field, value) => {
      await activate(TOKEN_A, 'Ana');
      const res = await patchSharing(TOKEN_A, { [field]: value }).expect(400);
      expect(res.body.error.code).toBe('INVALID_PROGRESS_SETTINGS');
    });

    it('cada interruptor novo é aceito sozinho, e só ele muda', async () => {
      await activate(TOKEN_A, 'Ana');
      for (const [flag] of PROGRESS_SHARING_FLAGS.slice(4)) {
        const res = await patchSharing(TOKEN_A, { [flag]: true }).expect(200);
        expect(res.body.settings[flag]).toBe(true);
        await patchSharing(TOKEN_A, { [flag]: false }).expect(200);
      }
      const owner = await sharing(TOKEN_A).expect(200);
      for (const [flag] of PROGRESS_SHARING_FLAGS) {
        expect(owner.body.settings[flag]).toBe(false);
      }
    });

    it('booleano é booleano, e repetir o mesmo PATCH converge no mesmo estado', async () => {
      await activate(TOKEN_A, 'Ana');
      await patchSharing(TOKEN_A, { shareWorkoutWeights: 'true' }).expect(400);

      const first = await patchSharing(TOKEN_A, { shareWorkoutExercises: true }).expect(200);
      const second = await patchSharing(TOKEN_A, { shareWorkoutExercises: true }).expect(200);
      expect({ ...second.body.settings, updatedAt: 0 }).toEqual({
        ...first.body.settings,
        updatedAt: 0,
      });
    });
  });

  // ------------------------------------------------------------------ agregado (§25)

  describe('estatísticas de treino no perfil', () => {
    it('cada estatística aparece só quando ligada, com o valor derivado das sessões', async () => {
      const { socialIdA } = await scenario();

      const expected: Array<[string, string, number]> = [
        ['shareWeeklyTrainingMinutes', 'weeklyTrainingMinutes', 55 + 30],
        ['shareWeeklyCompletedSets', 'weeklyCompletedSets', 8 + 3],
        ['shareWeeklyVolume', 'weeklyVolumeKg', 4560 + 1500],
        ['shareTotalWorkouts', 'totalWorkouts', 3],
      ];
      for (const [flag, field, value] of expected) {
        await patchSharing(TOKEN_A, { [flag]: true }).expect(200);
        const profile = await friendProfile(TOKEN_B, socialIdA).expect(200);
        expect(profile.body.profile.sharedProgress).toEqual({ [field]: value });
        await patchSharing(TOKEN_A, { [flag]: false }).expect(200);
      }

      const owner = await sharing(TOKEN_A).expect(200);
      expect(owner.body.availability).toMatchObject({
        weeklyTrainingMinutes: 'AVAILABLE',
        weeklyCompletedSets: 'AVAILABLE',
        weeklyVolume: 'AVAILABLE',
        totalWorkouts: 'AVAILABLE',
      });
    });

    it('a prévia do dono mostra exatamente o que o amigo recebe', async () => {
      const { socialIdA } = await scenario();
      await patchSharing(TOKEN_A, { shareWeeklyVolume: true, shareTotalWorkouts: true }).expect(
        200,
      );

      const preview = await request(server())
        .get('/v1/social/me/profile-preview')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
      const friend = await friendProfile(TOKEN_B, socialIdA).expect(200);
      expect(preview.body.profile.sharedProgress).toEqual(friend.body.profile.sharedProgress);
      expect(friend.body.profile.sharedProgress).toEqual({
        weeklyVolumeKg: 6060,
        totalWorkouts: 3,
      });
    });
  });

  // ------------------------------------------------------------------ check-in (§28–§37)

  describe('resumo de treino no check-in', () => {
    it('cada detalhe aparece só com o próprio interruptor', async () => {
      const { checkInId } = await scenario();

      const cases: Array<[Record<string, boolean>, Record<string, unknown>]> = [
        [{ shareWorkoutName: true }, { name: 'Superiores A' }],
        [{ shareWorkoutTime: true }, { startedAt: UPPER_A.startedAt }],
        [{ shareWorkoutDuration: true }, { durationSeconds: 55 * 60 }],
        [{ shareWorkoutVolume: true }, { totalVolumeKg: 4560 }],
        [{ shareWorkoutSets: true }, { completedSetCount: 8 }],
        [
          { shareWorkoutExercises: true },
          {
            exerciseCount: 4,
            exercises: [
              { name: 'Supino reto', primaryMuscle: 'CHEST' },
              { name: 'Remada curvada', primaryMuscle: 'BACK' },
              { name: 'Prancha' },
              { name: 'Barra fixa na porta' },
            ],
          },
        ],
        // Cargas sozinha não publica nada: carga sem série não tem onde aparecer (§31).
        [{ shareWorkoutWeights: true }, {}],
      ];

      for (const [flags, expected] of cases) {
        const reset = Object.fromEntries(PROGRESS_SHARING_FLAGS.slice(8).map(([f]) => [f, false]));
        await patchSharing(TOKEN_A, { ...reset, ...flags }).expect(200);
        const summary = await summaryInFeedOf(TOKEN_B, checkInId);
        if (Object.keys(expected).length === 0) {
          expect(summary).toBeUndefined();
        } else {
          expect(summary).toEqual(expected);
        }
      }
    });

    it('exercícios + séries mostram repetições; cargas só aparecem com as duas ligadas (§30/§31)', async () => {
      const { checkInId } = await scenario();

      await patchSharing(TOKEN_A, { shareWorkoutExercises: true, shareWorkoutSets: true }).expect(
        200,
      );
      let summary = await summaryInFeedOf(TOKEN_B, checkInId);
      expect(summary?.exercises).toEqual([
        {
          name: 'Supino reto',
          primaryMuscle: 'CHEST',
          sets: [{ reps: 10 }, { reps: 10 }, { reps: 10 }],
        },
        {
          name: 'Remada curvada',
          primaryMuscle: 'BACK',
          sets: [{ reps: 12 }, { reps: 12 }, { reps: 12 }],
        },
        { name: 'Prancha', sets: [{ durationSeconds: 60 }] },
        { name: 'Barra fixa na porta', sets: [{ reps: 8 }] },
      ]);
      expect(JSON.stringify(summary)).not.toContain('weightKg');

      await patchSharing(TOKEN_A, { shareWorkoutWeights: true }).expect(200);
      summary = await summaryInFeedOf(TOKEN_B, checkInId);
      expect((summary?.exercises as Array<Record<string, unknown>>)[0].sets).toEqual([
        { reps: 10, weightKg: 80 },
        { reps: 10, weightKg: 80 },
        { reps: 10, weightKg: 80 },
      ]);
      // Peso corporal não vira "0 kg".
      expect((summary?.exercises as Array<Record<string, unknown>>)[3].sets).toEqual([{ reps: 8 }]);

      // Séries desligada: as cargas somem junto, mesmo com "Cargas" ainda ligado.
      await patchSharing(TOKEN_A, { shareWorkoutSets: false }).expect(200);
      summary = await summaryInFeedOf(TOKEN_B, checkInId);
      expect(JSON.stringify(summary)).not.toContain('weightKg');
      expect(JSON.stringify(summary)).not.toContain('reps');
    });

    it('desligar vale para trás: o check-in antigo perde as cargas na próxima leitura (§37/§58)', async () => {
      const { checkInId } = await scenario();
      await patchSharing(TOKEN_A, {
        shareWorkoutExercises: true,
        shareWorkoutSets: true,
        shareWorkoutWeights: true,
      }).expect(200);
      expect(JSON.stringify(await summaryInFeedOf(TOKEN_B, checkInId))).toContain('"weightKg":80');

      await patchSharing(TOKEN_A, { shareWorkoutWeights: false }).expect(200);
      const raw = (await feed(TOKEN_B)).text;
      expect(raw).not.toContain('weightKg');
      expect(raw).toContain('Supino reto');
    });

    it('o mesmo resumo no Feed, no detalhe e para o próprio autor (§36)', async () => {
      const { checkInId } = await scenario();
      await patchSharing(TOKEN_A, { ...ALL_ON }).expect(200);

      const inFeed = await summaryInFeedOf(TOKEN_B, checkInId);
      const detail = await request(server())
        .get(`/v1/social/workout-checkins/${checkInId}?context=FRIEND`)
        .set('Authorization', auth(TOKEN_B))
        .expect(200);
      const own = await summaryInFeedOf(TOKEN_A, checkInId);

      expect(detail.body.workoutSummary).toEqual(inFeed);
      expect(own).toEqual(inFeed);
      expect(inFeed).toMatchObject({
        name: 'Superiores A',
        startedAt: UPPER_A.startedAt,
        durationSeconds: 3300,
        exerciseCount: 4,
        completedSetCount: 8,
        totalVolumeKg: 4560,
      });
    });

    it('uma sessão apagada depois do check-in deixa de ter resumo', async () => {
      const { checkInId, upperA } = await scenario();
      await patchSharing(TOKEN_A, { shareWorkoutName: true }).expect(200);
      expect(await summaryInFeedOf(TOKEN_B, checkInId)).toEqual({ name: 'Superiores A' });

      // A sessão nasceu com revision 1 (um push só). O DELETE é o mesmo caminho do app: um
      // tombstone do sync, com a revision base.
      const deleted = await request(server())
        .post('/v1/sync/push')
        .set('Authorization', auth(TOKEN_A))
        .set('Content-Type', 'application/json')
        .send(
          pushBody([
            {
              entityType: 'WORKOUT_SESSION',
              entitySyncId: upperA,
              operation: 'DELETE',
              baseRevision: 1,
            },
          ]),
        )
        .expect(200);
      expect(deleted.body.results[0].status).toBe('APPLIED');

      const item = (await feed(TOKEN_B)).body.items.find(
        (candidate: Record<string, unknown>) => candidate.checkInId === checkInId,
      );
      // A publicação continua (ela é do Social); o resumo não, porque não há mais fato a afirmar.
      expect(item).toBeDefined();
      expect('workoutSummary' in item).toBe(false);
    });
  });

  // ------------------------------------------------------------------ bloqueio (§43)

  it('bloqueio continua superior: B não recebe perfil, check-in nem resumo de A', async () => {
    const { socialIdA, socialIdB, checkInId } = await scenario();
    await patchSharing(TOKEN_A, { ...ALL_ON }).expect(200);
    expect(await summaryInFeedOf(TOKEN_B, checkInId)).toBeDefined();

    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(TOKEN_A))
      .send({ blockedSocialId: socialIdB })
      .expect(200);

    const raw = (await feed(TOKEN_B)).text;
    expect(raw).not.toContain(checkInId);
    expect(raw).not.toContain('Superiores A');
    await friendProfile(TOKEN_B, socialIdA).expect(404);
    await request(server())
      .get(`/v1/social/workout-checkins/${checkInId}?context=FRIEND`)
      .set('Authorization', auth(TOKEN_B))
      .expect(404);
  });

  // ------------------------------------------------------------------ privacidade (§55)

  it('com tudo ligado, nada privado atravessa — nem identificador, nem nota, nem RPE/RIR', async () => {
    const { socialIdA, checkInId, upperA } = await scenario();
    await patchSharing(TOKEN_A, { ...ALL_ON }).expect(200);

    const responses = [
      (await feed(TOKEN_B)).text,
      (await friendProfile(TOKEN_B, socialIdA).expect(200)).text,
      (
        await request(server())
          .get(`/v1/social/workout-checkins/${checkInId}?context=FRIEND`)
          .set('Authorization', auth(TOKEN_B))
          .expect(200)
      ).text,
    ];

    for (const body of responses) {
      for (const value of [...Object.values(PRIVATE), upperA, UID_A, 'a@example.com']) {
        expect({ value, present: body.includes(value) }).toEqual({ value, present: false });
      }
      for (const key of [
        'sessionSyncId',
        'templateSyncId',
        'syncId',
        'ownerUid',
        'friendCode',
        'notes',
        'machineLabel',
        'replacementReason',
        '"rpe"',
        '"rir"',
        'plannedExercise',
        'actualExercise',
        'localId',
      ]) {
        expect({ key, present: body.includes(key) }).toEqual({ key, present: false });
      }
    }
  });

  // ------------------------------------------------------------------ N+1 (§38/§47)

  it('o Feed não cresce em consultas com o número de publicações (§38)', async () => {
    const socialIdA = await activate(TOKEN_A, 'Ana');
    const socialIdB = await activate(TOKEN_B, 'Bruno');
    await befriend(TOKEN_A, TOKEN_B, socialIdB);
    await patchSharing(TOKEN_A, { ...ALL_ON }).expect(200);
    expect(socialIdA).toBeTruthy();

    const postgres = app.get(PostgresService);
    const queriesForFeed = async (): Promise<number> => {
      const spy = jest.spyOn(postgres, 'query');
      await feed(TOKEN_B);
      const count = spy.mock.calls.length;
      spy.mockRestore();
      return count;
    };

    for (let i = 0; i < 2; i++) await checkIn(TOKEN_A, await push(TOKEN_A, UPPER_A.payload));
    const withTwo = await queriesForFeed();

    for (let i = 0; i < 6; i++) await checkIn(TOKEN_A, await push(TOKEN_A, MORNING.payload));
    const withEight = await queriesForFeed();

    const items = (await feed(TOKEN_B)).body.items as Array<Record<string, unknown>>;
    expect(items).toHaveLength(8);
    expect(items.every((item) => item.workoutSummary !== undefined)).toBe(true);
    expect(withEight).toBe(withTwo);
  });
});

// ==================================================================== definição única (§34)

describe('métricas de treino — uma definição para o perfil e para o check-in (T19.H3 §34)', () => {
  const facts = (overrides: Partial<SocialWorkoutFacts> = {}): SocialWorkoutFacts => ({
    ownerUid: 'uid',
    sessionSyncId: 'session',
    name: 'Superiores A',
    startedAt: 1_000_000,
    finishedAt: 1_000_000 + 3_300_000,
    exercises: [
      {
        name: 'Supino',
        primaryMuscle: 'CHEST',
        sets: [
          { type: 'WARMUP', weightKg: 20, repetitions: 15, durationSeconds: null, completed: true },
          {
            type: 'NORMAL',
            weightKg: 22.5,
            repetitions: 10,
            durationSeconds: null,
            completed: true,
          },
          { type: 'NORMAL', weightKg: 80, repetitions: 8, durationSeconds: null, completed: false },
        ],
      },
      {
        name: 'Prancha',
        primaryMuscle: null,
        sets: [
          { type: 'NORMAL', weightKg: 10, repetitions: 3, durationSeconds: 45, completed: true },
        ],
      },
    ],
    ...overrides,
  });

  it('aquecimento, série não concluída e série por tempo não somam volume', () => {
    expect(workoutMetrics(facts())).toEqual({
      exerciseCount: 2,
      completedSetCount: 2,
      volumeKg: 225,
      durationSeconds: 3300,
    });
  });

  it('sem fim afirmável não há duração — nem zero', () => {
    expect(workoutMetrics(facts({ finishedAt: null })).durationSeconds).toBeNull();
    expect(workoutMetrics(facts({ finishedAt: 999_999 })).durationSeconds).toBeNull();
  });

  it('a soma da semana é a soma dos check-ins da semana', () => {
    const sessions = [facts(), facts({ finishedAt: null }), facts({ exercises: [] })];
    const perCheckIn = sessions.map((session) => projectWorkoutSummary(session, { ...ALL_ON }));
    const totals = trainingTotals(sessions);
    expect(totals.volumeKg).toBe(
      perCheckIn.reduce((sum, summary) => sum + (summary?.totalVolumeKg ?? 0), 0),
    );
    expect(totals.completedSets).toBe(
      perCheckIn.reduce((sum, summary) => sum + (summary?.completedSetCount ?? 0), 0),
    );
    expect(totals.trainingMinutes).toBe(55 + 0 + 55);
  });

  it('uma sessão sem nenhum interruptor ligado não produz resumo', () => {
    expect(projectWorkoutSummary(facts(), { ...SOCIAL_PROGRESS_SHARING_DEFAULTS })).toBeNull();
  });

  it('peso com duas casas não é arredondado para uma (anilha de 1,25 kg)', () => {
    const summary = projectWorkoutSummary(
      facts({
        exercises: [
          {
            name: 'Supino',
            primaryMuscle: null,
            sets: [
              {
                type: 'NORMAL',
                weightKg: 22.25,
                repetitions: 10,
                durationSeconds: null,
                completed: true,
              },
            ],
          },
        ],
      }),
      { ...ALL_ON },
    );
    expect(summary?.exercises?.[0].sets).toEqual([{ reps: 10, weightKg: 22.25 }]);
  });
});
