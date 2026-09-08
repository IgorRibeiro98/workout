import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { SOCIAL_FRIEND_RATE_LIMIT, SOCIAL_LIST_PAGE } from '../src/modules/social/social.limits';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Igor' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'João' },
  C: { token: 'token-c', uid: 'uid-c', email: 'c@example.com', name: 'Jonathas' },
} as const;

/**
 * O grafo social: descoberta por código, pedidos e amizade bilateral (T17.1).
 *
 * O que estes testes protegem, acima de tudo:
 *
 * 1. **descoberta é só por código exato.** Não há busca, listagem nem enumeração, e um código
 *    malformado, um inexistente e um de perfil desativado dão **a mesma** resposta;
 * 2. **amizade é um par, não duas relações.** `A-B == B-A`, duplicata é impossível, e amizade
 *    consigo mesmo não é representável;
 * 3. **só participante age.** A conta C não aceita, não recusa, não cancela e não desfaz nada
 *    entre A e B — e nem descobre que aquilo existe;
 * 4. **nenhum Firebase UID e nenhum e-mail sai numa resposta.**
 *
 * Auth é dublê: nenhum teste toca Firebase, rede ou VPS.
 */
describe('Grafo social: código, pedidos e amizade', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    const verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    app = await createTestApp(configFor(temp.path), verifier);
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  // ------------------------------------------------------------------------------ helpers

  const activate = async (account: { token: string; name: string }) => {
    const response = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(account.token))
      .send({ displayName: account.name });
    expect(response.status).toBe(200);
    return response.body.profile as {
      socialId: string;
      friendCode: string;
      displayName: string;
    };
  };

  const lookup = (token: string, friendCode: string) =>
    request(server())
      .post('/v1/social/friends/lookup')
      .set('Authorization', auth(token))
      .send({ friendCode });

  const send = (token: string, socialId: string) =>
    request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(token))
      .send({ socialId });

  const incoming = (token: string, query = '') =>
    request(server())
      .get(`/v1/social/friend-requests/incoming${query}`)
      .set('Authorization', auth(token));

  const outgoing = (token: string, query = '') =>
    request(server())
      .get(`/v1/social/friend-requests/outgoing${query}`)
      .set('Authorization', auth(token));

  const act = (token: string, requestId: string, action: 'accept' | 'reject' | 'cancel') =>
    request(server())
      .post(`/v1/social/friend-requests/${requestId}/${action}`)
      .set('Authorization', auth(token));

  const friends = (token: string, query = '') =>
    request(server()).get(`/v1/social/friends${query}`).set('Authorization', auth(token));

  const removeFriend = (token: string, socialId: string) =>
    request(server())
      .post('/v1/social/friends/remove')
      .set('Authorization', auth(token))
      .send({ socialId });

  const setPrivacy = (token: string, body: object) =>
    request(server()).patch('/v1/social/me/privacy').set('Authorization', auth(token)).send(body);

  const disableSocial = (token: string) =>
    request(server()).post('/v1/social/me/disable').set('Authorization', auth(token));

  const enableSocial = (token: string) =>
    request(server()).post('/v1/social/me/enable').set('Authorization', auth(token));

  /** A e B ativos e amigos, pelo caminho real: lookup → envio → aceite. */
  const makeFriends = async () => {
    const a = await activate(ACCOUNTS.A);
    const b = await activate(ACCOUNTS.B);
    await send(ACCOUNTS.A.token, b.socialId);
    const pending = await incoming(ACCOUNTS.B.token);
    const requestId = pending.body.requests[0].requestId as string;
    const accepted = await act(ACCOUNTS.B.token, requestId, 'accept');
    expect(accepted.status).toBe(200);
    return { a, b, requestId };
  };

  // ------------------------------------------------------------------ autenticação e perfil

  describe('nenhuma rota do grafo é pública', () => {
    const routes: Array<[string, () => request.Test]> = [
      [
        'POST /v1/social/friends/lookup',
        () =>
          request(server()).post('/v1/social/friends/lookup').send({ friendCode: 'SPK-AAAAAAAA' }),
      ],
      ['GET /v1/social/friends', () => request(server()).get('/v1/social/friends')],
      [
        'POST /v1/social/friends/remove',
        () => request(server()).post('/v1/social/friends/remove').send({ socialId: 'x' }),
      ],
      [
        'POST /v1/social/friend-requests',
        () => request(server()).post('/v1/social/friend-requests').send({ socialId: 'x' }),
      ],
      [
        'GET /v1/social/friend-requests/incoming',
        () => request(server()).get('/v1/social/friend-requests/incoming'),
      ],
      [
        'GET /v1/social/friend-requests/outgoing',
        () => request(server()).get('/v1/social/friend-requests/outgoing'),
      ],
      [
        'POST /v1/social/friend-requests/:id/accept',
        () => request(server()).post('/v1/social/friend-requests/qualquer/accept'),
      ],
      [
        'POST /v1/social/friend-requests/:id/reject',
        () => request(server()).post('/v1/social/friend-requests/qualquer/reject'),
      ],
      [
        'POST /v1/social/friend-requests/:id/cancel',
        () => request(server()).post('/v1/social/friend-requests/qualquer/cancel'),
      ],
    ];

    it.each(routes)('%s exige Firebase ID Token', async (_name, call) => {
      const response = await call();
      expect(response.status).toBe(401);
      expect(response.body.error.code).toBe('UNAUTHENTICATED');
    });
  });

  it('conta sem perfil social não alcança o grafo', async () => {
    // Autenticar não ativa Social (T17.0), e o grafo pertence ao perfil — não à conta.
    const responses = [
      await lookup(ACCOUNTS.A.token, 'SPK-AAAAAAAA'),
      await friends(ACCOUNTS.A.token),
      await incoming(ACCOUNTS.A.token),
      await outgoing(ACCOUNTS.A.token),
      await send(ACCOUNTS.A.token, 'qualquer'),
    ];
    for (const response of responses) {
      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('SOCIAL_NOT_ENABLED');
    }
  });

  it('perfil desativado suspende o grafo inteiro — inclusive a leitura', async () => {
    await makeFriends();
    await disableSocial(ACCOUNTS.A.token);

    for (const response of [
      await friends(ACCOUNTS.A.token),
      await incoming(ACCOUNTS.A.token),
      await lookup(ACCOUNTS.A.token, 'SPK-AAAAAAAA'),
    ]) {
      expect(response.status).toBe(409);
      expect(response.body.error.code).toBe('SOCIAL_PROFILE_DISABLED');
    }

    // E reativar devolve tudo: a amizade nunca foi apagada.
    await enableSocial(ACCOUNTS.A.token);
    const restored = await friends(ACCOUNTS.A.token);
    expect(restored.status).toBe(200);
    expect(restored.body.friends).toHaveLength(1);
  });

  // ------------------------------------------------------------------------------ lookup

  describe('lookup por friendCode', () => {
    it('encontra pelo código exato e devolve só o preview mínimo', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);

      const response = await lookup(ACCOUNTS.A.token, b.friendCode);

      expect(response.status).toBe(200);
      expect(response.body).toEqual({
        result: 'FOUND',
        profile: { socialId: b.socialId, displayName: 'João' },
        relationship: 'NONE',
        canSendFriendRequest: true,
      });
    });

    it('normaliza a entrada: minúsculas, sem hífen e com espaços chegam ao mesmo perfil', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      const random = b.friendCode.slice(4);

      const variants = [
        b.friendCode.toLowerCase(),
        b.friendCode.replace('-', ''),
        ` SPK ${random.slice(0, 4)} ${random.slice(4)} `,
      ];

      for (const variant of variants) {
        const response = await lookup(ACCOUNTS.A.token, variant);
        expect(response.body.result).toBe('FOUND');
        expect(response.body.profile.socialId).toBe(b.socialId);
      }
    });

    it('código inexistente, malformado e de perfil desativado dão a MESMA resposta', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      await disableSocial(ACCOUNTS.B.token);

      const inexistent = await lookup(ACCOUNTS.A.token, 'SPK-ZZZZZZZZ');
      const malformed = await lookup(ACCOUNTS.A.token, 'nada disso');
      const disabled = await lookup(ACCOUNTS.A.token, b.friendCode);

      // A igualdade é o ponto: distinguir qualquer um dos três transformaria a rota num oráculo
      // de existência — e o desativado é justamente quem não quer ser encontrado.
      expect(inexistent.body).toEqual({ result: 'NOT_FOUND' });
      expect(malformed.body).toEqual({ result: 'NOT_FOUND' });
      expect(disabled.body).toEqual({ result: 'NOT_FOUND' });
      expect(inexistent.status).toBe(200);
      expect(malformed.status).toBe(200);
      expect(disabled.status).toBe(200);
    });

    it('o próprio código responde SELF, e sem preview', async () => {
      const a = await activate(ACCOUNTS.A);

      const response = await lookup(ACCOUNTS.A.token, a.friendCode);

      expect(response.body).toEqual({ result: 'SELF' });
    });

    it('quem desligou pedidos aparece, mas não pode receber convite', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      await setPrivacy(ACCOUNTS.B.token, { friendRequestsEnabled: false });

      const response = await lookup(ACCOUNTS.A.token, b.friendCode);

      expect(response.body.result).toBe('FOUND');
      expect(response.body.canSendFriendRequest).toBe(false);

      // E a dica de UI não é a autoridade: o envio é recusado pelo servidor.
      const attempt = await send(ACCOUNTS.A.token, b.socialId);
      expect(attempt.status).toBe(403);
      expect(attempt.body.error.code).toBe('FRIEND_REQUESTS_DISABLED');
    });

    it('a relação já existente aparece no resultado', async () => {
      const { a, b } = await makeFriends();

      const fromA = await lookup(ACCOUNTS.A.token, b.friendCode);
      const fromB = await lookup(ACCOUNTS.B.token, a.friendCode);

      expect(fromA.body.relationship).toBe('FRIENDS');
      expect(fromA.body.canSendFriendRequest).toBe(false);
      expect(fromB.body.relationship).toBe('FRIENDS');
    });

    it('pedido pendente aparece como OUTGOING de um lado e INCOMING do outro', async () => {
      const a = await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      await send(ACCOUNTS.A.token, b.socialId);

      expect((await lookup(ACCOUNTS.A.token, b.friendCode)).body.relationship).toBe(
        'OUTGOING_PENDING',
      );
      expect((await lookup(ACCOUNTS.B.token, a.friendCode)).body.relationship).toBe(
        'INCOMING_PENDING',
      );
    });

    it('varrer códigos custa caro: o teto próprio do lookup responde 429', async () => {
      await activate(ACCOUNTS.A);

      const limit = SOCIAL_FRIEND_RATE_LIMIT.lookup.maxRequestsPerWindow;
      for (let i = 0; i < limit; i += 1) {
        expect((await lookup(ACCOUNTS.A.token, 'SPK-ZZZZZZZZ')).status).toBe(200);
      }

      const blocked = await lookup(ACCOUNTS.A.token, 'SPK-ZZZZZZZZ');
      expect(blocked.status).toBe(429);
      expect(blocked.body.error.code).toBe('SOCIAL_RATE_LIMITED');

      // O teto é por conta, e não por IP: a conta B continua funcionando normalmente.
      await activate(ACCOUNTS.B);
      expect((await lookup(ACCOUNTS.B.token, 'SPK-ZZZZZZZZ')).status).toBe(200);
    });

    it('não existe busca por nome, por e-mail nem listagem de perfis', async () => {
      await activate(ACCOUNTS.A);
      await activate(ACCOUNTS.B);

      // Nenhuma dessas rotas existe — e o teste é o que impede alguém de "só adicionar" uma.
      const attempts = [
        await request(server())
          .get('/v1/social/users?q=João')
          .set('Authorization', auth(ACCOUNTS.A.token)),
        await request(server())
          .get('/v1/social/profiles')
          .set('Authorization', auth(ACCOUNTS.A.token)),
        await request(server())
          .post('/v1/social/friends/lookup')
          .set('Authorization', auth(ACCOUNTS.A.token))
          .send({ email: 'b@example.com' }),
        await request(server())
          .post('/v1/social/friends/lookup')
          .set('Authorization', auth(ACCOUNTS.A.token))
          .send({ displayName: 'João' }),
      ];

      expect(attempts[0].status).toBe(404);
      expect(attempts[1].status).toBe(404);
      // O corpo com e-mail é recusado por nome, e não ignorado em silêncio.
      expect(attempts[2].status).toBe(400);
      expect(attempts[3].status).toBe(400);
    });
  });

  // ------------------------------------------------------------------------------ pedidos

  describe('envio de pedido', () => {
    it('cria um pedido pendente que aparece nas duas listas', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);

      const response = await send(ACCOUNTS.A.token, b.socialId);

      expect(response.status).toBe(200);
      expect(response.body.result).toBe('REQUEST_CREATED');
      expect(response.body.request.profile).toEqual({ socialId: b.socialId, displayName: 'João' });

      const received = await incoming(ACCOUNTS.B.token);
      expect(received.body.total).toBe(1);
      expect(received.body.requests[0].profile.displayName).toBe('Igor');
      expect(received.body.requests[0].direction).toBe('INCOMING');

      const sent = await outgoing(ACCOUNTS.A.token);
      expect(sent.body.requests[0].profile.displayName).toBe('João');
      expect(sent.body.requests[0].direction).toBe('OUTGOING');
    });

    it('reenviar não duplica: o mesmo pedido volta', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);

      const first = await send(ACCOUNTS.A.token, b.socialId);
      const second = await send(ACCOUNTS.A.token, b.socialId);
      const third = await send(ACCOUNTS.A.token, b.socialId);

      // O caso real é o retry depois de uma resposta perdida (§108): a situação lógica é a que o
      // cliente queria, e por isso é sucesso — não erro.
      expect(second.status).toBe(200);
      expect(second.body.result).toBe('REQUEST_ALREADY_PENDING');
      expect(second.body.request.requestId).toBe(first.body.request.requestId);
      expect(third.body.request.requestId).toBe(first.body.request.requestId);
      expect((await incoming(ACCOUNTS.B.token)).body.total).toBe(1);
    });

    it('pedido para si mesmo é impossível', async () => {
      const a = await activate(ACCOUNTS.A);

      const response = await send(ACCOUNTS.A.token, a.socialId);

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('SELF_FRIEND_REQUEST');
    });

    it('perfil desativado e socialId inexistente dão a mesma resposta', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      await disableSocial(ACCOUNTS.B.token);

      const toDisabled = await send(ACCOUNTS.A.token, b.socialId);
      const toNobody = await send(ACCOUNTS.A.token, '00000000-0000-4000-8000-000000000000');

      expect(toDisabled.status).toBe(404);
      expect(toNobody.status).toBe(404);
      expect(toDisabled.body.error.code).toBe('SOCIAL_PROFILE_NOT_FOUND');
      expect(toNobody.body.error.code).toBe('SOCIAL_PROFILE_NOT_FOUND');
    });

    it('pedido depois de já serem amigos é ALREADY_FRIENDS, e não cria nada', async () => {
      const { b } = await makeFriends();

      const response = await send(ACCOUNTS.A.token, b.socialId);

      expect(response.status).toBe(409);
      expect(response.body.error.code).toBe('ALREADY_FRIENDS');
      expect((await incoming(ACCOUNTS.B.token)).body.total).toBe(0);
    });

    it('o teto de envio impede que um laço vire centenas de convites', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);

      const limit = SOCIAL_FRIEND_RATE_LIMIT.sendRequest.maxRequestsPerWindow;
      for (let i = 0; i < limit; i += 1) {
        expect((await send(ACCOUNTS.A.token, b.socialId)).status).toBe(200);
      }

      const blocked = await send(ACCOUNTS.A.token, b.socialId);
      expect(blocked.status).toBe(429);
      expect(blocked.body.error.code).toBe('SOCIAL_RATE_LIMITED');
    });
  });

  describe('pedido cruzado', () => {
    it('A→B pendente e B→A enviado criam a amizade na hora, sem duplicata', async () => {
      const a = await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      await send(ACCOUNTS.A.token, b.socialId);

      const crossed = await send(ACCOUNTS.B.token, a.socialId);

      expect(crossed.status).toBe(200);
      expect(crossed.body.result).toBe('FRIENDSHIP_CREATED');
      expect(crossed.body.friend).toEqual({
        socialId: a.socialId,
        displayName: 'Igor',
        friendsSince: expect.any(Number),
      });

      // Nenhum pendente sobra, dos dois lados, e a amizade é uma só.
      expect((await incoming(ACCOUNTS.A.token)).body.total).toBe(0);
      expect((await incoming(ACCOUNTS.B.token)).body.total).toBe(0);
      expect((await outgoing(ACCOUNTS.A.token)).body.total).toBe(0);
      expect((await outgoing(ACCOUNTS.B.token)).body.total).toBe(0);
      expect((await friends(ACCOUNTS.A.token)).body.total).toBe(1);
      expect((await friends(ACCOUNTS.B.token)).body.total).toBe(1);
    });
  });

  // ------------------------------------------------------------------------------ respostas

  describe('aceitar, recusar e cancelar', () => {
    const pendingRequestId = async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      const sent = await send(ACCOUNTS.A.token, b.socialId);
      return sent.body.request.requestId as string;
    };

    it('quem enviou não pode aceitar o próprio pedido', async () => {
      const requestId = await pendingRequestId();

      const response = await act(ACCOUNTS.A.token, requestId, 'accept');

      // Se pudesse, "pedido" não significaria nada: a amizade deixaria de exigir consentimento.
      expect(response.status).toBe(403);
      expect(response.body.error.code).toBe('NOT_REQUEST_RECIPIENT');
      expect((await friends(ACCOUNTS.A.token)).body.total).toBe(0);
    });

    it('quem recebeu não pode cancelar; quem enviou não pode recusar', async () => {
      const requestId = await pendingRequestId();

      const cancelByRecipient = await act(ACCOUNTS.B.token, requestId, 'cancel');
      const rejectBySender = await act(ACCOUNTS.A.token, requestId, 'reject');

      expect(cancelByRecipient.body.error.code).toBe('NOT_REQUEST_SENDER');
      expect(rejectBySender.body.error.code).toBe('NOT_REQUEST_RECIPIENT');
    });

    it('aceitar duas vezes é idempotente', async () => {
      const requestId = await pendingRequestId();

      const first = await act(ACCOUNTS.B.token, requestId, 'accept');
      const second = await act(ACCOUNTS.B.token, requestId, 'accept');

      expect(first.body.result).toBe('ACCEPTED');
      // A resposta perdida e o toque duplo levam aqui: o estado que o cliente queria existe.
      expect(second.status).toBe(200);
      expect(second.body.result).toBe('ALREADY_FRIENDS');
      expect(second.body.friend.socialId).toBe(first.body.friend.socialId);
      expect((await friends(ACCOUNTS.B.token)).body.total).toBe(1);
    });

    it('recusar duas vezes é idempotente e não deixa pendência', async () => {
      const requestId = await pendingRequestId();

      const first = await act(ACCOUNTS.B.token, requestId, 'reject');
      const second = await act(ACCOUNTS.B.token, requestId, 'reject');

      expect(first.body).toEqual({ result: 'REJECTED' });
      expect(second.body).toEqual({ result: 'ALREADY_REJECTED' });
      expect((await incoming(ACCOUNTS.B.token)).body.total).toBe(0);
      expect((await outgoing(ACCOUNTS.A.token)).body.total).toBe(0);
      expect((await friends(ACCOUNTS.A.token)).body.total).toBe(0);
    });

    it('cancelar duas vezes é idempotente e some da lista do destinatário', async () => {
      const requestId = await pendingRequestId();

      const first = await act(ACCOUNTS.A.token, requestId, 'cancel');
      const second = await act(ACCOUNTS.A.token, requestId, 'cancel');

      expect(first.body).toEqual({ result: 'CANCELLED' });
      expect(second.body).toEqual({ result: 'ALREADY_CANCELLED' });
      expect((await incoming(ACCOUNTS.B.token)).body.total).toBe(0);
    });

    it('cancelar e aceitar: quem chegou primeiro decide, e o outro recebe um conflito honesto', async () => {
      const requestId = await pendingRequestId();

      await act(ACCOUNTS.A.token, requestId, 'cancel');
      const lateAccept = await act(ACCOUNTS.B.token, requestId, 'accept');

      // Nunca "cancelado + amizade criada escondida": ou um, ou o outro.
      expect(lateAccept.status).toBe(409);
      expect(lateAccept.body.error.code).toBe('FRIEND_REQUEST_NOT_PENDING');
      expect((await friends(ACCOUNTS.A.token)).body.total).toBe(0);
      expect((await friends(ACCOUNTS.B.token)).body.total).toBe(0);
    });

    it('aceitar depois de recusar não ressuscita o pedido', async () => {
      const requestId = await pendingRequestId();

      await act(ACCOUNTS.B.token, requestId, 'reject');
      const late = await act(ACCOUNTS.B.token, requestId, 'accept');

      expect(late.status).toBe(409);
      expect(late.body.error.code).toBe('FRIEND_REQUEST_NOT_PENDING');
      expect((await friends(ACCOUNTS.B.token)).body.total).toBe(0);
    });

    it('recusado não impede um pedido novo mais tarde', async () => {
      const requestId = await pendingRequestId();
      await act(ACCOUNTS.B.token, requestId, 'reject');

      const b = (
        await request(server()).get('/v1/social/me').set('Authorization', auth(ACCOUNTS.B.token))
      ).body.profile;
      const again = await send(ACCOUNTS.A.token, b.socialId);

      // O índice único é parcial (`WHERE status = 'PENDING'`): a linha terminal não ocupa a vaga.
      expect(again.body.result).toBe('REQUEST_CREATED');
      expect(again.body.request.requestId).not.toBe(requestId);
    });

    it('requestId inexistente responde NOT_FOUND', async () => {
      await activate(ACCOUNTS.A);
      const response = await act(ACCOUNTS.A.token, 'nao-existe', 'accept');
      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('FRIEND_REQUEST_NOT_FOUND');
    });
  });

  // ------------------------------------------------------------------------------ conta C

  describe('a conta C não participa de nada entre A e B', () => {
    it('não aceita, não recusa e não cancela — e nem descobre que o pedido existe', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      await activate(ACCOUNTS.C);
      const requestId = (await send(ACCOUNTS.A.token, b.socialId)).body.request.requestId;

      for (const action of ['accept', 'reject', 'cancel'] as const) {
        const response = await act(ACCOUNTS.C.token, requestId, action);
        // "Não existe", e não "não é seu": a segunda resposta ensinaria a C que ela acertou um
        // pedido entre outras duas pessoas.
        expect(response.status).toBe(404);
        expect(response.body.error.code).toBe('FRIEND_REQUEST_NOT_FOUND');
      }

      // E o pedido continua pendente, intocado.
      expect((await incoming(ACCOUNTS.B.token)).body.total).toBe(1);
      expect((await incoming(ACCOUNTS.C.token)).body.total).toBe(0);
      expect((await outgoing(ACCOUNTS.C.token)).body.total).toBe(0);
    });

    it('não desfaz a amizade de A com B', async () => {
      const { a, b } = await makeFriends();
      await activate(ACCOUNTS.C);

      const removeA = await removeFriend(ACCOUNTS.C.token, a.socialId);
      const removeB = await removeFriend(ACCOUNTS.C.token, b.socialId);

      expect(removeA.status).toBe(404);
      expect(removeB.status).toBe(404);
      expect(removeA.body.error.code).toBe('FRIENDSHIP_NOT_FOUND');
      expect((await friends(ACCOUNTS.A.token)).body.total).toBe(1);
      expect((await friends(ACCOUNTS.B.token)).body.total).toBe(1);
    });

    it('só vê a própria lista de amigos e os próprios pedidos', async () => {
      await makeFriends();
      await activate(ACCOUNTS.C);

      expect((await friends(ACCOUNTS.C.token)).body.friends).toEqual([]);
      expect((await incoming(ACCOUNTS.C.token)).body.requests).toEqual([]);
      expect((await outgoing(ACCOUNTS.C.token)).body.requests).toEqual([]);
    });
  });

  // ------------------------------------------------------------------------------ amizade

  describe('amizade bilateral', () => {
    it('o fluxo completo A → B: lookup, pedido, aceite e as duas listas', async () => {
      const a = await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);

      const found = await lookup(ACCOUNTS.A.token, b.friendCode);
      expect(found.body.result).toBe('FOUND');

      const sent = await send(ACCOUNTS.A.token, found.body.profile.socialId);
      expect(sent.body.result).toBe('REQUEST_CREATED');

      const received = await incoming(ACCOUNTS.B.token);
      expect(received.body.requests).toHaveLength(1);

      const accepted = await act(ACCOUNTS.B.token, received.body.requests[0].requestId, 'accept');
      expect(accepted.body.result).toBe('ACCEPTED');

      // Bilateral: a mesma amizade aparece nas duas listas, imediatamente. Sem atraso, sem
      // consistência eventual — é uma transação de SQLite.
      const friendsOfA = await friends(ACCOUNTS.A.token);
      const friendsOfB = await friends(ACCOUNTS.B.token);
      expect(friendsOfA.body.friends.map((f: { socialId: string }) => f.socialId)).toEqual([
        b.socialId,
      ]);
      expect(friendsOfB.body.friends.map((f: { socialId: string }) => f.socialId)).toEqual([
        a.socialId,
      ]);
      expect(friendsOfA.body.friends[0].friendsSince).toBe(friendsOfB.body.friends[0].friendsSince);

      // E o pendente sai da lista na hora.
      expect((await incoming(ACCOUNTS.B.token)).body.total).toBe(0);
      expect((await outgoing(ACCOUNTS.A.token)).body.total).toBe(0);
    });

    it('qualquer um do par desfaz, e a lista dos dois reflete', async () => {
      const { a, b } = await makeFriends();

      const removed = await removeFriend(ACCOUNTS.B.token, a.socialId);

      expect(removed.body).toEqual({ result: 'REMOVED' });
      expect((await friends(ACCOUNTS.A.token)).body.friends).toEqual([]);
      expect((await friends(ACCOUNTS.B.token)).body.friends).toEqual([]);
      // Remover não é bloquear: o perfil continua descobrível pelo código.
      expect((await lookup(ACCOUNTS.A.token, b.friendCode)).body.result).toBe('FOUND');
    });

    it('depois de desfazer, dá para ser amigo de novo', async () => {
      const { a, b } = await makeFriends();
      await removeFriend(ACCOUNTS.A.token, b.socialId);

      const again = await send(ACCOUNTS.A.token, b.socialId);
      const received = await incoming(ACCOUNTS.B.token);
      const accepted = await act(ACCOUNTS.B.token, received.body.requests[0].requestId, 'accept');

      expect(again.body.result).toBe('REQUEST_CREATED');
      expect(accepted.body.result).toBe('ACCEPTED');
      expect((await friends(ACCOUNTS.A.token)).body.friends[0].socialId).toBe(b.socialId);
      expect((await friends(ACCOUNTS.B.token)).body.friends[0].socialId).toBe(a.socialId);
    });

    it('desfazer o que não existe é FRIENDSHIP_NOT_FOUND, e não 500', async () => {
      const { b } = await makeFriends();
      await removeFriend(ACCOUNTS.A.token, b.socialId);

      const again = await removeFriend(ACCOUNTS.A.token, b.socialId);

      expect(again.status).toBe(404);
      expect(again.body.error.code).toBe('FRIENDSHIP_NOT_FOUND');
    });

    it('amigo com Social desativado some da lista e volta ao reativar', async () => {
      const { b } = await makeFriends();

      await disableSocial(ACCOUNTS.B.token);
      const hidden = await friends(ACCOUNTS.A.token);
      await enableSocial(ACCOUNTS.B.token);
      const back = await friends(ACCOUNTS.A.token);

      // A amizade **não** foi apagada: ela ficou invisível enquanto não havia perfil para mostrar.
      expect(hidden.body.friends).toEqual([]);
      expect(hidden.body.total).toBe(0);
      expect(back.body.friends[0].socialId).toBe(b.socialId);
    });

    it('pedido pendente com a outra ponta desativada fica suspenso, e não cancelado', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      const requestId = (await send(ACCOUNTS.A.token, b.socialId)).body.request.requestId;

      await disableSocial(ACCOUNTS.B.token);
      const suspended = await outgoing(ACCOUNTS.A.token);
      const inaccessible = await act(ACCOUNTS.A.token, requestId, 'cancel');
      await enableSocial(ACCOUNTS.B.token);

      expect(suspended.body.requests).toEqual([]);
      expect(inaccessible.status).toBe(404);
      // Suspenso, e não resolvido por conta própria: reativar devolve o pedido inteiro.
      expect((await incoming(ACCOUNTS.B.token)).body.requests[0].requestId).toBe(requestId);
    });
  });

  // ------------------------------------------------------------------------------ listagens

  describe('listagens', () => {
    it('amigos vêm em ordem alfabética estável', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      const c = await activate(ACCOUNTS.C);

      for (const target of [c, b]) {
        await send(ACCOUNTS.A.token, target.socialId);
      }
      for (const account of [ACCOUNTS.B, ACCOUNTS.C]) {
        const received = await incoming(account.token);
        await act(account.token, received.body.requests[0].requestId, 'accept');
      }

      const list = await friends(ACCOUNTS.A.token);
      expect(list.body.friends.map((f: { displayName: string }) => f.displayName)).toEqual([
        'Jonathas',
        'João',
      ]);
    });

    it('pedidos vêm dos mais recentes para os mais antigos', async () => {
      const b = await activate(ACCOUNTS.B);
      await activate(ACCOUNTS.A);
      await activate(ACCOUNTS.C);

      await send(ACCOUNTS.A.token, b.socialId);
      await send(ACCOUNTS.C.token, b.socialId);

      const received = await incoming(ACCOUNTS.B.token);
      expect(
        received.body.requests.map(
          (r: { profile: { displayName: string } }) => r.profile.displayName,
        ),
      ).toEqual(['Jonathas', 'Igor']);
    });

    it('a lista tem limite e cursor, e nunca devolve tudo por acidente', async () => {
      await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);
      const c = await activate(ACCOUNTS.C);
      for (const target of [b, c]) {
        await send(ACCOUNTS.A.token, target.socialId);
      }
      for (const account of [ACCOUNTS.B, ACCOUNTS.C]) {
        const received = await incoming(account.token);
        await act(account.token, received.body.requests[0].requestId, 'accept');
      }

      const firstPage = await friends(ACCOUNTS.A.token, '?limit=1');
      expect(firstPage.body.friends).toHaveLength(1);
      expect(firstPage.body.total).toBe(2);
      expect(firstPage.body.nextCursor).toEqual(expect.any(String));

      const secondPage = await friends(
        ACCOUNTS.A.token,
        `?limit=1&cursor=${encodeURIComponent(firstPage.body.nextCursor)}`,
      );
      expect(secondPage.body.friends).toHaveLength(1);
      expect(secondPage.body.friends[0].socialId).not.toBe(firstPage.body.friends[0].socialId);
      // A última página não oferece continuação — é assim que o cliente sabe que acabou.
      expect(secondPage.body.nextCursor).toBeUndefined();
    });

    it('limite fora de forma é recusado; acima do teto é cortado em silêncio', async () => {
      await activate(ACCOUNTS.A);

      for (const bad of ['zero', '0', '-3', '1.5']) {
        const response = await friends(ACCOUNTS.A.token, `?limit=${bad}`);
        expect(response.status).toBe(400);
        expect(response.body.error.code).toBe('INVALID_FRIEND_REQUEST');
      }

      // Pedir 500 é querer a lista, não um erro: o servidor entrega o que sustenta.
      const big = await friends(ACCOUNTS.A.token, `?limit=${SOCIAL_LIST_PAGE.maxLimit * 5}`);
      expect(big.status).toBe(200);
    });

    it('cursor inventado é recusado', async () => {
      await activate(ACCOUNTS.A);
      const response = await friends(ACCOUNTS.A.token, '?cursor=isso-nao-e-um-cursor');
      expect(response.status).toBe(400);
    });
  });

  // ------------------------------------------------------------------------------ vazamento

  describe('nenhuma resposta do grafo carrega identidade privada', () => {
    it('uid, e-mail e friendCode não aparecem em resposta nenhuma', async () => {
      const a = await activate(ACCOUNTS.A);
      const b = await activate(ACCOUNTS.B);

      const responses = [
        await lookup(ACCOUNTS.A.token, b.friendCode),
        await send(ACCOUNTS.A.token, b.socialId),
        await incoming(ACCOUNTS.B.token),
        await outgoing(ACCOUNTS.A.token),
      ];
      const requestId = (await incoming(ACCOUNTS.B.token)).body.requests[0].requestId;
      responses.push(await act(ACCOUNTS.B.token, requestId, 'accept'));
      responses.push(await friends(ACCOUNTS.A.token));
      responses.push(await friends(ACCOUNTS.B.token));
      responses.push(await removeFriend(ACCOUNTS.A.token, b.socialId));

      const body = JSON.stringify(responses.map((response) => response.body));

      for (const forbidden of [
        ACCOUNTS.A.uid,
        ACCOUNTS.B.uid,
        ACCOUNTS.A.email,
        ACCOUNTS.B.email,
        a.friendCode,
        b.friendCode,
        'ownerUid',
        'firebaseUid',
        '"uid"',
      ]) {
        expect(body).not.toContain(forbidden);
      }

      // E o que **deve** estar lá continua estando — senão o teste passaria com respostas vazias.
      expect(body).toContain(b.socialId);
      expect(body).toContain('João');
    });

    it('a lista de amigos não devolve o friendCode de ninguém', async () => {
      const { b } = await makeFriends();

      const list = await friends(ACCOUNTS.A.token);

      // Depois da amizade criada, quem identifica é o `socialId`. Uma lista de amigos com código
      // de convite de todo mundo seria uma lista redistribuível que ninguém escolheu publicar.
      expect(list.body.friends[0]).toEqual({
        socialId: b.socialId,
        displayName: 'João',
        friendsSince: expect.any(Number),
      });
    });
  });
});
