import request from 'supertest';
import {
  ACCOUNT_A,
  ACCOUNT_B,
  ACCOUNT_C,
  createSocialScenario,
  type AuditAccount,
  type SocialScenario,
} from './support/social-scenario';

/**
 * T17.12 — interações contextuais: reações e comentários por audiência.
 *
 * ## O que este arquivo existe para provar
 *
 * Que a mesma publicação pode estar em três lugares — o Feed de amigos, o Squad X e o Squad Y — e
 * que **cada um deles tem a própria conversa**:
 *
 * ```text
 * WorkoutCheckIn
 *  ├── Friend Feed → interações FRIEND
 *  ├── Squad X     → interações GROUP(X)
 *  └── Squad Y     → interações GROUP(Y)
 * ```
 *
 * Sem post duplicado, sem segundo Feed e sem vazamento entre audiências. As três afirmações que
 * mais importam, e que a T17.12 lista como bloqueantes se falharem (§179):
 *
 * 1. um comentário de `GROUP(X)` nunca aparece em `GROUP(Y)` nem no Feed de amigos;
 * 2. um `groupId` enviado pelo cliente **não concede nada** sozinho;
 * 3. participar de um Squad não cria amizade, e não amplia nada além daquela audiência.
 */
describe('T17.12 — interações por audiência', () => {
  let s: SocialScenario;
  /** O Squad X, do qual A é dona e B e C são membros. */
  let squadX: string;

  const auth = (a: AuditAccount) => ({ Authorization: `Bearer ${a.token}` });
  const groupContext = (groupId: string) => ({ type: 'GROUP', groupId });

  beforeEach(async () => {
    s = await createSocialScenario();
    await s.activate(ACCOUNT_A);
    await s.activate(ACCOUNT_B);
    await s.activate(ACCOUNT_C);
    // A é amiga de B e de C; **B e C não são amigos entre si** — a única relação entre os dois é o
    // Squad. É esse par que a T17.12 existe para servir (§76) e para conter (§10/§77–§81).
    await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
    await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
    squadX = await s.createGroup(ACCOUNT_A, 'Os Monstros');
    await s.addMember(ACCOUNT_A, squadX, ACCOUNT_B);
    await s.addMember(ACCOUNT_A, squadX, ACCOUNT_C);
  });

  afterEach(async () => {
    await s.close();
  });

  async function publish(account: AuditAccount, caption?: string): Promise<string> {
    const sessionSyncId = await s.pushSession(account);
    return s.publishCheckIn(account, sessionSyncId, caption ? { caption } : {});
  }

  function share(account: AuditAccount, checkInId: string, groupId: string) {
    return request(s.server())
      .post(`/v1/social/groups/${groupId}/checkins/${checkInId}`)
      .set(auth(account));
  }

  function react(
    account: AuditAccount,
    checkInId: string,
    type: string,
    context?: Record<string, unknown>,
  ) {
    return request(s.server())
      .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
      .set(auth(account))
      .send(context ? { type, context } : { type });
  }

  function unreact(account: AuditAccount, checkInId: string, context?: Record<string, unknown>) {
    return request(s.server())
      .delete(`/v1/social/workout-checkins/${checkInId}/reaction`)
      .set(auth(account))
      .send(context ? { context } : {});
  }

  function comment(
    account: AuditAccount,
    checkInId: string,
    body: string,
    context?: Record<string, unknown>,
  ) {
    return request(s.server())
      .post(`/v1/social/workout-checkins/${checkInId}/comments`)
      .set(auth(account))
      .send(context ? { body, context } : { body });
  }

  function listComments(
    account: AuditAccount,
    checkInId: string,
    query: Record<string, string> = {},
  ) {
    return request(s.server())
      .get(`/v1/social/workout-checkins/${checkInId}/comments`)
      .query(query)
      .set(auth(account));
  }

  function groupFeed(account: AuditAccount, groupId: string) {
    return request(s.server()).get(`/v1/social/groups/${groupId}/feed`).set(auth(account));
  }

  function friendFeed(account: AuditAccount) {
    return request(s.server()).get('/v1/social/feed').set(auth(account));
  }

  /** O card de `checkInId` como o feed de amigos de `account` o mostra. */
  async function friendCard(account: AuditAccount, checkInId: string) {
    const res = await friendFeed(account).expect(200);
    return res.body.items.find((item: { checkInId: string }) => item.checkInId === checkInId);
  }

  /** O card de `checkInId` como o feed de `groupId` o mostra. */
  async function groupCard(account: AuditAccount, groupId: string, checkInId: string) {
    const res = await groupFeed(account, groupId).expect(200);
    return res.body.items.find(
      (item: { checkIn: { checkInId: string } }) => item.checkIn.checkInId === checkInId,
    )?.checkIn;
  }

  const bodies = (res: { body: { items: Array<{ body: string }> } }) =>
    res.body.items.map((item) => item.body);

  // ================================================================ §133/§134/§135/§136

  describe('a autorização de contexto (§9/§134/§135/§136)', () => {
    it('membro de Squad sem amizade reage e comenta na audiência do Squad (§76/§133)', async () => {
      const checkInId = await publish(ACCOUNT_B, 'treino de B');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);

      // C não é amiga de B. A única relação entre elas é o Squad — e ela basta, aqui.
      await react(ACCOUNT_C, checkInId, 'FIRE', groupContext(squadX)).expect(200);
      await comment(ACCOUNT_C, checkInId, 'boa!', groupContext(squadX)).expect(201);

      const card = await groupCard(ACCOUNT_C, squadX, checkInId);
      expect(card.canInteract).toBe(true);
      expect(card.reactions).toEqual({ FIRE: 1 });
      expect(card.currentUserReaction).toBe('FIRE');
      expect(card.commentCount).toBe(1);
    });

    it('sem compartilhamento naquele Squad, o contexto é recusado (§134)', async () => {
      // Publicado e **não** compartilhado em Squad nenhum.
      const checkInId = await publish(ACCOUNT_B, 'só no feed');

      await react(ACCOUNT_C, checkInId, 'FIRE', groupContext(squadX))
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('CHECKIN_NOT_FOUND'));
      await comment(ACCOUNT_C, checkInId, 'boa!', groupContext(squadX)).expect(404);
      await listComments(ACCOUNT_C, checkInId, { context: 'GROUP', groupId: squadX }).expect(404);
    });

    it('o contexto do Squad errado é recusado — e nunca cai para FRIEND (§69/§135)', async () => {
      const squadY = await s.createGroup(ACCOUNT_A, 'Outro squad');
      await s.addMember(ACCOUNT_A, squadY, ACCOUNT_B);

      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);

      // B é membro dos dois Squads, e o check-in está só em X. Pedir `GROUP(Y)` é recusado, ainda
      // que B tivesse acesso ao mesmo check-in por dois outros caminhos — a amizade com A e o
      // próprio Squad X. Fail-closed: a resposta é `404`, e não uma interação FRIEND silenciosa.
      await react(ACCOUNT_B, checkInId, 'FIRE', groupContext(squadY)).expect(404);
      await comment(ACCOUNT_B, checkInId, 'boa!', groupContext(squadY)).expect(404);

      // E nada foi gravado em audiência nenhuma.
      expect((await friendCard(ACCOUNT_B, checkInId)).reactions).toEqual({});
      expect(await groupCard(ACCOUNT_B, squadX, checkInId)).toMatchObject({ reactions: {} });
    });

    it('quem não é membro não alcança o Squad, mesmo sabendo os dois identificadores (§136)', async () => {
      const squadPrivado = await s.createGroup(ACCOUNT_A, 'Só de A e B');
      await s.addMember(ACCOUNT_A, squadPrivado, ACCOUNT_B);

      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadPrivado).expect(201);

      // C conhece `checkInId` e `groupId` — é amiga de A e vê a publicação no Feed de amigos. O que
      // ela não tem é participação, e é só isso que autoriza a audiência do grupo.
      expect(await friendCard(ACCOUNT_C, checkInId)).toBeDefined();
      await react(ACCOUNT_C, checkInId, 'FIRE', groupContext(squadPrivado)).expect(404);
      await listComments(ACCOUNT_C, checkInId, {
        context: 'GROUP',
        groupId: squadPrivado,
      }).expect(404);
    });

    it('participar de um Squad não vira amizade nem acesso ao Feed de amigos (§77–§81)', async () => {
      const checkInId = await publish(ACCOUNT_B, 'de B');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);
      await react(ACCOUNT_C, checkInId, 'FIRE', groupContext(squadX)).expect(200);

      // C interagiu com B dentro do Squad. Isso não a torna amiga de B em lugar nenhum:
      const feed = await friendFeed(ACCOUNT_C).expect(200);
      expect(
        feed.body.items.some((item: { checkInId: string }) => item.checkInId === checkInId),
      ).toBe(false);

      // ...e no Feed de amigos ela continua sem autorização sobre a mesma publicação (§10).
      await react(ACCOUNT_C, checkInId, 'CLAP').expect(404);
      await comment(ACCOUNT_C, checkInId, 'oi').expect(404);
    });

    it('context.type GROUP sem groupId é 400, e não um 404 (§69)', async () => {
      const checkInId = await publish(ACCOUNT_A);
      await share(ACCOUNT_A, checkInId, squadX).expect(201);

      await request(s.server())
        .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
        .set(auth(ACCOUNT_A))
        .send({ type: 'FIRE', context: { type: 'GROUP' } })
        .expect(400)
        .expect((res) => expect(res.body.error.code).toBe('INVALID_CHECKIN_REQUEST'));

      await request(s.server())
        .post(`/v1/social/workout-checkins/${checkInId}/comments`)
        .set(auth(ACCOUNT_A))
        .send({ body: 'oi', context: { type: 'FRIEND', groupId: squadX } })
        .expect(400);
    });
  });

  // ================================================================ §137/§138 isolamento

  describe('isolamento entre audiências (§137/§138)', () => {
    it('três comentários da mesma pessoa, três audiências, três leituras (§137)', async () => {
      const squadY = await s.createGroup(ACCOUNT_A, 'Squad Y');
      await s.addMember(ACCOUNT_A, squadY, ACCOUNT_B);

      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      await share(ACCOUNT_A, checkInId, squadY).expect(201);

      await comment(ACCOUNT_B, checkInId, 'friend').expect(201);
      await comment(ACCOUNT_B, checkInId, 'x', groupContext(squadX)).expect(201);
      await comment(ACCOUNT_B, checkInId, 'y', groupContext(squadY)).expect(201);

      expect(bodies(await listComments(ACCOUNT_B, checkInId).expect(200))).toEqual(['friend']);
      expect(
        bodies(
          await listComments(ACCOUNT_B, checkInId, {
            context: 'GROUP',
            groupId: squadX,
          }).expect(200),
        ),
      ).toEqual(['x']);
      expect(
        bodies(
          await listComments(ACCOUNT_B, checkInId, {
            context: 'GROUP',
            groupId: squadY,
          }).expect(200),
        ),
      ).toEqual(['y']);

      // E as contagens de card seguem a mesma separação (§63/§64).
      expect((await friendCard(ACCOUNT_B, checkInId)).commentCount).toBe(1);
      expect((await groupCard(ACCOUNT_B, squadX, checkInId)).commentCount).toBe(1);
      expect((await groupCard(ACCOUNT_B, squadY, checkInId)).commentCount).toBe(1);
    });

    it('a mesma pessoa reage diferente em cada audiência, e uma não apaga a outra (§13/§138/§139)', async () => {
      const squadY = await s.createGroup(ACCOUNT_A, 'Squad Y');
      await s.addMember(ACCOUNT_A, squadY, ACCOUNT_B);

      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      await share(ACCOUNT_A, checkInId, squadY).expect(201);

      await react(ACCOUNT_B, checkInId, 'FIRE').expect(200);
      await react(ACCOUNT_B, checkInId, 'MUSCLE', groupContext(squadX)).expect(200);
      await react(ACCOUNT_B, checkInId, 'CLAP', groupContext(squadY)).expect(200);

      expect(await friendCard(ACCOUNT_B, checkInId)).toMatchObject({
        reactions: { FIRE: 1 },
        currentUserReaction: 'FIRE',
      });
      expect(await groupCard(ACCOUNT_B, squadX, checkInId)).toMatchObject({
        reactions: { MUSCLE: 1 },
        currentUserReaction: 'MUSCLE',
      });
      expect(await groupCard(ACCOUNT_B, squadY, checkInId)).toMatchObject({
        reactions: { CLAP: 1 },
        currentUserReaction: 'CLAP',
      });

      // §16 — remover é dentro da audiência, e não toca as outras duas.
      await unreact(ACCOUNT_B, checkInId, groupContext(squadX)).expect(200);
      expect(await groupCard(ACCOUNT_B, squadX, checkInId)).toMatchObject({
        reactions: {},
        currentUserReaction: null,
      });
      expect(await friendCard(ACCOUNT_B, checkInId)).toMatchObject({ currentUserReaction: 'FIRE' });
      expect(await groupCard(ACCOUNT_B, squadY, checkInId)).toMatchObject({
        currentUserReaction: 'CLAP',
      });
    });

    it('trocar a reação dentro de uma audiência não cria uma segunda (§15/§140)', async () => {
      const checkInId = await publish(ACCOUNT_A);
      await share(ACCOUNT_A, checkInId, squadX).expect(201);

      await react(ACCOUNT_B, checkInId, 'FIRE').expect(200);
      await react(ACCOUNT_B, checkInId, 'MUSCLE').expect(200);
      await react(ACCOUNT_B, checkInId, 'MUSCLE', groupContext(squadX)).expect(200);
      await react(ACCOUNT_B, checkInId, 'CLAP', groupContext(squadX)).expect(200);

      expect(await friendCard(ACCOUNT_B, checkInId)).toMatchObject({ reactions: { MUSCLE: 1 } });
      expect(await groupCard(ACCOUNT_B, squadX, checkInId)).toMatchObject({
        reactions: { CLAP: 1 },
      });
    });

    it('a unicidade de FRIEND vale no banco, apesar de group_id ser NULL (§93/§140)', async () => {
      const checkInId = await publish(ACCOUNT_A);
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      await react(ACCOUNT_B, checkInId, 'FIRE').expect(200);
      await react(ACCOUNT_B, checkInId, 'MUSCLE', groupContext(squadX)).expect(200);

      // A prova direta: a segunda linha `FRIEND` do mesmo par é impossível, e o índice parcial é o
      // que a torna impossível — uma `UNIQUE` comum sobre `(checkin_id, reactor_uid, group_id)`
      // deixaria passar, porque no SQLite cada `NULL` é distinto de qualquer outro.
      s.inDatabase((db) => {
        expect(() =>
          db
            .prepare(
              `INSERT INTO social_checkin_reactions
                 (checkin_id, reactor_uid, type, audience_type, group_id, created_at, updated_at)
               VALUES (?, ?, 'CLAP', 'FRIEND', NULL, 1, 1)`,
            )
            .run(checkInId, ACCOUNT_B.uid),
        ).toThrow(/UNIQUE/i);

        // E a de GROUP também, dentro do mesmo Squad.
        expect(() =>
          db
            .prepare(
              `INSERT INTO social_checkin_reactions
                 (checkin_id, reactor_uid, type, audience_type, group_id, created_at, updated_at)
               VALUES (?, ?, 'CLAP', 'GROUP', ?, 1, 1)`,
            )
            .run(checkInId, ACCOUNT_B.uid, squadX),
        ).toThrow(/UNIQUE/i);

        // As duas que existem continuam lá: uma por audiência.
        const rows = db
          .prepare(
            `SELECT audience_type AS audience, group_id AS groupId, type
               FROM social_checkin_reactions
              WHERE checkin_id = ? AND reactor_uid = ?
              ORDER BY audience_type`,
          )
          .all(checkInId, ACCOUNT_B.uid);
        expect(rows).toEqual([
          { audience: 'FRIEND', groupId: null, type: 'FIRE' },
          { audience: 'GROUP', groupId: squadX, type: 'MUSCLE' },
        ]);
      });
    });

    it('o banco recusa uma audiência malformada, mesmo por escrita direta (§27)', () => {
      s.inDatabase((db) => {
        expect(() =>
          db
            .prepare(
              `INSERT INTO social_checkin_comments
                 (id, checkin_id, author_uid, body, audience_type, group_id, created_at)
               VALUES ('c1', 'x', 'y', 'oi', 'GROUP', NULL, 1)`,
            )
            .run(),
        ).toThrow(/CHECK|constraint/i);
      });
    });
  });

  // ================================================================ §141 bloqueio

  describe('bloqueio dentro da mesma audiência (§39/§40/§41/§141)', () => {
    it('o caso de terceiro: A bloqueia B, e cada uma some para a outra — não para C (§141)', async () => {
      // A publicação é de C, e as três estão no mesmo Squad.
      const checkInId = await publish(ACCOUNT_C, 'de C');
      await share(ACCOUNT_C, checkInId, squadX).expect(201);

      await comment(ACCOUNT_A, checkInId, 'de A', groupContext(squadX)).expect(201);
      await comment(ACCOUNT_B, checkInId, 'de B', groupContext(squadX)).expect(201);
      await react(ACCOUNT_A, checkInId, 'FIRE', groupContext(squadX)).expect(200);
      await react(ACCOUNT_B, checkInId, 'FIRE', groupContext(squadX)).expect(200);

      const socialIdB = await s.socialIdOf(ACCOUNT_B);
      await request(s.server())
        .post('/v1/social/blocks')
        .set(auth(ACCOUNT_A))
        .send({ blockedSocialId: socialIdB })
        .expect(200);

      const query = { context: 'GROUP', groupId: squadX };

      // C não bloqueou ninguém: ela vê as duas.
      expect(bodies(await listComments(ACCOUNT_C, checkInId, query).expect(200)).sort()).toEqual([
        'de A',
        'de B',
      ]);
      expect((await groupCard(ACCOUNT_C, squadX, checkInId)).reactions).toEqual({ FIRE: 2 });

      // A não vê B, e B não vê A — inclusive na **contagem** (§39): um número que continuasse 2
      // contaria que existe alguém ali que o bloqueio deveria ter escondido.
      expect(bodies(await listComments(ACCOUNT_A, checkInId, query).expect(200))).toEqual(['de A']);
      expect((await groupCard(ACCOUNT_A, squadX, checkInId)).reactions).toEqual({ FIRE: 1 });
      expect(bodies(await listComments(ACCOUNT_B, checkInId, query).expect(200))).toEqual(['de B']);
      expect((await groupCard(ACCOUNT_B, squadX, checkInId)).reactions).toEqual({ FIRE: 1 });
    });
  });

  // ================================================================ §142–§147 ciclo de vida

  describe('ciclo de vida da audiência (§142–§147)', () => {
    it('sair do Squad leva junto as interações daquela pessoa ali — e só ali (§142/§143)', async () => {
      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);

      await comment(ACCOUNT_B, checkInId, 'no squad', groupContext(squadX)).expect(201);
      await react(ACCOUNT_B, checkInId, 'FIRE', groupContext(squadX)).expect(200);
      // ...e a conversa de B no Feed de amigos, que **não** pode ser tocada.
      await comment(ACCOUNT_B, checkInId, 'no feed').expect(201);
      await react(ACCOUNT_B, checkInId, 'CLAP').expect(200);

      await request(s.server())
        .post(`/v1/social/groups/${squadX}/leave`)
        .set(auth(ACCOUNT_B))
        .expect(204);

      const query = { context: 'GROUP', groupId: squadX };
      expect(bodies(await listComments(ACCOUNT_A, checkInId, query).expect(200))).toEqual([]);
      expect((await groupCard(ACCOUNT_A, squadX, checkInId)).reactions).toEqual({});

      // O Feed de amigos ficou intacto (§47).
      expect(bodies(await listComments(ACCOUNT_A, checkInId).expect(200))).toEqual(['no feed']);
      expect((await friendCard(ACCOUNT_A, checkInId)).reactions).toEqual({ CLAP: 1 });
    });

    it('voltar ao Squad não ressuscita a conversa antiga (§143)', async () => {
      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      await comment(ACCOUNT_B, checkInId, 'antes de sair', groupContext(squadX)).expect(201);

      await request(s.server())
        .post(`/v1/social/groups/${squadX}/leave`)
        .set(auth(ACCOUNT_B))
        .expect(204);
      await s.addMember(ACCOUNT_A, squadX, ACCOUNT_B);

      const query = { context: 'GROUP', groupId: squadX };
      expect(bodies(await listComments(ACCOUNT_B, checkInId, query).expect(200))).toEqual([]);
      expect((await groupCard(ACCOUNT_B, squadX, checkInId)).commentCount).toBe(0);
    });

    it('sair leva também a conversa que os outros deixaram nos posts de quem sai (§71/§143)', async () => {
      // O outro lado da saída, e o que uma revisão adversarial pegou: `leave` apaga os
      // compartilhamentos de quem sai, e com eles some o **objeto** da conversa. As reações e os
      // comentários que os outros deixaram ali ficariam órfãos de um share que não existe mais —
      // invisíveis, mas vivos — e voltariam à tona se a pessoa reentrasse e compartilhasse o mesmo
      // check-in de novo. É o mesmo evento de §70 ("o share sumiu"), e precisa ter o mesmo efeito.
      const checkInId = await publish(ACCOUNT_B, 'de B');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);
      await comment(ACCOUNT_C, checkInId, 'de C no post de B', groupContext(squadX)).expect(201);
      await react(ACCOUNT_C, checkInId, 'FIRE', groupContext(squadX)).expect(200);

      await request(s.server())
        .post(`/v1/social/groups/${squadX}/leave`)
        .set(auth(ACCOUNT_B))
        .expect(204);

      // B volta e traz o mesmo check-in de novo: a conversa antiga não pode reaparecer (§46/§143).
      await s.addMember(ACCOUNT_A, squadX, ACCOUNT_B);
      await share(ACCOUNT_B, checkInId, squadX).expect(201);

      const query = { context: 'GROUP', groupId: squadX };
      expect(bodies(await listComments(ACCOUNT_C, checkInId, query).expect(200))).toEqual([]);
      expect(await groupCard(ACCOUNT_C, squadX, checkInId)).toMatchObject({
        reactions: {},
        commentCount: 0,
        currentUserReaction: null,
      });
    });

    it('ser removido tem o mesmo efeito que sair (§144)', async () => {
      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      await comment(ACCOUNT_B, checkInId, 'do B', groupContext(squadX)).expect(201);

      const members = await request(s.server())
        .get(`/v1/social/groups/${squadX}/members`)
        .set(auth(ACCOUNT_A))
        .expect(200);
      const socialIdB = await s.socialIdOf(ACCOUNT_B);
      const membership = members.body.items.find(
        (item: { socialId: string | null }) => item.socialId === socialIdB,
      );

      await request(s.server())
        .delete(`/v1/social/groups/${squadX}/members/${membership.membershipId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      expect(
        bodies(
          await listComments(ACCOUNT_A, checkInId, {
            context: 'GROUP',
            groupId: squadX,
          }).expect(200),
        ),
      ).toEqual([]);
    });

    it('desfazer o compartilhamento revoga a audiência daquele Squad (§145)', async () => {
      const squadY = await s.createGroup(ACCOUNT_A, 'Squad Y');
      await s.addMember(ACCOUNT_A, squadY, ACCOUNT_B);

      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      await share(ACCOUNT_A, checkInId, squadY).expect(201);

      await comment(ACCOUNT_B, checkInId, 'no X', groupContext(squadX)).expect(201);
      await comment(ACCOUNT_B, checkInId, 'no Y', groupContext(squadY)).expect(201);
      await comment(ACCOUNT_B, checkInId, 'no feed').expect(201);

      await request(s.server())
        .delete(`/v1/social/groups/${squadX}/checkins/${checkInId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      // X perdeu o objeto da conversa; Y e o Feed de amigos continuam inteiros.
      await listComments(ACCOUNT_B, checkInId, { context: 'GROUP', groupId: squadX }).expect(404);
      expect(
        bodies(
          await listComments(ACCOUNT_B, checkInId, {
            context: 'GROUP',
            groupId: squadY,
          }).expect(200),
        ),
      ).toEqual(['no Y']);
      expect(bodies(await listComments(ACCOUNT_B, checkInId).expect(200))).toEqual(['no feed']);
    });

    it('excluir o Squad apaga a audiência dele, e nada além dela (§146)', async () => {
      const squadY = await s.createGroup(ACCOUNT_A, 'Squad Y');
      await s.addMember(ACCOUNT_A, squadY, ACCOUNT_B);

      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      await share(ACCOUNT_A, checkInId, squadY).expect(201);
      await comment(ACCOUNT_B, checkInId, 'no X', groupContext(squadX)).expect(201);
      await comment(ACCOUNT_B, checkInId, 'no Y', groupContext(squadY)).expect(201);
      await comment(ACCOUNT_B, checkInId, 'no feed').expect(201);
      await react(ACCOUNT_B, checkInId, 'FIRE').expect(200);

      await request(s.server())
        .delete(`/v1/social/groups/${squadX}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      // A publicação continua existindo inteira — excluir um Squad nunca alcança um check-in.
      expect(await friendCard(ACCOUNT_B, checkInId)).toMatchObject({
        commentCount: 1,
        reactions: { FIRE: 1 },
      });
      expect(
        bodies(
          await listComments(ACCOUNT_B, checkInId, {
            context: 'GROUP',
            groupId: squadY,
          }).expect(200),
        ),
      ).toEqual(['no Y']);

      // E o que era de X sumiu do banco, em vez de ficar órfão de um grupo que não responde mais.
      s.inDatabase((db) => {
        const alive = db
          .prepare(
            `SELECT COUNT(*) AS n FROM social_checkin_comments
              WHERE group_id = ? AND deleted_at IS NULL`,
          )
          .get(squadX) as { n: number };
        expect(alive.n).toBe(0);
      });
    });

    it('excluir o check-in derruba todas as audiências de uma vez (§147)', async () => {
      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      await comment(ACCOUNT_B, checkInId, 'no X', groupContext(squadX)).expect(201);
      await comment(ACCOUNT_B, checkInId, 'no feed').expect(201);

      await request(s.server())
        .delete(`/v1/social/workout-checkins/${checkInId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      await listComments(ACCOUNT_B, checkInId).expect(404);
      await listComments(ACCOUNT_B, checkInId, { context: 'GROUP', groupId: squadX }).expect(404);
      expect(await groupCard(ACCOUNT_B, squadX, checkInId)).toBeUndefined();
    });

    it('desfazer a amizade não alcança a audiência do Squad (§47/§48)', async () => {
      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      await comment(ACCOUNT_B, checkInId, 'no X', groupContext(squadX)).expect(201);
      await comment(ACCOUNT_B, checkInId, 'no feed').expect(201);

      const socialIdB = await s.socialIdOf(ACCOUNT_B);
      await request(s.server())
        .post('/v1/social/friends/remove')
        .set(auth(ACCOUNT_A))
        .send({ socialId: socialIdB })
        .expect(200);

      // A conversa do Squad continua: a participação é um consentimento próprio (§47).
      expect(
        bodies(
          await listComments(ACCOUNT_B, checkInId, {
            context: 'GROUP',
            groupId: squadX,
          }).expect(200),
        ),
      ).toEqual(['no X']);
      // A do Feed de amigos deixa de ser visível, sem hard delete (§48).
      expect((await groupCard(ACCOUNT_A, squadX, checkInId)).commentCount).toBe(1);
      await listComments(ACCOUNT_B, checkInId).expect(404);
    });
  });

  // ================================================================ §148/§149 conta

  describe('ciclo de vida da conta (§73/§148/§149)', () => {
    it('excluir a conta leva as interações das duas audiências (§73/§148)', async () => {
      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);

      // B deixa rastro nas duas audiências; C também, e o de C precisa sobreviver (§148).
      await comment(ACCOUNT_B, checkInId, 'no feed').expect(201);
      await comment(ACCOUNT_B, checkInId, 'no squad', groupContext(squadX)).expect(201);
      await react(ACCOUNT_B, checkInId, 'FIRE').expect(200);
      await react(ACCOUNT_B, checkInId, 'MUSCLE', groupContext(squadX)).expect(200);
      await comment(ACCOUNT_C, checkInId, 'de C no squad', groupContext(squadX)).expect(201);

      const countsFor = (uid: string) =>
        s.inDatabase((db) => ({
          comments: (
            db
              .prepare(`SELECT COUNT(*) AS n FROM social_checkin_comments WHERE author_uid = ?`)
              .get(uid) as { n: number }
          ).n,
          reactions: (
            db
              .prepare(`SELECT COUNT(*) AS n FROM social_checkin_reactions WHERE reactor_uid = ?`)
              .get(uid) as { n: number }
          ).n,
        }));

      expect(countsFor(ACCOUNT_B.uid)).toEqual({ comments: 2, reactions: 2 });

      await request(s.server()).delete('/v1/account').set(auth(ACCOUNT_B)).expect(200);

      // §148 — as duas audiências saem juntas. O `ON DELETE CASCADE` das duas tabelas é por `uid`,
      // e portanto indiferente à audiência: nenhuma coluna nova da T17.12 cria um caminho por onde
      // uma interação escape da exclusão.
      expect(countsFor(ACCOUNT_B.uid)).toEqual({ comments: 0, reactions: 0 });
      expect(countsFor(ACCOUNT_C.uid)).toEqual({ comments: 1, reactions: 0 });
      expect(
        bodies(
          await listComments(ACCOUNT_A, checkInId, {
            context: 'GROUP',
            groupId: squadX,
          }).expect(200),
        ),
      ).toEqual(['de C no squad']);
    });
  });

  // ================================================================ §150 moderação

  describe('moderação (§54/§55/§123/§125/§150)', () => {
    it('o dono do Squad apaga um comentário GROUP do próprio Squad (§54/§150)', async () => {
      const checkInId = await publish(ACCOUNT_B, 'de B');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);
      const created = await comment(
        ACCOUNT_C,
        checkInId,
        'texto ruim',
        groupContext(squadX),
      ).expect(201);

      // A é dona do Squad. O check-in é de B, o comentário é de C — e ainda assim A modera, porque
      // a conversa acontece na audiência dela.
      await request(s.server())
        .delete(`/v1/social/workout-checkins/${checkInId}/comments/${created.body.commentId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);

      expect(
        bodies(
          await listComments(ACCOUNT_B, checkInId, {
            context: 'GROUP',
            groupId: squadX,
          }).expect(200),
        ),
      ).toEqual([]);
    });

    it('o privilégio de dono não atravessa para o Feed de amigos (§55/§150)', async () => {
      // A é dona do Squad **e** amiga de B e C. Mesmo assim, um comentário FRIEND não é dela.
      const checkInId = await publish(ACCOUNT_B, 'de B');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);
      const created = await comment(ACCOUNT_A, checkInId, 'no feed').expect(201);

      // O comentário é da própria A aqui — para testar o privilégio preciso de um de outra pessoa.
      await request(s.server())
        .delete(`/v1/social/workout-checkins/${checkInId}/comments/${created.body.commentId}`)
        .set(auth(ACCOUNT_C))
        .expect(404);

      // C não é dona de nada, e o comentário continua vivo para quem pode vê-lo.
      expect(bodies(await listComments(ACCOUNT_B, checkInId).expect(200))).toEqual(['no feed']);
    });

    it('um membro comum não modera comentário alheio (§123/§150)', async () => {
      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      const created = await comment(ACCOUNT_B, checkInId, 'do B', groupContext(squadX)).expect(201);

      await request(s.server())
        .delete(`/v1/social/workout-checkins/${checkInId}/comments/${created.body.commentId}`)
        .set(auth(ACCOUNT_C))
        .expect(404);
    });

    it('o dono de outro Squad não modera este (§150)', async () => {
      const squadDeC = await s.createGroup(ACCOUNT_C, 'Squad de C');

      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      const created = await comment(ACCOUNT_B, checkInId, 'do B', groupContext(squadX)).expect(201);

      expect(squadDeC).not.toBe(squadX);
      await request(s.server())
        .delete(`/v1/social/workout-checkins/${checkInId}/comments/${created.body.commentId}`)
        .set(auth(ACCOUNT_C))
        .expect(404);
    });

    it('o autor do check-in continua moderando a própria publicação, em qualquer audiência (§124)', async () => {
      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadX).expect(201);
      const created = await comment(ACCOUNT_B, checkInId, 'do B', groupContext(squadX)).expect(201);

      await request(s.server())
        .delete(`/v1/social/workout-checkins/${checkInId}/comments/${created.body.commentId}`)
        .set(auth(ACCOUNT_A))
        .expect(204);
    });

    it('canDelete descreve as três autoridades (§125)', async () => {
      const checkInId = await publish(ACCOUNT_B, 'de B');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);
      await comment(ACCOUNT_C, checkInId, 'do C', groupContext(squadX)).expect(201);

      const query = { context: 'GROUP', groupId: squadX };
      // A dona do Squad pode apagar; C, autora, também; um membro comum sobre comentário alheio,
      // não — e para provar isso preciso de um quarto papel: B, autor do post, também pode.
      const forOwner = await listComments(ACCOUNT_A, checkInId, query).expect(200);
      expect(forOwner.body.items[0].canDelete).toBe(true);
      const forAuthorOfPost = await listComments(ACCOUNT_B, checkInId, query).expect(200);
      expect(forAuthorOfPost.body.items[0].canDelete).toBe(true);
      const forCommentAuthor = await listComments(ACCOUNT_C, checkInId, query).expect(200);
      expect(forCommentAuthor.body.items[0].canDelete).toBe(true);
    });
  });

  // ================================================================ §151 denúncia

  describe('denúncia de comentário de Squad (§58/§59/§151)', () => {
    it('denunciar um comentário GROUP visível funciona (§151)', async () => {
      const checkInId = await publish(ACCOUNT_B, 'de B');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);
      const created = await comment(
        ACCOUNT_C,
        checkInId,
        'texto ruim',
        groupContext(squadX),
      ).expect(201);

      await request(s.server())
        .post('/v1/social/reports')
        .set(auth(ACCOUNT_B))
        .send({ targetType: 'COMMENT', targetId: created.body.commentId, reason: 'SPAM' })
        .expect(200)
        .expect((res) => expect(res.body.result).toBe('REPORT_RECEIVED'));

      // §103 — o autor denunciado é derivado do banco, e é C.
      s.inDatabase((db) => {
        const row = db
          .prepare(`SELECT reported_uid AS uid, target_type AS type FROM social_reports LIMIT 1`)
          .get() as { uid: string; type: string };
        expect(row).toEqual({ uid: ACCOUNT_C.uid, type: 'COMMENT' });
      });
    });

    it('quem alcança o check-in só pelo Squad consegue denunciá-lo (§128)', async () => {
      // C não é amiga de B: ela alcança a publicação apenas pelo Squad. Depois da T17.12 ela pode
      // comentar ali — e precisa poder denunciar o que está vendo, ou a única pessoa presente ao
      // abuso ficaria sem o mecanismo sancionado para reportá-lo.
      const checkInId = await publish(ACCOUNT_B, 'legenda problemática');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);

      await request(s.server())
        .post('/v1/social/reports')
        .set(auth(ACCOUNT_C))
        .send({ targetType: 'CHECKIN', targetId: checkInId, reason: 'INAPPROPRIATE_BEHAVIOR' })
        .expect(200);

      s.inDatabase((db) => {
        const row = db
          .prepare(`SELECT reported_uid AS uid, target_type AS type FROM social_reports LIMIT 1`)
          .get() as { uid: string; type: string };
        expect(row).toEqual({ uid: ACCOUNT_B.uid, type: 'CHECKIN' });
      });
    });

    it('quem não alcança a publicação por caminho nenhum não denuncia (§104)', async () => {
      const squadFechado = await s.createGroup(ACCOUNT_A, 'Fechado');
      await s.addMember(ACCOUNT_A, squadFechado, ACCOUNT_B);

      // Uma publicação de B, compartilhada só num Squad de que C não participa. C não é amiga de B.
      const checkInId = await publish(ACCOUNT_B, 'de B');
      await share(ACCOUNT_B, checkInId, squadFechado).expect(201);

      await request(s.server())
        .post('/v1/social/reports')
        .set(auth(ACCOUNT_C))
        .send({ targetType: 'CHECKIN', targetId: checkInId, reason: 'SPAM' })
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('INVALID_REPORT_TARGET'));
    });

    it('quem não alcança o comentário naquela audiência não pode denunciá-lo (§59/§151)', async () => {
      // O Squad é só de A e B; C fica de fora, mas é amiga de A e enxerga o check-in no Feed.
      const squadFechado = await s.createGroup(ACCOUNT_A, 'Fechado');
      await s.addMember(ACCOUNT_A, squadFechado, ACCOUNT_B);

      const checkInId = await publish(ACCOUNT_A, 'de A');
      await share(ACCOUNT_A, checkInId, squadFechado).expect(201);
      const created = await comment(
        ACCOUNT_B,
        checkInId,
        'no squad',
        groupContext(squadFechado),
      ).expect(201);

      await request(s.server())
        .post('/v1/social/reports')
        .set(auth(ACCOUNT_C))
        .send({ targetType: 'COMMENT', targetId: created.body.commentId, reason: 'SPAM' })
        .expect(404)
        .expect((res) => expect(res.body.error.code).toBe('INVALID_REPORT_TARGET'));
    });
  });

  // ================================================================ compatibilidade e detalhe

  describe('detalhe e compatibilidade (§35/§68)', () => {
    it('o detalhe com contexto de Squad traz a conversa daquele Squad (§35/§66)', async () => {
      const checkInId = await publish(ACCOUNT_B, 'de B');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);
      await comment(ACCOUNT_C, checkInId, 'no squad', groupContext(squadX)).expect(201);
      await comment(ACCOUNT_A, checkInId, 'no feed').expect(201);

      const withContext = await request(s.server())
        .get(`/v1/social/workout-checkins/${checkInId}`)
        .query({ context: 'GROUP', groupId: squadX })
        .set(auth(ACCOUNT_C))
        .expect(200);
      expect(withContext.body.canInteract).toBe(true);
      expect(withContext.body.commentCount).toBe(1);

      // Sem contexto, C só alcança a publicação pelo Squad — e a T17.11 continua valendo ali:
      // leitura sim, interação não, porque ninguém disse de qual Squad a tela está falando.
      const withoutContext = await request(s.server())
        .get(`/v1/social/workout-checkins/${checkInId}`)
        .set(auth(ACCOUNT_C))
        .expect(200);
      expect(withoutContext.body.canInteract).toBe(false);
      expect(withoutContext.body.commentCount).toBe(0);
    });

    it('um cliente que não envia contexto continua interagindo no Feed de amigos (§68)', async () => {
      const checkInId = await publish(ACCOUNT_A, 'de A');

      // Exatamente o corpo da T17.9, sem `context`.
      await react(ACCOUNT_B, checkInId, 'FIRE').expect(200);
      await comment(ACCOUNT_B, checkInId, 'boa!').expect(201);

      expect(await friendCard(ACCOUNT_B, checkInId)).toMatchObject({
        reactions: { FIRE: 1 },
        commentCount: 1,
      });

      s.inDatabase((db) => {
        const row = db
          .prepare(
            `SELECT audience_type AS audience, group_id AS groupId
               FROM social_checkin_comments WHERE checkin_id = ?`,
          )
          .get(checkInId) as { audience: string; groupId: string | null };
        expect(row).toEqual({ audience: 'FRIEND', groupId: null });
      });
    });

    it('o DTO continua sem uid, em qualquer audiência (§99)', async () => {
      const checkInId = await publish(ACCOUNT_B, 'de B');
      await share(ACCOUNT_B, checkInId, squadX).expect(201);
      await comment(ACCOUNT_C, checkInId, 'no squad', groupContext(squadX)).expect(201);

      const res = await listComments(ACCOUNT_A, checkInId, {
        context: 'GROUP',
        groupId: squadX,
      }).expect(200);

      const serialized = JSON.stringify(res.body);
      for (const account of [ACCOUNT_A, ACCOUNT_B, ACCOUNT_C]) {
        expect(serialized).not.toContain(account.uid);
        expect(serialized).not.toContain(account.email);
      }
      expect(Object.keys(res.body.items[0]).sort()).toEqual([
        'author',
        'body',
        'canDelete',
        'commentId',
        'createdAt',
        'isCurrentUser',
      ]);
    });
  });
});
