import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import BetterSqlite3 from 'better-sqlite3';
import request from 'supertest';
import { PostgresService } from '../src/database/postgres.service';
import { AccountDeletionRepository } from '../src/modules/account-deletion/account-deletion.repository';
import { ACCOUNT_UID_COLUMNS } from '../src/modules/account-deletion/account-uid-inventory';
import {
  calculateProgress,
  calculateWeeklyConsistencies,
  type ConsistencyParameters,
  isMondayEpochDay,
  localEpochDay,
  type WeeklyConsistency,
  weekStartEpochDay,
} from '../src/modules/social/social-consistency';
import {
  evaluateVerifiedAchievements,
  levelFor,
  projectVerifiedXp,
  REMOTE_ACHIEVEMENT_CATALOG_VERSION,
  REMOTE_ACHIEVEMENTS,
  REMOTE_MISSION_CATALOG_VERSION,
  REMOTE_MISSIONS,
  REMOTE_XP_POLICY_VERSION,
  XP_SOURCE_AUTHORITY,
} from '../src/modules/social/social-gamification';
import {
  available,
  localDayWindows,
  PROGRESS_HORIZON_EPOCH_DAY,
  type SocialProgressContext,
  SyncedSocialProgressSource,
  unavailable,
} from '../src/modules/social/social-progress.source';
import { parseUpdateProgressSharingRequest } from '../src/modules/social/social-profile.validator';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { measurementPayload, pushBody, sessionPayload, uuid } from './support/sync-fixtures';
import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';

const CONTRACTS = join(__dirname, '..', '..', 'contracts', 'social', 'v1');

interface FixtureInstant {
  readonly iso: string;
  readonly millis: number;
  readonly local: string;
}

interface ConsistencyFixture {
  cases: Array<{
    name: string;
    timeZone: string;
    nowMillis: number;
    consistency: ConsistencyParameters;
    completedSessions: FixtureInstant[];
    expected: {
      weeks: WeeklyConsistency[];
      currentStreakWeeks: number;
      longestStreakWeeks: number;
    };
  }>;
}

interface ProjectionFixture {
  catalog: {
    xpPolicyVersion: number;
    missionCatalogVersion: number;
    achievementCatalogVersion: number;
    xpSources: typeof XP_SOURCE_AUTHORITY;
    missions: typeof REMOTE_MISSIONS;
    achievements: typeof REMOTE_ACHIEVEMENTS;
    levelCurve: {
      samples: Array<{
        totalXp: number;
        level: number;
        currentLevelXp: number;
        xpForNextLevel: number;
      }>;
    };
  };
  cases: Array<{
    name: string;
    timeZone: string;
    nowMillis: number;
    consistency: ConsistencyParameters | null;
    completedSessions: FixtureInstant[];
    bodyMeasurements: FixtureInstant[];
    expected: {
      completedWorkouts: number;
      currentStreakWeeks: number | null;
      longestStreakWeeks: number | null;
      xp: {
        workoutCompleted: number;
        firstWorkoutCompleted: number;
        weeklyGoalCompleted: number;
        missions: number;
        total: number;
      } | null;
      level: { level: number; currentLevelXp: number; xpForNextLevel: number } | null;
      measurementDays: number;
      earnedAchievementIds: string[];
    };
  }>;
}

const CONSISTENCY_FIXTURE = JSON.parse(
  readFileSync(join(CONTRACTS, 'consistency-streak.json'), 'utf8'),
) as ConsistencyFixture;
const PROJECTION_FIXTURE = JSON.parse(
  readFileSync(join(CONTRACTS, 'progress-projection.json'), 'utf8'),
) as ProjectionFixture;

const UID_A = 'uid-da-conta-a';
const UID_B = 'uid-da-conta-b';

/** Contagem por dia local a partir de instantes — o que a fonte real produz com SQL. */
function perDay(instants: readonly FixtureInstant[], timeZone: string): Map<number, number> {
  const map = new Map<number, number>();
  for (const instant of instants) {
    const day = localEpochDay(instant.millis, timeZone);
    map.set(day, (map.get(day) ?? 0) + 1);
  }
  return map;
}

/**
 * Social Progress V2 (T19.2): a autoridade remota de consistência, XP, nível e conquistas.
 *
 * ```text
 * fato canônico (sync_entities) ──▶ servidor reconstrói ──▶ métrica ──▶ privacidade ──▶ amigo
 * ```
 *
 * O que estes testes protegem:
 *
 * 1. **a regra é a do aparelho.** As fixtures em `contracts/social/v1/` são lidas também pelos
 *    testes do Android, que rodam `ConsistencyCalculator` e o motor de gamificação local sobre os
 *    mesmos fatos. Aqui, o TypeScript precisa dar a mesma resposta;
 * 2. **o servidor deriva; ele não acredita.** `level`, `xp`, `streak`, `unlocked` são recusados por
 *    nome — inclusive aninhados nos parâmetros de consistência;
 * 3. **idempotência.** A mesma sessão sincronizada duas vezes é uma sessão; a projeção recalculada
 *    é idêntica; tombstone e sessão não concluída não contam;
 * 4. **privacidade, bloqueio, isolamento de conta e exclusão** continuam valendo para as métricas
 *    novas exatamente como valiam para a contagem semanal.
 */
