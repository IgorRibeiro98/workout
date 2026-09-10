import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { AccountDeletionService } from '../src/modules/account-deletion/account-deletion.service';
import { AccountDeletionRepository } from '../src/modules/account-deletion/account-deletion.repository';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
} as const;

describe('Account Deletion & Disaster Recovery (T17.6)', () => {
  let temp: TempDb;
  let app: INestApplication | undefined;
  let verifier: FakeAuthTokenVerifier;
  let drFilePath: string;

  beforeEach(async () => {
    temp = createTempDb();
    drFilePath = join(temp.directory, 'deletion_tombstones.tsv');
    verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    const config = configFor(temp.path, {
      DELETION_TOMBSTONES_FILE_PATH: drFilePath,
      ACCOUNT_DELETION_HMAC_KEY: 'test-hmac-key-for-account-deletion-very-secret',
    });
    app = await createTestApp(config, verifier);
  });

  afterEach(async () => {
    await app?.close();
    app = undefined;
    temp.cleanup();
  });

  const server = () => app!.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  it('exclui conta completamente, limpa tabelas, grava tombstone e rejeita novas requisições com 403 ACCOUNT_DELETED', async () => {
    // 1. Cria perfil e configurações
    await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ displayName: ACCOUNTS.A.name })
      .expect(200);

    // 2. Chama DELETE /v1/account
    const delRes = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    expect(delRes.body.status).toBe('DELETED');

    // 3. Verifica que verifier recebeu deleteUser
    expect(verifier.deletedUids).toContain(ACCOUNTS.A.uid);

    // 4. Verifica que DR file foi preenchido
    expect(existsSync(drFilePath)).toBe(true);
    const drContent = readFileSync(drFilePath, 'utf8');
    expect(drContent).toContain('\t');

    // 5. Novas chamadas com token da conta A em qualquer rota são rejeitadas com 403 ACCOUNT_DELETED
    const rejectedRes = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ displayName: 'Alice Reloaded' })
      .expect(403);

    expect(rejectedRes.body.error.code).toBe('ACCOUNT_DELETED');

    // 6. Conta B continua funcionando normalmente (isolamento)
    await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ displayName: ACCOUNTS.B.name })
      .expect(200);
  }, 60_000);

  it('permite reconciliação DR para expurgar dados se um backup antigo for restaurado', async () => {
    // Cria dados para A
    await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ displayName: ACCOUNTS.A.name })
      .expect(200);

    const deletionService = app!.get(AccountDeletionService);
    const deletionRepo = app!.get(AccountDeletionRepository);

    // Calcula o hash de A
    const hashA = deletionService.hashUid(ACCOUNTS.A.uid);

    // Simula restore de snapshot onde A existia mas o DR log tinha a tombstone de A
    const purgedCount = await deletionService.reconcileTombstones(new Set([hashA]));
    expect(purgedCount).toBe(1);

    // Confirma que A agora está tombstoned
    expect(await deletionRepo.isTombstoned(hashA)).toBe(true);

    // E requisição de A é bloqueada com 403
    const blockedRes = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ displayName: 'Alice Reloaded' })
      .expect(403);
    expect(blockedRes.body.error.code).toBe('ACCOUNT_DELETED');
  }, 60_000);
});
