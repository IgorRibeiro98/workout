import { INestApplication } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import request from 'supertest';
import { CHALLENGE_PARTICIPANTS } from '../src/modules/social/challenge.limits';
import {
  account,
  acceptChallenge,
  befriend,
  createChallenge,
  createChallengeBody,
  enableSocial,
  pendingInvitationId,
  SAO_PAULO,
  saoPauloInstant,
  type TestAccount,
} from './support/challenge-fixtures';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { configFor, createTempDb, type TempDb } from './support/temp-db';

/**
 * Os desafios entre amigos: criação, convites, participação e autorização (T17.3).
 *
 * ```text
 * A (Igor)  cria  ──convida──▶  B (João)     aceita
 *                 ──convida──▶  C (Jonathas) aceita
 *
 * D (Ana)   não é amiga de ninguém — é a conta que **não** pode nada (§215)
 * ```
 *
 * Tudo pelas rotas reais, com autenticação dublê e SQLite temporário. Nenhuma linha é inserida à
 * mão: um `INSERT` em `challenge_participants` provaria que a consulta funciona, e não que alguém
 * consegue entrar num desafio.
 *
 * O relógio é fixo ([FakeClock]) porque o ciclo de vida **é** uma comparação com o instante de
 * agora. "Hoje" nestes testes é 8 de setembro de 2026, meio-dia em São Paulo — e os desafios
 * começam no dia 10, que é o primeiro dia que §15 permite com folga.
 */
