import { Readable } from 'node:stream';
import {
  assertSafeObjectName,
  assertSafeObjectPrefix,
  ObjectAlreadyExistsError,
  ObjectStorageUnavailableError,
  type ListObjectsOptions,
  type ObjectPage,
  type ObjectStorageClient,
  type WriteObjectOptions,
} from '../../src/object-storage/object-storage.client';

type Operation = 'write' | 'read' | 'exists' | 'remove' | 'list';

interface StoredObject {
  readonly bytes: Buffer;
  readonly contentType: string;
  readonly metadata: Readonly<Record<string, string>>;
  createdAt: number | null;
}

/**
 * Um Object Storage em memória com o **mesmo** contrato do bucket (T18.1).
 *
 * Ele existe para o que o provider local não consegue simular de forma controlada: o bucket fora
 * do ar em uma operação específica, um `delete` que falha, um objeto com data de criação
 * escolhida. É o que permite provar a matriz de falhas entre o Object Storage e o PostgreSQL sem
 * rede, sem GCP e sem dormir.
 *
 * Só em `test/`: não existe provider, flag ou variável que faça o processo de produção usá-lo.
 */
export class InMemoryObjectStorageClient implements ObjectStorageClient {
  readonly provider = 'gcs' as const;
  private readonly objects = new Map<string, StoredObject>();
  private readonly failing = new Set<Operation>();
  /** A "hora" em que os próximos objetos nascem. Controlável para simular objetos antigos. */
  private clock: () => number = () => Date.now();
  /** Uma trava de uso único por operação, para corridas reais e deterministas (T18.1.1). */
  private readonly pendingGates = new Map<
    Operation,
    { gate: Promise<void>; markEntered: () => void }
  >();
  /** Chaves cuja **próxima** `remove` falha — uma vez só, para provar um lote com falha parcial. */
  private readonly failingKeys = new Set<string>();

  /**
   * Faz a **próxima** chamada de [operation] esperar até que [release] seja chamado — e devolve
   * [entered], que resolve no instante em que a chamada **chegou** na trava.
   *
   * Existe para orquestrar deterministicamente a corrida entre uma escrita account-scoped já em
   * voo e uma exclusão de conta concorrente. Sem [entered], um teste que dispara a requisição
   * gated e a exclusão concorrente por `Promise.all`/sequência não tem garantia de que a primeira
   * já passou pelo `BearerAuthGuard` e chegou ao ponto certo antes de a segunda começar — o Node
   * pode processar a exclusão inteira primeiro, e aí o `BearerAuthGuard` da própria requisição
   * gated a rejeitaria na entrada, testando o guard em vez do fence. Aguardar `entered` fecha essa
   * janela: só depois dele o teste sabe que a escrita está **presa depois do guard**, exatamente
   * onde a corrida real acontece.
   */
  gateNext(operation: Operation): { release: () => void; entered: Promise<void> } {
    let release!: () => void;
    let markEntered!: () => void;
    const entered = new Promise<void>((resolve) => {
      markEntered = resolve;
    });
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    this.pendingGates.set(operation, { gate, markEntered });
    return { release, entered };
  }

  private async awaitGate(operation: Operation): Promise<void> {
    const entry = this.pendingGates.get(operation);
    if (entry) {
      this.pendingGates.delete(operation);
      entry.markEntered();
      await entry.gate;
    }
  }

  /** Faz [operation] falhar como infraestrutura indisponível até [restore]. */
  fail(operation: Operation): void {
    this.failing.add(operation);
  }

  /** Faz a próxima `remove(name)` falhar, sem afetar as demais chaves do mesmo lote. */
  failNextRemoveFor(name: string): void {
    this.failingKeys.add(name);
  }

  restore(operation?: Operation): void {
    if (operation === undefined) {
      this.failing.clear();
    } else {
      this.failing.delete(operation);
    }
  }

  setClock(clock: () => number): void {
    this.clock = clock;
  }

  /**
   * Reescreve a data de criação de um objeto existente — para simular um órfão antigo, ou (com
   * `null`) um objeto cujo provider não conseguiu provar a idade (T18.1.1 §9).
   */
  setCreatedAt(name: string, createdAt: number | null): void {
    const object = this.objects.get(name);
    if (!object) {
      throw new Error(`objeto inexistente no dublê: ${name}`);
    }
    object.createdAt = createdAt;
  }

  /** Corrompe os bytes de um objeto sem mudar o nome — para provar a verificação de hash. */
  corrupt(name: string, bytes: Buffer): void {
    const object = this.objects.get(name);
    if (!object) {
      throw new Error(`objeto inexistente no dublê: ${name}`);
    }
    this.objects.set(name, { ...object, bytes });
  }

  names(): string[] {
    return [...this.objects.keys()].sort();
  }

  metadataOf(name: string): Readonly<Record<string, string>> | undefined {
    return this.objects.get(name)?.metadata;
  }

  async write(name: string, bytes: Buffer, options: WriteObjectOptions): Promise<void> {
    assertSafeObjectName(name);
    this.maybeFail('write');
    const existing = this.objects.get(name);
    if (existing) {
      // Create-or-confirm-identical (T18.1.1 §6/§7): o mesmo contrato que `local` e `gcs` — o
      // dublê existe para provar que os três convergem igual, não para ter uma regra própria.
      if (existing.bytes.equals(bytes)) {
        await Promise.resolve();
        return;
      }
      throw new ObjectAlreadyExistsError();
    }
    this.objects.set(name, {
      bytes: Buffer.from(bytes),
      contentType: options.contentType,
      metadata: { ...options.metadata },
      createdAt: this.clock(),
    });
    // O objeto já está gravado quando a trava segura: exatamente o estado real de uma requisição
    // presa entre o upload e o `INSERT` da metadata.
    await this.awaitGate('write');
  }

  async read(name: string): Promise<Buffer | null> {
    assertSafeObjectName(name);
    this.maybeFail('read');
    const object = this.objects.get(name);
    await Promise.resolve();
    return object ? Buffer.from(object.bytes) : null;
  }

  async openRead(name: string): Promise<Readable | null> {
    const bytes = await this.read(name);
    return bytes === null ? null : Readable.from(bytes);
  }

  async exists(name: string): Promise<boolean> {
    assertSafeObjectName(name);
    this.maybeFail('exists');
    await Promise.resolve();
    return this.objects.has(name);
  }

  async remove(name: string): Promise<void> {
    assertSafeObjectName(name);
    this.maybeFail('remove');
    if (this.failingKeys.delete(name)) {
      throw new ObjectStorageUnavailableError('remove', new Error('falha injetada para a chave'));
    }
    this.objects.delete(name);
    await Promise.resolve();
  }

  async list(prefix: string, options: ListObjectsOptions): Promise<ObjectPage> {
    assertSafeObjectPrefix(prefix);
    this.maybeFail('list');
    const all = this.names().filter((name) => name.startsWith(prefix));
    const start = options.pageToken !== undefined ? all.indexOf(options.pageToken) + 1 : 0;
    const page = all.slice(start, start + options.pageSize);
    await Promise.resolve();
    return {
      objects: page.map((name) => {
        const object = this.objects.get(name)!;
        return { name, createdAt: object.createdAt, size: object.bytes.length };
      }),
      nextPageToken: start + options.pageSize < all.length ? page[page.length - 1] : undefined,
    };
  }

  private maybeFail(operation: Operation): void {
    if (this.failing.has(operation)) {
      throw new ObjectStorageUnavailableError(operation, new Error('falha injetada'));
    }
  }
}
