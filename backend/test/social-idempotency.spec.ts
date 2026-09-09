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
import { jpeg, noisyPhoto } from './support/image-fixtures';

/**
 * T17.13.1 §33–§44, §69 e §70 — a idempotência das mutações sociais tem **uma** forma.
 *
 * ## A regra, igual para todas
 *
 * ```text
 * autorização (perfil ativo, posse, participação)     ← nunca é pulada por replay (§35)
 *      │
 * chave de idempotência já existe?
 *      ├── mesmo payload canônico  →  devolve o resultado original, SEM consumir rate limit
 *      ├── payload diferente       →  409 CONFLICT
 *      └── ausente                 →  rate limit  →  executa a mutação
 * ```
 *
 * ## As duas coisas que estavam erradas
 *
 * 1. **o limitador vinha antes da conferência.** A primeira tentativa consumia a janela; a resposta
 *    se perdia na rede; o retry — que não cria nada — era recusado com `429` por um limite que
 *    existe para conter criação. O cliente ficava sem saber se a mutação aconteceu, e a única saída
 *    era esperar a janela virar;
 * 2. **a chave sozinha era tratada como a intenção.** Criar Squad com outro nome, convidar outra
 *    pessoa, enviar outra foto: os três devolviam `200` com o resultado **antigo**. O cliente
 *    acreditava ter feito o que pediu agora, e tinha feito outra coisa antes.
 */
