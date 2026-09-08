import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import {
  account,
  acceptChallenge,
  befriend,
  createChallenge,
  enableSocial,
  pendingInvitationId,
  pushCompletedSession,
  saoPauloInstant,
  type TestAccount,
} from './support/challenge-fixtures';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { configFor, createTempDb, type TempDb } from './support/temp-db';

/**
 * O smoke multi-usuário da T17.3 (§232–§239).
 *
 * Três contas reais, pelas rotas reais, na ordem em que as coisas acontecem de verdade:
 *
 * ```text
 * A vira amiga de B e C
 *   ↓
 * A cria o desafio e convida os dois
 *   ↓
 * B e C aceitam
 *   ↓
 * a janela começa
 *   ↓
 * treinos canônicos chegam por sync
 *   ↓
 * o placar reflete exatamente o que chegou
 * ```
 *
 * Cada cenário da tarefa é um `it`, na sequência, sobre o **mesmo** estado — porque é a sequência
 * que prova a convergência: o placar do smoke 3 depende do que o smoke 2 deixou, e o do smoke 5
 * depende de o desafio já ter terminado.
 */
describe('Smoke multi-usuário dos desafios', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;
  let challengeId: string;

  const a = account('a', 'Igor');
  const b = account('b', 'João');
  const c = account('c', 'Jonathas');

  const NOW = saoPauloInstant('2026-09-08T12:00:00');
  const START_DATE = '2026-09-10';
  const END_DATE = '2026-09-19';
  const STARTS = saoPauloInstant('2026-09-10T00:00:00');
  const ENDS_EXCLUSIVE = saoPauloInstant('2026-09-20T00:00:00');

  /**
   * Um período **depois** do desafio principal, para os cenários que criam desafios novos já com
   * o relógio adiantado (smokes 6 e 7).
   *
   * Voltar o relógio para reaproveitar as datas originais faria um desafio já encerrado parecer
   * aberto de novo — e a desativação do Social, que age sobre "desafios ainda não encerrados",
   * o alcançaria. Na produção o tempo não anda para trás; no teste, também não deve andar.
   */
  const LATER_START_DATE = '2026-10-01';
  const LATER_END_DATE = '2026-10-10';

  const auth = (who: TestAccount) => `Bearer ${who.token}`;

  /** O placar de agora, como `{ nome: pontos }`. */
  const scores = async (viewer: TestAccount = a): Promise<Record<string, number>> => {
    const response = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${challengeId}`)
      .set('Authorization', auth(viewer))
      .expect(200);
    return Object.fromEntries(
      response.body.participants.map((p: { displayName: string; score: number }) => [
        p.displayName,
        p.score,
      ]),
    );
  };

  const ranks = async (): Promise<Array<[string, number, number]>> => {
    const response = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${challengeId}`)
      .set('Authorization', auth(a))
      .expect(200);
    return response.body.participants.map(
      (p: { displayName: string; score: number; rank: number }) => [p.displayName, p.score, p.rank],
    );
  };

  beforeAll(async () => {
    temp = createTempDb();
    clock = new FakeClock(NOW);

    const verifier = new FakeAuthTokenVerifier();
    for (const who of [a, b, c]) {
      verifier.accept(who.token, { uid: who.uid });
    }
    app = await createTestApp(configFor(temp.path), verifier, undefined, clock);

    for (const who of [a, b, c]) {
      await enableSocial(app, who);
    }
  });

  afterAll(async () => {
    await app.close();
    temp.cleanup();
  });

  // ------------------------------------------------------------------------------- smoke 1

  it('smoke 1 — A vira amiga de B e C, cria o desafio, e os dois aceitam (§233)', async () => {
    await befriend(app, a, b);
    await befriend(app, a, c);

    const created = await createChallenge(app, a, [b, c], {
      name: '12 treinos',
      target: 12,
      startDate: START_DATE,
      endDate: END_DATE,
    });
    challengeId = created.challengeId;

    // Antes dos aceites: só o criador está dentro.
    const beforeAccepts = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${challengeId}`)
      .set('Authorization', auth(a))
      .expect(200);
    expect(beforeAccepts.body.challenge.status).toBe('UPCOMING');
    expect(beforeAccepts.body.challenge.participantCount).toBe(1);
    expect(beforeAccepts.body.pendingInvitationCount).toBe(2);

    // B e C aceitam — cada um pelo próprio convite, antes do início.
    await acceptChallenge(app, b, challengeId);
    await acceptChallenge(app, c, challengeId);

    const afterAccepts = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${challengeId}`)
      .set('Authorization', auth(a))
      .expect(200);
    expect(afterAccepts.body.challenge.participantCount).toBe(3);
    expect(afterAccepts.body.pendingInvitationCount).toBe(0);

    // A janela começa. Nenhum cron rodou: o estado é derivado do relógio.
    clock.set(STARTS + 60_000);
    const started = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${challengeId}`)
      .set('Authorization', auth(b))
      .expect(200);
    expect(started.body.challenge.status).toBe('ACTIVE');
  });

  // ------------------------------------------------------------------------------- smoke 2

  it('smoke 2 — treinos canônicos produzem exatamente A=5, B=4, C=3 (§234)', async () => {
    const at = (day: number, hour: number) =>
      saoPauloInstant(
        `2026-09-${String(day).padStart(2, '0')}T${String(hour).padStart(2, '0')}:00:00`,
      );

    for (let i = 0; i < 5; i += 1) {
      await pushCompletedSession(app, a, `a-${i}`, at(10 + i, 7));
    }
    for (let i = 0; i < 4; i += 1) {
      await pushCompletedSession(app, b, `b-${i}`, at(10 + i, 8));
    }
    for (let i = 0; i < 3; i += 1) {
      await pushCompletedSession(app, c, `c-${i}`, at(10 + i, 9));
    }

    expect(await scores()).toEqual({ Igor: 5, João: 4, Jonathas: 3 });
  });

  // ------------------------------------------------------------------------------- smoke 3

  it('smoke 3 — mais um treino de B produz empate, e o empate permanece (§235)', async () => {
    await pushCompletedSession(app, b, 'b-4', saoPauloInstant('2026-09-15T08:00:00'));

    expect(await scores()).toEqual({ Igor: 5, João: 5, Jonathas: 3 });
    // Competition ranking: 1, 1, 3. Ninguém "vence" por ter sincronizado primeiro.
    expect(await ranks()).toEqual([
      ['Igor', 5, 1],
      ['João', 5, 1],
      ['Jonathas', 3, 3],
    ]);
  });

  // ------------------------------------------------------------------------------- smoke 4

  it('smoke 4 — ACTIVE_DAYS conta o dia uma vez, com dois treinos nele (§236)', async () => {
    // Um desafio novo, do mesmo grupo. O relógio volta para antes do início para que criação e
    // aceites aconteçam quando aconteceriam de verdade.
    clock.set(NOW);
    const days = await createChallenge(app, a, [b], {
      name: '10 dias ativos',
      type: 'ACTIVE_DAYS',
      target: 10,
      startDate: START_DATE,
      endDate: END_DATE,
    });
    await acceptChallenge(app, b, days.challengeId);
    clock.set(STARTS + 60_000);

    // A já tem cinco treinos, em cinco dias distintos (smoke 2). Dois treinos a mais no **mesmo**
    // dia 10 não podem acrescentar dia nenhum.
    await pushCompletedSession(app, a, 'a-extra-manha', saoPauloInstant('2026-09-10T06:00:00'));
    await pushCompletedSession(app, a, 'a-extra-noite', saoPauloInstant('2026-09-10T21:00:00'));

    const response = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${days.challengeId}`)
      .set('Authorization', auth(a))
      .expect(200);

    const byName = Object.fromEntries(
      response.body.participants.map((p: { displayName: string; score: number }) => [
        p.displayName,
        p.score,
      ]),
    );
    // A: dias 10, 11, 12, 13, 14 = 5. Os dois treinos extras caem no dia 10, que já contava.
    expect(byName.Igor).toBe(5);
    // B: dias 10, 11, 12, 13 (smoke 2) + 15 (smoke 3) = 5.
    expect(byName['João']).toBe(5);

    // E a diferença entre os dois tipos, sobre **exatamente os mesmos treinos**: no desafio de
    // treinos, os dois extras valem 2 pontos (5 → 7); no de dias ativos, valem 0, porque o dia 10
    // já contava. É esta a distinção que §161/§162 pedem que a UI explique antes de a pessoa
    // escolher.
    expect(await scores()).toEqual({ Igor: 7, João: 5, Jonathas: 3 });
  });

  // ------------------------------------------------------------------------------- smoke 5

  it('smoke 5 — treino do período sincronizado DEPOIS do fim atualiza o resultado (§237)', async () => {
    // O desafio termina.
    clock.set(ENDS_EXCLUSIVE + 60_000);

    const ended = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${challengeId}`)
      .set('Authorization', auth(a))
      .expect(200);
    expect(ended.body.challenge.status).toBe('ENDED');
    // A tela precisa poder dizer a verdade: o resultado ainda pode convergir.
    expect(ended.body.resultMayStillChange).toBe(true);
    expect(await scores()).toEqual({ Igor: 7, João: 5, Jonathas: 3 });

    // C estava offline e treinou **durante** o período. Ele sincroniza agora, depois do fim.
    await pushCompletedSession(app, c, 'c-tardio-1', saoPauloInstant('2026-09-16T07:00:00'));
    await pushCompletedSession(app, c, 'c-tardio-2', saoPauloInstant('2026-09-17T07:00:00'));
    await pushCompletedSession(app, c, 'c-tardio-3', saoPauloInstant('2026-09-18T07:00:00'));
    // E um treino **fora** da janela, que não pode contar.
    await pushCompletedSession(app, c, 'c-fora', saoPauloInstant('2026-09-25T07:00:00'));

    // C passa o B: quem sincronizou depois não é penalizado, e o treino fora da janela não conta.
    expect(await scores()).toEqual({ Igor: 7, João: 5, Jonathas: 6 });
    expect(await ranks()).toEqual([
      ['Igor', 7, 1],
      ['Jonathas', 6, 2],
      ['João', 5, 3],
    ]);
  });

  // ------------------------------------------------------------------------------- smoke 6

  it('smoke 6 — desfazer a amizade não tira ninguém do desafio, mas fecha o perfil (§238)', async () => {
    await request(app.getHttpServer())
      .post('/v1/social/friends/remove')
      .set('Authorization', auth(a))
      .send({ socialId: b.socialId })
      .expect(200);

    // O desafio continua, com os três, e a pontuação de B continua contando.
    const detail = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${challengeId}`)
      .set('Authorization', auth(a))
      .expect(200);
    expect(detail.body.challenge.participantCount).toBe(3);
    expect(await scores()).toEqual({ Igor: 7, João: 5, Jonathas: 6 });

    // B continua vendo o desafio dele.
    const fromB = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${challengeId}`)
      .set('Authorization', auth(b))
      .expect(200);
    expect(fromB.body.viewer.status).toBe('JOINED');

    // Mas o **perfil social** de B deixou de ser acessível a A: autorizações distintas.
    await request(app.getHttpServer())
      .get(`/v1/social/friends/${b.socialId}/profile`)
      .set('Authorization', auth(a))
      .expect(404);

    // E A não pode mais convidar B para um desafio novo. O período é **futuro em relação ao
    // relógio de agora** (o desafio principal já terminou): voltar o relógio faria um desafio
    // encerrado parecer aberto de novo, e o tempo não anda para trás na produção.
    const invite = await request(app.getHttpServer())
      .post('/v1/social/challenges')
      .set('Authorization', auth(a))
      .send({
        clientRequestId: 'req-depois-do-unfriend',
        name: 'Novo desafio',
        type: 'WORKOUTS_COMPLETED',
        target: 10,
        startDate: LATER_START_DATE,
        endDate: LATER_END_DATE,
        timeZoneId: 'America/Sao_Paulo',
        invitedSocialIds: [b.socialId],
      })
      .expect(409);
    expect(invite.body.error.code).toBe('CHALLENGE_PARTICIPANT_NOT_AVAILABLE');
  });

  // ------------------------------------------------------------------------------- smoke 7

  it('smoke 7 — desativar o Social encerra a participação ativa e preserva o encerrado (§239)', async () => {
    // Um desafio novo, ainda não começado, para provar as três consequências de uma vez. O
    // período é futuro em relação ao relógio de agora — **sem voltar o relógio**, que faria o
    // desafio já encerrado parecer aberto e ser tocado pela desativação.
    const upcoming = await createChallenge(app, a, [c], {
      name: 'Desafio futuro',
      startDate: LATER_START_DATE,
      endDate: LATER_END_DATE,
    });
    await acceptChallenge(app, c, upcoming.challengeId);

    // E um convite que C ainda não respondeu.
    const pending = await createChallenge(app, a, [c], {
      name: 'Convite pendente',
      startDate: LATER_START_DATE,
      endDate: LATER_END_DATE,
    });
    await pendingInvitationId(app, c, pending.challengeId);

    await request(app.getHttpServer())
      .post('/v1/social/me/disable')
      .set('Authorization', auth(c))
      .expect(200);

    // 1. C saiu do desafio ainda em aberto: o placar dele para de ser publicado.
    const openDetail = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${upcoming.challengeId}`)
      .set('Authorization', auth(a))
      .expect(200);
    expect(openDetail.body.challenge.participantCount).toBe(1);
    expect(openDetail.body.withdrawnCount).toBe(1);

    // 2. O convite pendente foi recusado.
    const pendingDetail = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${pending.challengeId}`)
      .set('Authorization', auth(a))
      .expect(200);
    expect(pendingDetail.body.pendingInvitationCount).toBe(0);

    // 3. O desafio **encerrado** não foi tocado: ele é histórico de que outras pessoas
    //    participaram, e a pontuação de C nele continua registrada.
    expect(await scores()).toEqual({ Igor: 7, João: 5, Jonathas: 6 });

    // 4. C não movimenta mais nada enquanto estiver desativado — nem leitura.
    const blocked = await request(app.getHttpServer())
      .get('/v1/social/challenges')
      .set('Authorization', auth(c))
      .expect(409);
    expect(blocked.body.error.code).toBe('SOCIAL_PROFILE_DISABLED');

    // 5. Reativar devolve o acesso, e nada foi apagado.
    await request(app.getHttpServer())
      .post('/v1/social/me/enable')
      .set('Authorization', auth(c))
      .expect(200);
    const back = await request(app.getHttpServer())
      .get(`/v1/social/challenges/${challengeId}`)
      .set('Authorization', auth(c))
      .expect(200);
    expect(back.body.viewer.status).toBe('JOINED');
  });
});
