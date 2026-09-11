import type { AppConfig } from '../../config/app-config';
import type { ObjectStorageClient } from '../../object-storage/object-storage.client';
import type { DeletionTombstoneLedgerPort } from './deletion-tombstone-ledger.port';
import { FileDeletionTombstoneLedger } from './file-deletion-tombstone.ledger';
import { ObjectStorageDeletionTombstoneLedger } from './object-storage-deletion-tombstone.ledger';

/**
 * O **único** ponto do processo que escolhe a implementação do ledger anti-ressurreição
 * (T18.2 §26), no mesmo desenho de `object-storage.factory.ts`: `AccountDeletionModule` e o CLI de
 * reconciliação passam por aqui, e é isso que garante que os dois falam do **mesmo** ledger que a
 * escrita de exclusão usou — nunca um CLI lendo disco enquanto o processo grava no bucket.
 *
 * A escolha segue `config.objectStorageProvider` — a mesma variável de `OBJECT_STORAGE_PROVIDER`
 * que `object-storage.factory.ts` lê para o destino de mídia e backup —, e não o `.provider` do
 * cliente injetado: um dublê de teste pode se anunciar `gcs` para simular indisponibilidade de
 * bucket sem que o ambiente esteja de fato configurado para `gcs`, e o ledger não pode reagir a
 * isso — ele segue a **configuração declarada**, exatamente como o próprio `object-storage.factory
 * .ts` faz. É por isso que este arquivo (e `migrate-deletion-ledger-to-object-storage.ts`, que
 * recusa rodar fora de `gcs`) aparece como exceção deliberada em
 * `test/object-storage-structure.spec.ts` — no mesmo espírito de `migrate-social-media-to-object
 * -storage.ts`: ler a variável para decidir **qual ledger**, nunca para construir um
 * `LocalObjectStorageClient`/`GcsObjectStorageClient` por conta própria.
 */
export function createDeletionTombstoneLedger(
  config: AppConfig,
  objectStorage: ObjectStorageClient,
): DeletionTombstoneLedgerPort {
  switch (config.objectStorageProvider) {
    case 'local':
      return new FileDeletionTombstoneLedger(config);
    case 'gcs':
      return new ObjectStorageDeletionTombstoneLedger(objectStorage);
  }
}
