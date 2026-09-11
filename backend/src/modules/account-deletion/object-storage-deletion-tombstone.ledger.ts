import {
  ObjectAlreadyExistsError,
  ObjectStorageUnavailableError,
  type ObjectStorageClient,
} from '../../object-storage/object-storage.client';
import {
  DeletionTombstoneLedgerError,
  type DeletionTombstoneLedgerPort,
  type LedgerContents,
} from './deletion-tombstone-ledger.port';

/**
 * Namespace do ledger no bucket. Um tombstone por objeto — nunca o Firebase UID puro, só o hash
 * (T18.2 §27): o nome do objeto já é o dado que o ledger pode conter.
 */
const LEDGER_PREFIX = 'system/deletion-tombstones/';

const HASH_FORMAT = /^[0-9a-f]{64}$/;

/**
 * O ledger anti-ressurreição em Object Storage (T18.2 §26/§27/§29).
 *
 * ## Por que ele existe
 *
 * No Cloud Run o filesystem do container não é autoridade de nada durável: um container morto,
 * uma revision nova, um scale-to-zero não podem apagar o registro de uma exclusão. O bucket já é
 * a autoridade durável de foto e de backup (T18.1); este ledger só estende a mesma garantia ao
 * registro de exclusão, com a mesma identidade (ADC) e o mesmo cliente — nenhuma credencial nova,
 * nenhum caminho de configuração novo.
 *
 * ## Um hash, um objeto
 *
 * Cada tombstone é o objeto `system/deletion-tombstones/<hash>` — o **nome** já é o dado, e o
 * corpo (o epoch da exclusão) é só metadata de operador. Isso torna o ledger inteiro uma listagem
 * paginada por prefixo (`ObjectStorageClient.list`), sem precisar carregar um arquivo monolítico
 * inteiro na memória para ler uma linha.
 *
 * ## Convergência (§29)
 *
 * `write` é create-or-confirm-identical no resto do Object Storage, mas aqui a comparação exata de
 * bytes não importa: o que identifica o tombstone é o **nome** do objeto, não o corpo. Duas
 * exclusões da mesma conta (retry depois de falha parcial, ou o reconciliador reprocessando o
 * mesmo job) podem escrever timestamps diferentes para o mesmo hash — e as duas precisam
 * convergir, nunca colidir. Por isso `ObjectAlreadyExistsError` aqui é sucesso, não falha: a mera
 * existência do objeto já é o tombstone: qualquer falha real de infraestrutura continua subindo
 * como [DeletionTombstoneLedgerError], e é isso que mantém `LEDGER_PENDING` quando o bucket está
 * fora do ar (§29) — a diferença é só entre "já está lá" (convergência) e "não consegui confirmar"
 * (falha).
 */
export class ObjectStorageDeletionTombstoneLedger implements DeletionTombstoneLedgerPort {
  constructor(private readonly client: ObjectStorageClient) {}

  get location(): string {
    return `${this.client.provider}:${LEDGER_PREFIX}`;
  }

  async appendDurably(uidHash: string, deletedAt: number): Promise<void> {
    if (!HASH_FORMAT.test(uidHash)) {
      throw new DeletionTombstoneLedgerError('hash de tombstone fora do formato esperado');
    }

    try {
      await this.client.write(
        `${LEDGER_PREFIX}${uidHash}`,
        Buffer.from(String(Math.trunc(deletedAt))),
        {
          contentType: 'text/plain; charset=utf-8',
        },
      );
    } catch (error) {
      if (error instanceof ObjectAlreadyExistsError) {
        // Convergência: o tombstone já existia, seja desta tentativa ou de uma anterior.
        return;
      }
      // `ObjectStorageUnavailableError` e qualquer outra falha de infraestrutura propagam como
      // falha do ledger — nunca "não existe" (§29): quem chama precisa saber que a confirmação do
      // provider não aconteceu.
      throw new DeletionTombstoneLedgerError(
        `não foi possível persistir o ledger de exclusões: ${
          error instanceof Error ? error.name : 'erro desconhecido'
        }`,
      );
    }
  }

  async readHashes(): Promise<LedgerContents> {
    const hashes = new Set<string>();
    let pageToken: string | undefined;

    do {
      let page;
      try {
        page = await this.client.list(LEDGER_PREFIX, { pageSize: 1000, pageToken });
      } catch (error) {
        if (error instanceof ObjectStorageUnavailableError) {
          throw new DeletionTombstoneLedgerError(
            `não foi possível ler o ledger de exclusões: ${error.name}`,
          );
        }
        throw error;
      }
      for (const object of page.objects) {
        const hash = object.name.slice(LEDGER_PREFIX.length);
        if (!HASH_FORMAT.test(hash)) {
          // O nome do objeto é a única forma que o ledger de bucket admite; qualquer coisa fora
          // do formato é corrupção ou um objeto estranho colocado ali — nunca ignorado em
          // silêncio, pela mesma razão de `FileDeletionTombstoneLedger` recusar linha malformada.
          throw new DeletionTombstoneLedgerError(
            `objeto do ledger de exclusões fora do formato esperado`,
          );
        }
        hashes.add(hash);
      }
      pageToken = page.nextPageToken;
    } while (pageToken !== undefined);

    // Cada hash é, por definição, um único nome de objeto: não há repetição possível.
    return { hashes, lineCount: hashes.size };
  }
}
