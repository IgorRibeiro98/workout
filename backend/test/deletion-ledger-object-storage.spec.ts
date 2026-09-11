import { join } from 'node:path';
import { existsSync } from 'node:fs';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { DeletionTombstoneLedgerError } from '../src/modules/account-deletion/deletion-tombstone-ledger.port';
import { ObjectStorageDeletionTombstoneLedger } from '../src/modules/account-deletion/object-storage-deletion-tombstone.ledger';

const HASH_A = '1'.repeat(64);
const HASH_B = '2'.repeat(64);

/**
 * T18.2 §26/§27/§29 — o ledger anti-ressurreição em Object Storage.
 *
 * O provider é escolhido pelo `.provider` do cliente já resolvido (nunca por uma segunda leitura
 * de `OBJECT_STORAGE_PROVIDER`) — é por isso que sobrepor `OBJECT_STORAGE_CLIENT` por
 * `InMemoryObjectStorageClient` (que se declara `provider: 'gcs'`) já é suficiente para o
 * `AccountDeletionModule` inteiro trocar de ledger, sem precisar de GCP real.
 */
describe('T18.2 — ledger de exclusão de conta em Object Storage', () => {
  describe('unidade — ObjectStorageDeletionTombstoneLedger', () => {
    let store: InMemoryObjectStorageClient;
    let ledger: ObjectStorageDeletionTombstoneLedger;

    beforeEach(() => {
      store = new InMemoryObjectStorageClient();
      ledger = new ObjectStorageDeletionTombstoneLedger(store);
    });

    it('grava um tombstone como um objeto, nomeado pelo hash', async () => {
      await ledger.appendDurably(HASH_A, 1_000);
      expect(store.names()).toEqual([`system/deletion-tombstones/${HASH_A}`]);
    });

    it('converge: o mesmo hash duas vezes, com timestamps diferentes, não lança (§29)', async () => {
      await ledger.appendDurably(HASH_A, 1_000);
      await expect(ledger.appendDurably(HASH_A, 2_000)).resolves.toBeUndefined();
      expect(store.names()).toHaveLength(1);
    });

    it('recusa hash fora do formato esperado', async () => {
      await expect(ledger.appendDurably('não-é-hash', 1_000)).rejects.toThrow(
        DeletionTombstoneLedgerError,
      );
    });

    it('falha de infraestrutura na escrita propaga como falha do ledger, nunca como sucesso (§29)', async () => {
      store.fail('write');
      await expect(ledger.appendDurably(HASH_A, 1_000)).rejects.toThrow(
        DeletionTombstoneLedgerError,
      );
    });

    it('lê os hashes distintos gravados, com lineCount igual ao tamanho do conjunto', async () => {
      await ledger.appendDurably(HASH_A, 1_000);
      await ledger.appendDurably(HASH_B, 2_000);

      const contents = await ledger.readHashes();
      expect(contents.hashes.has(HASH_A)).toBe(true);
      expect(contents.hashes.has(HASH_B)).toBe(true);
      expect(contents.lineCount).toBe(2);
    });

    it('ausência total de tombstones é um conjunto vazio, não um erro (nada foi excluído ainda)', async () => {
      const contents = await ledger.readHashes();
      expect(contents.hashes.size).toBe(0);
      expect(contents.lineCount).toBe(0);
    });

    it('falha de infraestrutura na listagem propaga como falha do ledger', async () => {
      store.fail('list');
      await expect(ledger.readHashes()).rejects.toThrow(DeletionTombstoneLedgerError);
    });

    it('um objeto fora do formato de hash sob o prefixo é corrupção, e nunca ignorado em silêncio', async () => {
      await store.write('system/deletion-tombstones/objeto-estranho', Buffer.from('x'), {
        contentType: 'text/plain',
      });
      await expect(ledger.readHashes()).rejects.toThrow(DeletionTombstoneLedgerError);
    });

    it('location identifica o provider e o namespace, nunca um caminho de disco', () => {
      expect(ledger.location).toBe('gcs:system/deletion-tombstones/');
    });
  });

  describe('integração — a exclusão de conta grava no bucket, e o disco fica intocado', () => {
    let temp: TempDb;
    let app: INestApplication;
    let verifier: FakeAuthTokenVerifier;
    let store: InMemoryObjectStorageClient;
    let ledgerPath: string;

    const server = () => app.getHttpServer();

    beforeEach(async () => {
      temp = createTempDb();
      ledgerPath = join(temp.directory, 'deletion_tombstones.tsv');
      store = new InMemoryObjectStorageClient();
      verifier = new FakeAuthTokenVerifier();
      verifier.accept('token-a', { uid: 'uid-gcs-a', email: 'a@example.com' });

      app = await createTestApp(
        configFor(temp.path, {
          DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
          ACCOUNT_DELETION_HMAC_KEY: 'chave-hmac-de-teste-para-ledger-gcs',
          // O ledger segue `OBJECT_STORAGE_PROVIDER` — o dublê injetado abaixo só substitui QUEM
          // executa as operações; QUAL ledger é sempre decidido pela configuração declarada.
          OBJECT_STORAGE_PROVIDER: 'gcs',
          GCS_BUCKET_NAME: 'spark-teste-ledger-gcs',
        }),
        verifier,
        undefined,
        undefined,
        undefined,
        { objectStorageClient: store },
      );
    });

    afterEach(async () => {
      await app?.close();
      temp.cleanup();
    });

    it('DELETE /v1/account grava o tombstone no bucket, e o arquivo local nunca é criado', async () => {
      await request(server())
        .post('/v1/social/me/activate')
        .set('Authorization', 'Bearer token-a')
        .send({ displayName: 'Alice' })
        .expect(200);

      const res = await request(server())
        .delete('/v1/account')
        .set('Authorization', 'Bearer token-a')
        .expect(200);

      expect(res.body.status).toBe('DELETED');
      expect(store.names().some((name) => name.startsWith('system/deletion-tombstones/'))).toBe(
        true,
      );
      // O ledger de disco (`DELETION_TOMBSTONES_FILE_PATH`) nunca é tocado: o provider ativo é o
      // Object Storage (o dublê), e a fábrica escolhe um dos dois — nunca os dois.
      expect(existsSync(ledgerPath)).toBe(false);
    });

    it('bucket indisponível na hora do ledger mantém DELETION_PENDING, nunca DELETED (§29)', async () => {
      await request(server())
        .post('/v1/social/me/activate')
        .set('Authorization', 'Bearer token-a')
        .send({ displayName: 'Alice' })
        .expect(200);

      store.fail('write');

      const res = await request(server())
        .delete('/v1/account')
        .set('Authorization', 'Bearer token-a')
        .expect(200);

      expect(res.body.status).toBe('DELETION_PENDING');
      expect(store.names().some((name) => name.startsWith('system/deletion-tombstones/'))).toBe(
        false,
      );
    });
  });
});
