import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import BetterSqlite3 from 'better-sqlite3';
import { loadMigrations, runMigrations } from '../src/database/migration-runner';
import {
  SocialProgressPrivacyFilter,
  SocialProgressProjector,
  type SocialProgressProjection,
} from '../src/modules/social/social-progress.projector';
import {
  SOCIAL_PROGRESS_SHARING_DEFAULTS,
  type StoredProgressSettings,
} from '../src/modules/social/social-progress.repository';
import {
  available,
  canonicalWeekWindow,
  isValidTimeZone,
  type SocialProgressSource,
  SyncedSocialProgressSource,
  unavailable,
  unsupported,
} from '../src/modules/social/social-progress.source';
import { createTempDb, MIGRATIONS_DIR, type TempDb } from './support/temp-db';

const WEEK_FIXTURE = JSON.parse(
  readFileSync(
    join(__dirname, '..', '..', 'contracts', 'social', 'v1', 'weekly-window.json'),
    'utf8',
  ),
) as WeekFixture;

interface WeekFixture {
  cases: Array<{
    name: string;
    timeZone: string;
    nowMillis: number;
    weekStartMillis: number;
    weekEndMillis: number;
    weekDurationHours: number;
    completedSessions: Array<{ millis: number; note: string }>;
    expectedWeeklyWorkoutCount: number;
  }>;
}

const UID_A = 'uid-da-conta-a';
const UID_B = 'uid-da-conta-b';

/**
 * A projeção de progresso: semana canônica, fontes reais e ausência que não vira zero (T17.2).
 *
 * Estes testes não sobem HTTP. Eles existem para provar as três coisas que a API sozinha não
 * prova:
 *
 * 1. **a semana é a canônica**, com as mesmas fronteiras que o `ConsistencyCalculator` usa — e a
 *    prova é a fixture compartilhada, lida também pelo teste do Android;
 * 2. **cada métrica usa a autoridade esperada** (§147), e as que não têm autoridade remota
 *    respondem `UNSUPPORTED` em vez de um número inventado por uma regra paralela;
 * 3. **ausência de dado nunca vira zero** (§4/§146).
 */
