import request from 'supertest';
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
 * T17.11 — o ciclo de vida da conta, o push e a integridade do banco (§96–§106, §160–§166).
 *
 * ## As duas políticas que este arquivo separa
 *
 * Desativar o Social e excluir a conta **não** resolvem a posse de um Squad da mesma forma, e a
 * diferença é deliberada:
 *
 * ```text
 * Social Disable   ──▶ pode recusar        ──▶ a pessoa transfere ou exclui, e decide quem fica
 * Account Deletion ──▶ nunca é bloqueada   ──▶ o Squad vai junto, sem escolher um substituto
 * ```
 *
 * §98 e §101 explicam por quê: a desativação é reversível e comporta uma pergunta ao usuário; a
 * exclusão precisa ser determinística e não pode depender da escolha de um terceiro.
 */
describe('T17.11 — ciclo de vida, push e integridade', () => {
  let s: SocialScenario;

  const auth = (a: AuditAccount) => ({ Authorization: `Bearer ${a.token}` });

  beforeEach(async () => {
    s = await createSocialScenario();
    await s.activate(ACCOUNT_A);
    await s.activate(ACCOUNT_B);
    await s.activate(ACCOUNT_C);
  });

  afterEach(async () => {
    await s.close();
  });

  // =============================================================== §161 Social Disable

  describe('desativação do Social (§96–§99/§161)', () => {
    it('o dono de um squad com outras pessoas é recusado, com uma contagem (§98/§99)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const first = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      await s.addMember(ACCOUNT_A, first, ACCOUNT_B);
      const second = await s.createGroup(ACCOUNT_A, 'O outro');
      await s.addMember(ACCOUNT_A, second, ACCOUNT_B);

      const res = await request(s.server())
        .post('/v1/social/me/disable')
        .set(auth(ACCOUNT_A))
        .expect(409);

      expect(res.body.error.code).toBe('GROUP_OWNERSHIP_REQUIRES_ACTION');
      // §99 — a contagem, e nunca os membros.
      expect(res.body.error.message).toContain('2');
      expect(res.body.error.message).not.toContain(ACCOUNT_B.name);
      expect(JSON.stringify(res.body)).not.toContain(ACCOUNT_B.uid);

      // O perfil continua ativo: a recusa não deixou meio estado.
      const me = await request(s.server()).get('/v1/social/me').set(auth(ACCOUNT_A)).expect(200);
      expect(me.body.profile.status).toBe('ACTIVE');
    });

    it('depois de transferir a posse, o antigo dono desativa (§98)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);

      const members = await request(s.server())
        .get(`/v1/social/groups/${groupId}/members`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      const membershipId = members.body.items.find(
        (m: { role: string }) => m.role === 'MEMBER',
      ).membershipId;
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/transfer-ownership`)
        .set(auth(ACCOUNT_A))
        .send({ membershipId })
        .expect(204);

      await request(s.server()).post('/v1/social/me/disable').set(auth(ACCOUNT_A)).expect(200);

      // §97 — como MEMBER, A saiu do Squad. O Squad sobrevive com B.
      const state = s.inDatabase((db) => ({
        group: (
          db.prepare(`SELECT status FROM social_groups WHERE id = ?`).get(groupId) as {
            status: string;
          }
        ).status,
        members: (
          db
            .prepare(`SELECT COUNT(*) AS n FROM social_group_memberships WHERE group_id = ?`)
            .get(groupId) as { n: number }
        ).n,
      }));
      expect(state).toEqual({ group: 'ACTIVE', members: 1 });
    });

    it('o squad em que a pessoa está sozinha é excluído sem pergunta (§98)', async () => {
      const groupId = await s.createGroup(ACCOUNT_A);
      await request(s.server()).post('/v1/social/me/disable').set(auth(ACCOUNT_A)).expect(200);

      const group = s.inDatabase((db) =>
        db.prepare(`SELECT status FROM social_groups WHERE id = ?`).get(groupId),
      ) as { status: string };
      expect(group.status).toBe('DELETED');
    });

    it('as participações e os convites pendentes são resolvidos (§96/§97/§161)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.makeFriends(ACCOUNT_B, ACCOUNT_C);
      const groupId = await s.createGroup(ACCOUNT_A);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);

      // B é dono de um Squad vazio e recebeu um convite de C — os dois precisam ser resolvidos.
      const ownedByB = await s.createGroup(ACCOUNT_B, 'De Bruno');
      const groupOfC = await s.createGroup(ACCOUNT_C, 'De Carla');
      const invitationId = await s.invite(ACCOUNT_C, groupOfC, ACCOUNT_B);

      const sessionSyncId = await s.pushSession(ACCOUNT_B);
      const checkInId = await s.publishCheckIn(ACCOUNT_B, sessionSyncId);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/checkins/${checkInId}`)
        .set(auth(ACCOUNT_B))
        .expect(201);

      await request(s.server()).post('/v1/social/me/disable').set(auth(ACCOUNT_B)).expect(200);

      const state = s.inDatabase((db) => ({
        membershipsOfB: (
          db
            .prepare(`SELECT COUNT(*) AS n FROM social_group_memberships WHERE member_uid = ?`)
            .get(ACCOUNT_B.uid) as { n: number }
        ).n,
        ownedByB: (
          db.prepare(`SELECT status FROM social_groups WHERE id = ?`).get(ownedByB) as {
            status: string;
          }
        ).status,
        invitation: (
          db
            .prepare(`SELECT status FROM social_group_invitations WHERE id = ?`)
            .get(invitationId) as { status: string }
        ).status,
        sharesOfB: (
          db
            .prepare(`SELECT COUNT(*) AS n FROM social_group_checkin_shares WHERE author_uid = ?`)
            .get(ACCOUNT_B.uid) as { n: number }
        ).n,
        // §47 — a publicação de B continua inteira; ela só deixa de circular enquanto o Social
        // dela estiver desativado.
        checkIn: (
          db.prepare(`SELECT status FROM social_workout_checkins WHERE id = ?`).get(checkInId) as {
            status: string;
          }
        ).status,
      }));

      expect(state).toEqual({
        membershipsOfB: 0,
        ownedByB: 'DELETED',
        invitation: 'CANCELLED',
        sharesOfB: 0,
        checkIn: 'PUBLISHED',
      });

      // O Squad de A sobrevive, com A sozinha.
      const detail = await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      expect(detail.body.memberCount).toBe(1);
    });
  });

  // =============================================================== §160 Account Deletion

  describe('exclusão de conta (§100/§101/§102/§160)', () => {
    it('o dono é excluído: o squad some, e os dados de B e C ficam (§100/§102/§160)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      const groupId = await s.createGroup(ACCOUNT_A);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_C);

      const sessionB = await s.pushSession(ACCOUNT_B);
      const checkInB = await s.publishCheckIn(ACCOUNT_B, sessionB, { caption: 'de Bruno' });
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/checkins/${checkInB}`)
        .set(auth(ACCOUNT_B))
        .expect(201);

      // §100 — a exclusão **nunca** é bloqueada, mesmo com um Squad cheio de gente.
      await request(s.server())
        .delete('/v1/account')
        .set(auth(ACCOUNT_A))
        .expect((res) => expect([200, 202, 204]).toContain(res.status));

      const state = s.inDatabase((db) => ({
        group: db.prepare(`SELECT * FROM social_groups WHERE id = ?`).get(groupId),
        memberships: (
          db
            .prepare(`SELECT COUNT(*) AS n FROM social_group_memberships WHERE group_id = ?`)
            .get(groupId) as { n: number }
        ).n,
        shares: (
          db
            .prepare(`SELECT COUNT(*) AS n FROM social_group_checkin_shares WHERE group_id = ?`)
            .get(groupId) as { n: number }
        ).n,
        // §102 — o check-in de B continua existindo, e a sessão de treino dele também.
        checkInB: db
          .prepare(`SELECT status, caption FROM social_workout_checkins WHERE id = ?`)
          .get(checkInB) as { status: string; caption: string },
        sessionsB: (
          db
            .prepare(
              `SELECT COUNT(*) AS n FROM sync_entities
                WHERE owner_uid = ? AND entity_type = 'WORKOUT_SESSION' AND deleted = 0`,
            )
            .get(ACCOUNT_B.uid) as { n: number }
        ).n,
        profileB: db
          .prepare(`SELECT owner_uid FROM social_profiles WHERE owner_uid = ?`)
          .get(ACCOUNT_B.uid),
        profileC: db
          .prepare(`SELECT owner_uid FROM social_profiles WHERE owner_uid = ?`)
          .get(ACCOUNT_C.uid),
      }));

      expect(state.group).toBeUndefined();
      expect(state.memberships).toBe(0);
      expect(state.shares).toBe(0);
      expect(state.checkInB).toEqual({ status: 'PUBLISHED', caption: 'de Bruno' });
      expect(state.sessionsB).toBe(1);
      expect(state.profileB).toBeDefined();
      expect(state.profileC).toBeDefined();

      // As contas de B e C continuam operando normalmente.
      await request(s.server()).get('/v1/social/me').set(auth(ACCOUNT_B)).expect(200);
      await request(s.server()).get('/v1/social/groups').set(auth(ACCOUNT_C)).expect(200);
    });

    it('um MEMBER é excluído: o squad sobrevive sem ele (§100/§160)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);

      await request(s.server())
        .delete('/v1/account')
        .set(auth(ACCOUNT_B))
        .expect((res) => expect([200, 202, 204]).toContain(res.status));

      const detail = await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      expect(detail.body.memberCount).toBe(1);

      const memberships = s.inDatabase((db) =>
        db
          .prepare(`SELECT COUNT(*) AS n FROM social_group_memberships WHERE member_uid = ?`)
          .get(ACCOUNT_B.uid),
      ) as { n: number };
      expect(memberships.n).toBe(0);
    });
  });

  // =============================================================== §162 push

  describe('push do convite (§90–§95/§162)', () => {
    async function enablePush(account: AuditAccount): Promise<void> {
      await request(s.server())
        .post('/v1/social/notifications/devices')
        .set(auth(account))
        .send({
          deviceId: `device-${account.uid}`,
          platform: 'ANDROID',
          fcmToken: `token-${account.uid}`,
        })
        .expect((res) => expect([200, 201]).toContain(res.status));
      await request(s.server())
        .patch('/v1/social/notifications/preferences')
        .set(auth(account))
        .send({ pushEnabled: true })
        .expect(200);
    }

    function events(type = 'GROUP_INVITATION_RECEIVED') {
      return s.inDatabase((db) =>
        db
          .prepare(
            `SELECT id, recipient_uid AS recipientUid, entity_id AS entityId, status
               FROM social_notification_events WHERE type = ?`,
          )
          .all(type),
      ) as Array<{ id: string; recipientUid: string; entityId: string; status: string }>;
    }

    it('um convite novo gera um GROUP_INVITATION_RECEIVED, e o retry não gera outro (§90/§162)', async () => {
      await enablePush(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A);

      const socialId = await s.socialIdOf(ACCOUNT_B);
      const clientRequestId = uuid();
      const first = await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId })
        .expect(201);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId })
        .expect(201);

      const all = events();
      expect(all).toHaveLength(1);
      expect(all[0].recipientUid).toBe(ACCOUNT_B.uid);
      // §91 — `entityId` é o `invitationId`, e nada além disso circula.
      expect(all[0].entityId).toBe(first.body.invitationId);
    });

    it('o payload não carrega nome do squad, de quem convidou nem de membro (§91)', async () => {
      await enablePush(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);

      const row = s.inDatabase((db) =>
        db
          .prepare(
            `SELECT * FROM social_notification_events WHERE type = 'GROUP_INVITATION_RECEIVED'`,
          )
          .get(),
      ) as Record<string, unknown>;

      const serialized = JSON.stringify(row);
      for (const forbidden of ['Os Monstros', ACCOUNT_A.name, ACCOUNT_B.name, ACCOUNT_A.email]) {
        expect({ forbidden, present: serialized.includes(forbidden) }).toEqual({
          forbidden,
          present: false,
        });
      }
    });

    it('nenhum outro movimento do squad gera push (§95)', async () => {
      await enablePush(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);

      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const checkInId = await s.publishCheckIn(ACCOUNT_A, sessionSyncId);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/checkins/${checkInId}`)
        .set(auth(ACCOUNT_A))
        .expect(201);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/leave`)
        .set(auth(ACCOUNT_B))
        .expect(204);

      const groupTypes = s.inDatabase((db) =>
        db
          .prepare(`SELECT DISTINCT type FROM social_notification_events WHERE type LIKE 'GROUP%'`)
          .all(),
      ) as Array<{ type: string }>;
      // Só a categoria do convite existe: nada de "entrou", "saiu", "compartilhou" ou "excluiu".
      expect(groupTypes.map((row) => row.type)).toEqual(['GROUP_INVITATION_RECEIVED']);
    });

    it('a categoria é uma preferência sob o interruptor mestre (§94)', async () => {
      const prefs = await request(s.server())
        .get('/v1/social/notifications/preferences')
        .set(auth(ACCOUNT_B))
        .expect(200);
      expect(prefs.body.groupInvitationReceived).toBe(true);
      expect(prefs.body.pushEnabled).toBe(false);

      const updated = await request(s.server())
        .patch('/v1/social/notifications/preferences')
        .set(auth(ACCOUNT_B))
        .send({ groupInvitationReceived: false })
        .expect(200);
      expect(updated.body.groupInvitationReceived).toBe(false);
    });

    it('recusar antes do despacho suprime (§162)', async () => {
      await enablePush(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A);
      const invitationId = await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);

      await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/decline`)
        .set(auth(ACCOUNT_B))
        .expect(204);

      await dispatchOnce();
      expect(events()[0].status).toBe('SUPPRESSED');
    });

    it('bloquear antes do despacho suprime (§106)', async () => {
      await enablePush(ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A);
      await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);

      const socialIdA = await s.socialIdOf(ACCOUNT_A);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_B))
        .send({ blockedSocialId: socialIdA })
        .expect(200);

      await dispatchOnce();
      // §105 cancelou o convite; §106 garante que o evento não vira entrega.
      expect(['SUPPRESSED', 'CANCELLED']).toContain(events()[0].status);
    });

    /** Roda um ciclo do dispatcher, como a T17.5 faz nos testes dela. */
    async function dispatchOnce(): Promise<void> {
      const { NotificationDispatcher } = await import(
        '../src/modules/social/notification.dispatcher'
      );
      const dispatcher = s.app.get(NotificationDispatcher);
      await dispatcher.runDispatchCycle();
    }
  });

  // =============================================================== §165 integridade

  describe('integridade do banco (§118/§119/§165)', () => {
    it('foreign_key_check passa depois de um ciclo completo (§119/§165)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);
      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const checkInId = await s.publishCheckIn(ACCOUNT_A, sessionSyncId);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/checkins/${checkInId}`)
        .set(auth(ACCOUNT_A))
        .expect(201);

      const violations = s.inDatabase((db) => db.pragma('foreign_key_check'));
      expect(violations).toEqual([]);
    });

    it('as quatro UNIQUE do desenho são do banco, e não do serviço (§118)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);
      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const checkInId = await s.publishCheckIn(ACCOUNT_A, sessionSyncId);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/checkins/${checkInId}`)
        .set(auth(ACCOUNT_A))
        .expect(201);

      // 1. uma participação por (squad, pessoa)
      expect(() =>
        s.inDatabase((db) =>
          db
            .prepare(
              `INSERT INTO social_group_memberships (id, group_id, member_uid, role, joined_at)
               VALUES (?, ?, ?, 'MEMBER', 0)`,
            )
            .run(uuid(), groupId, ACCOUNT_B.uid),
        ),
      ).toThrow(/UNIQUE/i);

      // 2. um OWNER por squad
      expect(() =>
        s.inDatabase((db) =>
          db
            .prepare(
              `INSERT INTO social_group_memberships (id, group_id, member_uid, role, joined_at)
               VALUES (?, ?, ?, 'OWNER', 0)`,
            )
            .run(uuid(), groupId, ACCOUNT_C.uid),
        ),
      ).toThrow(/UNIQUE/i);

      // 3. um convite pendente por (squad, destinatário)
      s.inDatabase((db) =>
        db
          .prepare(
            `INSERT INTO social_group_invitations
               (id, group_id, sender_uid, recipient_uid, status, created_at, expires_at)
             VALUES (?, ?, ?, ?, 'PENDING', 0, 9999999999999)`,
          )
          .run(uuid(), groupId, ACCOUNT_A.uid, ACCOUNT_C.uid),
      );
      expect(() =>
        s.inDatabase((db) =>
          db
            .prepare(
              `INSERT INTO social_group_invitations
                 (id, group_id, sender_uid, recipient_uid, status, created_at, expires_at)
               VALUES (?, ?, ?, ?, 'PENDING', 0, 9999999999999)`,
            )
            .run(uuid(), groupId, ACCOUNT_A.uid, ACCOUNT_C.uid),
        ),
      ).toThrow(/UNIQUE/i);

      // 4. um compartilhamento por (squad, check-in)
      expect(() =>
        s.inDatabase((db) =>
          db
            .prepare(
              `INSERT INTO social_group_checkin_shares
                 (id, group_id, checkin_id, author_uid, created_at)
               VALUES (?, ?, ?, ?, 0)`,
            )
            .run(uuid(), groupId, checkInId, ACCOUNT_A.uid),
        ),
      ).toThrow(/UNIQUE/i);

      // 5. auto-convite é impossível no banco, além do serviço (§26)
      expect(() =>
        s.inDatabase((db) =>
          db
            .prepare(
              `INSERT INTO social_group_invitations
                 (id, group_id, sender_uid, recipient_uid, status, created_at, expires_at)
               VALUES (?, ?, ?, ?, 'PENDING', 0, 9999999999999)`,
            )
            .run(uuid(), groupId, ACCOUNT_A.uid, ACCOUNT_A.uid),
        ),
      ).toThrow(/CHECK/i);
    });

    it('nenhuma tabela de Squad é sync_entity nem alcança treino (§114/§115)', () => {
      const columns = s.inDatabase((db) => {
        const out: Record<string, string[]> = {};
        for (const table of [
          'social_groups',
          'social_group_memberships',
          'social_group_invitations',
          'social_group_checkin_shares',
        ]) {
          out[table] = (db.pragma(`table_info(${table})`) as Array<{ name: string }>).map(
            (c) => c.name,
          );
        }
        return out;
      });

      const all = Object.values(columns).flat();
      for (const forbidden of [
        'entity_type',
        'entity_sync_id',
        'revision',
        'payload',
        'session_sync_id',
        'source_session_sync_id',
        'exercise',
        'load',
        'reps',
        'notes',
      ]) {
        expect({ forbidden, present: all.includes(forbidden) }).toEqual({
          forbidden,
          present: false,
        });
      }
    });
  });

  // =============================================================== §166 performance

  it('as consultas críticas continuam bounded com volume (§121/§166)', async () => {
    // Um cenário razoável para o formato do produto: um Squad cheio, com muitos
    // compartilhamentos. O que este teste mede é **forma**, e não milissegundos: o plano das
    // consultas críticas precisa usar índice, e a contagem de linhas devolvidas precisa continuar
    // presa ao teto de página, e não ao volume gravado.
    await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
    const groupId = await s.createGroup(ACCOUNT_A);
    await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);

    const now = Date.now();
    s.inDatabase((db) => {
      const insertShare = db.prepare(
        `INSERT INTO social_group_checkin_shares (id, group_id, checkin_id, author_uid, created_at)
         VALUES (?, ?, ?, ?, ?)`,
      );
      const insertCheckIn = db.prepare(
        `INSERT INTO social_workout_checkins
           (id, author_uid, source_session_sync_id, client_request_id, status, caption,
            created_at, deleted_at)
         VALUES (?, ?, ?, ?, 'PUBLISHED', NULL, ?, NULL)`,
      );
      const tx = db.transaction(() => {
        for (let i = 0; i < 2_000; i += 1) {
          const checkInId = uuid();
          insertCheckIn.run(checkInId, ACCOUNT_A.uid, uuid(), uuid(), now - i * 1000);
          insertShare.run(uuid(), groupId, checkInId, ACCOUNT_A.uid, now - i * 1000);
        }
      });
      tx();
    });

    const started = Date.now();
    const feed = await request(s.server())
      .get(`/v1/social/groups/${groupId}/feed?limit=50`)
      .set(auth(ACCOUNT_B))
      .expect(200);
    const elapsed = Date.now() - started;

    expect(feed.body.items).toHaveLength(50);
    // Um teto folgado: ele não mede a máquina, mede que a consulta não virou varredura completa.
    expect(elapsed).toBeLessThan(2_000);

    // E o plano confirma: a leitura do feed entra pelo índice de `(group_id, created_at)`.
    const plan = s.inDatabase((db) =>
      db
        .prepare(
          `EXPLAIN QUERY PLAN
             SELECT s.id FROM social_group_checkin_shares s
              WHERE s.group_id = ? ORDER BY s.created_at DESC LIMIT 50`,
        )
        .all(groupId),
    ) as Array<{ detail: string }>;
    expect(plan.map((row) => row.detail).join(' ')).toContain('idx_social_group_shares_feed');
  }, 60_000);
});
