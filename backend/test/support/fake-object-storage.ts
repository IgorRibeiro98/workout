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
  createdAt: number;
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

  /** Faz [operation] falhar como infraestrutura indisponível até [restore]. */
  fail(operation: Operation): void {
    this.failing.add(operation);
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

  /** Reescreve a data de criação de um objeto existente — para simular um órfão antigo. */
  setCreatedAt(name: string, createdAt: number): void {
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
    if (this.objects.has(name)) {
      throw new ObjectAlreadyExistsError();
    }
    this.objects.set(name, {
      bytes: Buffer.from(bytes),
      contentType: options.contentType,
      metadata: { ...options.metadata },
      createdAt: this.clock(),
    });
    await Promise.resolve();
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
