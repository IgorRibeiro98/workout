import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import BetterSqlite3 from 'better-sqlite3';
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
  type SocialProgressContext,
  type SocialProgressSource,
  SyncedSocialProgressSource,
  unavailable,
  unsupported,
} from '../src/modules/social/social-progress.source';
import { PostgresService } from '../src/database/postgres.service';
import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';

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
 * 2. **cada métrica usa a autoridade esperada** (§147) — e, desde a T19.2, as que dependem de
 *    parâmetros que o dono ainda não declarou respondem `UNAVAILABLE` em vez de um número
 *    inventado por uma regra paralela (a autoridade remota em si é provada em
 *    `social-progress-v2.spec.ts`);
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
    let postgres: PostgresService;
    // Tipada pela **interface**: é assim que o resto do sistema a vê, e é o que garante que os
    // testes falem com a fronteira e não com detalhes da implementação.
    let source: SocialProgressSource;

    const TZ = 'America/Sao_Paulo';
    const NOW = Date.parse('2026-09-08T18:00:00Z');

    const contextOf = (weekTimeZone: string | null, nowMs = NOW): SocialProgressContext => ({
      weekTimeZone,
      consistency: null,
      nowMs,
    });
    const weeklyCount = async (ownerUid: string, tz: string | null, nowMs = NOW) =>
      (await source.project(ownerUid, contextOf(tz, nowMs))).weeklyWorkoutCount;

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

    it('conta as sessões concluídas da semana canônica do dono', async () => {
      insertSession(UID_A, Date.parse('2026-09-07T09:00:00Z')); // segunda, 6h local
      insertSession(UID_A, Date.parse('2026-09-08T22:00:00Z')); // terça, 19h local
      insertSession(UID_A, Date.parse('2026-09-14T01:00:00Z')); // domingo, 22h local

      expect(await weeklyCount(UID_A, TZ)).toEqual(available(3));
    });

    it('não conta sessão de outra semana — inclusive a que em UTC parece da semana certa', async () => {
      insertSession(UID_A, Date.parse('2026-09-07T09:00:00Z'));
      // Domingo 6, 20h em São Paulo: em UTC já é dia 6 às 23h, e ainda assim é da semana anterior.
      insertSession(UID_A, Date.parse('2026-09-06T23:00:00Z'));
      // Segunda 14, 0h30 local: já é a semana seguinte.
      insertSession(UID_A, Date.parse('2026-09-14T03:30:00Z'));

      expect(await weeklyCount(UID_A, TZ)).toEqual(available(1));
    });

    it('nunca conta status que não seja COMPLETED', async () => {
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));
      for (const status of ['PLANNED', 'IN_PROGRESS', 'PAUSED', 'CANCELLED']) {
        insertSession(UID_A, Date.parse('2026-09-08T13:00:00Z'), { status });
      }

      expect(await weeklyCount(UID_A, TZ)).toEqual(available(1));
    });

    it('não conta entidade com tombstone', async () => {
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));
      insertSession(UID_A, Date.parse('2026-09-08T13:00:00Z'), { deleted: 1 });

      expect(await weeklyCount(UID_A, TZ)).toEqual(available(1));
    });

    it('não conta outros agregados', async () => {
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));
      insertSession(UID_A, Date.parse('2026-09-08T13:00:00Z'), { entityType: 'BODY_MEASUREMENT' });
      insertSession(UID_A, Date.parse('2026-09-08T14:00:00Z'), { entityType: 'CHECK_IN' });

      expect(await weeklyCount(UID_A, TZ)).toEqual(available(1));
    });

    it('não conta a sessão de outra conta', async () => {
      // Ownership não é verificação depois da leitura: o `owner_uid` está no `WHERE`.
      insertSession(UID_B, Date.parse('2026-09-08T12:00:00Z'));
      insertSession(UID_A, Date.parse('2026-09-08T13:00:00Z'));

      expect(await weeklyCount(UID_A, TZ)).toEqual(available(1));
      expect(await weeklyCount(UID_B, TZ)).toEqual(available(1));
    });

    it('quem nunca sincronizou uma sessão recebe UNAVAILABLE, e não zero', async () => {
      // A diferença que importa: "treinou zero vezes esta semana" é um fato; "nunca sincronizou"
      // é ausência de informação, e o servidor não pode transformar uma na outra (§4/§74).
      expect(await weeklyCount(UID_A, TZ)).toEqual(unavailable());
    });

    it('quem já sincronizou e não treinou nesta semana recebe zero — aí o zero é verdade', async () => {
      insertSession(UID_A, Date.parse('2026-08-10T12:00:00Z'));
      expect(await weeklyCount(UID_A, TZ)).toEqual(available(0));
    });

    it('sem fuso declarado, a contagem é UNAVAILABLE', async () => {
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));
      expect(await weeklyCount(UID_A, null)).toEqual(unavailable());
      expect(await weeklyCount(UID_A, 'Terra/Media')).toEqual(unavailable());
    });

    it('nível, sequência e conquistas ficam UNAVAILABLE enquanto o dono não declara os parâmetros', async () => {
      // Não é `UNSUPPORTED`: desde a T19.2 existe autoridade remota (`social-consistency.ts`,
      // `social-gamification.ts`). O que falta aqui é o insumo que só o dono pode declarar — meta
      // por semana e início do acompanhamento — e "ainda não disponível" é a frase certa para isso.
      // As conquistas de treino, porém, já são afirmáveis só com a sessão.
      insertSession(UID_A, Date.parse('2026-09-08T12:00:00Z'));

      const projection = await source.project(UID_A, contextOf(TZ));
      expect(projection.level).toEqual(unavailable());
      expect(projection.consistencyStreak).toEqual(unavailable());
      expect(projection.highlightedAchievementIds).toEqual(available(['first_workout']));
    });

    it('a contagem semanal por dias locais reproduz cada caso da fixture sobre o banco real', async () => {
      // A T19.2 passou a somar a semana a partir da projeção por dia local (uma consulta para as
      // quatro métricas). O resultado precisa ser o mesmo da janela `[segunda, segunda)` da T17.2.
      for (const entry of WEEK_FIXTURE.cases) {
        const ownerUid = `uid-${entry.name}`;
        for (const session of entry.completedSessions) {
          insertSession(ownerUid, session.millis);
        }
        expect(await weeklyCount(ownerUid, entry.timeZone, entry.nowMillis)).toEqual(
          available(entry.expectedWeeklyWorkoutCount),
        );
      }
    });
  });

  // ------------------------------------------------------------------ projetor e filtro

  describe('o projetor não decide acesso e não calcula regra de domínio', () => {
    const fakeSource = (
      answer: (ownerUid: string, context: SocialProgressContext) => SocialProgressProjection,
    ): SocialProgressSource => ({
      project: (ownerUid, context) => Promise.resolve(answer(ownerUid, context)),
    });

    it('pergunta à fonte pelo dono, com o fuso e os parâmetros dele, e devolve exatamente o que ela respondeu', async () => {
      const seen: Array<{ ownerUid: string; context: SocialProgressContext }> = [];
      const projector = new SocialProgressProjector(
        fakeSource((ownerUid, context) => {
          seen.push({ ownerUid, context });
          return {
            level: available(14),
            consistencyStreak: unsupported(),
            weeklyWorkoutCount: available(3),
            highlightedAchievementIds: unavailable(),
          };
        }),
      );

      const consistency = { trackingStartedAtEpochDay: 20_682, weeklyGoals: [] };
      const projection = await projector.project(
        UID_A,
        { weekTimeZone: 'America/Sao_Paulo', consistency },
        1_700_000_000_000,
      );

      expect(projection.level).toEqual(available(14));
      expect(projection.weeklyWorkoutCount).toEqual(available(3));
      expect(projection.consistencyStreak).toEqual(unsupported());
      expect(seen).toEqual([
        {
          ownerUid: UID_A,
          context: { weekTimeZone: 'America/Sao_Paulo', consistency, nowMs: 1_700_000_000_000 },
        },
      ]);
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
