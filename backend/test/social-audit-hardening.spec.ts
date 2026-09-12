import request from 'supertest';
import { PostgresService } from '../src/database/postgres.service';
import {
  SOCIAL_GROUP_MAX_MEMBERS,
  SOCIAL_GROUP_MAX_OWNED,
} from '../src/modules/social/social-group.limits';
import {
  ACCOUNT_A,
  ACCOUNT_B,
  ACCOUNT_C,
  createSocialScenario,
  type AuditAccount,
  type SocialScenario,
} from './support/social-scenario';
import { uuid } from './support/sync-fixtures';

/**
 * As lacunas da auditoria de 2026-09-12 (§7), viradas em teste.
 *
 * Cada bloco aqui existe porque o defeito correspondente **passava** com a suíte inteira verde:
 * bloqueio cancelando o aviso de um terceiro, convite de Squad gravado sem o evento que o torna
 * visível, corpo malformado virando `500`, teto de domínio ultrapassado por duas requisições
 * simultâneas e cursor malformado derrubando a consulta.
 */
describe('Auditoria 2026-09-12 — regressões do backend social', () => {
  let s: SocialScenario;

  const auth = (account: AuditAccount) => ({ Authorization: `Bearer ${account.token}` });

  beforeEach(async () => {
    s = await createSocialScenario();
  });

  afterEach(async () => {
    await s.close();
  });

  // ================================================================= bloqueio × terceiros

  describe('bloquear alcança o par, e só o par', () => {
    const eventsFor = (uid: string): { entity_id: string; status: string }[] =>
      s.inDatabase(
        (db) =>
          db
            .prepare(
              `SELECT entity_id, status FROM social_notification_events WHERE recipient_uid = ?`,
            )
            .all(uid) as { entity_id: string; status: string }[],
      );

    it('a notificação pendente de um terceiro sobrevive ao bloqueio', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.activate(ACCOUNT_C);

      const socialA = await s.socialIdOf(ACCOUNT_A);

      // Dois pedidos para A: um de B (que vai ser bloqueado) e um de C (que não tem nada com isso).
      const fromB = await request(s.server())
        .post('/v1/social/friend-requests')
        .set(auth(ACCOUNT_B))
        .send({ socialId: socialA })
        .expect(200);
      const fromC = await request(s.server())
        .post('/v1/social/friend-requests')
        .set(auth(ACCOUNT_C))
        .send({ socialId: socialA })
        .expect(200);

      const requestFromB = fromB.body.request.requestId as string;
      const requestFromC = fromC.body.request.requestId as string;

      const before = eventsFor(ACCOUNT_A.uid);
      expect(before).toHaveLength(2);
      expect(before.every((event) => event.status === 'PENDING')).toBe(true);

      const socialB = await s.socialIdOf(ACCOUNT_B);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_A))
        .send({ blockedSocialId: socialB })
        .expect(200);

      const after = eventsFor(ACCOUNT_A.uid);
      const statusOf = (entityId: string) =>
        after.find((event) => event.entity_id === entityId)?.status;

      // O do par bloqueado cai; o do terceiro continua de pé. Antes, o `UPDATE` pegava todo evento
      // pendente dos dois usuários, sem filtro de entidade — e C perdia o aviso por um bloqueio de
      // que nunca participou.
      expect(statusOf(requestFromB)).toBe('CANCELLED');
      expect(statusOf(requestFromC)).toBe('PENDING');

      // E o pedido de C também continua pendente: a limpeza é do par.
      const requestStatus = s.inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT status FROM friend_requests WHERE request_id = ?`)
              .get(requestFromC) as { status: string } | undefined
          )?.status,
      );
      expect(requestStatus).toBe('PENDING');
    });

    it('bloquear sem perfil social é recusado, e não explode na chave estrangeira', async () => {
      await s.activate(ACCOUNT_B);
      const socialB = await s.socialIdOf(ACCOUNT_B);

      // A nunca ativou o Social: `social_blocks.blocker_uid` referencia `social_profiles`, e o
      // `INSERT` morria numa violação de FK — `500` para um pedido que o servidor sabia recusar.
      const response = await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_A))
        .send({ blockedSocialId: socialB });

      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('SOCIAL_NOT_ENABLED');
    });
  });

  // ================================================================= corpos malformados

  describe('corpo malformado é 4xx do cliente, nunca 5xx do servidor', () => {
    it('POST /v1/social/blocks recusa corpo fora do contrato', async () => {
      await s.activate(ACCOUNT_A);

      const bodies: unknown[] = [
        [],
        {},
        { blockedSocialId: 42 },
        { blockedSocialId: '' },
        { blockedSocialId: 'spark-1', blockerUid: ACCOUNT_B.uid },
        { blockedSocialId: 'spark-1', extra: true },
      ];

      for (const body of bodies) {
        const response = await request(s.server())
          .post('/v1/social/blocks')
          .set(auth(ACCOUNT_A))
          .send(body as object);
        expect(response.status).toBe(400);
        expect(response.body.error.code).toBe('INVALID_SOCIAL_REQUEST');
      }
    });

    it('POST /v1/social/workout-shares recusa corpo fora do contrato', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const socialB = await s.socialIdOf(ACCOUNT_B);

      const validSnapshot = {
        snapshotVersion: 1,
        name: 'Upper A',
        exercises: [
          {
            canonicalExerciseId: 'supino-reto-barra',
            sortOrder: 0,
            targetSets: 3,
            minReps: 8,
            maxReps: 12,
            restDurationSeconds: 90,
          },
        ],
      };

      const bodies: unknown[] = [
        // Sem envelope nenhum.
        [],
        // Tipos errados nos identificadores.
        { recipientSocialId: 7, clientRequestId: uuid(), snapshot: validSnapshot },
        { recipientSocialId: socialB, clientRequestId: null, snapshot: validSnapshot },
        // Snapshot que não é objeto, e lista de exercícios que não é lista.
        { recipientSocialId: socialB, clientRequestId: uuid(), snapshot: 'treino' },
        {
          recipientSocialId: socialB,
          clientRequestId: uuid(),
          snapshot: { ...validSnapshot, exercises: 'nenhum' },
        },
        // Chave desconhecida no envelope, no snapshot e dentro de um exercício: antes, qualquer
        // uma delas era persistida verbatim em `snapshot_json` e devolvida ao destinatário.
        {
          recipientSocialId: socialB,
          clientRequestId: uuid(),
          snapshot: validSnapshot,
          senderUid: ACCOUNT_A.uid,
        },
        {
          recipientSocialId: socialB,
          clientRequestId: uuid(),
          snapshot: { ...validSnapshot, contrabando: 'x' },
        },
        {
          recipientSocialId: socialB,
          clientRequestId: uuid(),
          snapshot: {
            ...validSnapshot,
            exercises: [{ ...validSnapshot.exercises[0], plannedWeight: 120 }],
          },
        },
      ];

      for (const body of bodies) {
        const response = await request(s.server())
          .post('/v1/social/workout-shares')
          .set(auth(ACCOUNT_A))
          .send(body as object);
        expect(response.status).toBe(400);
      }

      // Nada disso chegou ao banco.
      expect(
        s.inDatabase(
          (db) => (db.prepare(`SELECT COUNT(*) AS n FROM workout_shares`).get() as { n: number }).n,
        ),
      ).toBe(0);
    });
  });

  // ================================================================= convite de Squad atômico

  describe('convite de Squad e evento de notificação nascem juntos', () => {
    it('falha ao gravar o evento faz ROLLBACK do convite', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      const socialB = await s.socialIdOf(ACCOUNT_B);

      const postgres = s.app.get(PostgresService);
      // Injeção de falha **no banco**, dentro da transação e depois do convite já inserido — o
      // ponto exato em que o par ficava inconsistente enquanto o evento saía por outra conexão.
      await postgres.query(`
        CREATE OR REPLACE FUNCTION falha_evento_squad_fn() RETURNS trigger AS $$
        BEGIN
          RAISE EXCEPTION 'falha injetada no outbox do convite';
        END;
        $$ LANGUAGE plpgsql;
        DROP TRIGGER IF EXISTS falha_evento_squad ON social_notification_events;
        CREATE TRIGGER falha_evento_squad
        BEFORE INSERT ON social_notification_events
        FOR EACH ROW EXECUTE FUNCTION falha_evento_squad_fn();
      `);

      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId: socialB, clientRequestId: uuid() })
        .expect(500);

      const counts = () =>
        s.inDatabase((db) => ({
          invitations: (
            db.prepare(`SELECT COUNT(*) AS n FROM social_group_invitations`).get() as { n: number }
          ).n,
          events: (
            db
              .prepare(
                `SELECT COUNT(*) AS n FROM social_notification_events
                  WHERE type = 'GROUP_INVITATION_RECEIVED'`,
              )
              .get() as { n: number }
          ).n,
        }));

      // Nenhum dos dois: não pode existir convite que ninguém foi avisado de ter recebido.
      expect(counts()).toEqual({ invitations: 0, events: 0 });

      await postgres.query(`
        DROP TRIGGER IF EXISTS falha_evento_squad ON social_notification_events;
        DROP FUNCTION IF EXISTS falha_evento_squad_fn();
      `);

      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId: socialB, clientRequestId: uuid() })
        .expect(201);

      expect(counts()).toEqual({ invitations: 1, events: 1 });
    });
  });

  // ================================================================= tetos sob concorrência

  describe('teto de domínio resiste a duas requisições simultâneas', () => {
    it('criar dois Squads ao mesmo tempo na última vaga cria **um**', async () => {
      await s.activate(ACCOUNT_A);
      for (let i = 0; i < SOCIAL_GROUP_MAX_OWNED - 1; i += 1) {
        await s.createGroup(ACCOUNT_A, `Squad ${i}`);
      }

      const create = (name: string) =>
        request(s.server())
          .post('/v1/social/groups')
          .set(auth(ACCOUNT_A))
          .send({ name, clientRequestId: uuid() });

      const [one, two] = await Promise.all([create('Último A'), create('Último B')]);

      const statuses = [one.status, two.status].sort((a, b) => a - b);
      expect(statuses).toEqual([201, 422]);
      const refused = [one, two].find((response) => response.status === 422)!;
      expect(refused.body.error.code).toBe('GROUP_OWNED_LIMIT_REACHED');

      const owned = s.inDatabase(
        (db) =>
          (
            db
              .prepare(
                `SELECT COUNT(*) AS n FROM social_groups WHERE owner_uid = ? AND status = 'ACTIVE'`,
              )
              .get(ACCOUNT_A.uid) as { n: number }
          ).n,
      );
      expect(owned).toBe(SOCIAL_GROUP_MAX_OWNED);
    });

    it('dois aceites simultâneos na última vaga do Squad admitem **um**', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.activate(ACCOUNT_C);
      const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');

      // Enche até faltar exatamente uma vaga (o dono conta).
      for (let i = 0; i < SOCIAL_GROUP_MAX_MEMBERS - 2; i += 1) {
        const extra = s.extraAccount(i);
        await s.activate(extra);
        // O pedido parte da conta extra: o teto de envio da T17.1 é por remetente, e dezoito
        // pedidos saindo de A estourariam um limite que não tem nada a ver com este teste.
        await s.makeFriends(extra, ACCOUNT_A);
        await s.addMember(ACCOUNT_A, groupId, extra);
      }

      await s.makeFriends(ACCOUNT_B, ACCOUNT_A);
      await s.makeFriends(ACCOUNT_C, ACCOUNT_A);
      const inviteB = await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);
      const inviteC = await s.invite(ACCOUNT_A, groupId, ACCOUNT_C);

      const accept = (account: AuditAccount, invitationId: string) =>
        request(s.server())
          .post(`/v1/social/group-invitations/${invitationId}/accept`)
          .set(auth(account));

      const [resB, resC] = await Promise.all([
        accept(ACCOUNT_B, inviteB),
        accept(ACCOUNT_C, inviteC),
      ]);

      const statuses = [resB.status, resC.status].sort((a, b) => a - b);
      expect(statuses).toEqual([200, 422]);
      const refused = [resB, resC].find((response) => response.status === 422)!;
      expect(refused.body.error.code).toBe('GROUP_FULL');

      const members = s.inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT COUNT(*) AS n FROM social_group_memberships WHERE group_id = ?`)
              .get(groupId) as { n: number }
          ).n,
      );
      expect(members).toBe(SOCIAL_GROUP_MAX_MEMBERS);

      // O convite do perdedor não ficou aceito num Squad de que ele não participa.
      const refusedInvitation = refused === resB ? inviteB : inviteC;
      const invitationStatus = s.inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT status FROM social_group_invitations WHERE id = ?`)
              .get(refusedInvitation) as { status: string } | undefined
          )?.status,
      );
      expect(invitationStatus).toBe('PENDING');
    }, 60_000);
  });

  // ================================================================= cursor malformado

  describe('cursor malformado é 400, e não uma consulta com NaN', () => {
    const cursorOf = (primary: unknown, secondary: string) =>
      Buffer.from(JSON.stringify([primary, secondary]), 'utf8').toString('base64url');

    it('cursor não numérico na lista de pedidos é recusado', async () => {
      await s.activate(ACCOUNT_A);

      for (const path of ['incoming', 'outgoing']) {
        const response = await request(s.server())
          .get(`/v1/social/friend-requests/${path}?cursor=${cursorOf('ontem', 'algum-id')}`)
          .set(auth(ACCOUNT_A));
        expect(response.status).toBe(400);
        expect(response.body.error.code).toBe('INVALID_FRIEND_REQUEST');
      }
    });

    it('cursor não numérico na lista de desafios é recusado', async () => {
      await s.activate(ACCOUNT_A);

      const response = await request(s.server())
        .get(`/v1/social/challenges?cursor=${cursorOf('ontem', 'algum-id')}`)
        .set(auth(ACCOUNT_A));

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_CHALLENGE_REQUEST');
    });

    it('a lista de amigos continua aceitando cursor de texto — ela ordena por nome', async () => {
      await s.activate(ACCOUNT_A);
      await s.activate(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);

      const response = await request(s.server())
        .get(`/v1/social/friends?cursor=${cursorOf('Aaa', 'algum-id')}`)
        .set(auth(ACCOUNT_A));

      expect(response.status).toBe(200);
    });
  });
});
