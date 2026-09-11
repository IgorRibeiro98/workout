/**
 * A fronteira neutra do ledger anti-ressurreição de exclusões de conta (T17.13.1, estreitada na
 * T18.2 §26).
 *
 * ## O que ela é
 *
 * O ledger é a memória de uma exclusão que sobrevive à substituição do **banco** por um backup
 * antigo — é ele que responde "esta conta já foi excluída?" quando um restore de desastre traria a
 * conta de volta. Duas implementações honram este contrato:
 *
 * ```text
 * DeletionTombstoneLedgerPort
 *         │
 *         ├── FileDeletionTombstoneLedger           disco local (VPS, desenvolvimento, teste)
 *         └── ObjectStorageDeletionTombstoneLedger   Object Storage (Cloud Run, T18.2 §27)
 * ```
 *
 * A escolha segue `OBJECT_STORAGE_PROVIDER` (`deletion-tombstone-ledger.factory.ts`), e não uma
 * variável própria: no Cloud Run o filesystem do container não é autoridade de nada durável — nem
 * foto, nem backup, nem este ledger —, e são exatamente os mesmos ambientes em que
 * `OBJECT_STORAGE_PROVIDER=gcs` já é obrigatório. Duas variáveis independentes controlando a mesma
 * pergunta ("onde mora o que precisa sobreviver ao container?") é o tipo de configuração que
 * diverge sem ningém notar — um deploy com mídia em `gcs` e ledger em disco local seria exatamente
 * o bloqueante que a T18.2 proíbe.
 *
 * ## O que toda implementação precisa honrar
 *
 * - **`appendDurably` converge.** Gravar o mesmo hash duas vezes — retry depois de falha parcial,
 *   ou o reconciliador reprocessando o mesmo job — é sucesso, nunca erro. O disco já fazia isso
 *   apendando a mesma linha outra vez (o leitor consome um conjunto); o Object Storage faz o
 *   equivalente tratando `ObjectAlreadyExistsError` como convergência, e não como falha.
 * - **Falha de infraestrutura propaga.** `appendDurably` que não conseguiu confirmar a escrita
 *   lança [DeletionTombstoneLedgerError], e quem chama (`AccountDeletionService.advanceJob`) deixa
 *   o job em `LEDGER_PENDING` — nunca `DELETED` sem o registro confirmado (T18.2 §29).
 * - **Ausência do ledger em `readHashes()` é erro, nunca "zero exclusões".** As duas interpretações
 *   produzem o mesmo resultado visível (a reconciliação não apaga nada) e uma delas ressuscita
 *   contas — ver `deletion-tombstone.ledger.ts` (agora `FileDeletionTombstoneLedger`) para o
 *   raciocínio original, que continua valendo para as duas implementações.
 */
export interface LedgerContents {
  /** Os hashes distintos. */
  readonly hashes: ReadonlySet<string>;
  /**
   * Quantas entradas o ledger tinha, incluindo repetições — o número que o operador confere.
   * No provider de disco, repetição é uma linha reescrita; no Object Storage, cada hash já é um
   * nome de objeto único, então `lineCount === hashes.size` sempre.
   */
  readonly lineCount: number;
}

export interface DeletionTombstoneLedgerPort {
  /** Onde o ledger vive, para mensagem de operador — nunca para lógica de negócio. */
  readonly location: string;

  /**
   * Registra um hash de conta excluída e só retorna quando a escrita foi confirmada pelo provider.
   * Repetir o mesmo hash é permitido e esperado — ver o contrato de convergência acima.
   */
  appendDurably(uidHash: string, deletedAt: number): Promise<void>;

  /** Lê e valida o ledger inteiro. Lança se ele não existir ou estiver corrompido/inalcançável. */
  readHashes(): Promise<LedgerContents>;
}

/** O ledger não pôde ser lido, escrito ou validado. Nunca é engolida. */
export class DeletionTombstoneLedgerError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DeletionTombstoneLedgerError';
  }
}

export const DELETION_TOMBSTONE_LEDGER = Symbol('DELETION_TOMBSTONE_LEDGER');
