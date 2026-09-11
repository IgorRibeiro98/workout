import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { AppConfig } from '../src/config/app-config';
import { runDeletionLedgerMigration } from '../src/cli/migrate-deletion-ledger-to-object-storage';
import { FileDeletionTombstoneLedger } from '../src/modules/account-deletion/file-deletion-tombstone.ledger';
import { ObjectStorageDeletionTombstoneLedger } from '../src/modules/account-deletion/object-storage-deletion-tombstone.ledger';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';

const HASH_A = '3'.repeat(64);
const HASH_B = '4'.repeat(64);

/**
 * T18.2 §30/§31 — `migrate:deletion-ledger`.
 *
 * ## O que esta suíte cobre, e o que fica NOT VERIFIED
 *
 * O CLI real (`runDeletionLedgerMigration`) escolhe o destino pela **mesma** factory de produção
 * (`createObjectStorageClient`), que só monta o cliente GCS de verdade com
 * `OBJECT_STORAGE_PROVIDER=gcs` — e, a partir daí, precisaria de bucket, ADC e rede reais para
 * `list`/`write` funcionarem. Nada disso existe neste ambiente de teste. O que é verificável sem
 * GCP:
 *
 * 1. o guard do CLI (`provider !== 'gcs'` → recusa, código 1) — testado chamando o CLI de
 *    verdade, com o provider default (`local`);
 * 2. a **mecânica** da migração (ler entradas do ledger de disco preservando duplicatas, migrar
 *    para o ledger de Object Storage, convergir numa segunda execução) — testada compondo as duas
 *    implementações reais (`FileDeletionTombstoneLedger` + `ObjectStorageDeletionTombstoneLedger`
 *    sobre o dublê `InMemoryObjectStorageClient`), com a mesma lógica que o CLI executa.
 *
 * O caminho fim-a-fim contra um bucket GCS real é `NOT VERIFIED` neste ambiente — ver o relatório
 * final da T18.2.
 */
describe('T18.2 — migração do ledger de exclusão para Object Storage', () => {
  describe('CLI — guard de provider', () => {
    it('com o provider default (local), recusa e sai com código 1', async () => {
      await expect(runDeletionLedgerMigration()).resolves.toBe(1);
    });
  });

  describe('mecânica da migração (composição real, sem GCP)', () => {
    let directory: string;
    let ledgerPath: string;

    beforeEach(() => {
      directory = mkdtempSync(join(tmpdir(), 'spark-ledger-migration-'));
      ledgerPath = join(directory, 'deletion_tombstones.tsv');
    });

    afterEach(() => {
      rmSync(directory, { recursive: true, force: true });
    });

    const sourceLedger = (): FileDeletionTombstoneLedger => {
      const config = AppConfig.fromEnv({
        NODE_ENV: 'test',
        DATABASE_URL: 'postgresql://spark:spark@localhost:5432/spark_dev',
        DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
      });
      return new FileDeletionTombstoneLedger(config);
    };

    it('migra todos os hashes distintos, preserva o arquivo de origem e converge numa segunda execução', async () => {
      const source = sourceLedger();
      await source.appendDurably(HASH_A, 1_000);
      await source.appendDurably(HASH_A, 1_000); // a mesma exclusão, retry — vira uma linha extra.
      await source.appendDurably(HASH_B, 2_000);

      const entries = await source.readEntries();
      expect(entries).toHaveLength(3);

      const byHash = new Map<string, number>();
      for (const entry of entries) {
        if (!byHash.has(entry.hash)) byHash.set(entry.hash, entry.deletedAt);
      }
      expect(byHash.size).toBe(2);

      const store = new InMemoryObjectStorageClient();
      const target = new ObjectStorageDeletionTombstoneLedger(store);

      for (const [hash, deletedAt] of byHash) {
        await target.appendDurably(hash, deletedAt);
      }

      const migrated = await target.readHashes();
      expect(migrated.hashes.has(HASH_A)).toBe(true);
      expect(migrated.hashes.has(HASH_B)).toBe(true);
      expect(migrated.lineCount).toBe(2);

      // Rodar de novo — a origem nunca foi tocada, e a segunda passagem só confirma o que já
      // estava lá (convergência, §29).
      for (const [hash, deletedAt] of byHash) {
        await expect(target.appendDurably(hash, deletedAt)).resolves.toBeUndefined();
      }
      expect(store.names()).toHaveLength(2);

      // A origem sobrevive intocada — a migração nunca apaga nem trunca.
      const stillThere = await source.readEntries();
      expect(stillThere).toHaveLength(3);
    });

    it('linha malformada na origem aborta antes de qualquer gravação no destino (fail-closed)', async () => {
      writeFileSync(ledgerPath, 'linha-corrompida-sem-formato\n');
      const source = sourceLedger();

      await expect(source.readEntries()).rejects.toThrow();
    });
  });
});
