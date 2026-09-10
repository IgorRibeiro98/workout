import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import {
  challengeDayWindows,
  SyncedChallengeProgressSource,
} from '../src/modules/social/challenge-progress.source';
import { ChallengeScoringService } from '../src/modules/social/challenge.scoring';
import type { ChallengeProgressSource } from '../src/modules/social/challenge-progress.source';
import {
  account,
  acceptChallenge,
  befriend,
  createChallenge,
  enableSocial,
  pushCompletedSession,
  SAO_PAULO,
  saoPauloInstant,
  type TestAccount,
} from './support/challenge-fixtures';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { PostgresService } from '../src/database/postgres.service';
import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';

/**
 * A pontuação dos desafios (T17.3 §220–§222).
 *
 * ```text
 * WorkoutSession canônica ──(sync real)──▶ sync_entities
 *                                                │
 *                                    ChallengeProgressSource
 *                                                │
 *                                    ChallengeScoringService
 *                                                │
 *                                            placar
 * ```
 *
 * As sessões entram pelo **endpoint de sync real**, e não por `INSERT` em `sync_entities`. É o que
 * garante que a pontuação lê o mesmo estado que um aparelho de verdade produz — inclusive a
 * política que só aceita `COMPLETED` e a constraint de identidade que faz um reenvio não contar
 * duas vezes.
 *
 * ## O timestamp que decide: `startedAt`
 *
 * O Spark **não tem** `completedAt`. `startedAt` é o instante canônico que atribui um treino a um
 * dia — a regra de `ConsistencyCalculator`, congelada em `contracts/social/v1/weekly-window.json`
 * e usada pela T17.2. `finishedAt` é nulável no schema e não participa da elegibilidade; usá-lo
 * seria uma segunda regra de conclusão de treino (bloqueante), e perderia silenciosamente toda
 * sessão com ele nulo. Ver `challenge-progress.source.ts`.
 */
