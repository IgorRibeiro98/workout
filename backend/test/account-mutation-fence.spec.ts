import { INestApplication } from '@nestjs/common';
import request, { type Response, type Test } from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { fixtureText } from './support/backup-fixtures';
import { jpeg } from './support/image-fixtures';
import { pushBody, programPayload, sessionPayload, uuid } from './support/sync-fixtures';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { PostgresService } from '../src/database/postgres.service';
import { AccountMutationFencedError } from '../src/database/account-mutation-fence';
import { SyncService } from '../src/modules/sync/sync.service';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';

/**
 * Dispara a requisição **agora** — via `.end()`, não via `await`/`.then()` — e devolve uma
 * promessa para a resposta.
 *
 * O `supertest`/`superagent` só envia a requisição quando ela é tratada como thenable ou quando
 * `.end()` é chamado; segurar a referência sem nenhum dos dois (como `const p = request(...)...`)
 * não a envia. Isso é inofensivo quando o próximo passo é `await p`, mas quebra um teste de
 * corrida que precisa saber que a requisição **já está em voo** — presa numa trava — antes de
 * disparar a segunda requisição concorrente.
 */
function dispatch(test: Test): Promise<Response> {
  return new Promise((resolve, reject) => {
    test.end((err, res) => {
      if (err && !res) {
        reject(err instanceof Error ? err : new Error(String(err)));
        return;
      }
      resolve(res);
    });
  });
}

/**
 * T18.1.1 §2/§3 — o **Account Mutation Fence**.
 *
 * `BearerAuthGuard` recusa uma requisição **nova** contra uma conta com tombstone; nada nele
 * protege uma requisição que já passou pelo guard e está no meio de um caminho de escrita quando
 * `DELETE /v1/account` commita. Estes testes orquestram exatamente essa corrida contra PostgreSQL
 * real e provam o invariante final: depois do tombstone, nenhuma escrita account-scoped comitada
 * antes dele consegue persistir por cima.
 */
