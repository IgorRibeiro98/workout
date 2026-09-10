import { createReadStream } from 'node:fs';
import { mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { isAbsolute, join, resolve, sep } from 'node:path';
import type { Readable } from 'node:stream';
import {
  assertSafeObjectName,
  assertSafeObjectPrefix,
  ObjectAlreadyExistsError,
  ObjectStorageUnavailableError,
  UnsafeObjectNameError,
  type ListObjectsOptions,
  type ObjectPage,
  type ObjectStorageClient,
  type StoredObjectSummary,
  type WriteObjectOptions,
} from './object-storage.client';

/**
 * O provider `local`: objetos como arquivos sob uma raiz (T18.1).
 *
 * É o que desenvolvimento, teste e CI usam — sem Google Cloud, sem ADC, sem rede — e o que a
 * composição de VPS da T16.8 continua usando quando ninguém configura `gcs`. Ele honra o
 * **mesmo** contrato do bucket: gravação exclusiva (`wx`), ausência como `null`, remoção
 * idempotente e listagem paginada por prefixo com a data de criação de cada objeto.
 *
 * ## O layout no disco, e por que `social/checkins/` fica na raiz
 *
 * Antes da T18.1 a mídia vivia em `SOCIAL_MEDIA_ROOT/checkins/xx/yy/<uuid>.webp`, e é essa
 * estrutura que `ops/restore.sh` e `ops/verify-backup.sh` reconhecem dentro de um snapshot (pelo
 * diretório `checkins`), e que qualquer volume já existente tem. No bucket o namespace da mídia é
 * `social/checkins/…` (§8/§9); aqui, esse **único** prefixo é traduzido para a raiz —
 * `social/checkins/a/b` ↔ `<root>/checkins/a/b` — para que o layout local não mude e a
 * recuperação de desastre continue encontrando as fotos. Todo outro prefixo (`backups/`,
 * `_smoke/`) vive em `<root>/<nome>`, exatamente como no bucket.
 *
 * A tradução mora só aqui, é explícita e tem teste. Nenhum store sabe dela: eles falam nomes de
 * bucket, nos dois providers.
 *
 * ## Duas barreiras contra path traversal
 *
 * A allowlist de nome ([assertSafeObjectName]) e o confinamento do caminho resolvido na raiz.
 * Uma barreira só é o desenho em que path traversal reaparece na próxima refatoração.
 */
export class LocalObjectStorageClient implements ObjectStorageClient {
  readonly provider = 'local' as const;
  private readonly root: string;

  constructor(root: string) {
    this.root = resolve(root);
  }

  async write(name: string, bytes: Buffer, options: WriteObjectOptions): Promise<void> {
    const target = this.pathOf(name);
    // O sistema de arquivos não guarda `contentType` nem metadata — quem precisa deles é o
    // bucket. O contrato é o mesmo para que o chamador não saiba qual provider tem na mão.
    void options;
    try {
      await mkdir(join(target, '..'), { recursive: true });
      // `wx`: falhar se já existir. Uma chave é usada uma vez; reusá-la seria sobrescrever o
      // objeto de alguém, e prefiro que isso seja um erro barulhento a um silêncio.
      await writeFile(target, bytes, { flag: 'wx', mode: 0o640 });
    } catch (error) {
      if (codeOf(error) === 'EEXIST') {
        // Create-or-confirm-identical (T18.1.1 §6/§7): mesmos bytes é o retry da mesma escrita —
        // sucesso, sem regravar. Bytes diferentes é colisão real, e continua nunca sobrescrevendo.
        // A mesma decisão que o provider GCS toma sobre `412`; nenhum provider diverge em silêncio.
        const existing = await this.read(name);
        if (existing !== null && existing.equals(bytes)) {
          return;
        }
        throw new ObjectAlreadyExistsError();
      }
      throw new ObjectStorageUnavailableError('write', error);
    }
  }

  async read(name: string): Promise<Buffer | null> {
    const target = this.pathOf(name);
    try {
      if (!(await isFile(target))) {
        return null;
      }
      return await readFile(target);
    } catch (error) {
      if (codeOf(error) === 'ENOENT') {
        return null;
      }
      throw new ObjectStorageUnavailableError('read', error);
    }
  }

  async openRead(name: string): Promise<Readable | null> {
    const target = this.pathOf(name);
    // `stat` antes de abrir: além de confirmar a existência, confirma que o caminho é um
    // **arquivo**. Um diretório com o nome certo abriria um stream que falha depois, no meio da
    // resposta.
    try {
      if (!(await isFile(target))) {
        return null;
      }
    } catch (error) {
      throw new ObjectStorageUnavailableError('openRead', error);
    }
    return createReadStream(target);
  }

  async exists(name: string): Promise<boolean> {
    // A validação do nome fica fora do `try`: um nome recusado é erro do chamador, não
    // indisponibilidade do provider.
    const target = this.pathOf(name);
    try {
      return await isFile(target);
    } catch (error) {
      throw new ObjectStorageUnavailableError('exists', error);
    }
  }

  async remove(name: string): Promise<void> {
    const target = this.pathOf(name);
    try {
      await rm(target, { force: true });
    } catch (error) {
      throw new ObjectStorageUnavailableError('remove', error);
    }
  }

  /**
   * Uma página, em ordem determinística.
   *
   * A ordem é a da travessia em profundidade com entradas ordenadas por nome — segmento a
   * segmento —, e o cursor é o último nome devolvido. É uma ordem total consistente com a
   * travessia, o que é tudo o que uma paginação precisa; ela não tenta imitar a ordem por bytes
   * do GCS, porque nenhum consumidor compara páginas entre providers.
   */
  async list(prefix: string, options: ListObjectsOptions): Promise<ObjectPage> {
    assertSafeObjectPrefix(prefix);
    const disk = this.diskPrefixOf(prefix);
    const after = options.pageToken !== undefined ? options.pageToken.split('/') : null;
    const found: StoredObjectSummary[] = [];
    try {
      await this.collect(
        join(this.root, disk.diskPrefix),
        disk.objectPrefix,
        after,
        options.pageSize + 1,
        found,
      );
    } catch (error) {
      throw new ObjectStorageUnavailableError('list', error);
    }
    const hasMore = found.length > options.pageSize;
    const objects = hasMore ? found.slice(0, options.pageSize) : found;
    return {
      objects,
      nextPageToken: hasMore ? objects[objects.length - 1].name : undefined,
    };
  }

  private async collect(
    directory: string,
    objectPrefix: string,
    after: string[] | null,
    limit: number,
    out: StoredObjectSummary[],
  ): Promise<void> {
    let entries;
    try {
      entries = await readdir(directory, { withFileTypes: true });
    } catch (error) {
      // Prefixo ainda sem nenhum objeto é um estado normal — um servidor que nunca recebeu foto.
      if (codeOf(error) === 'ENOENT' || codeOf(error) === 'ENOTDIR') {
        return;
      }
      throw error;
    }
    entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    for (const entry of entries) {
      if (out.length >= limit) {
        return;
      }
      const name = `${objectPrefix}${entry.name}`;
      if (entry.isDirectory()) {
        if (after !== null && !mayContainAfter(name, after)) {
          continue;
        }
        await this.collect(join(directory, entry.name), `${name}/`, after, limit, out);
      } else if (entry.isFile()) {
        if (after !== null && compareSegments(name.split('/'), after) <= 0) {
          continue;
        }
        const info = await stat(join(directory, entry.name));
        // Objetos são imutáveis: o `mtime` de um arquivo nunca reescrito é a sua criação — e é
        // portátil, ao contrário de `birthtime`, que alguns sistemas de arquivos não guardam.
        out.push({ name, createdAt: info.mtimeMs, size: info.size });
      }
    }
  }

  /**
   * A tradução de nome para caminho — o **único** ponto do servidor que a faz.
   */
  private pathOf(name: string): string {
    const safe = assertSafeObjectName(name);
    const absolute = resolve(this.root, this.diskNameOf(safe));
    if (isAbsolute(safe) || (absolute !== this.root && !absolute.startsWith(this.root + sep))) {
      throw new UnsafeObjectNameError();
    }
    return absolute;
  }

  private diskNameOf(name: string): string {
    return name.startsWith(LEGACY_SOCIAL_PREFIX)
      ? `${LEGACY_DISK_PREFIX}${name.slice(LEGACY_SOCIAL_PREFIX.length)}`
      : name;
  }

  private diskPrefixOf(prefix: string): { diskPrefix: string; objectPrefix: string } {
    return prefix.startsWith(LEGACY_SOCIAL_PREFIX)
      ? {
          diskPrefix: `${LEGACY_DISK_PREFIX}${prefix.slice(LEGACY_SOCIAL_PREFIX.length)}`,
          objectPrefix: prefix,
        }
      : { diskPrefix: prefix, objectPrefix: prefix };
  }
}

/** O namespace da mídia no bucket, e onde ele fica no disco (o layout anterior à T18.1). */
const LEGACY_SOCIAL_PREFIX = 'social/checkins/';
const LEGACY_DISK_PREFIX = 'checkins/';

async function isFile(path: string): Promise<boolean> {
  try {
    return (await stat(path)).isFile();
  } catch (error) {
    if (codeOf(error) === 'ENOENT' || codeOf(error) === 'ENOTDIR') {
      return false;
    }
    throw error;
  }
}

function codeOf(error: unknown): string | undefined {
  return typeof error === 'object' && error !== null && 'code' in error
    ? String((error as { code: unknown }).code)
    : undefined;
}

/** Ordem segmento a segmento — a mesma da travessia com entradas ordenadas. */
function compareSegments(a: readonly string[], b: readonly string[]): number {
  const length = Math.min(a.length, b.length);
  for (let index = 0; index < length; index += 1) {
    if (a[index] < b[index]) return -1;
    if (a[index] > b[index]) return 1;
  }
  return a.length - b.length;
}

/** Um diretório só vale a descida se puder conter algum nome depois do cursor. */
function mayContainAfter(directoryName: string, after: readonly string[]): boolean {
  const segments = directoryName.split('/');
  const shared = Math.min(segments.length, after.length);
  for (let index = 0; index < shared; index += 1) {
    if (segments[index] < after[index]) return false;
    if (segments[index] > after[index]) return true;
  }
  // O diretório é um prefixo do cursor (ou igual a ele): pode conter nomes depois.
  return true;
}