describe('Pontuação de desafio', () => {
  const igor = account('igor', 'Igor');
  const joao = account('joao', 'João');
  const jonathas = account('jonathas', 'Jonathas');

  const NOW = saoPauloInstant('2026-09-08T12:00:00');
  const START_DATE = '2026-09-10';
  const END_DATE = '2026-09-19';
  const STARTS = saoPauloInstant('2026-09-10T00:00:00');
  /** A meia-noite local do dia **seguinte** ao último dia: a janela é `[início, fim)`. */
  const ENDS_EXCLUSIVE = saoPauloInstant('2026-09-20T00:00:00');

  // --------------------------------------------------------------------- ponta a ponta

  describe('sobre o sync real', () => {
    let temp: TempDb;
    let app: INestApplication;
    let clock: FakeClock;
    let challengeId: string;

    beforeEach(async () => {
      temp = createTempDb();
      clock = new FakeClock(NOW);

      const verifier = new FakeAuthTokenVerifier();
      for (const who of [igor, joao, jonathas]) {
        verifier.accept(who.token, { uid: who.uid });
      }
      app = await createTestApp(configFor(temp.path), verifier, undefined, clock);

      for (const who of [igor, joao, jonathas]) {
        await enableSocial(app, who);
      }
      await befriend(app, igor, joao);
      await befriend(app, igor, jonathas);

      const created = await createChallenge(app, igor, [joao, jonathas], {
        startDate: START_DATE,
        endDate: END_DATE,
        target: 12,
      });
      challengeId = created.challengeId;
      await acceptChallenge(app, joao, challengeId);
      await acceptChallenge(app, jonathas, challengeId);

      // O desafio começou.
      clock.set(STARTS + 60_000);
    });

    afterEach(async () => {
      await app.close();
      temp.cleanup();
    });

    const auth = (who: TestAccount) => `Bearer ${who.token}`;

    const leaderboard = async (who: TestAccount = igor) => {
      const response = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(who))
        .expect(200);
      return response.body;
    };

    it('WORKOUTS_COMPLETED: uma sessão concluída na janela vale 1 (§3/§220)', async () => {
      await pushCompletedSession(app, igor, 'sess-1', saoPauloInstant('2026-09-10T07:00:00'));
      await pushCompletedSession(app, igor, 'sess-2', saoPauloInstant('2026-09-11T07:00:00'));
      await pushCompletedSession(app, joao, 'sess-3', saoPauloInstant('2026-09-11T19:00:00'));

      const body = await leaderboard();
      const scores = Object.fromEntries(
        body.participants.map((p: { displayName: string; score: number }) => [
          p.displayName,
          p.score,
        ]),
      );
      expect(scores).toEqual({ Igor: 2, João: 1, Jonathas: 0 });
    });

    it('sessão fora da janela não conta, nas duas pontas', async () => {
      // Um segundo antes do início, e o instante exato do fim (exclusivo).
      await pushCompletedSession(app, igor, 'antes', STARTS - 1);
      await pushCompletedSession(app, igor, 'depois', ENDS_EXCLUSIVE);
      // E os dois extremos que **contam**: o primeiro instante e o último.
      await pushCompletedSession(app, igor, 'primeiro', STARTS);
      await pushCompletedSession(app, igor, 'ultimo', ENDS_EXCLUSIVE - 1);

      const body = await leaderboard();
      expect(
        body.participants.find((p: { displayName: string }) => p.displayName === 'Igor').score,
      ).toBe(2);
    });

    it('a mesma sessão reenviada conta uma vez só (§5/§208)', async () => {
      const at = saoPauloInstant('2026-09-12T07:00:00');
      // Três pushes do **mesmo** `syncId`: o reenvio depois de resposta perdida.
      await pushCompletedSession(app, igor, 'sess-repetida', at);
      await pushCompletedSession(app, igor, 'sess-repetida', at);
      await pushCompletedSession(app, igor, 'sess-repetida', at, 'device-b');

      const body = await leaderboard();
      expect(
        body.participants.find((p: { displayName: string }) => p.displayName === 'Igor').score,
      ).toBe(1);
    });

    it('sessão sincronizada DEPOIS do fim ainda conta pelo startedAt (§71/§72/§237)', async () => {
      await pushCompletedSession(app, igor, 'sess-online', saoPauloInstant('2026-09-11T07:00:00'));

      // O desafio termina.
      clock.set(ENDS_EXCLUSIVE + 60_000);
      const ended = await leaderboard();
      expect(ended.challenge.status).toBe('ENDED');
      expect(
        ended.participants.find((p: { displayName: string }) => p.displayName === 'João').score,
      ).toBe(0);
      // A tela precisa poder dizer a verdade: o resultado ainda pode mudar (§74/§179).
      expect(ended.resultMayStillChange).toBe(true);

      // O João estava offline e treinou **durante** o desafio. Ele sincroniza agora, depois do fim.
      await pushCompletedSession(
        app,
        joao,
        'sess-tardia-1',
        saoPauloInstant('2026-09-12T06:00:00'),
      );
      await pushCompletedSession(
        app,
        joao,
        'sess-tardia-2',
        saoPauloInstant('2026-09-13T06:00:00'),
      );

      const converged = await leaderboard();
      expect(
        converged.participants.find((p: { displayName: string }) => p.displayName === 'João').score,
      ).toBe(2);
      // E ele passa o Igor: quem sincronizou depois **não** é penalizado (§90).
      expect(converged.participants[0].displayName).toBe('João');
      expect(converged.participants[0].rank).toBe(1);
    });

    it('uma sessão feita depois do fim não conta, mesmo chegando junto com as tardias', async () => {
      clock.set(ENDS_EXCLUSIVE + 60_000);
      await pushCompletedSession(app, joao, 'dentro', saoPauloInstant('2026-09-12T06:00:00'));
      await pushCompletedSession(app, joao, 'fora', saoPauloInstant('2026-09-21T06:00:00'));

      const body = await leaderboard();
      expect(
        body.participants.find((p: { displayName: string }) => p.displayName === 'João').score,
      ).toBe(1);
    });

    it('ler o placar não escreve nada: nem sync, nem XP, nem placar (§81/§211)', async () => {
      await pushCompletedSession(app, igor, 'sess-1', saoPauloInstant('2026-09-10T07:00:00'));

      const postgres = app.get(PostgresService);
      const snapshot = async () => ({
        entities: (await postgres.query<{ n: number }>('SELECT COUNT(*) AS n FROM sync_entities'))
          .rows[0],
        changes: (await postgres.query<{ n: number }>('SELECT COUNT(*) AS n FROM sync_changes'))
          .rows[0],
        mutations: (await postgres.query<{ n: number }>('SELECT COUNT(*) AS n FROM sync_mutations'))
          .rows[0],
        participants: (
          await postgres.query('SELECT * FROM challenge_participants ORDER BY participant_uid')
        ).rows,
        challenges: (await postgres.query('SELECT * FROM challenges')).rows,
      });

      const before = await snapshot();
      // Dez leituras do placar.
      for (let i = 0; i < 10; i += 1) {
        await leaderboard();
      }
      const after = await snapshot();

      expect(after).toEqual(before);
    });

    it('empate permanece empate, em competition ranking 1-1-3 (§89/§222)', async () => {
      // Igor 8, João 8, Jonathas 6.
      for (let i = 0; i < 8; i += 1) {
        await pushCompletedSession(
          app,
          igor,
          `igor-${i}`,
          saoPauloInstant('2026-09-10T07:00:00') + i * 60_000,
        );
        await pushCompletedSession(
          app,
          joao,
          `joao-${i}`,
          saoPauloInstant('2026-09-10T08:00:00') + i * 60_000,
        );
      }
      for (let i = 0; i < 6; i += 1) {
        await pushCompletedSession(
          app,
          jonathas,
          `jon-${i}`,
          saoPauloInstant('2026-09-10T09:00:00') + i * 60_000,
        );
      }

      const body = await leaderboard();
      expect(
        body.participants.map((p: { displayName: string; score: number; rank: number }) => [
          p.displayName,
          p.score,
          p.rank,
        ]),
      ).toEqual([
        ['Igor', 8, 1],
        ['João', 8, 1],
        ['Jonathas', 6, 3],
      ]);
    });

    it('goalReached é separado da liderança, e o score pode passar do target (§86/§93)', async () => {
      // Criar e aceitar acontecem **antes** do início (§15/§54): o relógio volta para "hoje"
      // enquanto o cenário é montado, e avança de novo para a janela em que se pontua.
      clock.set(NOW);
      const created = await createChallenge(app, igor, [joao], {
        startDate: START_DATE,
        endDate: END_DATE,
        target: 2,
        name: 'Meta 2',
      });
      await acceptChallenge(app, joao, created.challengeId);
      clock.set(STARTS + 60_000);

      for (let i = 0; i < 5; i += 1) {
        await pushCompletedSession(
          app,
          igor,
          `i-${i}`,
          saoPauloInstant('2026-09-10T07:00:00') + i * 60_000,
        );
      }
      for (let i = 0; i < 3; i += 1) {
        await pushCompletedSession(
          app,
          joao,
          `j-${i}`,
          saoPauloInstant('2026-09-10T08:00:00') + i * 60_000,
        );
      }

      const response = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${created.challengeId}`)
        .set('Authorization', auth(igor))
        .expect(200);

      expect(
        response.body.participants.map(
          (p: { displayName: string; score: number; goalReached: boolean; rank: number }) => [
            p.displayName,
            p.score,
            p.goalReached,
            p.rank,
          ],
        ),
      ).toEqual([
        // 5 de 2: o número real, e não truncado na meta.
        ['Igor', 5, true, 1],
        // Também bateu a meta, e não é o líder. As duas perguntas são diferentes.
        ['João', 3, true, 2],
      ]);
    });

    it('a linha de quem está olhando é identificável (§170)', async () => {
      const body = await leaderboard(joao);
      const mine = body.participants.filter((p: { isViewer: boolean }) => p.isViewer);
      expect(mine).toHaveLength(1);
      expect(mine[0].displayName).toBe('João');
    });

    it('a privacidade da T17.2 não interfere na pontuação (§terceiro princípio/§122)', async () => {
      // O João deixa **todos** os interruptores de perfil desligados — que é o default (§14 da
      // T17.2). O perfil social dele não publica contagem de treinos nenhuma.
      const sharing = await request(app.getHttpServer())
        .get('/v1/social/me/progress-sharing')
        .set('Authorization', auth(joao))
        .expect(200);
      expect(sharing.body.settings.shareWeeklyWorkoutCount).toBe(false);

      const profile = await request(app.getHttpServer())
        .get(`/v1/social/friends/${joao.socialId}/profile`)
        .set('Authorization', auth(igor))
        .expect(200);
      expect(profile.body.profile.sharedProgress.weeklyWorkoutCount).toBeUndefined();

      // E ainda assim o placar do desafio mostra o progresso dele: são autorizações diferentes.
      await pushCompletedSession(app, joao, 'j-1', saoPauloInstant('2026-09-11T07:00:00'));
      const body = await leaderboard(igor);
      expect(
        body.participants.find((p: { displayName: string }) => p.displayName === 'João').score,
      ).toBe(1);
    });

    it('participar de um desafio não altera a privacidade da T17.2 (§122)', async () => {
      const after = await request(app.getHttpServer())
        .get('/v1/social/me/progress-sharing')
        .set('Authorization', auth(joao))
        .expect(200);
      expect(after.body.settings).toMatchObject({
        shareLevel: false,
        shareConsistencyStreak: false,
        shareWeeklyWorkoutCount: false,
        shareHighlightedAchievements: false,
      });
    });

    it('desfazer a amizade DEPOIS do aceite não tira ninguém do desafio (§59/§60/§238)', async () => {
      await pushCompletedSession(app, joao, 'j-1', saoPauloInstant('2026-09-11T07:00:00'));

      await request(app.getHttpServer())
        .post('/v1/social/friends/remove')
        .set('Authorization', auth(igor))
        .send({ socialId: joao.socialId })
        .expect(200);

      // O desafio continua, com os dois, e a pontuação do João continua sendo contada.
      const body = await leaderboard(igor);
      expect(body.challenge.participantCount).toBe(3);
      expect(
        body.participants.find((p: { displayName: string }) => p.displayName === 'João').score,
      ).toBe(1);

      // Mas o **perfil** social dele deixou de ser acessível (T17.2 §47): autorizações distintas.
      await request(app.getHttpServer())
        .get(`/v1/social/friends/${joao.socialId}/profile`)
        .set('Authorization', auth(igor))
        .expect(404);
    });

    describe('ACTIVE_DAYS', () => {
      let daysChallengeId: string;

      beforeEach(async () => {
        // O `beforeEach` externo já adiantou o relógio para depois do início. Criar exige que o
        // desafio comece no futuro (§15), então o relógio volta para "hoje" enquanto o cenário é
        // montado — criação e aceite acontecem antes, como aconteceriam de verdade.
        clock.set(NOW);
        const created = await createChallenge(app, igor, [joao], {
          name: '10 dias ativos',
          type: 'ACTIVE_DAYS',
          target: 10,
          startDate: START_DATE,
          endDate: END_DATE,
        });
        daysChallengeId = created.challengeId;
        await acceptChallenge(app, joao, daysChallengeId);
        clock.set(STARTS + 60_000);
      });

      const daysScore = async (who: TestAccount) => {
        const response = await request(app.getHttpServer())
          .get(`/v1/social/challenges/${daysChallengeId}`)
          .set('Authorization', auth(igor))
          .expect(200);
        return response.body.participants.find(
          (p: { displayName: string }) => p.displayName === who.displayName,
        ).score;
      };

      it('dois treinos no mesmo dia contam 1 (§7/§236)', async () => {
        await pushCompletedSession(app, igor, 'manha', saoPauloInstant('2026-09-10T07:00:00'));
        await pushCompletedSession(app, igor, 'noite', saoPauloInstant('2026-09-10T21:00:00'));

        expect(await daysScore(igor)).toBe(1);
      });

      it('um treino por dia em dias diferentes conta cada dia', async () => {
        await pushCompletedSession(app, igor, 'd10', saoPauloInstant('2026-09-10T07:00:00'));
        await pushCompletedSession(app, igor, 'd11', saoPauloInstant('2026-09-11T07:00:00'));
        await pushCompletedSession(app, igor, 'd12a', saoPauloInstant('2026-09-12T07:00:00'));
        await pushCompletedSession(app, igor, 'd12b', saoPauloInstant('2026-09-12T20:00:00'));

        expect(await daysScore(igor)).toBe(3);
      });

      it('a virada do dia é a meia-noite LOCAL, e não a UTC', async () => {
        // 22h em São Paulo do dia 10 é 01h UTC do dia 11. Se a contagem fosse por UTC, estes dois
        // treinos cairiam em dias diferentes — e o dia ativo viraria dois.
        await pushCompletedSession(app, igor, 'cedo', saoPauloInstant('2026-09-10T08:00:00'));
        await pushCompletedSession(app, igor, 'tarde', saoPauloInstant('2026-09-10T22:00:00'));

        expect(await daysScore(igor)).toBe(1);

        // E 00h05 local do dia 11 é um dia **novo**.
        await pushCompletedSession(app, igor, 'virou', saoPauloInstant('2026-09-11T00:05:00'));
        expect(await daysScore(igor)).toBe(2);
      });

      it('dia fora da janela não conta', async () => {
        await pushCompletedSession(app, igor, 'antes', saoPauloInstant('2026-09-09T20:00:00'));
        await pushCompletedSession(app, igor, 'depois', saoPauloInstant('2026-09-20T08:00:00'));
        await pushCompletedSession(app, igor, 'dentro', saoPauloInstant('2026-09-19T23:00:00'));

        expect(await daysScore(igor)).toBe(1);
      });
    });
  });

  // --------------------------------------------------------------------- janelas de dia

  describe('as janelas de dia respeitam o horário de verão (§203/§221)', () => {
    it('um dia não é sempre 24 horas', () => {
      // Nova York vira para o horário de verão em 8 de março de 2026 às 2h locais: o dia 8 tem
      // **23** horas. Somar 24h produziria uma faixa começando às 01h do dia 9.
      const windows = challengeDayWindows('2026-03-07', '2026-03-09', 'America/New_York');
      expect(windows).toHaveLength(3);

      const hours = windows.map((w) => (w.endMs - w.startMs) / 3_600_000);
      expect(hours).toEqual([24, 23, 24]);

      // As faixas são contíguas: o fim de um dia é o início do seguinte, sem buraco e sem
      // sobreposição. Um buraco perderia um treino; uma sobreposição o contaria duas vezes.
      expect(windows[0].endMs).toBe(windows[1].startMs);
      expect(windows[1].endMs).toBe(windows[2].startMs);
    });

    it('e o dia da volta tem 25 horas', () => {
      // 1 de novembro de 2026, Nova York volta do horário de verão.
      const windows = challengeDayWindows('2026-10-31', '2026-11-02', 'America/New_York');
      const hours = windows.map((w) => (w.endMs - w.startMs) / 3_600_000);
      expect(hours).toEqual([24, 25, 24]);
    });

    it('São Paulo não tem horário de verão desde 2019 — 24 horas sempre', () => {
      const windows = challengeDayWindows(START_DATE, END_DATE, SAO_PAULO);
      expect(windows).toHaveLength(10);
      for (const window of windows) {
        expect(window.endMs - window.startMs).toBe(24 * 3_600_000);
      }
      expect(windows[0].startMs).toBe(STARTS);
      expect(windows[windows.length - 1].endMs).toBe(ENDS_EXCLUSIVE);
    });

    it('fuso inválido ou período impossível produz nenhuma janela', () => {
      expect(challengeDayWindows(START_DATE, END_DATE, 'Mars/Olympus')).toEqual([]);
      expect(challengeDayWindows('2026-09-19', '2026-09-10', SAO_PAULO)).toEqual([]);
      expect(challengeDayWindows('2026-02-30', '2026-03-10', SAO_PAULO)).toEqual([]);
    });
  });

  // --------------------------------------------------------------------- a fonte, direta

  describe('a fonte canônica, sobre o banco', () => {
    let temp: TempDb;
    let source: SyncedChallengeProgressSource;
    let postgres: PostgresService;

    const insertSession = async (
      ownerUid: string,
      syncId: string,
      startedAt: number,
      status = 'COMPLETED',
      deleted = 0,
    ) => {
      await postgres.query(
        `INSERT INTO sync_entities
           (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
            last_server_sequence, payload, payload_hash, origin_device_id, created_at,
            updated_at, deleted)
         VALUES ($1, 'WORKOUT_SESSION', $2, 1, 1, 1, $3, 'hash', 'device-a', 1, 1, $4)`,
        [ownerUid, syncId, JSON.stringify({ syncId, status, startedAt }), deleted],
      );
    };

    beforeEach(async () => {
      temp = createTempDb();
      postgres = postgresFor(configFor(temp.path));
      await postgres.initialize();
      source = new SyncedChallengeProgressSource(postgres);
    });

    afterEach(async () => {
      await postgres.close();
      temp.cleanup();
    });

    it('só COMPLETED conta — a regra é declarada onde é aplicada (§4/§220)', async () => {
      // Estes status **não chegam** ao servidor pelo push real (o registry recusa). Inseridos à
      // mão aqui de propósito: é a única forma de provar que a cláusula existe, e é o que fará
      // este teste falhar se algum dia o registry passar a aceitá-los.
      await insertSession('uid-a', 'ok', STARTS + 3_600_000, 'COMPLETED');
      await insertSession('uid-a', 'planned', STARTS + 3_600_000, 'PLANNED');
      await insertSession('uid-a', 'progress', STARTS + 3_600_000, 'IN_PROGRESS');
      await insertSession('uid-a', 'paused', STARTS + 3_600_000, 'PAUSED');
      await insertSession('uid-a', 'cancelled', STARTS + 3_600_000, 'CANCELLED');

      expect(await source.countCompletedWorkouts('uid-a', STARTS, ENDS_EXCLUSIVE)).toBe(1);
      expect(await source.countActiveDays('uid-a', START_DATE, END_DATE, SAO_PAULO)).toBe(1);
    });

    it('sessão apagada pelo usuário (tombstone) não conta', async () => {
      await insertSession('uid-a', 'viva', STARTS + 3_600_000, 'COMPLETED', 0);
      await insertSession('uid-a', 'apagada', STARTS + 7_200_000, 'COMPLETED', 1);

      expect(await source.countCompletedWorkouts('uid-a', STARTS, ENDS_EXCLUSIVE)).toBe(1);
    });

    it('a contagem é isolada por dono — nunca soma o treino de outra conta (§200)', async () => {
      await insertSession('uid-a', 'a1', STARTS + 3_600_000);
      await insertSession('uid-b', 'b1', STARTS + 3_600_000);
      await insertSession('uid-b', 'b2', STARTS + 7_200_000);

      expect(await source.countCompletedWorkouts('uid-a', STARTS, ENDS_EXCLUSIVE)).toBe(1);
      expect(await source.countCompletedWorkouts('uid-b', STARTS, ENDS_EXCLUSIVE)).toBe(2);
      expect(await source.countCompletedWorkouts('uid-c', STARTS, ENDS_EXCLUSIVE)).toBe(0);
    });

    it('zero é zero, e não ausência: um participante sem treino tem 0 de 12', async () => {
      // Diferente da T17.2, onde "nunca sincronizou" responde `UNAVAILABLE`. Num desafio, quem não
      // treinou tem zero — e mostrar campo ausente faria a linha dele sumir do placar.
      expect(await source.countCompletedWorkouts('uid-novo', STARTS, ENDS_EXCLUSIVE)).toBe(0);
      expect(await source.countActiveDays('uid-novo', START_DATE, END_DATE, SAO_PAULO)).toBe(0);
    });

    it('a pontuação é determinística: mesma entrada, mesma saída (§210)', async () => {
      for (let i = 0; i < 5; i += 1) {
        await insertSession('uid-a', `s-${i}`, STARTS + i * 24 * 3_600_000 + 3_600_000);
      }
      const first = await source.countCompletedWorkouts('uid-a', STARTS, ENDS_EXCLUSIVE);
      const days = await source.countActiveDays('uid-a', START_DATE, END_DATE, SAO_PAULO);

      for (let i = 0; i < 10; i += 1) {
        expect(await source.countCompletedWorkouts('uid-a', STARTS, ENDS_EXCLUSIVE)).toBe(first);
        expect(await source.countActiveDays('uid-a', START_DATE, END_DATE, SAO_PAULO)).toBe(days);
      }
      expect(first).toBe(5);
      expect(days).toBe(5);
    });
  });

  // --------------------------------------------------------------------- ranking puro

  describe('o ranking, sobre uma fonte de mentira', () => {
    /** Uma fonte controlada: prova o ranking sem montar banco, sync nem HTTP. */
    const sourceOf = (scores: Record<string, number>): ChallengeProgressSource => ({
      countCompletedWorkouts: (ownerUid) => Promise.resolve(scores[ownerUid] ?? 0),
      countActiveDays: (ownerUid) => Promise.resolve(scores[ownerUid] ?? 0),
    });

    const challenge = {
      type: 'WORKOUTS_COMPLETED' as const,
      target: 12,
      startDate: START_DATE,
      endDate: END_DATE,
      timeZoneId: SAO_PAULO,
      startsAt: STARTS,
      endsAtExclusive: ENDS_EXCLUSIVE,
    };

    const participant = (uid: string, name: string) => ({
      ownerUid: uid,
      socialId: `social-${uid}`,
      displayName: name,
      role: 'MEMBER' as const,
    });

    it('ordena por score decrescente, e o exemplo da tarefa fecha', async () => {
      const scoring = new ChallengeScoringService(sourceOf({ igor: 8, joao: 7, jonathas: 5 }));
      const board = await scoring.leaderboard(challenge, [
        participant('jonathas', 'Jonathas'),
        participant('igor', 'Igor'),
        participant('joao', 'João'),
      ]);

      expect(
        board.map((p) => `${p.displayName} ${p.score} / ${challenge.target} #${p.rank}`),
      ).toEqual(['Igor 8 / 12 #1', 'João 7 / 12 #2', 'Jonathas 5 / 12 #3']);
      expect(board.every((p) => !p.goalReached)).toBe(true);
    });

    it('empate triplo mantém a mesma posição para todos', async () => {
      const scoring = new ChallengeScoringService(sourceOf({ a: 4, b: 4, c: 4 }));
      const board = await scoring.leaderboard(challenge, [
        participant('a', 'Ana'),
        participant('b', 'Bruno'),
        participant('c', 'Caio'),
      ]);
      expect(board.map((p) => p.rank)).toEqual([1, 1, 1]);
    });

    it('a ordem de exibição de um empate é estável entre leituras', async () => {
      const scoring = new ChallengeScoringService(sourceOf({ a: 4, b: 4 }));
      const order = async () =>
        (
          await scoring.leaderboard(challenge, [participant('b', 'Bruno'), participant('a', 'Ana')])
        ).map((p) => p.displayName);

      // A entrada vem em ordem diferente da saída, e a saída é sempre a mesma: sem isto, duas
      // linhas empatadas trocariam de lugar entre dois refreshes da tela.
      expect(await order()).toEqual(['Ana', 'Bruno']);
      expect(await order()).toEqual(await order());
    });

    it('ninguém pontua fora do próprio uid', async () => {
      const seen: string[] = [];
      const scoring = new ChallengeScoringService({
        countCompletedWorkouts: (ownerUid) => {
          seen.push(ownerUid);
          return Promise.resolve(1);
        },
        countActiveDays: () => Promise.resolve(0),
      });
      await scoring.leaderboard(challenge, [participant('a', 'Ana'), participant('b', 'Bruno')]);
      expect(seen.sort()).toEqual(['a', 'b']);
    });
  });
});
