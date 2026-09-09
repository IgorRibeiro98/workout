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
import {
  SOCIAL_GROUP_INVITATION_TTL_MS,
  SOCIAL_GROUP_MAX_MEMBERS,
  SOCIAL_GROUP_MAX_OWNED,
} from '../src/modules/social/social-group.limits';

/**
 * T17.11 — o núcleo do Squad: criação, convite, participação e posse (§145–§152).
 *
 * ## Tudo entra pelo caminho real
 *
 * Nenhum `INSERT` de fixture. Os perfis entram por `activate`, a amizade por pedido e aceite, o
 * Squad por `POST /v1/social/groups`, e o convite pelo endpoint. Semear as tabelas à mão provaria
 * as afirmações contra a fixture em vez de contra o protocolo — e é o protocolo que precisa ser
 * auditado.
 */
describe('T17.11 — Squads privados: núcleo', () => {
  let s: SocialScenario;

  beforeEach(async () => {
    s = await createSocialScenario();
    await s.activate(ACCOUNT_A);
    await s.activate(ACCOUNT_B);
    await s.activate(ACCOUNT_C);
  });

  afterEach(async () => {
    await s.close();
  });

  const auth = (a: AuditAccount) => ({ Authorization: `Bearer ${a.token}` });

  // =============================================================== §145 criação

  describe('criação (§16/§17/§18/§145)', () => {
    it('social ativo cria, e o criador nasce OWNER e membro (§15/§17)', async () => {
      const res = await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name: 'Os Monstros', clientRequestId: uuid() })
        .expect(201);

      expect(res.body.role).toBe('OWNER');
      expect(res.body.memberCount).toBe(1);
      expect(res.body.name).toBe('Os Monstros');
      // §7 — o identificador público é um UUID do servidor, e não um sequencial.
      expect(res.body.groupId).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
      );

      // §15 — a posse não existe fora da participação.
      const members = await request(s.server())
        .get(`/v1/social/groups/${res.body.groupId}/members`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      expect(members.body.items).toHaveLength(1);
      expect(members.body.items[0].role).toBe('OWNER');
      expect(members.body.items[0].isCurrentUser).toBe(true);
    });

    it('social desativado não cria (§16)', async () => {
      await request(s.server()).post('/v1/social/me/disable').set(auth(ACCOUNT_A)).expect(200);

      await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name: 'Os Monstros', clientRequestId: uuid() })
        .expect(403)
        .expect((res) => expect(res.body.error.code).toBe('SOCIAL_NOT_ENABLED'));
    });

    it('o teto de squads criados é aplicado no servidor (§18)', async () => {
      for (let i = 0; i < SOCIAL_GROUP_MAX_OWNED; i += 1) {
        await s.createGroup(ACCOUNT_A, `Squad ${i}`);
      }

      await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name: 'Mais um', clientRequestId: uuid() })
        .expect(422)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_OWNED_LIMIT_REACHED'));

      // §46 — excluir libera a vaga.
      const list = await request(s.server())
        .get('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .expect(200);
      await request(s.server())
        .delete(`/v1/social/groups/${list.body.items[0].groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name: 'Agora cabe', clientRequestId: uuid() })
        .expect(201);
    });

    it('o mesmo clientRequestId produz um squad só (§146)', async () => {
      const clientRequestId = uuid();
      const first = await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name: 'Os Monstros', clientRequestId })
        .expect(201);
      const second = await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name: 'Os Monstros', clientRequestId })
        .expect(201);

      expect(second.body.groupId).toBe(first.body.groupId);
      const list = await request(s.server())
        .get('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .expect(200);
      expect(list.body.items).toHaveLength(1);
    });

    it.each([
      ['vazio', '   '],
      ['curto demais', 'ab'],
      ['longo demais', 'x'.repeat(41)],
      ['com quebra de linha', 'Os\nMonstros'],
      ['com caractere de controle', 'Os\u0007Monstros'],
      ['com marca bidirecional', 'Os\u202EMonstros'],
      ['com invisível', 'Os\u200BMonstros'],
    ])('recusa nome %s (§8)', async (_label, name) => {
      await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name, clientRequestId: uuid() })
        .expect(400)
        .expect((res) => expect(res.body.error.code).toBe('INVALID_GROUP_NAME'));
    });

    it('aceita emoji e conta em code points (§8)', async () => {
      // 20 code points, 40 unidades UTF-16: um limite contado em `String.length` recusaria.
      const name = '💪'.repeat(20);
      const res = await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name, clientRequestId: uuid() })
        .expect(201);
      expect(res.body.name).toBe(name);
    });

    it('recusa campo decidido pelo servidor, por nome (§83)', async () => {
      for (const field of ['ownerUid', 'memberCount', 'role', 'status', 'groupId']) {
        await request(s.server())
          .post('/v1/social/groups')
          .set(auth(ACCOUNT_A))
          .send({ name: 'Os Monstros', clientRequestId: uuid(), [field]: 'x' })
          .expect(400)
          .expect((res) => expect(res.body.error.code).toBe('INVALID_GROUP_REQUEST'));
      }
    });
  });

  // =============================================================== §5 privacidade

  describe('um Squad é privado (§4/§5/§59/§60)', () => {
    it('não existe busca nem listagem pública de squads', async () => {
      for (const path of [
        '/v1/social/groups/search?q=Monstros',
        '/v1/social/public/groups',
        '/v1/social/groups/discover',
      ]) {
        const res = await request(s.server()).get(path).set(auth(ACCOUNT_A));
        // Ou a rota não existe (404 do Nest), ou ela casa com `groups/:groupId` e responde o
        // `GROUP_NOT_FOUND` de quem não é membro. Nenhuma das duas devolve um squad.
        expect(res.status).toBe(404);
        expect(res.body?.error?.code ?? 'GROUP_NOT_FOUND').not.toBe('OK');
      }
    });

    it('ter o groupId não concede nada: não-membro recebe 404 em toda superfície (§59/§60)', async () => {
      const groupId = await s.createGroup(ACCOUNT_A);

      const surfaces: Array<[string, () => request.Test]> = [
        ['detail', () => request(s.server()).get(`/v1/social/groups/${groupId}`)],
        ['members', () => request(s.server()).get(`/v1/social/groups/${groupId}/members`)],
        ['feed', () => request(s.server()).get(`/v1/social/groups/${groupId}/feed`)],
      ];

      for (const [label, build] of surfaces) {
        const res = await build().set(auth(ACCOUNT_C));
        expect({ label, status: res.status, code: res.body.error?.code }).toEqual({
          label,
          status: 404,
          code: 'GROUP_NOT_FOUND',
        });
      }
    });

    it('um squad excluído responde igual a um que nunca existiu (§60)', async () => {
      const groupId = await s.createGroup(ACCOUNT_A);
      await request(s.server())
        .delete(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_NOT_FOUND'));
    });
  });

  // =============================================================== §147 convite

  describe('convite (§22–§28/§147)', () => {
    let groupId: string;

    beforeEach(async () => {
      groupId = await s.createGroup(ACCOUNT_A);
    });

    it('amigo direto ativo é convidável (§23)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const socialId = await s.socialIdOf(ACCOUNT_B);

      const res = await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId: uuid() })
        .expect(201);

      expect(res.body.status).toBe('PENDING');
      expect(res.body.groupName).toBe('Os Monstros');
      expect(res.body.memberCount).toBe(1);
    });

    it('não-amigo é recusado, mesmo com o socialId em mãos (§23/§24/§25)', async () => {
      const socialId = await s.socialIdOf(ACCOUNT_B);

      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId: uuid() })
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_INVITE_NOT_ALLOWED'));
    });

    it.each([
      ['A bloqueou B', ACCOUNT_A, ACCOUNT_B],
      ['B bloqueou A', ACCOUNT_B, ACCOUNT_A],
    ])('bloqueio impede o convite — %s (§23/§105)', async (_label, blocker, blocked) => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const blockedSocialId = await s.socialIdOf(blocked);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(blocker))
        .send({ blockedSocialId })
        .expect((res) => expect([200, 201, 204]).toContain(res.status));

      const socialId = await s.socialIdOf(ACCOUNT_B);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId: uuid() })
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_INVITE_NOT_ALLOWED'));
    });

    it('convidar a si mesmo é impossível (§26)', async () => {
      const socialId = await s.socialIdOf(ACCOUNT_A);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId: uuid() })
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_INVITE_NOT_ALLOWED'));
    });

    it('convidar quem já é membro não cria participação duplicada (§27)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);

      const socialId = await s.socialIdOf(ACCOUNT_B);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId: uuid() })
        .expect(409)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_ALREADY_MEMBER'));

      const count = s.inDatabase((db) =>
        db
          .prepare(
            `SELECT COUNT(*) AS n FROM social_group_memberships
              WHERE group_id = ? AND member_uid = ?`,
          )
          .get(groupId, ACCOUNT_B.uid),
      ) as { n: number };
      expect(count.n).toBe(1);
    });

    it('convite duplicado converge para o mesmo convite (§28)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const socialId = await s.socialIdOf(ACCOUNT_B);

      const first = await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId: uuid() })
        .expect(201);
      const second = await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId: uuid() })
        .expect(201);

      expect(second.body.invitationId).toBe(first.body.invitationId);

      const pending = s.inDatabase((db) =>
        db
          .prepare(
            `SELECT COUNT(*) AS n FROM social_group_invitations
              WHERE group_id = ? AND recipient_uid = ? AND status = 'PENDING'`,
          )
          .get(groupId, ACCOUNT_B.uid),
      ) as { n: number };
      expect(pending.n).toBe(1);
    });

    it('somente o OWNER convida (§22)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.makeFriends(ACCOUNT_B, ACCOUNT_C);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);

      const socialId = await s.socialIdOf(ACCOUNT_C);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_B))
        .send({ socialId, clientRequestId: uuid() })
        .expect(403)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_FORBIDDEN'));
    });

    it('squad cheio recusa o convite (§11/§29)', async () => {
      // Enche o Squad até o teto e depois tenta o vigésimo primeiro.
      const extras: AuditAccount[] = [];
      for (let i = 0; i < SOCIAL_GROUP_MAX_MEMBERS - 1; i += 1) {
        const extra = s.extraAccount(i);
        await s.activate(extra);
        // O pedido parte da conta extra, e não de A: o teto de `sendRequest` da T17.1 é por
        // remetente (15/min), e dezenove pedidos saindo de A estourariam um limite que não tem
        // nada a ver com o que este teste afirma.
        await s.makeFriends(extra, ACCOUNT_A);
        await s.addMember(ACCOUNT_A, groupId, extra);
        extras.push(extra);
      }

      const detail = await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      expect(detail.body.memberCount).toBe(SOCIAL_GROUP_MAX_MEMBERS);

      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const socialId = await s.socialIdOf(ACCOUNT_B);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId: uuid() })
        .expect(422)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_FULL'));
    }, 30_000);

    it('não existe convite por friendCode, displayName ou e-mail (§24)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      for (const field of ['friendCode', 'displayName', 'email', 'uid']) {
        await request(s.server())
          .post(`/v1/social/groups/${groupId}/invitations`)
          .set(auth(ACCOUNT_A))
          .send({ [field]: 'qualquer', clientRequestId: uuid() })
          .expect(400)
          .expect((res) => expect(res.body.error.code).toBe('INVALID_GROUP_REQUEST'));
      }
    });
  });

  // =============================================================== §148 aceite

  describe('aceite (§29/§30/§31/§148)', () => {
    let groupId: string;
    let invitationId: string;

    beforeEach(async () => {
      groupId = await s.createGroup(ACCOUNT_A);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      invitationId = await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);
    });

    it('o convite válido vira participação (§29)', async () => {
      const res = await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set(auth(ACCOUNT_B))
        .expect(200);

      expect(res.body.groupId).toBe(groupId);
      expect(res.body.role).toBe('MEMBER');
      expect(res.body.memberCount).toBe(2);
    });

    it('convite expirado é indisponível (§21/§29)', async () => {
      s.clock.advance(SOCIAL_GROUP_INVITATION_TTL_MS + 1);

      await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set(auth(ACCOUNT_B))
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('INVITATION_NOT_AVAILABLE'));

      // §21 — a expiração é derivada: ele some da lista sem que nada tenha passado por ali.
      const list = await request(s.server())
        .get('/v1/social/groups/invitations')
        .set(auth(ACCOUNT_B))
        .expect(200);
      expect(list.body.items).toHaveLength(0);
    });

    it('desfazer a amizade antes do aceite invalida o convite (§30)', async () => {
      const socialIdB = await s.socialIdOf(ACCOUNT_B);
      await request(s.server())
        .post('/v1/social/friends/remove')
        .set(auth(ACCOUNT_A))
        .send({ socialId: socialIdB })
        .expect(200);

      await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set(auth(ACCOUNT_B))
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('INVITATION_NOT_AVAILABLE'));
    });

    it('bloqueio antes do aceite invalida o convite (§29/§105)', async () => {
      const socialIdA = await s.socialIdOf(ACCOUNT_A);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_B))
        .send({ blockedSocialId: socialIdA })
        .expect((res) => expect([200, 201, 204]).toContain(res.status));

      await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set(auth(ACCOUNT_B))
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('INVITATION_NOT_AVAILABLE'));
    });

    it('social desativado não aceita (§29/§96)', async () => {
      await request(s.server()).post('/v1/social/me/disable').set(auth(ACCOUNT_B)).expect(200);

      await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set(auth(ACCOUNT_B))
        .expect(403)
        .expect((res) => expect(res.body.error.code).toBe('SOCIAL_NOT_ENABLED'));
    });

    it('um terceiro não aceita o convite de outra pessoa (§29)', async () => {
      await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set(auth(ACCOUNT_C))
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('INVITATION_NOT_AVAILABLE'));
    });

    it('um invitationId de terceiro não vaza existência (§29/§60)', async () => {
      const unknown = uuid();
      const known = await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set(auth(ACCOUNT_C));
      const absent = await request(s.server())
        .post(`/v1/social/group-invitations/${unknown}/accept`)
        .set(auth(ACCOUNT_C));

      expect(known.status).toBe(absent.status);
      expect(known.body.error.code).toBe(absent.body.error.code);
    });

    it('aceitar duas vezes converge, e não cria duas participações (§29)', async () => {
      await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set(auth(ACCOUNT_B))
        .expect(200);
      await request(s.server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set(auth(ACCOUNT_B))
        .expect((res) => expect([200, 404]).toContain(res.status));

      const count = s.inDatabase((db) =>
        db
          .prepare(`SELECT COUNT(*) AS n FROM social_group_memberships WHERE group_id = ?`)
          .get(groupId),
      ) as { n: number };
      expect(count.n).toBe(2);
    });

    it('a prévia do convite não entrega a lista de membros (§139)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_C);

      const list = await request(s.server())
        .get('/v1/social/groups/invitations')
        .set(auth(ACCOUNT_B))
        .expect(200);

      const [item] = list.body.items;
      expect(Object.keys(item).sort()).toEqual(
        [
          'createdAt',
          'expiresAt',
          'groupId',
          'groupName',
          'invitationId',
          'inviterDisplayName',
          'inviterSocialId',
          'memberCount',
          'status',
        ].sort(),
      );
      // A contagem sim; os nomes de quem está lá, não.
      expect(item.memberCount).toBe(2);
      expect(JSON.stringify(list.body)).not.toContain(ACCOUNT_C.name);
    });
  });

  // =============================================================== §149/§150 composição

  describe('participação e posse (§38–§45/§149/§150)', () => {
    let groupId: string;

    beforeEach(async () => {
      groupId = await s.createGroup(ACCOUNT_A);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);
    });

    it('o MEMBER sai, e sair de novo converge (§38)', async () => {
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/leave`)
        .set(auth(ACCOUNT_B))
        .expect(204);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/leave`)
        .set(auth(ACCOUNT_B))
        .expect(204);

      await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_B))
        .expect(404);
    });

    it('o OWNER não sai sem resolver a posse (§39)', async () => {
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/leave`)
        .set(auth(ACCOUNT_A))
        .expect(409)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_OWNER_ACTION_REQUIRED'));
    });

    it('o OWNER transfere a posse, e a troca é atômica (§40/§150)', async () => {
      const members = await request(s.server())
        .get(`/v1/social/groups/${groupId}/members`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      const targetMembership = members.body.items.find(
        (m: { role: string }) => m.role === 'MEMBER',
      ).membershipId;

      await request(s.server())
        .post(`/v1/social/groups/${groupId}/transfer-ownership`)
        .set(auth(ACCOUNT_A))
        .send({ membershipId: targetMembership })
        .expect(204);

      // §150 — nunca 0 OWNER, nunca 2. E é o banco que garante, não a ordem das escritas.
      const roles = s.inDatabase((db) =>
        db
          .prepare(
            `SELECT member_uid AS uid, role FROM social_group_memberships
              WHERE group_id = ? ORDER BY role`,
          )
          .all(groupId),
      ) as Array<{ uid: string; role: string }>;
      expect(roles.filter((r) => r.role === 'OWNER')).toHaveLength(1);
      expect(roles.find((r) => r.role === 'OWNER')!.uid).toBe(ACCOUNT_B.uid);

      const owner = s.inDatabase((db) =>
        db.prepare(`SELECT owner_uid AS uid FROM social_groups WHERE id = ?`).get(groupId),
      ) as { uid: string };
      expect(owner.uid).toBe(ACCOUNT_B.uid);

      // O antigo dono virou MEMBER, e agora pode sair.
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/leave`)
        .set(auth(ACCOUNT_A))
        .expect(204);
    });

    it('o índice do banco torna dois OWNER irrepresentáveis (§14/§150)', () => {
      expect(() =>
        s.inDatabase((db) =>
          db
            .prepare(
              `INSERT INTO social_group_memberships (id, group_id, member_uid, role, joined_at)
               VALUES (?, ?, ?, 'OWNER', ?)`,
            )
            .run(uuid(), groupId, ACCOUNT_C.uid, Date.now()),
        ),
      ).toThrow(/UNIQUE/i);
    });

    it('o OWNER remove um MEMBER, e remover de novo converge (§42)', async () => {
      const members = await request(s.server())
        .get(`/v1/social/groups/${groupId}/members`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      const membershipId = members.body.items.find(
        (m: { role: string }) => m.role === 'MEMBER',
      ).membershipId;

      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/members/${membershipId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);
      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/members/${membershipId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_B))
        .expect(404);
    });

    it('o OWNER não remove a si mesmo (§43)', async () => {
      const members = await request(s.server())
        .get(`/v1/social/groups/${groupId}/members`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      const ownMembership = members.body.items.find(
        (m: { isCurrentUser: boolean }) => m.isCurrentUser,
      ).membershipId;

      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/members/${ownMembership}`)
        .set(auth(ACCOUNT_A))
        .expect(409)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_OWNER_ACTION_REQUIRED'));
    });

    it('um MEMBER não administra o squad (§149)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_C);

      const members = await request(s.server())
        .get(`/v1/social/groups/${groupId}/members`)
        .set(auth(ACCOUNT_B))
        .expect(200);
      const otherMembership = members.body.items.find(
        (m: { role: string; isCurrentUser: boolean }) => m.role === 'MEMBER' && !m.isCurrentUser,
      ).membershipId;

      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/members/${otherMembership}`)
        .set(auth(ACCOUNT_B))
        .expect(403);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/transfer-ownership`)
        .set(auth(ACCOUNT_B))
        .send({ membershipId: otherMembership })
        .expect(403);
      await request(s.server())
        .delete(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_B))
        .expect(403);
    });

    it('um terceiro não administra nada, e nem descobre que o squad existe (§149)', async () => {
      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/members/${uuid()}`)
        .set(auth(ACCOUNT_C))
        .expect(404);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/transfer-ownership`)
        .set(auth(ACCOUNT_C))
        .send({ membershipId: uuid() })
        .expect(404);
      await request(s.server())
        .delete(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_C))
        .expect(404);
    });

    it('remover do squad não desfaz a amizade (§44)', async () => {
      const members = await request(s.server())
        .get(`/v1/social/groups/${groupId}/members`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      const membershipId = members.body.items.find(
        (m: { role: string }) => m.role === 'MEMBER',
      ).membershipId;

      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/members/${membershipId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      const friends = await request(s.server())
        .get('/v1/social/friends')
        .set(auth(ACCOUNT_A))
        .expect(200);
      expect(JSON.stringify(friends.body)).toContain(await s.socialIdOf(ACCOUNT_B));
    });

    it('quem saiu precisa de novo convite para voltar (§45)', async () => {
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/leave`)
        .set(auth(ACCOUNT_B))
        .expect(204);

      // Não existe rota de entrada por conta própria: nem com o groupId em mãos.
      await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_B))
        .expect(404);

      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);
      await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_B))
        .expect(200);
    });
  });

  // =============================================================== §151/§152

  describe('amizade e bloqueio depois da participação (§31/§32/§33/§151/§152)', () => {
    let groupId: string;

    beforeEach(async () => {
      groupId = await s.createGroup(ACCOUNT_A);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);
    });

    it('desfazer a amizade NÃO remove ninguém do squad (§31/§151)', async () => {
      const socialIdB = await s.socialIdOf(ACCOUNT_B);
      await request(s.server())
        .post('/v1/social/friends/remove')
        .set(auth(ACCOUNT_A))
        .send({ socialId: socialIdB })
        .expect(200);

      const detail = await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_B))
        .expect(200);
      expect(detail.body.memberCount).toBe(2);
      expect(detail.body.role).toBe('MEMBER');
    });

    it('participar do mesmo squad NÃO cria amizade (§109)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_C);

      // B e C estão no mesmo Squad e não são amigos: nenhuma superfície de amizade os liga.
      const friendsB = await request(s.server())
        .get('/v1/social/friends')
        .set(auth(ACCOUNT_B))
        .expect(200);
      expect(JSON.stringify(friendsB.body)).not.toContain(await s.socialIdOf(ACCOUNT_C));

      // §110 — e o perfil completo continua fechado.
      await request(s.server())
        .get(`/v1/social/friends/${await s.socialIdOf(ACCOUNT_C)}/profile`)
        .set(auth(ACCOUNT_B))
        .expect((res) => expect([403, 404]).toContain(res.status));
    });

    it('bloqueio preserva as participações e oculta a identidade no par (§33/§34/§152)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_C);

      const socialIdC = await s.socialIdOf(ACCOUNT_C);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_B))
        .send({ blockedSocialId: socialIdC })
        .expect((res) => expect([200, 201, 204]).toContain(res.status));

      // §152 — as participações continuam de pé, e a contagem continua a mesma (§35).
      const detail = await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_B))
        .expect(200);
      expect(detail.body.memberCount).toBe(3);

      // §34 — mas a identidade do par bloqueado some da lista, nos dois sentidos.
      for (const [viewer, hiddenName, hiddenSocialId] of [
        [ACCOUNT_B, ACCOUNT_C.name, socialIdC],
        [ACCOUNT_C, ACCOUNT_B.name, await s.socialIdOf(ACCOUNT_B)],
      ] as Array<[AuditAccount, string, string]>) {
        const members = await request(s.server())
          .get(`/v1/social/groups/${groupId}/members`)
          .set(auth(viewer))
          .expect(200);

        expect(members.body.items).toHaveLength(3);
        const opaque = members.body.items.filter((m: { available: boolean }) => !m.available);
        expect(opaque).toHaveLength(1);
        expect(opaque[0].socialId).toBeNull();
        expect(opaque[0].displayName).toBeNull();
        // §36/§37 — o `membershipId` continua, e não é um uid.
        expect(typeof opaque[0].membershipId).toBe('string');
        expect(opaque[0].membershipId).not.toBe(ACCOUNT_C.uid);
        expect(opaque[0].membershipId).not.toBe(ACCOUNT_B.uid);

        const serialized = JSON.stringify(members.body);
        expect(serialized).not.toContain(hiddenName);
        expect(serialized).not.toContain(hiddenSocialId);
      }
    });

    it('o dono remove um participante que o bloqueou, sem receber a identidade dele (§36)', async () => {
      const socialIdA = await s.socialIdOf(ACCOUNT_A);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_B))
        .send({ blockedSocialId: socialIdA })
        .expect((res) => expect([200, 201, 204]).toContain(res.status));

      const members = await request(s.server())
        .get(`/v1/social/groups/${groupId}/members`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      const opaque = members.body.items.find((m: { available: boolean }) => !m.available);
      expect(opaque.socialId).toBeNull();
      expect(JSON.stringify(members.body)).not.toContain(ACCOUNT_B.name);

      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/members/${opaque.membershipId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      const after = await request(s.server())
        .get(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      expect(after.body.memberCount).toBe(1);
    });

    it('o bloqueio cancela convites de squad pendentes entre o par (§105)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      const invitationId = await s.invite(ACCOUNT_A, groupId, ACCOUNT_C);

      const socialIdA = await s.socialIdOf(ACCOUNT_A);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_C))
        .send({ blockedSocialId: socialIdA })
        .expect((res) => expect([200, 201, 204]).toContain(res.status));

      const status = s.inDatabase((db) =>
        db.prepare(`SELECT status FROM social_group_invitations WHERE id = ?`).get(invitationId),
      ) as { status: string };
      expect(status.status).toBe('CANCELLED');

      const list = await request(s.server())
        .get('/v1/social/groups/invitations')
        .set(auth(ACCOUNT_C))
        .expect(200);
      expect(list.body.items).toHaveLength(0);
    });
  });

  // =============================================================== §46–§49 exclusão

  describe('exclusão do squad (§46/§47/§48/§49)', () => {
    it('apaga o contexto de grupo e nada mais (§48/§49)', async () => {
      const groupId = await s.createGroup(ACCOUNT_A);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      const pendingInvite = await s.invite(ACCOUNT_A, groupId, ACCOUNT_C);

      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const checkInId = await s.publishCheckIn(ACCOUNT_A, sessionSyncId);
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/checkins/${checkInId}`)
        .set(auth(ACCOUNT_A))
        .expect(201);

      await request(s.server())
        .delete(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      const state = s.inDatabase((db) => ({
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
        invitation: (
          db
            .prepare(`SELECT status FROM social_group_invitations WHERE id = ?`)
            .get(pendingInvite) as { status: string }
        ).status,
        // §47/§49 — a publicação continua inteira.
        checkIn: (
          db.prepare(`SELECT status FROM social_workout_checkins WHERE id = ?`).get(checkInId) as {
            status: string;
          }
        ).status,
        // §47 — e a sessão de treino nunca foi tocada.
        sessions: (
          db
            .prepare(
              `SELECT COUNT(*) AS n FROM sync_entities
                WHERE owner_uid = ? AND entity_type = 'WORKOUT_SESSION' AND deleted = 0`,
            )
            .get(ACCOUNT_A.uid) as { n: number }
        ).n,
      }));

      expect(state).toEqual({
        memberships: 0,
        shares: 0,
        invitation: 'CANCELLED',
        checkIn: 'PUBLISHED',
        sessions: 1,
      });

      // §49 — e o check-in continua no Feed de amigos, conforme a política original dele.
      const feed = await request(s.server())
        .get('/v1/social/feed')
        .set(auth(ACCOUNT_B))
        .expect(200);
      expect(feed.body.items.map((i: { checkInId: string }) => i.checkInId)).toContain(checkInId);
    });

    it('excluir duas vezes converge (§46)', async () => {
      const groupId = await s.createGroup(ACCOUNT_A);
      await request(s.server())
        .delete(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);
      await request(s.server())
        .delete(`/v1/social/groups/${groupId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);
    });
  });
});
