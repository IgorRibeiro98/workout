import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { PostgresService } from '../src/database/postgres.service';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
} as const;

/**
 * T17.13.1 §19–§21 e §67 — a verificação de tombstone falha **fechada**.
 *
 * ## O defeito
 *
 * `BearerAuthGuard.isTombstoned` era:
 *
 * ```ts
 * if (!this.sqlite || !this.sqlite.isOpen) return false;
 * try { ...SELECT... } catch { return false; }
 * ```
 *
 * E `false` ali significa **conta ativa**. Um banco fechado, uma tabela ausente ou qualquer erro
 * na consulta faziam uma conta excluída voltar a atravessar o guard e a escrever no servidor —
 * exatamente durante o incidente em que ninguém está olhando para isso. A falha era silenciosa nos
 * dois sentidos: nada no log dizia que a verificação não tinha acontecido, e quem tivesse um token
 * de conta excluída não precisava fazer nada além de esperar.
 *
 * ## A regra agora
 *
 * ```text
 * conta ativa      + banco saudável  →  segue
 * conta com lápide + banco saudável  →  403 ACCOUNT_DELETED
 * não dá para avaliar                →  503 ACCOUNT_STATE_UNAVAILABLE
 * ```
 *
 * O custo de fail-closed é 503 numa janela que já está degradada; o custo de fail-open é a
 * garantia inteira.
 */
describe('T17.13.1 — tombstone do Auth Guard falha fechado', () => {
  let temp: TempDb;
  let app: INestApplication | undefined;
  let verifier: FakeAuthTokenVerifier;

  const server = () => app!.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  beforeEach(async () => {
    temp = createTempDb();
    verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    app = await createTestApp(
      configFor(temp.path, {
        DELETION_TOMBSTONES_FILE_PATH: join(temp.directory, 'deletion_tombstones.tsv'),
        ACCOUNT_DELETION_HMAC_KEY: 'chave-hmac-de-teste-para-exclusao-de-conta',
        SOCIAL_MEDIA_ROOT: join(temp.directory, 'media'),
      }),
      verifier,
    );
    await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ displayName: ACCOUNTS.A.name })
      .expect(200);
  });

  afterEach(async () => {
    await app?.close();
    app = undefined;
    temp.cleanup();
  });

  it('conta ativa com banco saudável passa normalmente (§21)', async () => {
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
  });

  it('conta com lápide continua recebendo 403 ACCOUNT_DELETED (§21)', async () => {
    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    const res = await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);
    expect(res.body.error.code).toBe('ACCOUNT_DELETED');
  });

  it('o SELECT do tombstone lançando ⇒ 503, e nunca "conta ativa" (§19/§21)', async () => {
    // A tabela desaparece debaixo do guard. O SELECT passa a lançar erro de tabela inexistente.
    await app!.get(PostgresService).query(`DROP TABLE account_deletion_tombstones`);

    const res = await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(503);
    expect(res.body.error.code).toBe('ACCOUNT_STATE_UNAVAILABLE');
  });

  it('conexão fechada ⇒ 503 (§19/§21)', async () => {
    // O banco fecha sob o processo: é o que acontece durante um shutdown, ou se o serviço nunca
    // chegou a abrir. Antes, isto era `return false` — conta ativa.
    await app!.get(PostgresService).close();

    const res = await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(503);
    expect(res.body.error.code).toBe('ACCOUNT_STATE_UNAVAILABLE');
  });

  it('as rotas de exclusão continuam alcançáveis por uma conta com lápide (§20)', async () => {
    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    // A única superfície que sobra: consultar e reexecutar a própria exclusão.
    const status = await request(server())
      .get('/v1/account/deletion-status')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(status.body.status).toBe('DELETED');

    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
  });

  it('a isenção das rotas de conta não vaza por query string nem por prefixo parecido (§20)', async () => {
    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    // O truque histórico (T17.10 §85/§118): a comparação era sobre a URL inteira, e uma query
    // string bastava para a rota parecer uma rota de conta.
    const bypass = await request(server())
      .get('/v1/social/me?x=/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);
    expect(bypass.body.error.code).toBe('ACCOUNT_DELETED');

    const hashTrick = await request(server())
      .get('/v1/social/me?redirect=%2Fv1%2Faccount%2Fdeletion-status')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);
    expect(hashTrick.body.error.code).toBe('ACCOUNT_DELETED');
  }, 60_000);

  it('mesmo quando o banco não responde, a rota de exclusão continua isenta (§20)', async () => {
    // A isenção é decidida pelo **caminho**, antes de qualquer consulta: uma conta em processo de
    // exclusão precisa conseguir consultar o próprio estado mesmo com o banco degradado. O que
    // ela não pode é usar o resto da API — e isso o teste de 503 acima já fixa.
    await app!.get(PostgresService).close();

    // Não é 503 do guard: o pedido chega ao controller, que falha por outro motivo (o banco).
    // O que importa aqui é que o guard não o converteu em 503 antes de olhar o caminho.
    const res = await request(server())
      .get('/v1/account/deletion-status')
      .set('Authorization', auth(ACCOUNTS.A.token));
    expect(res.body?.error?.code).not.toBe('ACCOUNT_STATE_UNAVAILABLE');
  });
});
