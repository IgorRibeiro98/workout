import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PostgresService } from '../src/database/postgres.service';
import type { CanonicalTrainingSource } from '../src/modules/social/canonical-training.source';
import { evaluateVerifiedAchievements } from '../src/modules/social/social-gamification';
import {
  PROGRESS_SHARING_CONTRACT_VERSION,
  SOCIAL_AVAILABILITY_REASONS,
  SOCIAL_FIELD_AVAILABILITIES,
} from '../src/modules/social/social-profile.contract';
import { PROGRESS_SHARING_FLAGS } from '../src/modules/social/social-progress.repository';
import {
  SyncedSocialProgressSource,
  available,
  unsupported,
} from '../src/modules/social/social-progress.source';
import { MAX_WEEKLY_SESSIONS_FOR_TRAINING_STATS } from '../src/modules/social/social.limits';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';

const TOKEN_A = 'token-da-conta-a';
const UID_A = 'uid-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_B = 'uid-da-conta-b';

/** Terça-feira, 15h em São Paulo — a semana canônica vai de segunda 07/09 a domingo 13/09. */
const NOW = Date.parse('2026-09-08T18:00:00Z');
const TZ = 'America/Sao_Paulo';
/** Segunda-feira 07/09/2026 (epoch day), o início da semana canônica acima. */
const MONDAY = Math.floor(Date.UTC(2026, 8, 7) / 86_400_000);
/** Segunda 07/09, 00:00 em São Paulo. */
const WEEK_START = Date.parse('2026-09-07T03:00:00Z');

const CONSISTENCY = {
  trackingStartedAtEpochDay: MONDAY,
  weeklyGoals: [{ weekStartEpochDay: MONDAY, goal: 3 }],
};

const AVAILABILITY_KEYS = [
  'level',
  'consistencyStreak',
  'weeklyWorkoutCount',
  'highlightedAchievements',
  'weeklyTrainingMinutes',
  'weeklyCompletedSets',
  'weeklyVolume',
  'totalWorkouts',
] as const;

const CHECK_IN_DETAIL_FLAGS = [
  'shareWorkoutName',
  'shareWorkoutTime',
  'shareWorkoutDuration',
  'shareWorkoutExercises',
  'shareWorkoutSets',
  'shareWorkoutWeights',
  'shareWorkoutVolume',
] as const;

const workSet = (weight: number, repetitions: number, overrides: Record<string, unknown> = {}) => ({
  setNumber: 1,
  type: 'NORMAL',
  weight,
  repetitions,
  completed: true,
  startedAt: null,
  finishedAt: null,
  rpe: null,
  rir: null,
  durationSeconds: null,
  ...overrides,
});

const exerciseWith = (name: string, sets: Record<string, unknown>[]) => ({
  plannedOrder: 0,
  executionOrder: 0,
  exerciseNameSnapshot: name,
  plannedExercise: null,
  actualExercise: null,
  machineLabelSnapshot: null,
  primaryMuscleSnapshot: null,
  restDurationSecondsSnapshot: 90,
  startedAt: null,
  finishedAt: null,
  notes: null,
  replacementReason: null,
  sets,
});

/** Uma sessão desta semana, começando [minutesAfterWeekStart] depois de segunda 00:00. */
const sessionThisWeek =
  (minutesAfterWeekStart: number, overrides: Record<string, unknown> = {}) =>
  (syncId: string) =>
    sessionPayload(syncId, {
      startedAt: WEEK_START + minutesAfterWeekStart * 60_000,
      finishedAt: WEEK_START + (minutesAfterWeekStart + 30) * 60_000,
      ...overrides,
    });

/** Sábado da semana anterior: conta no total e em nenhuma estatística desta semana. */
const LAST_WEEK = (syncId: string) =>
  sessionPayload(syncId, {
    startedAt: Date.parse('2026-09-05T12:00:00Z'),
    finishedAt: Date.parse('2026-09-05T12:45:00Z'),
  });