describe('Desafios entre amigos', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;
  let verifier: FakeAuthTokenVerifier;

  const igor = account('igor', 'Igor');
  const joao = account('joao', 'João');
  const jonathas = account('jonathas', 'Jonathas');
  /** A conta de fora: sem amizade com ninguém. Ela é a prova de isolamento entre contas. */
  const ana = account('ana', 'Ana');

  const NOW = saoPauloInstant('2026-09-08T12:00:00');
  const STARTS = saoPauloInstant('2026-09-10T00:00:00');

  beforeEach(async () => {
    temp = createTempDb();
    clock = new FakeClock(NOW);

    verifier = new FakeAuthTokenVerifier();
    for (const who of [igor, joao, jonathas, ana]) {
      verifier.accept(who.token, { uid: who.uid });
    }

    app = await createTestApp(configFor(temp.path), verifier, undefined, clock);

    for (const who of [igor, joao, jonathas, ana]) {
      await enableSocial(app, who);
    }
    await befriend(app, igor, joao);
    await befriend(app, igor, jonathas);
  });

  afterEach(async () => {
    await app.close();
    temp.cleanup();
  });

  const auth = (who: TestAccount) => `Bearer ${who.token}`;

  // ------------------------------------------------------------------------------- criação

  describe('criação', () => {
    it('cria o desafio, entra o criador e convida os amigos — em uma operação', async () => {
      const response = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(createChallengeBody([joao, jonathas]))
        .expect(200);

      expect(response.body.result).toBe('CREATED');
      expect(response.body.challenge).toMatchObject({
        name: '12 treinos',
        type: 'WORKOUTS_COMPLETED',
        target: 12,
        startDate: '2026-09-10',
        endDate: '2026-10-09',
        timeZoneId: SAO_PAULO,
        // Ainda não começou, e o criador já está dentro (§31).
        status: 'UPCOMING',
        participantCount: 1,
      });
      // `challengeId` é UUID opaco, e não um `rowid` (§25).
      expect(response.body.challenge.challengeId).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      );

      // Os dois convidados receberam convite; o criador **não** (§42).
      for (const who of [joao, jonathas]) {
        const invites = await request(app.getHttpServer())
          .get('/v1/social/challenge-invitations')
          .set('Authorization', auth(who))
          .expect(200);
        expect(invites.body.invitations).toHaveLength(1);
        expect(invites.body.invitations[0].status).toBe('PENDING');
      }
      const creatorInvites = await request(app.getHttpServer())
        .get('/v1/social/challenge-invitations')
        .set('Authorization', auth(igor))
        .expect(200);
      expect(creatorInvites.body.invitations).toHaveLength(0);
    });

    it('recusa o tipo desconhecido, a meta fora de faixa e o fuso inválido', async () => {
      const cases: Array<[Record<string, unknown>, string]> = [
        [{ type: 'TOTAL_VOLUME' }, 'INVALID_CHALLENGE_TYPE'],
        [{ type: 'XP_GAINED' }, 'INVALID_CHALLENGE_TYPE'],
        [{ target: 0 }, 'INVALID_CHALLENGE_TARGET'],
        [{ target: -3 }, 'INVALID_CHALLENGE_TARGET'],
        [{ target: 5000 }, 'INVALID_CHALLENGE_TARGET'],
        [{ target: 1.5 }, 'INVALID_CHALLENGE_TARGET'],
        [{ timeZoneId: 'Mars/Olympus' }, 'INVALID_CHALLENGE_TIMEZONE'],
        [{ timeZoneId: '' }, 'INVALID_CHALLENGE_TIMEZONE'],
      ];

      for (const [overrides, code] of cases) {
        const response = await request(app.getHttpServer())
          .post('/v1/social/challenges')
          .set('Authorization', auth(igor))
          .send(createChallengeBody([joao], overrides))
          .expect(400);
        expect(response.body.error.code).toBe(code);
      }
    });

    it('a meta de ACTIVE_DAYS não pode ser maior que a duração (§21)', async () => {
      // 10/09 a 19/09 são dez dias inclusivos. Onze dias ativos é impossível por construção, e um
      // desafio em que todo mundo perde não é um desafio.
      const impossible = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(
          createChallengeBody([joao], {
            type: 'ACTIVE_DAYS',
            target: 11,
            startDate: '2026-09-10',
            endDate: '2026-09-19',
          }),
        )
        .expect(400);
      expect(impossible.body.error.code).toBe('INVALID_CHALLENGE_TARGET');

      // Exatamente a duração é permitido: é o desafio "todo dia".
      await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(
          createChallengeBody([joao], {
            type: 'ACTIVE_DAYS',
            target: 10,
            startDate: '2026-09-10',
            endDate: '2026-09-19',
          }),
        )
        .expect(200);
    });

    it('o desafio precisa começar a partir do dia seguinte, no fuso escolhido (§15)', async () => {
      // Hoje é 8 de setembro em São Paulo.
      for (const startDate of ['2026-09-08', '2026-09-07', '2026-01-01']) {
        const response = await request(app.getHttpServer())
          .post('/v1/social/challenges')
          .set('Authorization', auth(igor))
          .send(createChallengeBody([joao], { startDate, endDate: '2026-10-09' }))
          .expect(400);
        expect(response.body.error.code).toBe('INVALID_CHALLENGE_PERIOD');
      }

      // Amanhã é o primeiro dia aceito.
      await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(createChallengeBody([joao], { startDate: '2026-09-09', endDate: '2026-09-30' }))
        .expect(200);
    });

    it('recusa período invertido, longo demais e data que não existe', async () => {
      const cases = [
        { startDate: '2026-10-09', endDate: '2026-09-10' },
        { startDate: '2026-09-10', endDate: '2027-09-10' },
        { startDate: '2026-02-30', endDate: '2026-03-10' },
        { startDate: '10/09/2026', endDate: '2026-10-09' },
      ];
      for (const overrides of cases) {
        const response = await request(app.getHttpServer())
          .post('/v1/social/challenges')
          .set('Authorization', auth(igor))
          .send(createChallengeBody([joao], overrides))
          .expect(400);
        expect(response.body.error.code).toBe('INVALID_CHALLENGE_PERIOD');
      }
    });

    it('só amigos ativos podem ser convidados (§32)', async () => {
      // Ana existe e tem perfil social, mas não é amiga do Igor.
      const notFriend = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(createChallengeBody([joao], { invitedSocialIds: [ana.socialId] }))
        .expect(409);
      expect(notFriend.body.error.code).toBe('CHALLENGE_PARTICIPANT_NOT_AVAILABLE');

      // Um `socialId` inventado responde **a mesma coisa**: a criação não é um verificador de
      // existência de perfil (§182).
      const unknown = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(createChallengeBody([joao], { invitedSocialIds: [randomUUID()] }))
        .expect(409);
      expect(unknown.body.error.code).toBe('CHALLENGE_PARTICIPANT_NOT_AVAILABLE');
    });

    it('amigo com Social desativado não pode ser convidado', async () => {
      await request(app.getHttpServer())
        .post('/v1/social/me/disable')
        .set('Authorization', auth(joao))
        .expect(200);

      const response = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(createChallengeBody([joao]))
        .expect(409);
      expect(response.body.error.code).toBe('CHALLENGE_PARTICIPANT_NOT_AVAILABLE');
    });

    it('um convidado inválido não cria desafio nenhum — atomicidade (§45)', async () => {
      await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(createChallengeBody([], { invitedSocialIds: [joao.socialId, ana.socialId] }))
        .expect(409);

      // Nem desafio, nem convite para o João — que era um convidado **válido** do mesmo pedido.
      const list = await request(app.getHttpServer())
        .get('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .expect(200);
      expect(list.body.challenges).toHaveLength(0);

      const invites = await request(app.getHttpServer())
        .get('/v1/social/challenge-invitations')
        .set('Authorization', auth(joao))
        .expect(200);
      expect(invites.body.invitations).toHaveLength(0);
    });

    it('convidado repetido não vira dois convites (§43)', async () => {
      const { challengeId } = await createChallenge(app, igor, [], {
        invitedSocialIds: [joao.socialId, joao.socialId, joao.socialId],
      });

      const invites = await request(app.getHttpServer())
        .get('/v1/social/challenge-invitations')
        .set('Authorization', auth(joao))
        .expect(200);
      expect(invites.body.invitations).toHaveLength(1);
      expect(invites.body.invitations[0].challenge.challengeId).toBe(challengeId);
    });

    it('o teto de participantes é do servidor (§30/§166)', async () => {
      const tooMany = Array.from({ length: CHALLENGE_PARTICIPANTS.max }, () => joao.socialId).map(
        (_, index) => `social-inventado-${index}`,
      );
      const response = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(createChallengeBody([], { invitedSocialIds: tooMany }))
        .expect(400);
      expect(response.body.error.code).toBe('TOO_MANY_PARTICIPANTS');
    });

    it('o cliente não envia creatorUid, score, progress nem rank (§33, bloqueantes)', async () => {
      const forbidden = [
        { creatorUid: igor.uid },
        { ownerUid: igor.uid },
        { participantUids: [joao.uid] },
        { score: 8 },
        { progress: 8 },
        { rank: 1 },
        { winner: igor.socialId },
        { goalReached: true },
        { status: 'ACTIVE' },
        { startsAt: STARTS },
      ];

      for (const extra of forbidden) {
        const response = await request(app.getHttpServer())
          .post('/v1/social/challenges')
          .set('Authorization', auth(igor))
          .send({ ...createChallengeBody([joao]), ...extra })
          .expect(400);
        expect(response.body.error.code).toBe('INVALID_CHALLENGE_REQUEST');
      }

      // E nenhum deles criou nada pelo caminho.
      const list = await request(app.getHttpServer())
        .get('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .expect(200);
      expect(list.body.challenges).toHaveLength(0);
    });

    it('o mesmo clientRequestId não cria dois desafios (§187/§189)', async () => {
      const body = createChallengeBody([joao]);

      const first = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(body)
        .expect(200);
      const second = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(body)
        .expect(200);

      expect(first.body.result).toBe('CREATED');
      expect(second.body.result).toBe('ALREADY_CREATED');
      expect(second.body.challenge.challengeId).toBe(first.body.challenge.challengeId);

      const list = await request(app.getHttpServer())
        .get('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .expect(200);
      expect(list.body.total).toBe(1);

      // E o convite do João continua sendo **um**.
      const invites = await request(app.getHttpServer())
        .get('/v1/social/challenge-invitations')
        .set('Authorization', auth(joao))
        .expect(200);
      expect(invites.body.invitations).toHaveLength(1);
    });

    it('mesmo clientRequestId com conteúdo diferente é conflito (§190)', async () => {
      const clientRequestId = randomUUID();

      await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(createChallengeBody([joao], { clientRequestId, target: 12 }))
        .expect(200);

      const conflict = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .send(createChallengeBody([joao], { clientRequestId, target: 30 }))
        .expect(409);
      expect(conflict.body.error.code).toBe('CHALLENGE_IDEMPOTENCY_CONFLICT');
    });
  });

  // ------------------------------------------------------------------------------- convites

  describe('convites', () => {
    it('só o destinatário aceita, e aceitar entra no desafio (§51)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      const invitationId = await pendingInvitationId(app, joao, challengeId);

      // O criador não aceita o convite do outro.
      const byCreator = await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(igor))
        .expect(404);
      expect(byCreator.body.error.code).toBe('CHALLENGE_INVITATION_NOT_FOUND');

      // Uma conta de fora também não — e recebe "não existe", não "não é seu" (§182).
      await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(ana))
        .expect(404);

      const accepted = await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(accepted.body.result).toBe('ACCEPTED');
      expect(accepted.body.challenge.participantCount).toBe(2);
    });

    it('aceitar duas vezes é sucesso, e não duplica participante (§191)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      const invitationId = await pendingInvitationId(app, joao, challengeId);

      await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(joao))
        .expect(200);
      const again = await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(again.body.result).toBe('ALREADY_PARTICIPATING');

      const detail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(igor))
        .expect(200);
      expect(detail.body.challenge.participantCount).toBe(2);
    });

    it('recusar é só do destinatário, e recusar duas vezes é sucesso (§52/§192)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      const invitationId = await pendingInvitationId(app, joao, challengeId);

      const first = await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/decline`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(first.body.result).toBe('DECLINED');

      const second = await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/decline`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(second.body.result).toBe('ALREADY_DECLINED');

      // Depois de recusar, o desafio não é acessível — não há participação (§100).
      await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(joao))
        .expect(404);
    });

    it('aceitar depois do início é bloqueado, e o convite fica EXPIRED (§54/§55/§56)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      const invitationId = await pendingInvitationId(app, joao, challengeId);

      // O desafio começa. Nenhum cron rodou: o estado é derivado do relógio (§27/§55).
      clock.set(STARTS + 60_000);

      const late = await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(joao))
        .expect(409);
      expect(late.body.error.code).toBe('CHALLENGE_ALREADY_STARTED');

      const invites = await request(app.getHttpServer())
        .get('/v1/social/challenge-invitations')
        .set('Authorization', auth(joao))
        .expect(200);
      expect(invites.body.invitations[0].status).toBe('EXPIRED');
    });

    it('desfazer a amizade antes do aceite bloqueia o aceite (§57/§58)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      const invitationId = await pendingInvitationId(app, joao, challengeId);

      await request(app.getHttpServer())
        .post('/v1/social/friends/remove')
        .set('Authorization', auth(igor))
        .send({ socialId: joao.socialId })
        .expect(200);

      const response = await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(joao))
        .expect(404);
      expect(response.body.error.code).toBe('CHALLENGE_INVITATION_NOT_FOUND');
    });

    it('o convite de um desafio cancelado aparece como CANCELLED e não pode ser aceito', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      const invitationId = await pendingInvitationId(app, joao, challengeId);

      await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/cancel`)
        .set('Authorization', auth(igor))
        .expect(200);

      const response = await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(joao))
        .expect(409);
      expect(response.body.error.code).toBe('CHALLENGE_CANCELLED');

      const invites = await request(app.getHttpServer())
        .get('/v1/social/challenge-invitations')
        .set('Authorization', auth(joao))
        .expect(200);
      expect(invites.body.invitations[0].status).toBe('CANCELLED');
    });

    it('quem só foi convidado NÃO vê o placar (§99)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao, jonathas]);
      await acceptChallenge(app, jonathas, challengeId);

      // O João ainda não aceitou: o detalhe é `404` para ele.
      await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(joao))
        .expect(404);

      // O que ele vê é o preview: regras, quem convidou, quantos aceitaram — e nada de progresso.
      const invites = await request(app.getHttpServer())
        .get('/v1/social/challenge-invitations')
        .set('Authorization', auth(joao))
        .expect(200);

      const preview = invites.body.invitations[0];
      expect(preview.challenge).toMatchObject({
        name: '12 treinos',
        type: 'WORKOUTS_COMPLETED',
        target: 12,
        participantCount: 2,
      });
      expect(preview.challenge.creator.displayName).toBe('Igor');
      // Nenhuma lista de participantes, nenhum placar, nenhuma pontuação.
      expect(JSON.stringify(preview)).not.toContain('score');
      expect(preview.challenge.participants).toBeUndefined();
      expect(preview.participants).toBeUndefined();
    });
  });

  // ------------------------------------------------------------------------------- participação

  describe('participação', () => {
    it('membro sai; criador não sai, cancela (§61/§62)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      const creatorLeave = await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/leave`)
        .set('Authorization', auth(igor))
        .expect(409);
      expect(creatorLeave.body.error.code).toBe('CANNOT_LEAVE_AS_CREATOR');

      const left = await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/leave`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(left.body.result).toBe('LEFT');

      // Sair duas vezes é sucesso (§193).
      const again = await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/leave`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(again.body.result).toBe('ALREADY_LEFT');
    });

    it('quem saiu não aparece no placar competitivo, e a linha permanece (§64/§65/§96)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao, jonathas]);
      await acceptChallenge(app, joao, challengeId);
      await acceptChallenge(app, jonathas, challengeId);

      clock.set(STARTS + 60_000);

      await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/leave`)
        .set('Authorization', auth(joao))
        .expect(200);

      const detail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(igor))
        .expect(200);

      expect(detail.body.participants.map((p: { displayName: string }) => p.displayName)).toEqual([
        'Igor',
        'Jonathas',
      ]);
      expect(detail.body.withdrawnCount).toBe(1);
      expect(detail.body.challenge.participantCount).toBe(2);

      // E o João continua vendo o desafio de que participou — a linha não foi apagada.
      const his = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(his.body.viewer).toMatchObject({ status: 'WITHDRAWN', canLeave: false });
    });

    it('não existe rejoin nesta fase (§66)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      const invitationId = await pendingInvitationId(app, joao, challengeId);
      await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(joao))
        .expect(200);

      await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/leave`)
        .set('Authorization', auth(joao))
        .expect(200);

      // Reaceitar o mesmo convite não o traz de volta: o convite já é terminal, e a participação
      // é `WITHDRAWN`.
      const retry = await request(app.getHttpServer())
        .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(retry.body.result).toBe('ALREADY_PARTICIPATING');

      const detail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(detail.body.viewer.status).toBe('WITHDRAWN');
    });
  });

  // ------------------------------------------------------------------------------- ciclo de vida

  describe('ciclo de vida', () => {
    it('UPCOMING → ACTIVE → ENDED, derivado do relógio e sem cron (§27)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      const statusNow = async () =>
        (
          await request(app.getHttpServer())
            .get(`/v1/social/challenges/${challengeId}`)
            .set('Authorization', auth(igor))
            .expect(200)
        ).body.challenge.status;

      expect(await statusNow()).toBe('UPCOMING');

      clock.set(STARTS);
      expect(await statusNow()).toBe('ACTIVE');

      clock.set(saoPauloInstant('2026-10-09T23:59:59'));
      expect(await statusNow()).toBe('ACTIVE');

      // O fim é exclusivo: a meia-noite do dia seguinte ao último dia.
      clock.set(saoPauloInstant('2026-10-10T00:00:00'));
      expect(await statusNow()).toBe('ENDED');
    });

    it('a janela que começa sem participantes suficientes vira VOID (§28/§29)', async () => {
      // Ninguém aceita.
      const { challengeId } = await createChallenge(app, igor, [joao]);
      clock.set(STARTS);

      const detail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(igor))
        .expect(200);

      expect(detail.body.challenge.status).toBe('VOID');
      // Sem competição, sem placar: um desafio de uma linha seria uma disputa inventada (§95).
      expect(detail.body.participants).toEqual([]);
      expect(detail.body.resultMayStillChange).toBe(false);
    });

    it('só o criador cancela, e cancelar duas vezes é sucesso (§67/§194)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      const byMember = await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/cancel`)
        .set('Authorization', auth(joao))
        .expect(403);
      expect(byMember.body.error.code).toBe('NOT_CHALLENGE_CREATOR');

      const first = await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/cancel`)
        .set('Authorization', auth(igor))
        .expect(200);
      expect(first.body.result).toBe('CANCELLED');

      const second = await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/cancel`)
        .set('Authorization', auth(igor))
        .expect(200);
      expect(second.body.result).toBe('ALREADY_CANCELLED');
    });

    it('cancelar um desafio em andamento é permitido, e não há resultado (§69/§94)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);
      clock.set(STARTS + 3 * 24 * 60 * 60 * 1000);

      await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/cancel`)
        .set('Authorization', auth(igor))
        .expect(200);

      const detail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(detail.body.challenge.status).toBe('CANCELLED');
      expect(detail.body.participants).toEqual([]);
      expect(detail.body.resultMayStillChange).toBe(false);
    });

    it('as regras não mudam depois da criação — bait-and-switch é impossível (§47/§48)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao], { target: 5 });
      await acceptChallenge(app, joao, challengeId);

      // Não existe rota de edição. As tentativas plausíveis não têm handler.
      for (const [method, path] of [
        ['patch', `/v1/social/challenges/${challengeId}`],
        ['put', `/v1/social/challenges/${challengeId}`],
        ['post', `/v1/social/challenges/${challengeId}`],
      ] as const) {
        const agent = request(app.getHttpServer());
        await agent[method](path).set('Authorization', auth(igor)).send({ target: 30 }).expect(404);
      }

      const detail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(detail.body.challenge.target).toBe(5);
    });
  });

  // ------------------------------------------------------------------------------- autorização

  describe('autorização e isolamento de conta', () => {
    it('uma conta que não participa não vê o desafio, mesmo sabendo o id (§100/§101/§215)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      const response = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(ana))
        .expect(404);
      expect(response.body.error.code).toBe('CHALLENGE_NOT_FOUND');

      // E a resposta é indistinguível de um id inventado (§182).
      const invented = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${randomUUID()}`)
        .set('Authorization', auth(ana))
        .expect(404);
      expect(invented.body.error.code).toBe(response.body.error.code);
    });

    it('uma conta de fora não cancela, não sai e não responde por ninguém (§215)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/cancel`)
        .set('Authorization', auth(ana))
        .expect(404);
      await request(app.getHttpServer())
        .post(`/v1/social/challenges/${challengeId}/leave`)
        .set('Authorization', auth(ana))
        .expect(404);

      // Nada mudou.
      const detail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(igor))
        .expect(200);
      expect(detail.body.challenge.status).toBe('UPCOMING');
      expect(detail.body.challenge.participantCount).toBe(2);
    });

    it('toda rota exige autenticação (§112)', async () => {
      const routes: Array<['get' | 'post', string]> = [
        ['post', '/v1/social/challenges'],
        ['get', '/v1/social/challenges'],
        ['get', '/v1/social/challenges/qualquer'],
        ['post', '/v1/social/challenges/qualquer/cancel'],
        ['post', '/v1/social/challenges/qualquer/leave'],
        ['get', '/v1/social/challenge-invitations'],
        ['post', '/v1/social/challenge-invitations/qualquer/accept'],
        ['post', '/v1/social/challenge-invitations/qualquer/decline'],
      ];
      for (const [method, path] of routes) {
        await request(app.getHttpServer())[method](path).expect(401);
      }
    });

    it('sem perfil social, nenhuma rota de desafio responde (§113)', async () => {
      // Uma conta autenticada de verdade que **nunca ativou** o Social. Ela é diferente de um
      // token inválido: a autenticação passa, e o que falta é o perfil — que é o estado normal de
      // quem nunca tocou no interruptor (T17.0: login não ativa Social).
      const semSocial = account('sem-social', 'Sem Social');
      verifier.accept(semSocial.token, { uid: semSocial.uid });

      for (const [method, path] of [
        ['get', '/v1/social/challenges'],
        ['get', '/v1/social/challenge-invitations'],
        ['get', '/v1/social/challenges/qualquer'],
        ['post', '/v1/social/challenges/qualquer/cancel'],
        ['post', '/v1/social/challenges/qualquer/leave'],
        ['post', '/v1/social/challenge-invitations/qualquer/accept'],
        ['post', '/v1/social/challenge-invitations/qualquer/decline'],
      ] as const) {
        const agent = request(app.getHttpServer());
        const response = await agent[method](path)
          .set('Authorization', auth(semSocial))
          .expect(404);
        expect(response.body.error.code).toBe('SOCIAL_NOT_ENABLED');
      }

      // E criar também não: o desafio é do domínio social, e ele exige perfil social.
      const create = await request(app.getHttpServer())
        .post('/v1/social/challenges')
        .set('Authorization', auth(semSocial))
        .send(createChallengeBody([joao]))
        .expect(404);
      expect(create.body.error.code).toBe('SOCIAL_NOT_ENABLED');
    });

    it('perfil desativado não movimenta desafio — nem leitura (§113)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      await request(app.getHttpServer())
        .post('/v1/social/me/disable')
        .set('Authorization', auth(joao))
        .expect(200);

      for (const [method, path] of [
        ['get', '/v1/social/challenges'],
        ['get', `/v1/social/challenges/${challengeId}`],
        ['get', '/v1/social/challenge-invitations'],
      ] as const) {
        const agent = request(app.getHttpServer());
        const response = await agent[method](path).set('Authorization', auth(joao)).expect(409);
        expect(response.body.error.code).toBe('SOCIAL_PROFILE_DISABLED');
      }
    });
  });

  // ------------------------------------------------------------------------------- privacidade

  describe('privacidade do DTO', () => {
    it('nenhuma resposta carrega uid, e-mail, friendCode ou dado de treino (§125–§127)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao, jonathas]);
      await acceptChallenge(app, joao, challengeId);
      await acceptChallenge(app, jonathas, challengeId);
      clock.set(STARTS + 60_000);

      const bodies: string[] = [];
      for (const [method, path, who] of [
        ['get', '/v1/social/challenges', igor],
        ['get', `/v1/social/challenges/${challengeId}`, igor],
        ['get', `/v1/social/challenges/${challengeId}`, joao],
        ['get', '/v1/social/challenge-invitations', joao],
      ] as const) {
        const agent = request(app.getHttpServer());
        const response = await agent[method](path).set('Authorization', auth(who)).expect(200);
        bodies.push(JSON.stringify(response.body));
      }

      const forbidden = [
        // Identidade privada de infraestrutura.
        igor.uid,
        joao.uid,
        jonathas.uid,
        'ownerUid',
        'owner_uid',
        'creatorUid',
        'firebaseUid',
        'email',
        'friendCode',
        // Histórico de treino bruto.
        'sessionId',
        'syncId',
        'exerciseId',
        'exercises',
        'sets',
        'reps',
        'weight',
        'notes',
        'payload',
        'startedAt',
        'finishedAt',
        // Medidas corporais.
        'bodyFat',
        'measurement',
        'weightKg',
      ];

      for (const body of bodies) {
        for (const needle of forbidden) {
          expect({ needle, present: body.includes(needle) }).toEqual({ needle, present: false });
        }
      }
    });

    it('o número de convites pendentes é só do criador (§172/§173)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao, jonathas]);
      await acceptChallenge(app, joao, challengeId);

      const creatorView = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(igor))
        .expect(200);
      expect(creatorView.body.pendingInvitationCount).toBe(1);

      const memberView = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(joao))
        .expect(200);
      // Ausente, e não zero: o membro não precisa saber quem falta responder.
      expect(memberView.body.pendingInvitationCount).toBeUndefined();
      // E o nome de quem ainda não aceitou não aparece em lugar nenhum.
      expect(JSON.stringify(memberView.body)).not.toContain('Jonathas');
    });
  });

  // ------------------------------------------------------------------------------- listagem

  describe('listagem', () => {
    it('lista os meus desafios, ordenados por status (§107)', async () => {
      const ativo = await createChallenge(app, igor, [joao], {
        name: 'Ativo',
        startDate: '2026-09-09',
        endDate: '2026-09-30',
      });
      const proximo = await createChallenge(app, igor, [joao], {
        name: 'Proximo',
        startDate: '2026-09-20',
        endDate: '2026-09-30',
      });
      await acceptChallenge(app, joao, ativo.challengeId);
      await acceptChallenge(app, joao, proximo.challengeId);

      clock.set(saoPauloInstant('2026-09-09T10:00:00'));

      const list = await request(app.getHttpServer())
        .get('/v1/social/challenges')
        .set('Authorization', auth(igor))
        .expect(200);

      expect(
        list.body.challenges.map((c: { name: string; status: string }) => [c.name, c.status]),
      ).toEqual([
        ['Ativo', 'ACTIVE'],
        ['Proximo', 'UPCOMING'],
      ]);
    });

    it('a conta de fora não vê desafio nenhum', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      const list = await request(app.getHttpServer())
        .get('/v1/social/challenges')
        .set('Authorization', auth(ana))
        .expect(200);
      expect(list.body).toMatchObject({ challenges: [], total: 0 });
    });
  });

  // ------------------------------------------------------------------------------- desativar

  describe('desativar o Social (§115–§120)', () => {
    it('recusa os convites pendentes, tira das participações e cancela o que criou', async () => {
      // O João participa de um desafio do Igor e criou um dos seus.
      const doIgor = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, doIgor.challengeId);

      await befriend(app, joao, jonathas);
      const doJoao = await createChallenge(app, joao, [jonathas], { name: 'Do Joao' });
      await acceptChallenge(app, jonathas, doJoao.challengeId);

      // E tem um convite pendente.
      const pendente = await createChallenge(app, igor, [joao], { name: 'Pendente' });

      await request(app.getHttpServer())
        .post('/v1/social/me/disable')
        .set('Authorization', auth(joao))
        .expect(200);

      // 1. Saiu do desafio do Igor (§117) — o placar dele para de ser publicado.
      const doIgorDetail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${doIgor.challengeId}`)
        .set('Authorization', auth(igor))
        .expect(200);
      expect(doIgorDetail.body.challenge.participantCount).toBe(1);
      expect(doIgorDetail.body.withdrawnCount).toBe(1);

      // 2. O desafio que ele criou foi cancelado (§118) — ninguém mais poderia encerrá-lo.
      const doJoaoDetail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${doJoao.challengeId}`)
        .set('Authorization', auth(jonathas))
        .expect(200);
      expect(doJoaoDetail.body.challenge.status).toBe('CANCELLED');

      // 3. O convite pendente foi recusado (§116).
      const pendenteDetail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${pendente.challengeId}`)
        .set('Authorization', auth(igor))
        .expect(200);
      expect(pendenteDetail.body.pendingInvitationCount).toBe(0);
    });

    it('desativar não apaga a amizade nem o histórico de desafio (§120)', async () => {
      const { challengeId } = await createChallenge(app, igor, [joao]);
      await acceptChallenge(app, joao, challengeId);

      await request(app.getHttpServer())
        .post('/v1/social/me/disable')
        .set('Authorization', auth(joao))
        .expect(200);
      await request(app.getHttpServer())
        .post('/v1/social/me/enable')
        .set('Authorization', auth(joao))
        .expect(200);

      // A amizade continua (T17.1: desativar suspende, não desfaz).
      const friends = await request(app.getHttpServer())
        .get('/v1/social/friends')
        .set('Authorization', auth(joao))
        .expect(200);
      expect(friends.body.friends.map((f: { displayName: string }) => f.displayName)).toContain(
        'Igor',
      );

      // E o desafio continua visível para ele — como participação encerrada, não apagada.
      const detail = await request(app.getHttpServer())
        .get(`/v1/social/challenges/${challengeId}`)
        .set('Authorization', auth(joao))
        .expect(200);
      expect(detail.body.viewer.status).toBe('WITHDRAWN');
    });
  });
});