describe('Projeção de progresso social', () => {
  // ------------------------------------------------------------------ a semana canônica

  describe('a semana canônica é a mesma dos dois lados', () => {
    it.each(WEEK_FIXTURE.cases.map((entry) => [entry.name, entry] as const))(
      'janela e contagem: %s',
      (_name, entry) => {
        const window = canonicalWeekWindow(entry.nowMillis, entry.timeZone);

        expect(window).toEqual({
          startMs: entry.weekStartMillis,
          endMs: entry.weekEndMillis,
        });
        // A duração confirma que o fim é a meia-noite da segunda seguinte, e não
        // "início + 7×24h": nas semanas com virada de horário de verão ela não é 168h.
        expect((window!.endMs - window!.startMs) / 3_600_000).toBe(entry.weekDurationHours);

        const counted = entry.completedSessions.filter(
          (session) => session.millis >= window!.startMs && session.millis < window!.endMs,
        );
        expect(counted).toHaveLength(entry.expectedWeeklyWorkoutCount);
      },
    );

    it('o intervalo é fechado no início e aberto no fim', () => {
      // Sem isso, um treino da meia-noite da segunda seguinte contaria em duas semanas.
      const window = canonicalWeekWindow(Date.parse('2026-09-08T18:00:00Z'), 'America/Sao_Paulo')!;
      const next = canonicalWeekWindow(window.endMs, 'America/Sao_Paulo')!;
      expect(next.startMs).toBe(window.endMs);
    });

    it('um fuso inválido não vira UTC — ele não produz janela nenhuma', () => {
      // Um palpite produziria uma semana plausível e errada, que é pior do que campo ausente.
      expect(canonicalWeekWindow(Date.now(), 'Terra/Media')).toBeNull();
      expect(canonicalWeekWindow(Date.now(), '')).toBeNull();
      expect(isValidTimeZone('America/Sao_Paulo')).toBe(true);
      expect(isValidTimeZone('UTC-3')).toBe(false);
    });
  });

  // ------------------------------------------------------------------ a fonte sobre o banco real

  describe('a fonte lê o estado canônico sincronizado, e nada além dele', () => {
    let temp: TempDb;
    let db: BetterSqlite3.Database;
    // Tipada pela **interface**: é assim que o resto do sistema a vê, e é o que garante que os
    // testes falem com a fronteira e não com detalhes da implementação.
    let source: SocialProgressSource;

    const TZ = 'America/Sao_Paulo';
    const NOW = Date.parse('2026-09-08T18:00:00Z');

    beforeEach(() => {
      temp = createTempDb();
      db = new BetterSqlite3(temp.path);
      db.pragma('foreign_keys = ON');
      runMigrations(db, loadMigrations(MIGRATIONS_DIR));
      // A fonte recebe o `SqliteService`; aqui basta um objeto com a conexão, porque é só isso
      // que ela usa — e é essa pobreza da dependência que mantém a fronteira estreita.
      source = new SyncedSocialProgressSource({ connection: db } as never);
    });

    afterEach(() => {
      db.close();
      temp.cleanup();
    });

    const insertSession = (
      ownerUid: string,
      startedAt: number,
      overrides: { status?: string; deleted?: number; entityType?: string } = {},
    ): void => {
      db.prepare(
        `INSERT INTO sync_entities
           (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
            last_server_sequence, payload, payload_hash, origin_device_id, created_at, updated_at,
            deleted)
         VALUES (?, ?, ?, 1, 1, 1, ?, 'hash', 'device-1', 1, 1, ?)`,
      ).run(
        ownerUid,
        overrides.entityType ?? 'WORKOUT_SESSION',
        `sync-${Math.random().toString(36).slice(2)}`,
        JSON.stringify({ status: overrides.status ?? 'COMPLETED', startedAt }),
        overrides.deleted ?? 0,
      );
    };

    it('conta as sessões concluídas da semana canônica do dono', () => {
      insertSession(UID_A, Date.parse('2026-09-07T09:00:00Z')); // segunda, 6h local
      insertSession(UID_A, Date.parse('2026-09-08T22:00:00Z')); // terça, 19h local
      insertSession(UID_A, Date.parse('2026-09-14T01:00:00Z')); // domingo, 22h local

      expect(source.getWeeklyWorkoutCount(UID_A, TZ, NOW)).toEqual(available(3));
    });

    it('não conta sessão de outra semana — inclusive a que em UTC parece da semana certa', () => {
      insertSession(UID_A, Date.parse('2026-09-07T09:00:00Z'));
      // Domingo 6, 20h em São Paulo: em UTC já é dia 6 às 23h, e ainda assim é da semana anterior.
      insertSession(UID_A, Date.parse('2026-09-06T23:00:00Z'));
      // Segunda 14, 0h30 local: já é a semana seguinte.
      insertSession(UID_A, Date.parse('2026-09-14T03:30:00Z'));

      expect(source.getWeeklyWorkoutCount(UID_A, TZ, NOW)).toEqual(available(1));
    });

    it('nunca conta status que não seja COMPLETED', () => {
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));
      for (const status of ['PLANNED', 'IN_PROGRESS', 'PAUSED', 'CANCELLED']) {
        insertSession(UID_A, Date.parse('2026-09-08T13:00:00Z'), { status });
      }

      expect(source.getWeeklyWorkoutCount(UID_A, TZ, NOW)).toEqual(available(1));
    });

    it('não conta entidade com tombstone', () => {
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));
      insertSession(UID_A, Date.parse('2026-09-08T13:00:00Z'), { deleted: 1 });

      expect(source.getWeeklyWorkoutCount(UID_A, TZ, NOW)).toEqual(available(1));
    });

    it('não conta outros agregados', () => {
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));
      insertSession(UID_A, Date.parse('2026-09-08T13:00:00Z'), { entityType: 'BODY_MEASUREMENT' });
      insertSession(UID_A, Date.parse('2026-09-08T14:00:00Z'), { entityType: 'CHECK_IN' });

      expect(source.getWeeklyWorkoutCount(UID_A, TZ, NOW)).toEqual(available(1));
    });

    it('não conta a sessão de outra conta', () => {
      // Ownership não é verificação depois da leitura: o `owner_uid` está no `WHERE`.
      insertSession(UID_B, Date.parse('2026-09-08T12:00:00Z'));
      insertSession(UID_A, Date.parse('2026-09-08T13:00:00Z'));

      expect(source.getWeeklyWorkoutCount(UID_A, TZ, NOW)).toEqual(available(1));
      expect(source.getWeeklyWorkoutCount(UID_B, TZ, NOW)).toEqual(available(1));
    });

    it('quem nunca sincronizou uma sessão recebe UNAVAILABLE, e não zero', () => {
      // A diferença que importa: "treinou zero vezes esta semana" é um fato; "nunca sincronizou"
      // é ausência de informação, e o servidor não pode transformar uma na outra (§4/§74).
      expect(source.getWeeklyWorkoutCount(UID_A, TZ, NOW)).toEqual(unavailable());
    });

    it('quem já sincronizou e não treinou nesta semana recebe zero — aí o zero é verdade', () => {
      insertSession(UID_A, Date.parse('2026-08-10T12:00:00Z'));
      expect(source.getWeeklyWorkoutCount(UID_A, TZ, NOW)).toEqual(available(0));
    });

    it('sem fuso declarado, a contagem é UNAVAILABLE', () => {
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));
      expect(source.getWeeklyWorkoutCount(UID_A, null, NOW)).toEqual(unavailable());
      expect(source.getWeeklyWorkoutCount(UID_A, 'Terra/Media', NOW)).toEqual(unavailable());
    });

    it('nível, sequência e conquistas não têm autoridade remota nesta versão', () => {
      // Elas não são `UNAVAILABLE` (que significaria "sincronize e resolve"): `xp_transactions`,
      // `weekly_goal_history` no sync incremental e `achievement_unlocks` simplesmente não chegam
      // ao servidor. Responder um número aqui exigiria portar `XpCalculatorService`,
      // `ConsistencyCalculator` e `AchievementEvaluator` — a segunda autoridade que a T17.2 proíbe.
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));

      expect(source.getLevel(UID_A)).toEqual(unsupported());
      expect(source.getConsistencyStreak(UID_A)).toEqual(unsupported());
      expect(source.getEarnedAchievementIds(UID_A)).toEqual(unsupported());
    });
  });

  // ------------------------------------------------------------------ projetor e filtro

  describe('o projetor não decide acesso e não calcula regra de domínio', () => {
    const fakeSource = (overrides: Partial<SocialProgressSource> = {}): SocialProgressSource => ({
      getLevel: () => unsupported(),
      getConsistencyStreak: () => unsupported(),
      getWeeklyWorkoutCount: () => unsupported(),
      getEarnedAchievementIds: () => unsupported(),
      ...overrides,
    });

    it('pergunta à fonte pelo dono, e devolve exatamente o que ela respondeu', () => {
      const seen: string[] = [];
      const projector = new SocialProgressProjector(
        fakeSource({
          getLevel: (ownerUid) => {
            seen.push(ownerUid);
            return available(14);
          },
          getWeeklyWorkoutCount: (ownerUid, tz, now) => {
            seen.push(`${ownerUid}|${tz}|${now}`);
            return available(3);
          },
        }),
      );

      const projection = projector.project(UID_A, 'America/Sao_Paulo', 1_700_000_000_000);

      expect(projection.level).toEqual(available(14));
      expect(projection.weeklyWorkoutCount).toEqual(available(3));
      expect(seen).toEqual([UID_A, `${UID_A}|America/Sao_Paulo|1700000000000`]);
    });
  });

  describe('o filtro de privacidade exige as duas condições', () => {
    const filter = new SocialProgressPrivacyFilter();

    const projection = (
      overrides: Partial<SocialProgressProjection> = {},
    ): SocialProgressProjection => ({
      level: available(14),
      consistencyStreak: available(4),
      weeklyWorkoutCount: available(3),
      highlightedAchievementIds: available(['first_workout']),
      ...overrides,
    });

    const settings = (overrides: Partial<StoredProgressSettings> = {}): StoredProgressSettings => ({
      ...SOCIAL_PROGRESS_SHARING_DEFAULTS,
      updatedAt: 1,
      ...overrides,
    });

    it('nada é publicado com os defaults', () => {
      expect(filter.apply(projection(), settings())).toEqual({});
    });

    it('só o campo ligado aparece', () => {
      expect(filter.apply(projection(), settings({ shareLevel: true }))).toEqual({ level: 14 });
      expect(filter.apply(projection(), settings({ shareWeeklyWorkoutCount: true }))).toEqual({
        weeklyWorkoutCount: 3,
      });
    });

    it('ligado sem dado não publica zero — publica nada', () => {
      expect(
        filter.apply(
          projection({ weeklyWorkoutCount: unavailable() }),
          settings({ shareWeeklyWorkoutCount: true }),
        ),
      ).toEqual({});
      expect(
        filter.apply(projection({ level: unsupported() }), settings({ shareLevel: true })),
      ).toEqual({});
    });

    it('escondido e indisponível produzem exatamente a mesma resposta', () => {
      const hidden = filter.apply(projection(), settings({ shareLevel: false }));
      const missing = filter.apply(
        projection({ level: unavailable() }),
        settings({ shareLevel: true }),
      );
      expect(hidden).toEqual(missing);
      expect('level' in hidden).toBe(false);
    });

    it('a disponibilidade não passa por privacidade — ela é a pergunta do dono', () => {
      const availability = filter.availabilityOf(
        projection({ weeklyWorkoutCount: unavailable(), level: unsupported() }),
      );
      expect(availability).toEqual({
        level: 'UNSUPPORTED',
        consistencyStreak: 'AVAILABLE',
        weeklyWorkoutCount: 'UNAVAILABLE',
        highlightedAchievements: 'AVAILABLE',
      });
    });
  });
});
