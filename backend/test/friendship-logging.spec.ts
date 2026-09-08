import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';

const A = { token: 'token-secreto-da-conta-a-xyz789', uid: 'uid-completo-da-conta-a-1234' };
const B = { token: 'token-secreto-da-conta-b-abc123', uid: 'uid-completo-da-conta-b-5678' };
const EMAIL_A = 'atleta.a@example.com';
const EMAIL_B = 'atleta.b@example.com';
const NAME_A = 'Igor Ribeiro Sobrenome';
const NAME_B = 'João Neto Sobrenome';

/**
 * O que o grafo social registra — e o que ele nunca registra (T17.1 §97).
 *
 * O log precisa responder "qual conta (por prefixo), qual operação, como terminou". Ele não pode
 * responder "quem é essa pessoa" nem "quem é amigo de quem por nome".
 *
 * O `friendCode` tem tratamento próprio pela mesma razão da T17.0, agora mais forte: com o lookup
 * existindo, um log que carregasse o código transformaria qualquer cópia de log numa lista de
 * convites válidos e utilizáveis — que é exatamente o que a rota de lookup consome.
 */
describe('Observabilidade do grafo social: metadata sim, identidade não', () => {
  let temp: TempDb;
  let app: INestApplication;
  let written: string[];
  let restore: () => void;

  beforeEach(async () => {
    temp = createTempDb();
    written = [];
    const original = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((chunk: string | Uint8Array, ...rest: unknown[]): boolean => {
      written.push(typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8'));
      return original(chunk as never, ...(rest as []));
    }) as typeof process.stdout.write;
    restore = () => {
      process.stdout.write = original;
    };

    app = await createTestApp(
      configFor(temp.path, { LOG_LEVEL: 'debug' }),
      new FakeAuthTokenVerifier()
        .accept(A.token, { uid: A.uid, email: EMAIL_A })
        .accept(B.token, { uid: B.uid, email: EMAIL_B }),
    );
  });

  afterEach(async () => {
    restore();
    await app?.close();
    temp.cleanup();
  });

  const logs = () => written.join('\n');

  /** Exercita todas as rotas do grafo, para o log conter tudo o que ele consegue conter. */
  const exerciseEveryRoute = async () => {
    const server = app.getHttpServer();
    const activate = (token: string, displayName: string) =>
      request(server)
        .post('/v1/social/me/activate')
        .set('Authorization', `Bearer ${token}`)
        .send({ displayName });

    const a = (await activate(A.token, NAME_A)).body.profile;
    const b = (await activate(B.token, NAME_B)).body.profile;

    await request(server)
      .post('/v1/social/friends/lookup')
      .set('Authorization', `Bearer ${A.token}`)
      .send({ friendCode: b.friendCode });
    await request(server)
      .post('/v1/social/friends/lookup')
      .set('Authorization', `Bearer ${A.token}`)
      .send({ friendCode: 'SPK-ZZZZZZZZ' });

    const sent = await request(server)
      .post('/v1/social/friend-requests')
      .set('Authorization', `Bearer ${A.token}`)
      .send({ socialId: b.socialId });
    const requestId = sent.body.request.requestId as string;

    await request(server)
      .get('/v1/social/friend-requests/incoming')
      .set('Authorization', `Bearer ${B.token}`);
    await request(server)
      .get('/v1/social/friend-requests/outgoing')
      .set('Authorization', `Bearer ${A.token}`);
    await request(server)
      .post(`/v1/social/friend-requests/${requestId}/accept`)
      .set('Authorization', `Bearer ${B.token}`);
    await request(server).get('/v1/social/friends').set('Authorization', `Bearer ${A.token}`);
    await request(server)
      .post('/v1/social/friends/remove')
      .set('Authorization', `Bearer ${A.token}`)
      .send({ socialId: b.socialId });

    return { a, b };
  };

  it('registra a operação e o desfecho de cada rota do grafo', async () => {
    await exerciseEveryRoute();

    for (const event of [
      'social.friends.lookup',
      'social.friends.request.sent',
      'social.friends.requests.listed',
      'social.friends.request.accepted',
      'social.friends.listed',
      'social.friends.removed',
    ]) {
      expect(logs()).toContain(event);
    }
    // Sem observabilidade nenhuma, os testes de ausência abaixo passariam vazios.
    expect(logs()).toContain('"result":"FOUND"');
    expect(logs()).toContain('"result":"NOT_FOUND"');
  });

  it('não registra friendCode, displayName, socialId, e-mail nem uid completo', async () => {
    const { a, b } = await exerciseEveryRoute();

    for (const secret of [
      a.friendCode,
      b.friendCode,
      a.socialId,
      b.socialId,
      NAME_A,
      NAME_B,
      EMAIL_A,
      EMAIL_B,
      A.uid,
      B.uid,
      A.token,
      B.token,
    ]) {
      expect({ secret, leaked: logs().includes(secret) }).toEqual({ secret, leaked: false });
    }

    // O prefixo de uid **é** permitido: ele correlaciona um relato de suporte com uma linha de log
    // sem identificar ninguém sozinho.
    expect(logs()).toContain(A.uid.slice(0, 6));
  });

  it('o corpo da requisição não vai para o log', async () => {
    await exerciseEveryRoute();

    // O corpo do lookup carrega um `friendCode` e o do envio um `socialId`: se algum caminho
    // registrasse `req.body`, os dois estariam aqui.
    expect(logs()).not.toContain('"friendCode"');
    expect(logs()).not.toContain('"displayName"');
  });
});