/**
 * T19.H5 — Compartilhar Progresso com disponibilidade **real**: contrato versionado, motivo para
 * cada `UNAVAILABLE` e as regras de domínio que a T19.H3 prometeu (total, semana, zero).
 *
 * O defeito que motivou a tarefa não era de cálculo: um app novo contra um servidor anterior à
 * T19.H3 lia a falta de uma chave como "Ainda não disponível" e tomava `400` num interruptor que o
 * servidor não conhecia. O que estes testes protegem é que o servidor **diga** o que conhece e por
 * que um campo falta — e que nada disso chegue a quem não é o dono.
 */
describe('Compartilhar Progresso — disponibilidade real (T19.H5)', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    jest.spyOn(Date, 'now').mockReturnValue(NOW);
    app = await createTestApp(
      configFor(temp.path),
      new FakeAuthTokenVerifier()
        .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
        .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' }),
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

  /** Sincroniza as sessões pelo caminho real do app (T16), em lotes do tamanho que o push aceita. */
  const push = async (
    token: string,
    payloads: ReadonlyArray<(syncId: string) => Record<string, unknown>>,
  ): Promise<void> => {
    for (let offset = 0; offset < payloads.length; offset += 50) {
      const batch = payloads.slice(offset, offset + 50).map((payload) => {
        const syncId = uuid();
        return { entityType: 'WORKOUT_SESSION', entitySyncId: syncId, payload: payload(syncId) };
      });
      const res = await request(server())
        .post('/v1/sync/push')
        .set('Authorization', auth(token))
        .set('Content-Type', 'application/json')
        .send(pushBody(batch))
        .expect(200);
      for (const result of res.body.results as Array<{ status: string }>) {
        expect(result.status).toBe('APPLIED');
      }
    }
  };

  const patchSharing = (token: string, body: object) =>
    request(server())
      .patch('/v1/social/me/progress-sharing')
      .set('Authorization', auth(token))
      .send(body);

  const sharing = (token: string) =>
    request(server())
      .get('/v1/social/me/progress-sharing')
      .set('Authorization', auth(token))
      .expect(200);

  const friendProfile = (token: string, socialId: string) =>
    request(server())
      .get(`/v1/social/friends/${socialId}/profile`)
      .set('Authorization', auth(token))
      .expect(200);

  // ------------------------------------------------------------------ contrato versionado (§6–§8)

  describe('o servidor declara a versão do contrato', () => {
    it('GET e PATCH declaram a versão 2, com os quinze interruptores e as oito disponibilidades', async () => {
      await activate(TOKEN_A, 'Ana');

      for (const res of [
        await sharing(TOKEN_A),
        await patchSharing(TOKEN_A, { shareWorkoutName: true }).expect(200),
      ]) {
        expect(res.body.contractVersion).toBe(PROGRESS_SHARING_CONTRACT_VERSION);
        expect(PROGRESS_SHARING_CONTRACT_VERSION).toBe(2);
        for (const [flag] of PROGRESS_SHARING_FLAGS) {
          expect({ flag, type: typeof res.body.settings[flag] }).toEqual({
            flag,
            type: 'boolean',
          });
        }
        expect(Object.keys(res.body.availability).sort()).toEqual([...AVAILABILITY_KEYS].sort());
        expect(typeof res.body.availabilityReasons).toBe('object');
      }
    });

    it('a forma que um APK anterior à T19.H5 lê não mudou: disponibilidade continua um mapa de strings', async () => {
      // Todo APK publicado lê `availability.level` como string e ignora chave desconhecida. Trocar
      // a forma para `{ status, reason }` quebraria a tela inteira nos aparelhos antigos — o motivo
      // vai num mapa ao lado, e a versão numa chave nova.
      await activate(TOKEN_A, 'Ana');
      const res = await sharing(TOKEN_A);

      for (const key of AVAILABILITY_KEYS) {
        expect(SOCIAL_FIELD_AVAILABILITIES).toContain(res.body.availability[key]);
      }
    });

    it('o cliente não declara motivo nem versão — recusado, e nada muda', async () => {
      await activate(TOKEN_A, 'Ana');

      for (const body of [
        { availabilityReasons: { totalWorkouts: 'NO_SYNCED_WORKOUTS' } },
        { contractVersion: 2 },
        { shareWorkoutName: true, availabilityReasons: {} },
      ]) {
        const res = await patchSharing(TOKEN_A, body).expect(400);
        expect(res.body.error.code).toBe('INVALID_PROGRESS_SETTINGS');
      }
      expect((await sharing(TOKEN_A)).body.settings.shareWorkoutName).toBe(false);
    });
  });

  // ------------------------------------------------------------------ motivos (§10/§18/§19)

  describe('cada UNAVAILABLE tem um motivo real', () => {
    it('conta sem sessão sincronizada, com fuso: tudo que depende de treino diz NO_SYNCED_WORKOUTS', async () => {
      await activate(TOKEN_A, 'Ana');
      await patchSharing(TOKEN_A, { weekTimeZone: TZ, consistency: CONSISTENCY }).expect(200);

      const res = await sharing(TOKEN_A);
      for (const key of AVAILABILITY_KEYS) {
        expect({ key, status: res.body.availability[key] }).toEqual({
          key,
          status: 'UNAVAILABLE',
        });
        expect({ key, reason: res.body.availabilityReasons[key] }).toEqual({
          key,
          reason: 'NO_SYNCED_WORKOUTS',
        });
      }
    });

    it('sem fuso: a semana pede o fuso — e treinos totais já está disponível com uma sessão', async () => {
      await activate(TOKEN_A, 'Ana');
      await push(TOKEN_A, [sessionThisWeek(60)]);

      const res = await sharing(TOKEN_A);
      // §18: com sessão canônica, o total não depende de fuso nem de parâmetro.
      expect(res.body.availability.totalWorkouts).toBe('AVAILABLE');
      expect('totalWorkouts' in res.body.availabilityReasons).toBe(false);
      for (const key of AVAILABILITY_KEYS.filter((k) => k !== 'totalWorkouts')) {
        expect({ key, reason: res.body.availabilityReasons[key] }).toEqual({
          key,
          reason: 'WEEK_TIME_ZONE_MISSING',
        });
      }
    });

    it('com fuso e sessão, sem parâmetros: só nível e sequência ficam de fora, e dizem por quê', async () => {
      await activate(TOKEN_A, 'Ana');
      await patchSharing(TOKEN_A, { weekTimeZone: TZ }).expect(200);
      await push(TOKEN_A, [sessionThisWeek(60)]);

      const res = await sharing(TOKEN_A);
      expect(res.body.availabilityReasons).toEqual({
        level: 'CONSISTENCY_PARAMETERS_MISSING',
        consistencyStreak: 'CONSISTENCY_PARAMETERS_MISSING',
      });
      for (const key of [
        'weeklyWorkoutCount',
        'highlightedAchievements',
        'weeklyTrainingMinutes',
        'weeklyCompletedSets',
        'weeklyVolume',
        'totalWorkouts',
      ]) {
        expect({ key, status: res.body.availability[key] }).toEqual({ key, status: 'AVAILABLE' });
      }
    });

    it('com fuso, sessão e parâmetros declarados, tudo está disponível e não há motivo nenhum', async () => {
      await activate(TOKEN_A, 'Ana');
      await patchSharing(TOKEN_A, { weekTimeZone: TZ, consistency: CONSISTENCY }).expect(200);
      await push(TOKEN_A, [sessionThisWeek(60)]);

      const res = await sharing(TOKEN_A);
      for (const key of AVAILABILITY_KEYS) {
        expect({ key, status: res.body.availability[key] }).toEqual({ key, status: 'AVAILABLE' });
      }
      expect(res.body.availabilityReasons).toEqual({});
    });

    it('todo motivo que o servidor escreve pertence ao conjunto declarado no contrato', async () => {
      await activate(TOKEN_A, 'Ana');
      const seen = new Set<string>();
      const collect = async () => {
        const res = await sharing(TOKEN_A);
        for (const reason of Object.values(
          res.body.availabilityReasons as Record<string, string>,
        )) {
          seen.add(reason);
        }
      };
      await collect(); // sem fuso
      await patchSharing(TOKEN_A, { weekTimeZone: TZ }).expect(200);
      await collect(); // sem sessão
      await push(TOKEN_A, [sessionThisWeek(60)]);
      await collect(); // sem parâmetros

      for (const reason of seen) {
        expect(SOCIAL_AVAILABILITY_REASONS).toContain(reason);
      }
      expect([...seen].sort()).toEqual(
        ['CONSISTENCY_PARAMETERS_MISSING', 'NO_SYNCED_WORKOUTS', 'WEEK_TIME_ZONE_MISSING'].sort(),
      );
    });

    it('acima do teto de leitura da semana: SOURCE_LIMIT_REACHED — nunca uma soma truncada (§25)', async () => {
      const socialIdA = await activate(TOKEN_A, 'Ana');
      const socialIdB = await activate(TOKEN_B, 'Bruno');
      await befriend(TOKEN_A, TOKEN_B, socialIdB);
      await patchSharing(TOKEN_A, {
        weekTimeZone: TZ,
        shareWeeklyVolume: true,
        shareTotalWorkouts: true,
      }).expect(200);

      const limit = MAX_WEEKLY_SESSIONS_FOR_TRAINING_STATS;
      // Um treino a cada minuto a partir de segunda 01:00: todos na semana canônica.
      await push(
        TOKEN_A,
        Array.from({ length: limit }, (_, index) => sessionThisWeek(60 + index)),
      );
      let res = await sharing(TOKEN_A);
      expect(res.body.availability.weeklyVolume).toBe('AVAILABLE');

      await push(TOKEN_A, [sessionThisWeek(60 + limit)]);
      res = await sharing(TOKEN_A);
      for (const key of ['weeklyTrainingMinutes', 'weeklyCompletedSets', 'weeklyVolume']) {
        expect({ key, status: res.body.availability[key] }).toEqual({ key, status: 'UNAVAILABLE' });
        expect({ key, reason: res.body.availabilityReasons[key] }).toEqual({
          key,
          reason: 'SOURCE_LIMIT_REACHED',
        });
      }
      // As contagens não leem séries, e continuam exatas.
      expect(res.body.availability.totalWorkouts).toBe('AVAILABLE');
      expect(res.body.availability.weeklyWorkoutCount).toBe('AVAILABLE');

      // O amigo recebe o total e **não** recebe um volume menor que o real.
      const profile = await friendProfile(TOKEN_B, socialIdA);
      expect(profile.body.profile.sharedProgress).toEqual({ totalWorkouts: limit + 1 });
    });
  });

  // ------------------------------------------------------------------ zero é valor (§19–§23)

  describe('zero é valor, não ausência', () => {
    it('semana sem treino, com sessões antes: minutos, séries e volume publicam 0', async () => {
      const socialIdA = await activate(TOKEN_A, 'Ana');
      const socialIdB = await activate(TOKEN_B, 'Bruno');
      await befriend(TOKEN_A, TOKEN_B, socialIdB);
      await patchSharing(TOKEN_A, {
        weekTimeZone: TZ,
        shareWeeklyTrainingMinutes: true,
        shareWeeklyCompletedSets: true,
        shareWeeklyVolume: true,
        shareTotalWorkouts: true,
      }).expect(200);
      await push(TOKEN_A, [LAST_WEEK]);

      const owner = await sharing(TOKEN_A);
      for (const key of ['weeklyTrainingMinutes', 'weeklyCompletedSets', 'weeklyVolume']) {
        expect({ key, status: owner.body.availability[key] }).toEqual({ key, status: 'AVAILABLE' });
      }
      const profile = await friendProfile(TOKEN_B, socialIdA);
      expect(profile.body.profile.sharedProgress).toEqual({
        weeklyTrainingMinutes: 0,
        weeklyCompletedSets: 0,
        weeklyVolumeKg: 0,
        totalWorkouts: 1,
      });
    });

    it('treino só de peso corporal: volume 0 disponível, séries contadas, minutos pela duração', async () => {
      const socialIdA = await activate(TOKEN_A, 'Ana');
      const socialIdB = await activate(TOKEN_B, 'Bruno');
      await befriend(TOKEN_A, TOKEN_B, socialIdB);
      await patchSharing(TOKEN_A, {
        weekTimeZone: TZ,
        shareWeeklyTrainingMinutes: true,
        shareWeeklyCompletedSets: true,
        shareWeeklyVolume: true,
      }).expect(200);
      await push(TOKEN_A, [
        sessionThisWeek(60, {
          finishedAt: WEEK_START + (60 + 40) * 60_000,
          exercises: [
            exerciseWith('Flexão', [workSet(0, 20), workSet(0, 15), workSet(0, 12)]),
            exerciseWith('Barra fixa', [workSet(0, 8, { type: 'WARMUP' }), workSet(0, 6)]),
          ],
        }),
      ]);

      const profile = await friendProfile(TOKEN_B, socialIdA);
      expect(profile.body.profile.sharedProgress).toEqual({
        weeklyTrainingMinutes: 40,
        // O aquecimento da barra fixa não é série de trabalho.
        weeklyCompletedSets: 4,
        weeklyVolumeKg: 0,
      });
    });

    it('tempo treinado soma max(fim − início, 0): sessão sem fim ou com fim antes do início soma zero', async () => {
      const socialIdA = await activate(TOKEN_A, 'Ana');
      const socialIdB = await activate(TOKEN_B, 'Bruno');
      await befriend(TOKEN_A, TOKEN_B, socialIdB);
      await patchSharing(TOKEN_A, { weekTimeZone: TZ, shareWeeklyTrainingMinutes: true }).expect(
        200,
      );
      await push(TOKEN_A, [
        sessionThisWeek(60, { finishedAt: WEEK_START + (60 + 45) * 60_000 }),
        sessionThisWeek(300, { finishedAt: null }),
        // Relógio do aparelho voltou no meio do treino: nenhuma duração negativa subtrai do total.
        sessionThisWeek(600, { finishedAt: WEEK_START + 500 * 60_000 }),
      ]);

      const profile = await friendProfile(TOKEN_B, socialIdA);
      expect(profile.body.profile.sharedProgress).toEqual({ weeklyTrainingMinutes: 45 });
    });
  });

  // ------------------------------------------------------------------ detalhes do check-in (§26–§28)

  describe('detalhes do check-in são preferência, sem disponibilidade global', () => {
    it.each(CHECK_IN_DETAIL_FLAGS.map((flag) => [flag]))(
      '%s sozinho: 200 sem publicação nenhuma, e true três vezes converge em true',
      async (flag) => {
        await activate(TOKEN_A, 'Ana');

        for (let attempt = 0; attempt < 3; attempt++) {
          const res = await patchSharing(TOKEN_A, { [flag]: true }).expect(200);
          expect(res.body.contractVersion).toBe(2);
          for (const [other] of PROGRESS_SHARING_FLAGS) {
            expect({ other, value: res.body.settings[other] }).toEqual({
              other,
              value: other === flag,
            });
          }
        }

        // Nenhuma linha duplicada: uma conta, uma linha de preferências.
        const rows = await app.get(PostgresService).query<{
          total: string;
        }>('SELECT COUNT(*) AS total FROM social_progress_settings WHERE owner_uid = $1', [UID_A]);
        expect(Number(rows.rows[0]?.total)).toBe(1);
      },
    );
  });

  // ------------------------------------------------------------------ só o dono (§11)

  it('o amigo e a prévia não recebem disponibilidade, motivo nem versão', async () => {
    const socialIdA = await activate(TOKEN_A, 'Ana');
    const socialIdB = await activate(TOKEN_B, 'Bruno');
    await befriend(TOKEN_A, TOKEN_B, socialIdB);
    // Tudo ligado e nada sincronizado: o dono vê oito motivos; o amigo, um perfil vazio.
    const allOn = Object.fromEntries(PROGRESS_SHARING_FLAGS.map(([flag]) => [flag, true]));
    await patchSharing(TOKEN_A, { ...allOn, weekTimeZone: TZ }).expect(200);
    expect(Object.keys((await sharing(TOKEN_A)).body.availabilityReasons)).toHaveLength(8);

    const friend = await friendProfile(TOKEN_B, socialIdA);
    const preview = await request(server())
      .get('/v1/social/me/profile-preview')
      .set('Authorization', auth(TOKEN_A))
      .expect(200);

    for (const res of [friend, preview]) {
      expect(res.body.profile.sharedProgress).toEqual({});
      for (const needle of [
        'availability',
        'Reason',
        'reason',
        'contractVersion',
        ...SOCIAL_AVAILABILITY_REASONS,
      ]) {
        expect({ needle, present: res.text.includes(needle) }).toEqual({ needle, present: false });
      }
    }
  });

  // ------------------------------------------------------------------ idempotência (§40)

  it('reler três vezes não escreve nada: a mesma resposta, o mesmo updatedAt', async () => {
    await activate(TOKEN_A, 'Ana');
    await patchSharing(TOKEN_A, { weekTimeZone: TZ, shareTotalWorkouts: true }).expect(200);
    await push(TOKEN_A, [sessionThisWeek(60)]);

    const first = await sharing(TOKEN_A);
    const second = await sharing(TOKEN_A);
    const third = await sharing(TOKEN_A);
    expect(second.body).toEqual(first.body);
    expect(third.body).toEqual(first.body);
  });
});