describe('T17.13.1 — idempotência padronizada das mutações sociais', () => {
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

  // ================================================================== §36 Group create

  describe('criação de Squad (§36)', () => {
    it('retry exato devolve o mesmo Squad, sem criar um segundo', async () => {
      const clientRequestId = uuid();
      const body = { name: 'Os Monstros', clientRequestId };

      const first = await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send(body)
        .expect(201);
      const second = await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send(body)
        .expect(201);

      expect(second.body.groupId).toBe(first.body.groupId);
      expect(
        s.inDatabase(
          (db) => (db.prepare(`SELECT COUNT(*) AS n FROM social_groups`).get() as { n: number }).n,
        ),
      ).toBe(1);
      // E nenhum efeito colateral duplicado: o dono entra uma vez só.
      expect(
        s.inDatabase(
          (db) =>
            (
              db.prepare(`SELECT COUNT(*) AS n FROM social_group_memberships`).get() as {
                n: number;
              }
            ).n,
        ),
      ).toBe(1);
    });

    it('mesma chave com outro nome é 409, e não o Squad antigo', async () => {
      const clientRequestId = uuid();
      await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name: 'Os Monstros', clientRequestId })
        .expect(201);

      const res = await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name: 'Outro nome completamente', clientRequestId })
        .expect(409);

      expect(res.body.error.code).toBe('IDEMPOTENCY_CONFLICT');
      expect(
        s.inDatabase(
          (db) => (db.prepare(`SELECT COUNT(*) AS n FROM social_groups`).get() as { n: number }).n,
        ),
      ).toBe(1);
    });

    it('o retry exato funciona mesmo com a quota de criação esgotada (§43)', async () => {
      // Esgota o limitador de criação com Squads legítimos.
      const created: string[] = [];
      let exhausted = false;
      for (let i = 0; i < 40; i += 1) {
        const res = await request(s.server())
          .post('/v1/social/groups')
          .set(auth(ACCOUNT_A))
          .send({ name: `Squad ${i}`, clientRequestId: uuid() });
        if (res.status === 429) {
          exhausted = true;
          break;
        }
        if (res.status === 201) created.push(res.body.groupId as string);
        // Outros limites (teto de Squads criados) também encerram o laço: o que importa é ter um
        // `clientRequestId` já usado e o limitador consumido.
        if (res.status === 409 || res.status === 422 || res.status === 403) break;
      }

      // Um retry do **primeiro** Squad criado precisa continuar convergindo.
      const firstId = created[0];
      const firstName = 'Squad 0';
      const clientRequestId = s.inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT client_request_id AS id FROM social_groups WHERE id = ?`)
              .get(firstId) as { id: string }
          ).id,
      );

      const replay = await request(s.server())
        .post('/v1/social/groups')
        .set(auth(ACCOUNT_A))
        .send({ name: firstName, clientRequestId })
        .expect(201);
      expect(replay.body.groupId).toBe(firstId);

      // E uma mutação **nova** continua sujeita ao limite quando ele foi atingido.
      if (exhausted) {
        await request(s.server())
          .post('/v1/social/groups')
          .set(auth(ACCOUNT_A))
          .send({ name: 'Mais um', clientRequestId: uuid() })
          .expect(429);
      }
    });
  });

  // ================================================================== §37 Group invite

  describe('convite de Squad (§37)', () => {
    it('retry exato devolve o mesmo convite', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      const socialId = await s.socialIdOf(ACCOUNT_B);
      const clientRequestId = uuid();

      const first = await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId })
        .expect(201);
      const second = await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId })
        .expect(201);

      expect(second.body.invitationId).toBe(first.body.invitationId);
      expect(
        s.inDatabase(
          (db) =>
            (
              db.prepare(`SELECT COUNT(*) AS n FROM social_group_invitations`).get() as {
                n: number;
              }
            ).n,
        ),
      ).toBe(1);
      // E um único evento de notificação: o retry não avisa B duas vezes.
      expect(
        s.inDatabase(
          (db) =>
            (
              db
                .prepare(
                  `SELECT COUNT(*) AS n FROM social_notification_events
                   WHERE type = 'GROUP_INVITATION_RECEIVED'`,
                )
                .get() as { n: number }
            ).n,
        ),
      ).toBe(1);
    });

    it('mesma chave com outro destinatário é 409, e nunca o convite de B', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      const clientRequestId = uuid();

      const toB = await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId: await s.socialIdOf(ACCOUNT_B), clientRequestId })
        .expect(201);

      const res = await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId: await s.socialIdOf(ACCOUNT_C), clientRequestId })
        .expect(409);

      expect(res.body.error.code).toBe('IDEMPOTENCY_CONFLICT');
      // O convite de B não foi devolvido como se fosse de C, e C não recebeu nada.
      expect(res.body.invitationId).toBeUndefined();
      expect(
        s.inDatabase(
          (db) =>
            (
              db
                .prepare(
                  `SELECT COUNT(*) AS n FROM social_group_invitations WHERE recipient_uid = ?`,
                )
                .get(ACCOUNT_C.uid) as { n: number }
            ).n,
        ),
      ).toBe(0);
      expect(toB.body.invitationId).toBeDefined();
    });

    it('o replay não devolve o convite a quem deixou de ser dono (§35)', async () => {
      await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
      await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
      const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
      await s.addMember(ACCOUNT_A, groupId, ACCOUNT_B);

      const socialId = await s.socialIdOf(ACCOUNT_C);
      const clientRequestId = uuid();
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId })
        .expect(201);

      // A posse passa para B. A autorização é revalidada **antes** da conferência de idempotência.
      const membershipId = s.inDatabase(
        (db) =>
          (
            db
              .prepare(
                `SELECT id FROM social_group_memberships WHERE group_id = ? AND member_uid = ?`,
              )
              .get(groupId, ACCOUNT_B.uid) as { id: string }
          ).id,
      );
      await request(s.server())
        .post(`/v1/social/groups/${groupId}/transfer-ownership`)
        .set(auth(ACCOUNT_A))
        .send({ membershipId })
        .expect(204);

      const replay = await request(s.server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set(auth(ACCOUNT_A))
        .send({ socialId, clientRequestId })
        .expect(403);
      expect(replay.body.error.code).toBe('GROUP_FORBIDDEN');
    });
  });

  // ================================================================== §38 CheckIn

  describe('publicação de check-in (§38)', () => {
    it('retry exato devolve o mesmo check-in, sem publicar duas vezes', async () => {
      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const clientRequestId = uuid();
      const body = { sessionSyncId, clientRequestId, caption: 'foi bom' };

      const first = await request(s.server())
        .post('/v1/social/workout-checkins')
        .set(auth(ACCOUNT_A))
        .send(body)
        .expect(201);
      const second = await request(s.server())
        .post('/v1/social/workout-checkins')
        .set(auth(ACCOUNT_A))
        .send(body)
        .expect(201);

      expect(second.body.checkInId).toBe(first.body.checkInId);
      expect(
        s.inDatabase(
          (db) =>
            (db.prepare(`SELECT COUNT(*) AS n FROM social_workout_checkins`).get() as { n: number })
              .n,
        ),
      ).toBe(1);
    });

    it('a mesma chave para outra sessão é 409 (§38)', async () => {
      const one = await s.pushSession(ACCOUNT_A);
      const two = await s.pushSession(ACCOUNT_A, Date.now() - 3_600_000);
      const clientRequestId = uuid();

      await request(s.server())
        .post('/v1/social/workout-checkins')
        .set(auth(ACCOUNT_A))
        .send({ sessionSyncId: one, clientRequestId })
        .expect(201);

      const res = await request(s.server())
        .post('/v1/social/workout-checkins')
        .set(auth(ACCOUNT_A))
        .send({ sessionSyncId: two, clientRequestId })
        .expect(409);
      expect(res.body.error.code).toBe('CHECKIN_REQUEST_CONFLICT');
    });
  });

  // ================================================================== §39–§44 Media

  describe('upload de mídia (§39–§44/§70)', () => {
    const upload = (
      account: AuditAccount,
      sessionSyncId: string,
      clientUploadId: string,
      bytes: Buffer,
    ) =>
      request(s.server())
        .post('/v1/social/checkin-media')
        .query({ sessionSyncId, clientUploadId })
        .set(auth(account))
        .set('Content-Type', 'image/jpeg')
        .send(bytes);

    it('mesma chave + mesmos bytes ⇒ mesmo mediaId, um arquivo só', async () => {
      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const clientUploadId = uuid();
      const bytes = await jpeg();

      const first = await upload(ACCOUNT_A, sessionSyncId, clientUploadId, bytes).expect(201);
      const second = await upload(ACCOUNT_A, sessionSyncId, clientUploadId, bytes).expect(201);

      expect(second.body.mediaId).toBe(first.body.mediaId);
      expect(s.filesOnDisk()).toHaveLength(1);
      expect(
        s.inDatabase(
          (db) =>
            (db.prepare(`SELECT COUNT(*) AS n FROM social_checkin_media`).get() as { n: number }).n,
        ),
      ).toBe(1);
    });

    it('mesma chave + bytes diferentes ⇒ 409, e nunca a foto antiga (§40)', async () => {
      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const clientUploadId = uuid();

      const first = await upload(ACCOUNT_A, sessionSyncId, clientUploadId, await jpeg()).expect(
        201,
      );
      const res = await upload(
        ACCOUNT_A,
        sessionSyncId,
        clientUploadId,
        await noisyPhoto(600),
      ).expect(409);

      expect(res.body.error.code).toBe('MEDIA_UPLOAD_CONFLICT');
      // O `mediaId` antigo **não** foi devolvido: era isso que fazia a pessoa publicar a foto
      // errada acreditando ter enviado a nova.
      expect(res.body.mediaId).toBeUndefined();
      expect(first.body.mediaId).toBeDefined();
      expect(s.filesOnDisk()).toHaveLength(1);
    });

    it('mesma chave + outra sessão ⇒ 404, e nunca a mídia da sessão original (§70)', async () => {
      const one = await s.pushSession(ACCOUNT_A);
      const two = await s.pushSession(ACCOUNT_A, Date.now() - 3_600_000);
      const clientUploadId = uuid();
      const bytes = await jpeg();

      await upload(ACCOUNT_A, one, clientUploadId, bytes).expect(201);
      const res = await upload(ACCOUNT_A, two, clientUploadId, bytes).expect(404);
      expect(res.body.error.code).toBe('MEDIA_NOT_FOUND');
      expect(res.body.mediaId).toBeUndefined();
    });

    it('dois uploads idênticos simultâneos convergem em um arquivo útil (§44)', async () => {
      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const clientUploadId = uuid();
      const bytes = await jpeg();

      const [a, b] = await Promise.all([
        upload(ACCOUNT_A, sessionSyncId, clientUploadId, bytes),
        upload(ACCOUNT_A, sessionSyncId, clientUploadId, bytes),
      ]);

      expect(a.status).toBe(201);
      expect(b.status).toBe(201);
      expect(a.body.mediaId).toBe(b.body.mediaId);
      // Uma linha e **um** arquivo: o perdedor da corrida apaga o que escreveu, em vez de deixar
      // um órfão permanente.
      expect(
        s.inDatabase(
          (db) =>
            (db.prepare(`SELECT COUNT(*) AS n FROM social_checkin_media`).get() as { n: number }).n,
        ),
      ).toBe(1);
      expect(s.filesOnDisk()).toHaveLength(1);
    });

    it('o retry exato funciona mesmo com a quota de upload esgotada (§43)', async () => {
      const sessionSyncId = await s.pushSession(ACCOUNT_A);
      const clientUploadId = uuid();
      const bytes = await jpeg();

      const first = await upload(ACCOUNT_A, sessionSyncId, clientUploadId, bytes).expect(201);

      // Esgota a janela do limitador com uploads **novos**.
      let limited = false;
      for (let i = 0; i < 40; i += 1) {
        const res = await upload(ACCOUNT_A, sessionSyncId, uuid(), await noisyPhoto(300 + i));
        if (res.status === 429) {
          limited = true;
          break;
        }
      }
      expect(limited).toBe(true);

      // A resposta original se perdeu na rede; o cliente reenvia exatamente a mesma coisa.
      const replay = await upload(ACCOUNT_A, sessionSyncId, clientUploadId, bytes).expect(201);
      expect(replay.body.mediaId).toBe(first.body.mediaId);
    });
  });
});