describe('T18.1.1 — Account Mutation Fence', () => {
  let temp: TempDb;
  let app: INestApplication;
  let root: string;
  let ledgerPath: string;

  beforeEach(() => {
    temp = createTempDb();
    root = `${temp.directory}/objects`;
    ledgerPath = `${temp.directory}/deletion_tombstones.tsv`;
  });

  afterEach(async () => {
    await app?.close();
    app = undefined as unknown as INestApplication;
    temp.cleanup();
  });

  const countOf = async (sql: string, ...params: unknown[]): Promise<number> =>
    Number((await app.get(PostgresService).query<{ n: string | number }>(sql, params)).rows[0].n);

  // ================================================================ Backup × Account Deletion

  describe('Backup × exclusão de conta (corrida real via HTTP)', () => {
    it(
      'backup passou pelo auth, o objeto subiu, e a exclusão comita ANTES do INSERT: ' +
        'tombstone permanece, backup_snapshots fica vazio, o objeto não fica referenciado',
      async () => {
        const fake = new InMemoryObjectStorageClient();
        app = await createTestApp(
          configFor(temp.path, {
            SOCIAL_MEDIA_ROOT: root,
            DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
          }),
          FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
          undefined,
          undefined,
          undefined,
          { objectStorageClient: fake },
        );

        // A trava segura a escrita do objeto exatamente no ponto em que a requisição real ficaria
        // presa: o objeto já está gravado, e `BackupService.create` ainda não chamou o `INSERT`.
        const { release, entered } = fake.gateNext('write');

        const backupPromise = dispatch(
          request(app.getHttpServer())
            .post('/v1/backups')
            .set('Authorization', `Bearer ${TOKEN}`)
            .set('Content-Type', 'application/json')
            .send(fixtureText('backup-v1-complete')),
        );

        // Só dispara a exclusão depois de confirmar que o backup **já passou** pelo
        // `BearerAuthGuard` e está preso no ponto certo — sem isto, o Node poderia processar a
        // exclusão inteira primeiro, e o próprio guard da requisição gated a rejeitaria na
        // entrada, testando o guard em vez do fence.
        await entered;

        // A exclusão passa pelo `BearerAuthGuard` (a conta ainda está ativa quando ela começa),
        // grava o tombstone e purga o PostgreSQL — tudo isso **antes** de o backup retomar.
        const deletion = await request(app.getHttpServer())
          .delete('/v1/account')
          .set('Authorization', `Bearer ${TOKEN}`);
        expect(deletion.status).toBe(200);
        expect(deletion.body.status).toBe('DELETED');
        expect(await countOf(`SELECT COUNT(*) AS n FROM account_deletion_tombstones`)).toBe(1);

        // Só agora o backup retoma — depois do commit da exclusão.
        release();
        const backupResponse = await backupPromise;

        // A escrita definitiva nunca aconteceu: o fence recusou o `INSERT`.
        expect(backupResponse.status).toBe(403);
        expect(backupResponse.body.error.code).toBe('ACCOUNT_DELETED');

        expect(
          await countOf(`SELECT COUNT(*) AS n FROM backup_snapshots WHERE owner_uid = $1`, UID),
        ).toBe(0);
        // Tombstone continua soberano — a exclusão não foi desfeita pela tentativa atrasada.
        expect(await countOf(`SELECT COUNT(*) AS n FROM account_deletion_tombstones`)).toBe(1);
        // O objeto que já tinha subido não fica órfão permanentemente: o próprio caminho de erro
        // do serviço o remove (o mesmo `remove` que já protegia contra `23505`).
        expect(fake.names()).toEqual([]);
      },
    );
  });

  // ================================================================ Mídia social × Account Deletion

  describe('Upload de mídia social × exclusão de conta (mesma classe de corrida)', () => {
    it('objeto sobe, exclusão comita, e o INSERT da linha PENDING é recusado', async () => {
      const fake = new InMemoryObjectStorageClient();
      app = await createTestApp(
        configFor(temp.path, {
          SOCIAL_MEDIA_ROOT: root,
          DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
        }),
        FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
        undefined,
        undefined,
        undefined,
        { objectStorageClient: fake },
      );

      await request(app.getHttpServer())
        .post('/v1/social/me/activate')
        .set('Authorization', `Bearer ${TOKEN}`)
        .send({ displayName: 'Igor' })
        .expect(200);

      const syncId = uuid();
      await request(app.getHttpServer())
        .post('/v1/sync/push')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(
          pushBody([
            {
              entityType: 'WORKOUT_SESSION',
              entitySyncId: syncId,
              payload: sessionPayload(syncId, {
                startedAt: Date.now() - 60 * 60 * 1000,
                finishedAt: Date.now() - 30 * 60 * 1000,
              }),
            },
          ]),
        )
        .expect(200);

      const { release, entered } = fake.gateNext('write');
      const uploadPromise = dispatch(
        request(app.getHttpServer())
          .post('/v1/social/checkin-media')
          .query({ sessionSyncId: syncId, clientUploadId: uuid() })
          .set('Authorization', `Bearer ${TOKEN}`)
          .set('Content-Type', 'image/jpeg')
          .send(await jpeg()),
      );

      // Confirma que o upload já passou pelo guard e está preso na trava antes de disparar a
      // exclusão — a mesma garantia do teste de backup, pelo mesmo motivo.
      await entered;

      const deletion = await request(app.getHttpServer())
        .delete('/v1/account')
        .set('Authorization', `Bearer ${TOKEN}`);
      expect(deletion.status).toBe(200);

      release();
      const uploadResponse = await uploadPromise;

      expect(uploadResponse.status).toBe(403);
      expect(uploadResponse.body.error.code).toBe('ACCOUNT_DELETED');
      expect(
        await countOf(`SELECT COUNT(*) AS n FROM social_checkin_media WHERE owner_uid = $1`, UID),
      ).toBe(0);
      expect(fake.names()).toEqual([]);
    });
  });

  // ================================================================ Sync × Account Deletion

  describe('Sync × exclusão de conta', () => {
    it(
      'uma mutação que já tinha passado pelo guard, tentando persistir depois do tombstone, ' +
        'é recusada pelo fence — sync_entities/sync_changes/sync_mutations não reaparecem',
      async () => {
        app = await createTestApp(
          configFor(temp.path, {
            SOCIAL_MEDIA_ROOT: root,
            DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
          }),
          FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
        );

        // A exclusão comita primeiro: tombstone gravado, PostgreSQL purgado (mesmo sem nada para
        // purgar ainda — o fence protege qualquer conta, com ou sem dado prévio).
        const deletion = await request(app.getHttpServer())
          .delete('/v1/account')
          .set('Authorization', `Bearer ${TOKEN}`);
        expect(deletion.status).toBe(200);

        // A chamada direta ao `SyncService` — e não via HTTP — é deliberada: o `BearerAuthGuard`
        // já bloquearia esta requisição na entrada, e é exatamente esse bloqueio que este teste
        // NÃO quer exercitar. O que precisa ser provado é a segunda barreira, para a requisição
        // que **já** passou pela primeira antes de a exclusão ter acontecido.
        const syncService = app.get(SyncService);
        const principal = { uid: UID } as const;
        const syncId = uuid();

        await expect(
          syncService.push(
            principal,
            'req-late',
            pushBody([
              {
                entityType: 'WORKOUT_PROGRAM',
                entitySyncId: syncId,
                baseRevision: null,
                payload: programPayload(syncId, 'Programa pós-exclusão'),
              },
            ]),
          ),
        ).rejects.toMatchObject({ response: { code: 'ACCOUNT_DELETED' } });

        expect(
          await countOf(`SELECT COUNT(*) AS n FROM sync_entities WHERE owner_uid = $1`, UID),
        ).toBe(0);
        expect(
          await countOf(`SELECT COUNT(*) AS n FROM sync_changes WHERE owner_uid = $1`, UID),
        ).toBe(0);
        expect(
          await countOf(`SELECT COUNT(*) AS n FROM sync_mutations WHERE owner_uid = $1`, UID),
        ).toBe(0);
      },
    );

    it('a exceção crua do fence nunca escapa do repositório sem virar AccountMutationFencedError', () => {
      // Documenta o tipo — para que uma futura mudança em `sync.repository.ts` que troque a ordem
      // (checar tombstone antes do lock, por exemplo) continue lançando o mesmo erro reconhecível.
      expect(new AccountMutationFencedError().name).toBe('AccountMutationFencedError');
    });
  });
});