// ==================================================================== premissas e montagem

describe('premissas dos motivos (T19.H5)', () => {
  it('conquista vazia só acontece sem sync: uma sessão ou uma medição sempre produzem uma', () => {
    // A fonte responde `NO_SYNCED_WORKOUTS` para lista de conquistas vazia. Isso só é verdade
    // enquanto o catálogo tiver uma conquista de alvo 1 para treino e outra para medição; se
    // alguém mudar o catálogo, este teste quebra antes de o motivo passar a mentir.
    expect(
      evaluateVerifiedAchievements({
        completedWorkouts: 1,
        longestStreakWeeks: null,
        measurementDays: null,
      }),
    ).toContain('first_workout');
    expect(
      evaluateVerifiedAchievements({
        completedWorkouts: 0,
        longestStreakWeeks: null,
        measurementDays: 1,
      }),
    ).toContain('first_measurement');
  });

  it('sem fonte de fatos, as estatísticas da semana são UNSUPPORTED — nunca UNAVAILABLE sem causa', async () => {
    // A montagem com dublê de agregados não tem de onde ler séries: "esta instância não sabe
    // calcular" é `UNSUPPORTED`. Produção sempre injeta a fonte (`SocialModule`).
    const aggregatesOnly: CanonicalTrainingSource = {
      hasAnyCompletedSession: () => Promise.resolve(true),
      countCompletedWorkouts: () => Promise.resolve(4),
      countActiveDays: () => Promise.resolve(0),
      getCompletedWorkoutCounts: () => Promise.resolve(new Map()),
      getCompletedWorkoutSummaries: () => Promise.resolve([]),
      countCompletedWorkoutsPerDay: () => Promise.resolve(new Map()),
      countBodyMeasurementsPerDay: () => Promise.resolve(new Map()),
      findSessionForCheckIn: () => Promise.resolve(null),
    };
    const source = new SyncedSocialProgressSource(aggregatesOnly);

    for (const weekTimeZone of [TZ, null]) {
      const projection = await source.project(UID_A, {
        weekTimeZone,
        consistency: null,
        nowMs: NOW,
      });
      expect(projection.weeklyTrainingMinutes).toEqual(unsupported());
      expect(projection.weeklyCompletedSets).toEqual(unsupported());
      expect(projection.weeklyVolumeKg).toEqual(unsupported());
      expect(projection.totalWorkouts).toEqual(available(4));
    }
  });
});