describe('Social Progress V2 — autoridade remota de gamificação (T19.2)', () => {
  // ------------------------------------------------------------------ T19.2A: consistência pura

  describe('a sequência canônica é a mesma dos dois lados (fixture compartilhada)', () => {
    it('a fixture existe e cobre os casos que importam', () => {
      const names = CONSISTENCY_FIXTURE.cases.map((entry) => entry.name);
      expect(names.length).toBeGreaterThanOrEqual(6);
      expect(names).toEqual(
        expect.arrayContaining([
          'sequencia-com-quebra',
          'inicio-no-meio-da-semana-sem-treino',
          'meta-muda-de-3-para-2',
          'virada-de-dia-e-de-semana',
          'new-york-com-inicio-do-horario-de-verao',
        ]),
      );
    });

    it.each(CONSISTENCY_FIXTURE.cases.map((entry) => [entry.name, entry] as const))(
      'semanas e sequência: %s',
      (_name, entry) => {
        const today = localEpochDay(entry.nowMillis, entry.timeZone);
        const weeks = calculateWeeklyConsistencies(
          perDay(entry.completedSessions, entry.timeZone),
          entry.consistency,
          today,
        );
        const progress = calculateProgress(weeks, today);

        expect(weeks).toEqual(entry.expected.weeks);
        expect(progress.currentStreakWeeks).toBe(entry.expected.currentStreakWeeks);
        expect(progress.longestStreakWeeks).toBe(entry.expected.longestStreakWeeks);
      },
    );

    it('a semana começa na segunda-feira e epoch day 4 é uma segunda', () => {
      expect(isMondayEpochDay(4)).toBe(true);
      expect(weekStartEpochDay(4)).toBe(4);
      // 2026-09-08 (terça) → 2026-09-07 (segunda)
      expect(weekStartEpochDay(20_704)).toBe(20_703);
      expect(weekStartEpochDay(20_703)).toBe(20_703);
      // domingo 2026-09-13 ainda é a semana de 7/9
      expect(weekStartEpochDay(20_709)).toBe(20_703);
      expect(isMondayEpochDay(20_704)).toBe(false);
    });

    it('sem parâmetros nenhuma semana existe — e sem semanas a sequência é zero, nunca erro', () => {
      const progress = calculateProgress([], 20_704);
      expect(progress.currentStreakWeeks).toBe(0);
      expect(progress.longestStreakWeeks).toBe(0);
    });

    it('as janelas de dia cobrem o horizonte inteiro sem buraco nem sobreposição', () => {
      const tz = 'America/Sao_Paulo';
      const windows = localDayWindows(weekStartEpochDay(PROGRESS_HORIZON_EPOCH_DAY), 20_709, tz);
      expect(windows[0].epochDay).toBe(weekStartEpochDay(PROGRESS_HORIZON_EPOCH_DAY));
      expect(windows[windows.length - 1].epochDay).toBe(20_709);
      for (let i = 1; i < windows.length; i++) {
        expect(windows[i].startMs).toBe(windows[i - 1].endMs);
        expect(windows[i].epochDay).toBe(windows[i - 1].epochDay + 1);
      }
      // A virada de horário de verão de São Paulo não existe mais, mas a de Nova York existe: um
      // dia de 23h e um de 25h, e nenhum de 24h "forçado".
      const ny = localDayWindows(20_519, 20_522, 'America/New_York'); // 2026-03-07..2026-03-10
      const hours = ny.map((w) => (w.endMs - w.startMs) / 3_600_000);
      expect(hours).toContain(23);
      expect(hours.every((h) => h === 23 || h === 24)).toBe(true);
    });
  });

  // ------------------------------------------------------------------ T19.2B/C: XP, nível, conquistas puros

  describe('a autoridade remota de gamificação (fixture compartilhada)', () => {
    it('o catálogo espelhado é exatamente o da fixture — mudar um lado exige mudar o outro', () => {
      const catalog = PROJECTION_FIXTURE.catalog;
      expect(catalog.xpPolicyVersion).toBe(REMOTE_XP_POLICY_VERSION);
      expect(catalog.missionCatalogVersion).toBe(REMOTE_MISSION_CATALOG_VERSION);
      expect(catalog.achievementCatalogVersion).toBe(REMOTE_ACHIEVEMENT_CATALOG_VERSION);
      expect(catalog.xpSources).toEqual(XP_SOURCE_AUTHORITY);
      expect(catalog.missions).toEqual(REMOTE_MISSIONS);
      expect(catalog.achievements).toEqual(REMOTE_ACHIEVEMENTS);
    });

    it('a matriz classifica toda origem de XP, e o recorde pessoal fica fora da projeção', () => {
      const byEvent = new Map(XP_SOURCE_AUTHORITY.map((source) => [source.event, source]));
      // Os nove `GamificationEventType` do Kotlin, sem exceção.
      expect([...byEvent.keys()].sort()).toEqual(
        [
          'EXERCISE_COMPLETED',
          'FIRST_EXERCISE_COMPLETED',
          'FIRST_WORKOUT_COMPLETED',
          'MISSION_COMPLETED',
          'PERSONAL_RECORD_CREATED',
          'STREAK_MILESTONE_REACHED',
          'WEEKLY_GOAL_COMPLETED',
          'WORKOUT_COMPLETED',
          'WORKOUT_STARTED',
        ].sort(),
      );
      expect(byEvent.get('PERSONAL_RECORD_CREATED')?.authority).toBe('UNSUPPORTED_SERVER_SIDE');
      // Nenhuma origem depende de o cliente **enviar** o fato: tudo o que vale XP no servidor é
      // reconstruído do que já sincroniza.
      expect(XP_SOURCE_AUTHORITY.filter((s) => s.authority === 'VERIFIABLE')).toEqual([]);
      for (const source of XP_SOURCE_AUTHORITY) {
        if (source.xp !== null && source.authority !== 'UNSUPPORTED_SERVER_SIDE') {
          expect(source.authority).toBe('RECONSTRUCTABLE');
        }
      }
    });

    it('as conquistas de PERFORMANCE nunca são afirmadas, com qualquer contagem', () => {
      const earned = evaluateVerifiedAchievements({
        completedWorkouts: 1_000,
        longestStreakWeeks: 100,
        measurementDays: 100,
      });
      expect(earned).toEqual(
        REMOTE_ACHIEVEMENTS.filter((a) => a.authority === 'RECONSTRUCTABLE').map((a) => a.id),
      );
      expect(earned.some((id) => id.includes('pr'))).toBe(false);
      expect(REMOTE_ACHIEVEMENTS.filter((a) => a.category === 'PERFORMANCE')).toHaveLength(4);
      for (const definition of REMOTE_ACHIEVEMENTS.filter((a) => a.category === 'PERFORMANCE')) {
        expect(definition.authority).toBe('UNSUPPORTED_SERVER_SIDE');
      }
    });

    it('uma categoria sem fato não é avaliada — nem como obtida, nem como perdida', () => {
      expect(
        evaluateVerifiedAchievements({
          completedWorkouts: 100,
          longestStreakWeeks: null,
          measurementDays: null,
        }),
      ).toEqual(['first_workout', '10_workouts', '25_workouts', '50_workouts', '100_workouts']);
      expect(
        evaluateVerifiedAchievements({
          completedWorkouts: 0,
          longestStreakWeeks: 0,
          measurementDays: 0,
        }),
      ).toEqual([]);
    });

    it('a curva de nível é a do aparelho, ponto a ponto', () => {
      for (const sample of PROJECTION_FIXTURE.catalog.levelCurve.samples) {
        expect(levelFor(sample.totalXp)).toEqual({
          level: sample.level,
          currentLevelXp: sample.currentLevelXp,
          xpForNextLevel: sample.xpForNextLevel,
        });
      }
      // Os limiares canônicos: 500, 1500, 3000, 5000...
      expect(levelFor(499).level).toBe(1);
      expect(levelFor(500).level).toBe(2);
      expect(levelFor(1_499).level).toBe(2);
      expect(levelFor(1_500).level).toBe(3);
      expect(levelFor(2_999).level).toBe(3);
      expect(levelFor(3_000).level).toBe(4);
      expect(levelFor(-50)).toEqual({ level: 1, currentLevelXp: 0, xpForNextLevel: 500 });
    });

    it.each(PROJECTION_FIXTURE.cases.map((entry) => [entry.name, entry] as const))(
      'XP, nível e conquistas: %s',
      (_name, entry) => {
        const today = localEpochDay(entry.nowMillis, entry.timeZone);
        const sessionsPerDay = perDay(entry.completedSessions, entry.timeZone);
        const weeks = entry.consistency
          ? calculateWeeklyConsistencies(sessionsPerDay, entry.consistency, today)
          : null;
        const progress = weeks ? calculateProgress(weeks, today) : null;

        expect(progress?.currentStreakWeeks ?? null).toBe(entry.expected.currentStreakWeeks);
        expect(progress?.longestStreakWeeks ?? null).toBe(entry.expected.longestStreakWeeks);

        if (entry.consistency && weeks) {
          const xp = projectVerifiedXp({
            completedWorkouts: entry.completedSessions.length,
            sessionsPerDay,
            weeks,
          });
          expect(xp).toEqual(entry.expected.xp);
          expect(levelFor(xp.total)).toEqual(entry.expected.level);
        } else {
          expect(entry.expected.xp).toBeNull();
        }

        const measurementDays = perDay(entry.bodyMeasurements, entry.timeZone).size;
        expect(measurementDays).toBe(entry.expected.measurementDays);
        expect(
          evaluateVerifiedAchievements({
            completedWorkouts: entry.completedSessions.length,
            longestStreakWeeks: progress ? progress.longestStreakWeeks : null,
            measurementDays,
          }),
        ).toEqual(entry.expected.earnedAchievementIds);
      },
    );

    it('a projeção de XP é uma função pura: os mesmos fatos dão o mesmo XP, quantas vezes for', () => {
      const entry = PROJECTION_FIXTURE.cases[0];
      const today = localEpochDay(entry.nowMillis, entry.timeZone);
      const sessionsPerDay = perDay(entry.completedSessions, entry.timeZone);
      const weeks = calculateWeeklyConsistencies(sessionsPerDay, entry.consistency!, today);
      const facts = { completedWorkouts: entry.completedSessions.length, sessionsPerDay, weeks };
      expect(projectVerifiedXp(facts)).toEqual(projectVerifiedXp(facts));
    });
  });

  // ------------------------------------------------------------------ a fonte sobre o banco real

  describe('a fonte reconstrói a projeção do sync_entities — e só dele', () => {
    let temp: TempDb;
    let db: BetterSqlite3.Database;
    let postgres: PostgresService;
    let source: SyncedSocialProgressSource;

    beforeEach(async () => {
      temp = createTempDb();
      postgres = postgresFor(configFor(temp.path));
      await postgres.initialize();
      db = new BetterSqlite3(temp.path);
      db.pragma('foreign_keys = ON');
      source = new SyncedSocialProgressSource(postgres);
    });

    afterEach(async () => {
      db.close();
      await postgres.close();
      temp.cleanup();
    });

    const insertEntity = (
      ownerUid: string,
      entityType: 'WORKOUT_SESSION' | 'BODY_MEASUREMENT',
      payload: Record<string, unknown>,
      overrides: { syncId?: string; deleted?: number } = {},
    ): string => {
      const syncId = overrides.syncId ?? `sync-${Math.random().toString(36).slice(2)}`;
      db.prepare(
        `INSERT INTO sync_entities
           (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
            last_server_sequence, payload, payload_hash, origin_device_id, created_at, updated_at,
            deleted)
         VALUES (?, ?, ?, 1, 1, 1, ?, 'hash', 'device-1', 1, 1, ?)`,
      ).run(ownerUid, entityType, syncId, JSON.stringify(payload), overrides.deleted ?? 0);
      return syncId;
    };

    const insertSession = (
      ownerUid: string,
      startedAt: number,
      overrides: { status?: string; deleted?: number; syncId?: string } = {},
    ): string =>
      insertEntity(
        ownerUid,
        'WORKOUT_SESSION',
        { status: overrides.status ?? 'COMPLETED', startedAt, finishedAt: startedAt + 3_600_000 },
        overrides,
      );

    const insertMeasurement = (ownerUid: string, date: number): string =>
      insertEntity(ownerUid, 'BODY_MEASUREMENT', { date, createdAt: date, weightKg: 80 });

    const contextFor = (entry: {
      timeZone: string;
      nowMillis: number;
      consistency: ConsistencyParameters | null;
    }): SocialProgressContext => ({
      weekTimeZone: entry.timeZone,
      consistency: entry.consistency,
      nowMs: entry.nowMillis,
    });

    it.each(PROJECTION_FIXTURE.cases.map((entry) => [entry.name, entry] as const))(
      'projeção sobre o banco: %s',
      async (_name, entry) => {
        for (const session of entry.completedSessions) {
          insertSession(UID_A, session.millis);
        }
        for (const measurement of entry.bodyMeasurements) {
          insertMeasurement(UID_A, measurement.millis);
        }

        const projection = await source.project(UID_A, contextFor(entry));

        if (entry.expected.level) {
          expect(projection.level).toEqual(available(entry.expected.level.level));
          expect(projection.consistencyStreak).toEqual(
            available(entry.expected.currentStreakWeeks),
          );
        } else {
          expect(projection.level).toEqual(unavailable());
          expect(projection.consistencyStreak).toEqual(unavailable());
        }
        expect(projection.highlightedAchievementIds).toEqual(
          available(entry.expected.earnedAchievementIds),
        );
      },
    );

    it.each(CONSISTENCY_FIXTURE.cases.map((entry) => [entry.name, entry] as const))(
      'sequência sobre o banco: %s',
      async (_name, entry) => {
        for (const session of entry.completedSessions) {
          insertSession(UID_A, session.millis);
        }
        const projection = await source.project(UID_A, contextFor(entry));
        if (entry.completedSessions.length === 0) {
          // Nenhuma sessão sincronizada: o servidor não sabe se são zero treinos ou zero
          // sincronizações, e não afirma sequência zero.
          expect(projection.consistencyStreak).toEqual(unavailable());
        } else {
          expect(projection.consistencyStreak).toEqual(
            available(entry.expected.currentStreakWeeks),
          );
        }
      },
    );

    const TEN = PROJECTION_FIXTURE.cases.find(
      (c) => c.name === 'dez-treinos-duas-semanas-cumpridas',
    )!;

    it('a mesma sessão canônica sincronizada de novo é uma sessão — nem XP, nem semana, nem conquista em dobro', async () => {
      // `sync_entities` tem chave `(owner_uid, entity_type, entity_sync_id)`: um replay do push é
      // um UPSERT da mesma linha. Aqui simulamos o resultado — a linha existe uma vez — e provamos
      // que a projeção não depende de "quantas vezes chegou".
      for (const session of TEN.completedSessions) {
        insertSession(UID_A, session.millis);
      }
      const before = await source.project(UID_A, contextFor(TEN));

      const replayed = TEN.completedSessions[0];
      const syncId = 'sync-replay';
      insertSession(UID_A, replayed.millis, { syncId });
      expect(() => insertSession(UID_A, replayed.millis, { syncId })).toThrow();

      // A "réplica" acrescentou uma sessão nova de verdade (syncId diferente) — então o total
      // sobe em exatamente um treino, e não em dois.
      const after = await source.project(UID_A, contextFor(TEN));
      expect(after.level).toEqual(before.level); // 11 treinos: 2500 XP, ainda nível 3
      expect(after.consistencyStreak).toEqual(before.consistencyStreak);
      expect(after.highlightedAchievementIds).toEqual(before.highlightedAchievementIds);
    });

    it('recalcular a projeção duas vezes dá o mesmo resultado — não existe estado a inflar', async () => {
      for (const session of TEN.completedSessions) {
        insertSession(UID_A, session.millis);
      }
      for (const measurement of TEN.bodyMeasurements) {
        insertMeasurement(UID_A, measurement.millis);
      }
      const first = await source.project(UID_A, contextFor(TEN));
      const second = await source.project(UID_A, contextFor(TEN));
      const third = await source.project(UID_A, contextFor(TEN));
      expect(second).toEqual(first);
      expect(third).toEqual(first);
      expect(first.level).toEqual(available(3));
    });

    it('sessão não concluída, tombstone e outros agregados não contam para nada', async () => {
      const base = TEN.completedSessions[0].millis;
      insertSession(UID_A, base);
      for (const status of ['PLANNED', 'IN_PROGRESS', 'PAUSED', 'CANCELLED']) {
        insertSession(UID_A, base + 60_000, { status });
      }
      insertSession(UID_A, base + 120_000, { deleted: 1 });
      insertEntity(UID_A, 'BODY_MEASUREMENT', { date: base, createdAt: base }, { deleted: 1 });

      const projection = await source.project(UID_A, contextFor(TEN));
      // 1 treino: 100 + 100 = 200 XP → nível 1; só `first_workout`; nenhuma medição viva.
      expect(projection.level).toEqual(available(1));
      expect(projection.highlightedAchievementIds).toEqual(available(['first_workout']));
    });

    it('a projeção é da conta — as sessões de B não entram na de A, e vice-versa', async () => {
      for (const session of TEN.completedSessions) {
        insertSession(UID_B, session.millis);
      }
      insertSession(UID_A, TEN.completedSessions[0].millis);

      const a = await source.project(UID_A, contextFor(TEN));
      const b = await source.project(UID_B, contextFor(TEN));
      expect(a.level).toEqual(available(1));
      expect(a.highlightedAchievementIds).toEqual(available(['first_workout']));
      expect(b.level).toEqual(available(3));
    });

    it('sem fuso declarado nada é afirmado; sem sessão sincronizada nível e sequência ficam UNAVAILABLE', async () => {
      expect(await source.project(UID_A, { ...contextFor(TEN), weekTimeZone: null })).toEqual({
        level: unavailable(),
        consistencyStreak: unavailable(),
        weeklyWorkoutCount: unavailable(),
        highlightedAchievementIds: unavailable(),
      });

      // Só uma medição: as conquistas de corpo já são afirmáveis; o resto não.
      insertMeasurement(UID_A, TEN.bodyMeasurements[0].millis);
      const projection = await source.project(UID_A, contextFor(TEN));
      expect(projection.level).toEqual(unavailable());
      expect(projection.consistencyStreak).toEqual(unavailable());
      expect(projection.weeklyWorkoutCount).toEqual(unavailable());
      expect(projection.highlightedAchievementIds).toEqual(available(['first_measurement']));
    });

    it('o treino anterior ao horizonte continua contando como treino, mas não como semana', async () => {
      // 2019-06-03 (segunda): antes de 2020-01-01. Conta para "N treinos" e para o XP de
      // conclusão; não entra em nenhuma janela de dia, então não vira semana nem missão.
      insertSession(UID_A, Date.UTC(2019, 5, 3, 12));
      for (const session of TEN.completedSessions) {
        insertSession(UID_A, session.millis);
      }
      const projection = await source.project(UID_A, contextFor(TEN));
      // 11 treinos → 2400 + 100 = 2500 XP → nível 3 (< 3000).
      expect(projection.level).toEqual(available(3));
      expect(projection.consistencyStreak).toEqual(available(TEN.expected.currentStreakWeeks!));
    });
  });

  // ------------------------------------------------------------------ o validador

  describe('o servidor não aceita progresso pronto — nem aninhado nos parâmetros', () => {
    const MONDAY = 20_703; // 2026-09-07

    it('aceita parâmetros de consistência bem formados', () => {
      const request = parseUpdateProgressSharingRequest({
        consistency: {
          trackingStartedAtEpochDay: MONDAY + 1,
          weeklyGoals: [
            { weekStartEpochDay: MONDAY, goal: 3 },
            { weekStartEpochDay: MONDAY - 7, goal: 2 },
          ],
        },
      });
      expect(request.consistency).toEqual({
        trackingStartedAtEpochDay: MONDAY + 1,
        weeklyGoals: [
          { weekStartEpochDay: MONDAY, goal: 3 },
          { weekStartEpochDay: MONDAY - 7, goal: 2 },
        ],
      });
    });

    it('aceita a meta da próxima semana — é o que setWeeklyGoal grava no aparelho', () => {
      const todayUtc = Math.floor(Date.now() / 86_400_000);
      const nextMonday = todayUtc - ((((todayUtc - 4) % 7) + 7) % 7) + 7;
      const request = parseUpdateProgressSharingRequest({
        consistency: {
          trackingStartedAtEpochDay: todayUtc,
          weeklyGoals: [{ weekStartEpochDay: nextMonday, goal: 4 }],
        },
      });
      expect(request.consistency?.weeklyGoals).toEqual([
        { weekStartEpochDay: nextMonday, goal: 4 },
      ]);
      // Duas semanas à frente já não é configuração do app.
      expect(() =>
        parseUpdateProgressSharingRequest({
          consistency: {
            trackingStartedAtEpochDay: todayUtc,
            weeklyGoals: [{ weekStartEpochDay: nextMonday + 7, goal: 4 }],
          },
        }),
      ).toThrow();
    });

    it.each([
      ['level no topo', { level: 7 }],
      ['xp no topo', { xp: 100_000 }],
      ['currentStreakWeeks no topo', { currentStreakWeeks: 12 }],
      ['unlockedAchievementIds no topo', { unlockedAchievementIds: ['100_workouts'] }],
      [
        'streak dentro de consistency',
        { consistency: { trackingStartedAtEpochDay: MONDAY, weeklyGoals: [], streak: 9 } },
      ],
      [
        'longestStreak dentro de consistency',
        { consistency: { trackingStartedAtEpochDay: MONDAY, weeklyGoals: [], longestStreak: 9 } },
      ],
      [
        'completedWorkouts dentro de um snapshot',
        {
          consistency: {
            trackingStartedAtEpochDay: MONDAY,
            weeklyGoals: [{ weekStartEpochDay: MONDAY, goal: 3, completedWorkouts: 3 }],
          },
        },
      ],
      [
        'campo desconhecido em consistency',
        { consistency: { trackingStartedAtEpochDay: MONDAY, weeklyGoals: [], verified: true } },
      ],
    ])('recusa a requisição inteira: %s', (_name, body) => {
      expect(() => parseUpdateProgressSharingRequest({ shareLevel: true, ...body })).toThrow(
        expect.objectContaining({
          response: expect.objectContaining({ code: 'INVALID_PROGRESS_SETTINGS' }),
        }),
      );
    });

    it.each([
      ['meta zero', { weekStartEpochDay: MONDAY, goal: 0 }],
      ['meta oito', { weekStartEpochDay: MONDAY, goal: 8 }],
      ['meta fracionária', { weekStartEpochDay: MONDAY, goal: 2.5 }],
      ['semana que não é segunda', { weekStartEpochDay: MONDAY + 1, goal: 3 }],
      ['semana antes do piso', { weekStartEpochDay: 4, goal: 3 }],
      ['semana no futuro distante', { weekStartEpochDay: MONDAY + 7 * 520, goal: 3 }],
    ])('recusa snapshot inválido: %s', (_name, snapshot) => {
      expect(() =>
        parseUpdateProgressSharingRequest({
          consistency: { trackingStartedAtEpochDay: MONDAY, weeklyGoals: [snapshot] },
        }),
      ).toThrow();
    });

    it('recusa semanas repetidas, lista que não é lista e início fora do intervalo', () => {
      expect(() =>
        parseUpdateProgressSharingRequest({
          consistency: {
            trackingStartedAtEpochDay: MONDAY,
            weeklyGoals: [
              { weekStartEpochDay: MONDAY, goal: 3 },
              { weekStartEpochDay: MONDAY, goal: 2 },
            ],
          },
        }),
      ).toThrow();
      expect(() =>
        parseUpdateProgressSharingRequest({
          consistency: { trackingStartedAtEpochDay: MONDAY, weeklyGoals: 'nenhuma' },
        }),
      ).toThrow();
      expect(() =>
        parseUpdateProgressSharingRequest({
          consistency: { trackingStartedAtEpochDay: 100, weeklyGoals: [] },
        }),
      ).toThrow();
      expect(() =>
        parseUpdateProgressSharingRequest({
          consistency: { trackingStartedAtEpochDay: MONDAY + 3_650, weeklyGoals: [] },
        }),
      ).toThrow();
    });
  });

  // ------------------------------------------------------------------ HTTP: privacidade, bloqueio, exclusão

  describe('pela API: privacidade, bloqueio, isolamento e exclusão', () => {
    const TOKEN_A = 'token-da-conta-a';
    const TOKEN_B = 'token-da-conta-b';
    const TOKEN_C = 'token-da-conta-c';
    const UID_C = 'uid-da-conta-c';

    const CASE = PROJECTION_FIXTURE.cases.find(
      (c) => c.name === 'dez-treinos-duas-semanas-cumpridas',
    )!;
    const TZ = CASE.timeZone;
    const NOW = CASE.nowMillis;

    let temp: TempDb;
    let app: INestApplication;
    let verifier: FakeAuthTokenVerifier;

    beforeEach(async () => {
      temp = createTempDb();
      verifier = new FakeAuthTokenVerifier()
        .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
        .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' })
        .accept(TOKEN_C, { uid: UID_C, email: 'c@example.com' });
      app = await createTestApp(
        configFor(temp.path, {
          ACCOUNT_DELETION_HMAC_KEY: 'test-hmac-key-for-account-deletion-very-secret',
        }),
        verifier,
      );
    });

    afterEach(async () => {
      jest.restoreAllMocks();
      await app?.close();
      temp.cleanup();
    });

    const server = () => app.getHttpServer();
    const auth = (token: string) => `Bearer ${token}`;

    const activate = (token: string, displayName: string) =>
      request(server())
        .post('/v1/social/me/activate')
        .set('Authorization', auth(token))
        .send({ displayName });

    const friendProfile = (token: string, socialId: string) =>
      request(server())
        .get(`/v1/social/friends/${socialId}/profile`)
        .set('Authorization', auth(token));

    const preview = (token: string) =>
      request(server()).get('/v1/social/me/profile-preview').set('Authorization', auth(token));

    const sharing = (token: string) =>
      request(server()).get('/v1/social/me/progress-sharing').set('Authorization', auth(token));

    const patchSharing = (token: string, body: object) =>
      request(server())
        .patch('/v1/social/me/progress-sharing')
        .set('Authorization', auth(token))
        .send(body);

    const becomeFriends = async (): Promise<{ socialIdA: string; socialIdB: string }> => {
      const a = await activate(TOKEN_A, 'Ana');
      const b = await activate(TOKEN_B, 'Igor');
      const sent = await request(server())
        .post('/v1/social/friend-requests')
        .set('Authorization', auth(TOKEN_A))
        .send({ socialId: b.body.profile.socialId });
      await request(server())
        .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
        .set('Authorization', auth(TOKEN_B))
        .expect(200);
      return { socialIdA: a.body.profile.socialId, socialIdB: b.body.profile.socialId };
    };

    const push = (
      token: string,
      mutations: Parameters<typeof pushBody>[0],
      deviceId = 'device-a',
    ) =>
      request(server())
        .post('/v1/sync/push')
        .set('Authorization', auth(token))
        .set('Content-Type', 'application/json')
        .send(pushBody(mutations, deviceId));

    const pushSession = (
      token: string,
      startedAt: number,
      syncId = uuid(),
      deviceId = 'device-a',
    ) =>
      push(
        token,
        [
          {
            entityType: 'WORKOUT_SESSION',
            entitySyncId: syncId,
            payload: sessionPayload(syncId, { startedAt, finishedAt: startedAt + 3_600_000 }),
          },
        ],
        deviceId,
      );

    const pushMeasurement = (token: string, date: number) => {
      const syncId = uuid();
      return push(token, [
        {
          entityType: 'BODY_MEASUREMENT',
          entitySyncId: syncId,
          payload: { ...measurementPayload(syncId), date, createdAt: date },
        },
      ]);
    };

    /** Sobe o cenário da fixture para o dono B, pelo caminho real (push). */
    const seedFixtureFor = async (token: string) => {
      for (const session of CASE.completedSessions) {
        await pushSession(token, session.millis).expect(200);
      }
      for (const measurement of CASE.bodyMeasurements) {
        await pushMeasurement(token, measurement.millis).expect(200);
      }
    };

    const freezeClock = () => jest.spyOn(Date, 'now').mockReturnValue(NOW);

    const ALL_ON = {
      shareLevel: true,
      shareConsistencyStreak: true,
      shareWeeklyWorkoutCount: true,
      shareHighlightedAchievements: true,
    };

    it('declarar parâmetros e sincronizar leva nível, sequência e conquistas de UNAVAILABLE a AVAILABLE — nunca UNSUPPORTED', async () => {
      await activate(TOKEN_B, 'Igor');
      freezeClock();

      // Nada declarado, nada sincronizado.
      let owner = await sharing(TOKEN_B);
      expect(owner.body.availability).toEqual({
        level: 'UNAVAILABLE',
        consistencyStreak: 'UNAVAILABLE',
        weeklyWorkoutCount: 'UNAVAILABLE',
        highlightedAchievements: 'UNAVAILABLE',
      });
      expect(owner.body.settings.consistency).toBeNull();

      // Fuso + sessões, sem parâmetros: só o que não depende deles.
      await patchSharing(TOKEN_B, { weekTimeZone: TZ }).expect(200);
      await seedFixtureFor(TOKEN_B);
      owner = await sharing(TOKEN_B);
      expect(owner.body.availability).toEqual({
        level: 'UNAVAILABLE',
        consistencyStreak: 'UNAVAILABLE',
        weeklyWorkoutCount: 'AVAILABLE',
        highlightedAchievements: 'AVAILABLE',
      });

      // Parâmetros declarados: tudo disponível.
      const patched = await patchSharing(TOKEN_B, { consistency: CASE.consistency }).expect(200);
      expect(patched.body.settings.consistency).toEqual(CASE.consistency);
      expect(patched.body.availability).toEqual({
        level: 'AVAILABLE',
        consistencyStreak: 'AVAILABLE',
        weeklyWorkoutCount: 'AVAILABLE',
        highlightedAchievements: 'AVAILABLE',
      });
      expect(JSON.stringify(owner.body)).not.toContain('UNSUPPORTED');
    });

    it('o amigo vê exatamente a projeção derivada — e a prévia do dono é a mesma coisa', async () => {
      const { socialIdB } = await becomeFriends();
      freezeClock();
      await seedFixtureFor(TOKEN_B);
      await patchSharing(TOKEN_B, {
        ...ALL_ON,
        weekTimeZone: TZ,
        consistency: CASE.consistency,
      }).expect(200);

      const friend = await friendProfile(TOKEN_A, socialIdB).expect(200);
      expect(friend.body.profile.sharedProgress).toEqual({
        level: CASE.expected.level!.level,
        consistencyStreak: CASE.expected.currentStreakWeeks,
        weeklyWorkoutCount: 1,
        highlightedAchievementIds: CASE.expected.earnedAchievementIds,
      });

      const mine = await preview(TOKEN_B).expect(200);
      expect(mine.body.profile.sharedProgress).toEqual(friend.body.profile.sharedProgress);
    });

    it('o replay do mesmo push — mesma sessão, mesmo clientMutationId ou não — não infla nada', async () => {
      const { socialIdB } = await becomeFriends();
      freezeClock();
      await seedFixtureFor(TOKEN_B);
      await patchSharing(TOKEN_B, {
        ...ALL_ON,
        weekTimeZone: TZ,
        consistency: CASE.consistency,
      }).expect(200);
      const before = (await friendProfile(TOKEN_A, socialIdB)).body.profile.sharedProgress;

      // A mesma sessão canônica, de dois aparelhos, três vezes.
      const syncId = uuid();
      const startedAt = CASE.completedSessions[CASE.completedSessions.length - 1].millis + 60_000;
      await pushSession(TOKEN_B, startedAt, syncId, 'device-a').expect(200);
      await pushSession(TOKEN_B, startedAt, syncId, 'device-b').expect(200);
      await pushSession(TOKEN_B, startedAt, syncId, 'device-a').expect(200);

      const after = (await friendProfile(TOKEN_A, socialIdB)).body.profile.sharedProgress;
      // Exatamente um treino a mais: a semana atual passa de 1 para 2, e nada mais muda (11
      // treinos = 2500 XP, ainda nível 3; 10_workouts já estava lá).
      expect(after).toEqual({ ...before, weeklyWorkoutCount: 2 });
    });

    it('o histórico reconstruído não vira evento social: ler a projeção não cria notificação, check-in nem "nova conquista"', async () => {
      const { socialIdB } = await becomeFriends();
      freezeClock();
      await seedFixtureFor(TOKEN_B);
      await patchSharing(TOKEN_B, {
        ...ALL_ON,
        weekTimeZone: TZ,
        consistency: CASE.consistency,
      }).expect(200);

      const postgres = app.get(PostgresService);
      const snapshot = async () => {
        const rows = await Promise.all(
          [
            'social_notification_events',
            'social_workout_checkins',
            'social_notification_deliveries',
            'sync_changes',
          ].map(async (table) =>
            Number(
              (
                await postgres.query<{ total: string | number }>(
                  `SELECT COUNT(*) AS total FROM ${table}`,
                )
              ).rows[0].total,
            ),
          ),
        );
        return rows;
      };

      const before = await snapshot();
      await friendProfile(TOKEN_A, socialIdB).expect(200);
      await preview(TOKEN_B).expect(200);
      await sharing(TOKEN_B).expect(200);
      await friendProfile(TOKEN_A, socialIdB).expect(200);
      expect(await snapshot()).toEqual(before);
    });

    it('privacidade: um interruptor desligado tira o campo do JSON — o servidor continua sabendo calculá-lo', async () => {
      const { socialIdB } = await becomeFriends();
      freezeClock();
      await seedFixtureFor(TOKEN_B);
      await patchSharing(TOKEN_B, {
        shareLevel: false,
        shareConsistencyStreak: true,
        shareWeeklyWorkoutCount: false,
        shareHighlightedAchievements: false,
        weekTimeZone: TZ,
        consistency: CASE.consistency,
      }).expect(200);

      const friend = await friendProfile(TOKEN_A, socialIdB).expect(200);
      expect(friend.body.profile.sharedProgress).toEqual({
        consistencyStreak: CASE.expected.currentStreakWeeks,
      });
      const body = JSON.stringify(friend.body);
      for (const forbidden of [
        'level',
        'highlightedAchievementIds',
        'weeklyWorkoutCount',
        'consistency"',
        'trackingStartedAtEpochDay',
        'weeklyGoals',
      ]) {
        expect({ forbidden, present: body.includes(forbidden) }).toEqual({
          forbidden,
          present: false,
        });
      }

      // E o dono continua vendo "disponível" para o que desligou.
      expect((await sharing(TOKEN_B)).body.availability.level).toBe('AVAILABLE');

      // Desligar tudo depois: objeto vazio, sem vestígio.
      await patchSharing(TOKEN_B, { shareConsistencyStreak: false }).expect(200);
      expect((await friendProfile(TOKEN_A, socialIdB)).body.profile.sharedProgress).toEqual({});
    });

    it('os parâmetros de consistência do dono nunca chegam ao amigo, e nenhum dado bruto chega junto com as conquistas', async () => {
      const { socialIdB } = await becomeFriends();
      freezeClock();
      await seedFixtureFor(TOKEN_B);
      await patchSharing(TOKEN_B, {
        ...ALL_ON,
        weekTimeZone: TZ,
        consistency: CASE.consistency,
      }).expect(200);

      const response = await friendProfile(TOKEN_A, socialIdB).expect(200);
      const body = JSON.stringify(response.body);
      for (const forbidden of [
        UID_A,
        UID_B,
        'ownerUid',
        'trackingStartedAtEpochDay',
        'weeklyGoals',
        'weekStartEpochDay',
        'weekTimeZone',
        'xp',
        'Xp',
        'weightKg',
        'startedAt',
        'finishedAt',
        'payload',
        'syncId',
        'availability',
        'UNAVAILABLE',
        'UNSUPPORTED',
        'PERFORMANCE',
        '_pr',
        // "share" aparece só como prefixo de `sharedProgress` — que é o envelope do contrato.
        'shareLevel',
        'shareConsistencyStreak',
        'shareHighlightedAchievements',
      ]) {
        expect({ forbidden, present: body.includes(forbidden) }).toEqual({
          forbidden,
          present: false,
        });
      }
      expect(Object.keys(response.body.profile.sharedProgress).sort()).toEqual([
        'consistencyStreak',
        'highlightedAchievementIds',
        'level',
        'weeklyWorkoutCount',
      ]);
    });

    it('bloqueio vence: depois do block o perfil some para os dois lados', async () => {
      const { socialIdA, socialIdB } = await becomeFriends();
      freezeClock();
      await seedFixtureFor(TOKEN_B);
      await patchSharing(TOKEN_B, {
        ...ALL_ON,
        weekTimeZone: TZ,
        consistency: CASE.consistency,
      }).expect(200);
      await friendProfile(TOKEN_A, socialIdB).expect(200);

      await request(server())
        .post('/v1/social/blocks')
        .set('Authorization', auth(TOKEN_A))
        .send({ blockedSocialId: socialIdB })
        .expect(200);

      const blocked = await friendProfile(TOKEN_A, socialIdB);
      expect(blocked.status).toBe(404);
      expect(blocked.body.error.code).toBe('FRIEND_PROFILE_NOT_FOUND');
      const reverse = await friendProfile(TOKEN_B, socialIdA);
      expect(reverse.status).toBe(404);
    });

    it('terceiro não lê, e o progresso de B não vaza para a projeção de A', async () => {
      const { socialIdA, socialIdB } = await becomeFriends();
      await activate(TOKEN_C, 'Carla');
      freezeClock();
      await seedFixtureFor(TOKEN_B);
      await patchSharing(TOKEN_B, {
        ...ALL_ON,
        weekTimeZone: TZ,
        consistency: CASE.consistency,
      }).expect(200);
      await patchSharing(TOKEN_A, {
        ...ALL_ON,
        weekTimeZone: TZ,
        consistency: CASE.consistency,
      }).expect(200);

      const stranger = await friendProfile(TOKEN_C, socialIdB);
      expect(stranger.status).toBe(404);

      // A não sincronizou nada: com todos os interruptores ligados, A publica nada.
      const aSeenByB = await friendProfile(TOKEN_B, socialIdA).expect(200);
      expect(aSeenByB.body.profile.sharedProgress).toEqual({});
      expect((await sharing(TOKEN_A)).body.availability.level).toBe('UNAVAILABLE');
    });

    it('exclusão de conta apaga os parâmetros junto com o resto, e a reconciliação não os ressuscita', async () => {
      await activate(TOKEN_B, 'Igor');
      freezeClock();
      await seedFixtureFor(TOKEN_B);
      await patchSharing(TOKEN_B, {
        ...ALL_ON,
        weekTimeZone: TZ,
        consistency: CASE.consistency,
      }).expect(200);

      const postgres = app.get(PostgresService);
      const countGoals = async () =>
        Number(
          (
            await postgres.query<{ total: string | number }>(
              `SELECT COUNT(*) AS total FROM social_progress_weekly_goals WHERE owner_uid = $1`,
              [UID_B],
            )
          ).rows[0].total,
        );
      expect(await countGoals()).toBe(CASE.consistency!.weeklyGoals.length);

      await request(server()).delete('/v1/account').set('Authorization', auth(TOKEN_B)).expect(200);

      expect(await countGoals()).toBe(0);
      const settings = await postgres.query(
        `SELECT 1 FROM social_progress_settings WHERE owner_uid = $1`,
        [UID_B],
      );
      expect(settings.rows).toHaveLength(0);
      const sessions = await postgres.query(`SELECT 1 FROM sync_entities WHERE owner_uid = $1`, [
        UID_B,
      ]);
      expect(sessions.rows).toHaveLength(0);

      // A conta excluída não volta a configurar nada.
      const rejected = await patchSharing(TOKEN_B, { consistency: CASE.consistency });
      expect(rejected.status).toBe(403);

      // E o inventário de purge conhece a tabela nova — é ele que a reconciliação de DR usa.
      expect(ACCOUNT_UID_COLUMNS).toEqual(
        expect.arrayContaining([
          { table: 'social_progress_weekly_goals', column: 'owner_uid', role: 'OWNER' },
        ]),
      );
      const repository = app.get(AccountDeletionRepository);
      expect(await repository.listAllOwnerUidsInDatabase()).not.toContain(UID_B);
    });

    it('substituir os parâmetros troca o conjunto inteiro — a meta antiga não sobrevive ao lado da nova', async () => {
      await activate(TOKEN_B, 'Igor');
      freezeClock();
      await patchSharing(TOKEN_B, { weekTimeZone: TZ, consistency: CASE.consistency }).expect(200);

      const replaced = {
        trackingStartedAtEpochDay: CASE.consistency!.trackingStartedAtEpochDay + 7,
        weeklyGoals: [
          { weekStartEpochDay: CASE.consistency!.weeklyGoals[0].weekStartEpochDay + 7, goal: 2 },
        ],
      };
      const response = await patchSharing(TOKEN_B, { consistency: replaced }).expect(200);
      expect(response.body.settings.consistency).toEqual(replaced);
      expect((await sharing(TOKEN_B)).body.settings.consistency).toEqual(replaced);
    });

    it('o corpo com progresso pronto é recusado pela API com INVALID_PROGRESS_SETTINGS', async () => {
      await activate(TOKEN_B, 'Igor');
      for (const body of [
        { level: 99 },
        { xp: 1_000_000, shareLevel: true },
        { consistency: { ...CASE.consistency, currentStreakWeeks: 52 } },
        {
          consistency: {
            trackingStartedAtEpochDay: CASE.consistency!.trackingStartedAtEpochDay,
            weeklyGoals: [
              { weekStartEpochDay: CASE.consistency!.weeklyGoals[0].weekStartEpochDay, goal: 99 },
            ],
          },
        },
      ]) {
        const response = await patchSharing(TOKEN_B, body);
        expect(response.status).toBe(400);
        expect(response.body.error.code).toBe('INVALID_PROGRESS_SETTINGS');
      }
      // E nada foi gravado pela metade.
      expect((await sharing(TOKEN_B)).body.settings.consistency).toBeNull();
    });
  });
});
