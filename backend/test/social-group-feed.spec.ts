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
import { jpeg } from './support/image-fixtures';
import { SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN } from '../src/modules/social/social-group.limits';

/**
 * T17.11 — o feed do Squad (§50–§75, §153–§159).
 *
 * ## O que este arquivo existe para provar
 *
 * Que o feed de um Squad é o **mesmo** `WorkoutCheckIn` da T17.8/T17.9 lido por outra audiência —
 * e não um segundo modelo de publicação. Todas as afirmações abaixo são sobre isso: nada entra
 * automaticamente, só o autor traz o próprio check-in, o vínculo some sem tocar na publicação, e a
 * autorização de **interagir** continua sendo a da amizade.
 */
describe('T17.11 — feed do Squad', () => {
  let s: SocialScenario;
  let groupId: string;

  const auth = (a: AuditAccount) => ({ Authorization: `Bearer ${a.token}` });

  beforeEach(async () => {
    s = await createSocialScenario();
    await s.activate(ACCOUNT_A);
    await s.activate(ACCOUNT_B);
    await s.activate(ACCOUNT_C);
    // A é dona; B entra por convite. A e B são amigos (a amizade é o que autoriza o convite);
    // C entra depois nos testes que precisam de um terceiro.
    await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
    groupId = await s.createGroup(ACCOUNT_A);
    await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);
  });

  afterEach(async () => {
    await s.close();
  });

  /** Publica um check-in de `account` e devolve o `checkInId`. */
  async function publish(
    account: AuditAccount,
    extra: Record<string, unknown> = {},
  ): Promise<string> {
    const sessionSyncId = await s.pushSession(account);
    return s.publishCheckIn(account, sessionSyncId, extra);
  }

  function share(account: AuditAccount, checkInId: string, group = groupId) {
    return request(s.server())
      .post(`/v1/social/groups/${group}/checkins/${checkInId}`)
      .set(auth(account));
  }

  function readFeed(account: AuditAccount, group = groupId) {
    return request(s.server()).get(`/v1/social/groups/${group}/feed`).set(auth(account));
  }

  // =============================================================== §153/§154

  describe('nada entra sozinho (§51/§52/§154)', () => {
    it('publicar um check-in NÃO o coloca no feed do squad (§52/§154)', async () => {
      await publish(ACCOUNT_B, { caption: 'Hoje foi' });

      const feed = await readFeed(ACCOUNT_A).expect(200);
      expect(feed.body.items).toHaveLength(0);

      // E o Feed de amigos tem o post: o check-in existe, ele só não foi trazido para o grupo.
      const friendFeed = await request(s.server())
        .get('/v1/social/feed')
        .set(auth(ACCOUNT_A))
        .expect(200);
      expect(friendFeed.body.items).toHaveLength(1);
    });

    it('entrar no squad não traz os check-ins antigos de ninguém (§52)', async () => {
      const checkInId = await publish(ACCOUNT_A);
      await share(ACCOUNT_A, checkInId).expect(201);

      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_C);

      // C vê o que **foi compartilhado**, e é isso: não existe backfill de nada.
      const feed = await readFeed(ACCOUNT_C).expect(200);
      expect(feed.body.items).toHaveLength(1);
      expect(feed.body.items[0].checkIn.checkInId).toBe(checkInId);
    });

    it('o compartilhamento é explícito e devolve o card já projetado (§53)', async () => {
      const checkInId = await publish(ACCOUNT_A, { caption: 'Finalmente saiu esse treino' });
      const res = await share(ACCOUNT_A, checkInId).expect(201);

      expect(res.body.sharedGroupCount).toBe(1);
      expect(res.body.item.checkIn.checkInId).toBe(checkInId);
      expect(res.body.item.checkIn.caption).toBe('Finalmente saiu esse treino');
      // §85 — o envelope acrescenta **um** campo, e ele é a ação social de compartilhar.
      expect(Object.keys(res.body.item).sort()).toEqual(['checkIn', 'sharedToGroupAt']);
      expect(typeof res.body.item.sharedToGroupAt).toBe('number');
    });
  });

  // =============================================================== §55/§56/§68

  describe('só o autor, e uma vez por squad (§55/§56/§68)', () => {
    it('B não reposta o check-in de A (§56)', async () => {
      const checkInId = await publish(ACCOUNT_A);

      await share(ACCOUNT_B, checkInId)
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('CHECKIN_NOT_FOUND'));

      expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(0);
    });

    it('compartilhar duas vezes converge, e mantém a data original (§55)', async () => {
      const checkInId = await publish(ACCOUNT_A);
      const first = await share(ACCOUNT_A, checkInId).expect(201);

      s.clock.advance(60_000);
      const second = await share(ACCOUNT_A, checkInId).expect(201);

      expect(second.body.item.sharedToGroupAt).toBe(first.body.item.sharedToGroupAt);
      expect(second.body.sharedGroupCount).toBe(1);
      expect((await readFeed(ACCOUNT_A).expect(200)).body.items).toHaveLength(1);
    });

    it('o mesmo check-in entra em vários squads, até o teto (§67/§68)', async () => {
      const checkInId = await publish(ACCOUNT_A);

      const groups = [groupId];
      for (let i = 1; i < SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN; i += 1) {
        groups.push(await s.createGroup(ACCOUNT_A, `Squad ${i}`));
      }
      for (const group of groups) {
        await share(ACCOUNT_A, checkInId, group).expect(201);
      }

      // O sexto Squad é de **B**, e não de A: o teto de posse (5) já foi consumido pelos cinco
      // acima, e criar mais um como A esbarraria em `GROUP_OWNED_LIMIT_REACHED` — um limite que
      // não é o que este teste afirma.
      const extra = await s.createGroup(ACCOUNT_B, 'O sexto');
      await s.addMember(ACCOUNT_B, extra, ACCOUNT_A);
      await share(ACCOUNT_A, checkInId, extra)
        .expect(422)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_SHARE_LIMIT_REACHED'));

      // §67 — e o Feed de amigos continua tendo o mesmo post: são audiências distintas sobre o
      // mesmo objeto, e não cópias.
      const friendFeed = await request(s.server())
        .get('/v1/social/feed')
        .set(auth(ACCOUNT_B))
        .expect(200);
      expect(friendFeed.body.items.map((i: { checkInId: string }) => i.checkInId)).toContain(
        checkInId,
      );
    });

    it('não é membro do squad, não compartilha nem o próprio check-in (§57)', async () => {
      const checkInId = await publish(ACCOUNT_C);
      await share(ACCOUNT_C, checkInId)
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_NOT_FOUND'));
    });
  });

  // =============================================================== §155 independência

  it('compartilhar no squad não altera o Feed de amigos, a sessão nem o check-in (§155)', async () => {
    const sessionSyncId = await s.pushSession(ACCOUNT_A);
    const checkInId = await s.publishCheckIn(ACCOUNT_A, sessionSyncId, { caption: 'Hoje foi' });

    const before = s.inDatabase((db) => ({
      checkIn: db.prepare(`SELECT * FROM social_workout_checkins WHERE id = ?`).get(checkInId),
      session: db
        .prepare(`SELECT * FROM sync_entities WHERE owner_uid = ? AND entity_sync_id = ?`)
        .get(ACCOUNT_A.uid, sessionSyncId),
    }));
    const friendFeedBefore = await request(s.server())
      .get('/v1/social/feed')
      .set(auth(ACCOUNT_B))
      .expect(200);

    await share(ACCOUNT_A, checkInId).expect(201);

    const after = s.inDatabase((db) => ({
      checkIn: db.prepare(`SELECT * FROM social_workout_checkins WHERE id = ?`).get(checkInId),
      session: db
        .prepare(`SELECT * FROM sync_entities WHERE owner_uid = ? AND entity_sync_id = ?`)
        .get(ACCOUNT_A.uid, sessionSyncId),
    }));
    const friendFeedAfter = await request(s.server())
      .get('/v1/social/feed')
      .set(auth(ACCOUNT_B))
      .expect(200);

    expect(after).toEqual(before);
    expect(friendFeedAfter.body).toEqual(friendFeedBefore.body);
  });

  // =============================================================== §153 autorização

  describe('quem lê o feed (§59/§60/§153)', () => {
    it('membro vê; não-membro recebe 404 mesmo com groupId e checkInId em mãos (§59/§60)', async () => {
      const checkInId = await publish(ACCOUNT_A, { caption: 'Hoje foi' });
      await share(ACCOUNT_A, checkInId).expect(201);

      expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(1);

      // C conhece os dois identificadores e não é membro. Nem o feed, nem o detalhe.
      await readFeed(ACCOUNT_C)
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('GROUP_NOT_FOUND'));
      await request(s.server())
        .get(`/v1/social/workout-checkins/${checkInId}`)
        .set(auth(ACCOUNT_C))
        .expect(404);
    });

    it('social desativado do autor tira o post do feed do squad (§61)', async () => {
      const checkInId = await publish(ACCOUNT_A);
      await share(ACCOUNT_A, checkInId).expect(201);
      expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(1);

      // A é dona do Squad e está sozinha nele com B: desativar exige resolver a posse antes.
      // Transferir para B é o caminho, e é ele que este teste usa para poder desativar A.
      const members = await request(s.server())
        .get(`/v1/social/groups/${groupId}/members`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      const bMembership = members.body.items.find(
        (m: { isCurrentUser: boolean }) => !m.isCurrentUser,
      ).membershipId;
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/transfer-ownership`)
        .set(auth(ACCOUNT_A))
        .send({ membershipId: bMembership })
        .expect(204);
      await request(s.server()).post('/v1/social/me/disable').set(auth(ACCOUNT_A)).expect(200);

      // §97 — desativar tira A do Squad e apaga os compartilhamentos dela.
      expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(0);

      // §47 — e o check-in continua existindo.
      const stored = s.inDatabase((db) =>
        db.prepare(`SELECT status FROM social_workout_checkins WHERE id = ?`).get(checkInId),
      ) as { status: string };
      expect(stored.status).toBe('PUBLISHED');
    });

    it('social desativado do viewer fecha o feed do squad para ele (§96)', async () => {
      const checkInId = await publish(ACCOUNT_A);
      await share(ACCOUNT_A, checkInId).expect(201);

      await request(s.server()).post('/v1/social/me/disable').set(auth(ACCOUNT_B)).expect(200);
      await readFeed(ACCOUNT_B)
        .expect(403)
        .expect((res) => expect(res.body.error.code).toBe('SOCIAL_NOT_ENABLED'));
    });
  });

  // =============================================================== §156 saída/remoção

  describe('quem sai deixa de aparecer, e não volta sozinho (§62/§63/§64/§156)', () => {
    it('o autor sai: os compartilhamentos dele somem, e o check-in fica intacto (§62/§156)', async () => {
      const checkInId = await publish(ACCOUNT_B, { caption: 'Hoje foi' });
      await share(ACCOUNT_B, checkInId).expect(201);
      expect((await readFeed(ACCOUNT_A).expect(200)).body.items).toHaveLength(1);

      await request(s.server())
        .post(`/v1/social/groups/${groupId}/leave`)
        .set(auth(ACCOUNT_B))
        .expect(204);

      expect((await readFeed(ACCOUNT_A).expect(200)).body.items).toHaveLength(0);

      const stored = s.inDatabase((db) =>
        db
          .prepare(`SELECT status, caption FROM social_workout_checkins WHERE id = ?`)
          .get(checkInId),
      ) as { status: string; caption: string };
      expect(stored).toEqual({ status: 'PUBLISHED', caption: 'Hoje foi' });

      // §49 — e ele continua no Feed de amigos de A, que continua amiga de B.
      const friendFeed = await request(s.server())
        .get('/v1/social/feed')
        .set(auth(ACCOUNT_A))
        .expect(200);
      expect(friendFeed.body.items.map((i: { checkInId: string }) => i.checkInId)).toContain(
        checkInId,
      );
    });

    it('o autor removido tem o mesmo destino (§63)', async () => {
      const checkInId = await publish(ACCOUNT_B);
      await share(ACCOUNT_B, checkInId).expect(201);

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

      expect((await readFeed(ACCOUNT_A).expect(200)).body.items).toHaveLength(0);
    });

    it('voltar ao squad NÃO ressuscita compartilhamentos antigos (§64)', async () => {
      const checkInId = await publish(ACCOUNT_B);
      await share(ACCOUNT_B, checkInId).expect(201);

      await request(s.server())
        .post(`/v1/social/groups/${groupId}/leave`)
        .set(auth(ACCOUNT_B))
        .expect(204);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);

      expect((await readFeed(ACCOUNT_A).expect(200)).body.items).toHaveLength(0);
      expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(0);

      const shares = s.inDatabase((db) =>
        db
          .prepare(`SELECT COUNT(*) AS n FROM social_group_checkin_shares WHERE group_id = ?`)
          .get(groupId),
      ) as { n: number };
      expect(shares.n).toBe(0);
    });
  });

  // =============================================================== §128/§129/§130

  describe('desfazer o compartilhamento (§128/§129/§130)', () => {
    it('o autor desfaz, e o check-in continua inteiro (§128/§130)', async () => {
      const checkInId = await publish(ACCOUNT_A, { caption: 'Hoje foi' });
      await share(ACCOUNT_A, checkInId).expect(201);

      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/checkins/${checkInId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);
      // Idempotente.
      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/checkins/${checkInId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(0);

      const detail = await request(s.server())
        .get(`/v1/social/workout-checkins/${checkInId}`)
        .set(auth(ACCOUNT_B))
        .expect(200);
      expect(detail.body.caption).toBe('Hoje foi');
    });

    it('o dono do squad NÃO remove o compartilhamento de outro (§129/§130)', async () => {
      const checkInId = await publish(ACCOUNT_B);
      await share(ACCOUNT_B, checkInId).expect(201);

      await request(s.server())
        .delete(`/v1/social/groups/${groupId}/checkins/${checkInId}`)
        .set(auth(ACCOUNT_A))
        .expect(204); // idempotente do ponto de vista de A: ela não tem share nenhum ali

      // O vínculo de B continua de pé — A não modera conteúdo por exclusão nesta fase.
      expect((await readFeed(ACCOUNT_A).expect(200)).body.items).toHaveLength(1);
    });
  });

  // =============================================================== §157 bloqueio

  describe('bloqueio no feed do squad (§33/§65/§66/§157)', () => {
    beforeEach(async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_C);
    });

    it('B bloqueia A: o conteúdo de A some para B, e o de B some para A (§65/§157)', async () => {
      const fromA = await publish(ACCOUNT_A, { caption: 'de A' });
      await share(ACCOUNT_A, fromA).expect(201);
      const fromB = await publish(ACCOUNT_B, { caption: 'de B' });
      await share(ACCOUNT_B, fromB).expect(201);

      expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(2);

      const socialIdA = await s.socialIdOf(ACCOUNT_A);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_B))
        .send({ blockedSocialId: socialIdA })
        .expect(200);

      const feedB = await readFeed(ACCOUNT_B).expect(200);
      expect(
        feedB.body.items.map((i: { checkIn: { checkInId: string } }) => i.checkIn.checkInId),
      ).toEqual([fromB]);

      const feedA = await readFeed(ACCOUNT_A).expect(200);
      expect(
        feedA.body.items.map((i: { checkIn: { checkInId: string } }) => i.checkIn.checkInId),
      ).toEqual([fromA]);

      // §58 — e o terceiro continua vendo os dois: o bloqueio de um não veta o grupo inteiro.
      const feedC = await readFeed(ACCOUNT_C).expect(200);
      expect(feedC.body.items).toHaveLength(2);
    });

    it('a mídia do par bloqueado responde 404 (§81/§157)', async () => {
      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const mediaId = await s.uploadPhoto(ACCOUNT_A, sessionSyncId, await jpeg());
      const checkInId = await s.publishCheckIn(ACCOUNT_A, sessionSyncId, { mediaId });
      await share(ACCOUNT_A, checkInId).expect(201);

      // Antes do bloqueio, B baixa os bytes pelo caminho do Squad.
      await request(s.server()).get(`/v1/social/media/${mediaId}`).set(auth(ACCOUNT_B)).expect(200);

      const socialIdA = await s.socialIdOf(ACCOUNT_A);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_B))
        .send({ blockedSocialId: socialIdA })
        .expect(200);

      await request(s.server()).get(`/v1/social/media/${mediaId}`).set(auth(ACCOUNT_B)).expect(404);
    });

    it('desbloquear devolve o acesso, porque o consentimento de participação continua (§66)', async () => {
      const checkInId = await publish(ACCOUNT_A, { caption: 'de A' });
      await share(ACCOUNT_A, checkInId).expect(201);

      const socialIdA = await s.socialIdOf(ACCOUNT_A);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_B))
        .send({ blockedSocialId: socialIdA })
        .expect(200);
      expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(0);

      await request(s.server())
        .delete(`/v1/social/blocks/${socialIdA}`)
        .set(auth(ACCOUNT_B))
        .expect(200);

      // §66 — o acesso volta. Ele nunca dependeu da amizade (que o bloqueio desfez): ele vem do
      // Squad, e as duas participações continuam de pé.
      expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(1);
    });
  });

  // =============================================================== §74/§75/§79 conteúdo

  describe('o que o Squad recebe, e o que ele nunca recebe (§74/§75/§79)', () => {
    it('o membro vê legenda e foto, e nada de treino atravessa (§74/§75)', async () => {
      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const mediaId = await s.uploadPhoto(ACCOUNT_A, sessionSyncId, await jpeg());
      const checkInId = await s.publishCheckIn(ACCOUNT_A, sessionSyncId, {
        mediaId,
        caption: 'Finalmente saiu esse treino',
      });
      await share(ACCOUNT_A, checkInId).expect(201);

      const feed = await readFeed(ACCOUNT_B).expect(200);
      const [item] = feed.body.items;

      expect(item.checkIn.caption).toBe('Finalmente saiu esse treino');
      expect(item.checkIn.media.mediaId).toBe(mediaId);
      // §79 — e os bytes descem pelo endpoint autenticado, com a autorização vinda do Squad.
      await request(s.server()).get(`/v1/social/media/${mediaId}`).set(auth(ACCOUNT_B)).expect(200);

      // §75 — nada de treino, em nenhum campo e em nenhuma profundidade.
      const serialized = JSON.stringify(feed.body);
      for (const forbidden of [
        sessionSyncId,
        ACCOUNT_A.uid,
        ACCOUNT_A.email,
        'exercise',
        'sets',
        'reps',
        'load',
        'duration',
        'notes',
        'sessionSyncId',
        'storageKey',
        'friendCode',
      ]) {
        expect({ forbidden, present: serialized.includes(forbidden) }).toEqual({
          forbidden,
          present: false,
        });
      }
    });

    it('o card do feed do squad é o DTO da T17.9, com um envelope (§69/§85)', async () => {
      const checkInId = await publish(ACCOUNT_A, { caption: 'Hoje foi' });
      await share(ACCOUNT_A, checkInId).expect(201);

      const feed = await readFeed(ACCOUNT_B).expect(200);
      const [item] = feed.body.items;

      expect(Object.keys(item).sort()).toEqual(['checkIn', 'sharedToGroupAt']);
      expect(Object.keys(item.checkIn).sort()).toEqual(
        [
          'author',
          'canInteract',
          'caption',
          'checkInId',
          'commentCount',
          'currentUserReaction',
          'isCurrentUser',
          'media',
          'publishedAt',
          'reactions',
          'type',
        ].sort(),
      );
      expect(Object.keys(item.checkIn.author).sort()).toEqual(['displayName', 'socialId']);
    });

    it('a ordenação é a data do compartilhamento, não a da publicação (§84)', async () => {
      // O antigo é publicado primeiro e compartilhado por último: ele precisa aparecer no topo.
      const older = await publish(ACCOUNT_A, { caption: 'antigo' });
      s.clock.advance(60_000);
      const newer = await publish(ACCOUNT_A, { caption: 'novo' });

      await share(ACCOUNT_A, newer).expect(201);
      s.clock.advance(60_000);
      await share(ACCOUNT_A, older).expect(201);

      const feed = await readFeed(ACCOUNT_B).expect(200);
      expect(
        feed.body.items.map((i: { checkIn: { checkInId: string } }) => i.checkIn.checkInId),
      ).toEqual([older, newer]);
      expect(feed.body.items[0].checkIn.publishedAt).toBeLessThan(
        feed.body.items[1].checkIn.publishedAt,
      );
    });
  });

  // =============================================================== §158/§159 interação

  describe('interação: o Squad sozinho não autoriza (§70/§71/§72/§158/§159)', () => {
    it('membro só de Squad vê, mas não reage nem comenta (§70/§158)', async () => {
      // C entra no Squad **sem** ser amiga de B: a única relação entre os dois é o grupo.
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_C);

      const checkInId = await publish(ACCOUNT_B, { caption: 'de B' });
      await share(ACCOUNT_B, checkInId).expect(201);

      const feed = await readFeed(ACCOUNT_C).expect(200);
      expect(feed.body.items).toHaveLength(1);
      // §72 — a tela sabe que não deve oferecer, e o servidor recusa de qualquer forma.
      expect(feed.body.items[0].checkIn.canInteract).toBe(false);

      // §82 — o detalhe abre (o acesso veio do Squad)...
      const detail = await request(s.server())
        .get(`/v1/social/workout-checkins/${checkInId}`)
        .set(auth(ACCOUNT_C))
        .expect(200);
      expect(detail.body.canInteract).toBe(false);

      // ...e as mutações são recusadas.
      await request(s.server())
        .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
        .set(auth(ACCOUNT_C))
        .send({ type: 'FIRE' })
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('CHECKIN_NOT_FOUND'));
      await request(s.server())
        .post(`/v1/social/workout-checkins/${checkInId}/comments`)
        .set(auth(ACCOUNT_C))
        .send({ body: 'boa!' })
        .expect(404);
      await request(s.server())
        .get(`/v1/social/workout-checkins/${checkInId}/comments`)
        .set(auth(ACCOUNT_C))
        .expect(404);
    });

    it('o amigo que também é do squad continua interagindo (§73/§159)', async () => {
      const checkInId = await publish(ACCOUNT_A, { caption: 'de A' });
      await share(ACCOUNT_A, checkInId).expect(201);

      const feed = await readFeed(ACCOUNT_B).expect(200);
      expect(feed.body.items[0].checkIn.canInteract).toBe(true);

      await request(s.server())
        .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
        .set(auth(ACCOUNT_B))
        .send({ type: 'FIRE' })
        .expect(200);
      await request(s.server())
        .post(`/v1/social/workout-checkins/${checkInId}/comments`)
        .set(auth(ACCOUNT_B))
        .send({ body: 'boa!' })
        .expect(201);

      // §159 — a autorização veio da Friendship, e é ela que aparece no card.
      const after = await readFeed(ACCOUNT_B).expect(200);
      expect(after.body.items[0].checkIn.reactions).toEqual({ FIRE: 1 });
      expect(after.body.items[0].checkIn.commentCount).toBe(1);
    });

    it('o card de quem só tem o Squad não carrega a conversa dos amigos do autor (§71)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_C);

      const checkInId = await publish(ACCOUNT_A, { caption: 'de A' });
      await share(ACCOUNT_A, checkInId).expect(201);
      await request(s.server())
        .post(`/v1/social/workout-checkins/${checkInId}/comments`)
        .set(auth(ACCOUNT_B))
        .send({ body: 'boa, Alice!' })
        .expect(201);

      // B é amiga de A e vê a conversa.
      const feedB = await readFeed(ACCOUNT_B).expect(200);
      expect(feedB.body.items[0].checkIn.commentCount).toBe(1);

      // C também é amiga de A — ela veria. O caso de §71 é quem **só** tem o Squad, e para provar
      // isso o autor precisa ser alguém de quem C não é amiga: B.
      const fromB = await publish(ACCOUNT_B, { caption: 'de B' });
      await share(ACCOUNT_B, fromB).expect(201);
      await request(s.server())
        .post(`/v1/social/workout-checkins/${fromB}/comments`)
        .set(auth(ACCOUNT_A))
        .send({ body: 'boa, Bruno!' })
        .expect(201);

      const feedC = await readFeed(ACCOUNT_C).expect(200);
      const cardFromB = feedC.body.items.find(
        (i: { checkIn: { checkInId: string } }) => i.checkIn.checkInId === fromB,
      );
      expect(cardFromB.checkIn.canInteract).toBe(false);
      expect(cardFromB.checkIn.commentCount).toBe(0);
      expect(cardFromB.checkIn.reactions).toEqual({});
      expect(cardFromB.checkIn.currentUserReaction).toBeNull();
    });
  });

  // =============================================================== §141 seletor

  it('a tela de escolha sabe em quais squads o check-in já está (§141)', async () => {
    const checkInId = await publish(ACCOUNT_A);
    const second = await s.createGroup(ACCOUNT_A, 'Outro squad');

    await share(ACCOUNT_A, checkInId).expect(201);

    const res = await request(s.server())
      .get(`/v1/social/workout-checkins/${checkInId}/groups`)
      .set(auth(ACCOUNT_A))
      .expect(200);
    expect(res.body.groupIds).toEqual([groupId]);
    expect(res.body.groupIds).not.toContain(second);

    // Só o autor pergunta: um terceiro não descobre onde a publicação de alguém circula.
    await request(s.server())
      .get(`/v1/social/workout-checkins/${checkInId}/groups`)
      .set(auth(ACCOUNT_B))
      .expect(404);
  });

  // =============================================================== §86 recorte

  it('o feed é bounded: janela de compartilhamento e teto de itens (§86/§87)', async () => {
    const checkInId = await publish(ACCOUNT_A);
    await share(ACCOUNT_A, checkInId).expect(201);

    // Fora da janela de 30 dias sobre a **data do compartilhamento**.
    s.clock.advance(31 * 24 * 60 * 60 * 1000);
    expect((await readFeed(ACCOUNT_B).expect(200)).body.items).toHaveLength(0);

    // E o `limit` fora de forma é recusado; acima do teto é atendido até o teto.
    await request(s.server())
      .get(`/v1/social/groups/${groupId}/feed?limit=abc`)
      .set(auth(ACCOUNT_B))
      .expect(400);
    await request(s.server())
      .get(`/v1/social/groups/${groupId}/feed?limit=500`)
      .set(auth(ACCOUNT_B))
      .expect(200);
    await request(s.server())
      .get(`/v1/social/groups/${groupId}/feed?users=${uuid()}`)
      .set(auth(ACCOUNT_B))
      .expect(400);
  });
});
