import { createHash, randomUUID } from 'node:crypto';
import type { Readable } from 'node:stream';
import type {
  ObjectStorageClient,
  StoredObjectSummary,
} from '../../object-storage/object-storage.client';
import { OBJECT_STORAGE_LIST_PAGE_SIZE } from '../../object-storage/object-storage.limits';
import { OUTPUT_EXTENSION } from './social-media.limits';

/**
 * A fronteira de armazenamento de mídia social (T17.9 §22, T18.1).
 *
 * ## Por que ela existe como interface
 *
 * Porque "onde os bytes moram" é a decisão que mais provavelmente muda depois — e mudou: na
 * T17.9 era o volume da VPS, desde a T18.1 pode ser um bucket privado do Google Cloud Storage.
 * Uma interface estreita — dá para gravar, ler, apagar e listar — é o que permitiu trocar isso
 * sem tocar em autorização, validação, quota ou DTO. E é o que garante que **nada** além desta
 * fronteira sabe traduzir uma chave em caminho ou em nome de objeto: o resto do módulo conhece
 * `storageKey`, e só.
 *
 * ## O que ela nunca aceita
 *
 * Um caminho vindo do cliente (§24/§25). A chave é **gerada aqui** ([newStorageKey]), e toda
 * operação a valida antes de tocar no provider. Um `../../etc/passwd` que chegasse pela rede não
 * atravessa `assertSafeStorageKey`, e mesmo que atravessasse, o provider local recusa qualquer
 * caminho resolvido fora da raiz — duas barreiras, porque path traversal é a falha em que uma
 * barreira sozinha historicamente falha.
 */
export interface SocialMediaStore {
  /** Gera uma chave opaca nova. Nunca deriva de uid, nome, `friendCode` ou nome de arquivo (§23). */
  newStorageKey(): string;

  /** Grava **uma vez**: uma chave já ocupada é erro, nunca sobrescrita (T18.1 §10). */
  write(storageKey: string, bytes: Buffer): Promise<void>;

  /** `null` quando o objeto não existe — restore inconsistente não pode derrubar o backend (§141). */
  openRead(storageKey: string): Promise<Readable | null>;

  /**
   * Os bytes inteiros, ou `null` quando o objeto não existe (T18.1.1). Usado pelo migrador de
   * mídia legada (`migrate-social-media-to-object-storage`), que precisa dos bytes inteiros — de
   * origem e de destino — para o SHA-256, não de um stream.
   */
  read(storageKey: string): Promise<Buffer | null>;

  exists(storageKey: string): Promise<boolean>;

  /** Idempotente: apagar o que já não existe é sucesso. */
  remove(storageKey: string): Promise<void>;

  /**
   * Uma página das chaves presentes no armazenamento, com a data de criação de cada uma — para a
   * varredura de órfãos (§140), que precisa da data para respeitar o período de carência
   * (T18.1 §16). Sempre bounded e sempre restrita ao namespace da mídia: um objeto de backup no
   * mesmo bucket nunca aparece aqui.
   */
  listObjects(pageToken?: string): Promise<SocialMediaPage>;
}

export interface StoredMediaObject {
  readonly storageKey: string;
  /** `null` quando o provider não conseguiu provar a idade do objeto (T18.1.1 §9) — nunca `0`. */
  readonly createdAt: number | null;
}

export interface SocialMediaPage {
  readonly objects: readonly StoredMediaObject[];
  readonly nextPageToken?: string;
}

export const SOCIAL_MEDIA_STORE = Symbol('SOCIAL_MEDIA_STORE');

/** O prefixo de todas as chaves desta fase. Um diretório por propósito (§23). */
const CHECKIN_PREFIX = 'checkins';

/**
 * O namespace da mídia dentro do bucket compartilhado (T18.1 §8/§9).
 *
 * A chave no PostgreSQL continua sendo `checkins/xx/yy/<uuid>.webp`; o objeto vive em
 * `social/checkins/xx/yy/<uuid>.webp`. A tradução acontece **aqui**, e só aqui — nenhuma linha
 * de `social_checkin_media` precisou mudar, e o domínio continua sem saber que existe um bucket.
 */
const OBJECT_NAMESPACE = 'social/';

/**
 * A forma de uma chave válida: `checkins/<2 hex>/<2 hex>/<uuid v4>.webp`.
 *
 * O fan-out de dois níveis existe para que o diretório não vire uma pasta com dezenas de milhares
 * de entradas — o que degrada `readdir` e algumas operações de sistema de arquivos muito antes de
 * o disco encher. Os dois nibbles saem do próprio UUID, então a chave continua sendo uma coisa só.
 */
const KEY_PATTERN = new RegExp(
  `^${CHECKIN_PREFIX}/[0-9a-f]{2}/[0-9a-f]{2}/` +
    `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.${OUTPUT_EXTENSION}$`,
);

export class UnsafeStorageKeyError extends Error {
  constructor() {
    // Sem o valor recusado na mensagem: ela pode acabar em log, e o valor veio de fora.
    super('chave de armazenamento inválida');
    this.name = 'UnsafeStorageKeyError';
  }
}

/**
 * A validação de chave, isolada e pura — para que o teste possa provar §25 sem tocar em disco.
 *
 * Recusa tudo o que não for exatamente a forma gerada por [ObjectStorageSocialMediaStore.newStorageKey]:
 * caminho absoluto, `..` em qualquer posição, separador do Windows, byte nulo, extensão diferente.
 * Uma allowlist estrita, e não uma blocklist de sequências perigosas — blocklist é o desenho em
 * que sempre falta uma codificação.
 */
export function assertSafeStorageKey(storageKey: unknown): string {
  if (typeof storageKey !== 'string' || !KEY_PATTERN.test(storageKey)) {
    throw new UnsafeStorageKeyError();
  }
  return storageKey;
}

/**
 * A mídia sobre o Object Storage do processo (T18.1 §6/§13).
 *
 * Uma implementação só, para os dois providers: quem decide entre o disco local e o bucket é o
 * `ObjectStorageClient` injetado — escolhido em `object-storage.factory.ts`, nunca aqui. O que
 * este adaptador acrescenta ao cliente é o que é **do domínio**: a forma da chave, o namespace
 * `social/` e a garantia de que a listagem nunca sai dele.
 */
export class ObjectStorageSocialMediaStore implements SocialMediaStore {
  constructor(private readonly client: ObjectStorageClient) {}

  newStorageKey(): string {
    const id = randomUUID();
    // Os dois níveis vêm do próprio identificador: nada de aleatoriedade extra a guardar, e a
    // chave continua sendo derivável de si mesma.
    return `${CHECKIN_PREFIX}/${id.slice(0, 2)}/${id.slice(2, 4)}/${id}.${OUTPUT_EXTENSION}`;
  }

  async write(storageKey: string, bytes: Buffer): Promise<void> {
    // A colisão sobe como está: quem chama gerou a chave agora, e uma chave já ocupada é um
    // defeito a investigar — nunca um objeto a substituir.
    await this.client.write(objectNameOf(storageKey), bytes, {
      contentType: 'image/webp',
      metadata: { 'spark-sha256': contentHashOf(bytes) },
    });
  }

  async openRead(storageKey: string): Promise<Readable | null> {
    return this.client.openRead(objectNameOf(storageKey));
  }

  async read(storageKey: string): Promise<Buffer | null> {
    return this.client.read(objectNameOf(storageKey));
  }

  async exists(storageKey: string): Promise<boolean> {
    return this.client.exists(objectNameOf(storageKey));
  }

  async remove(storageKey: string): Promise<void> {
    await this.client.remove(objectNameOf(storageKey));
  }

  async listObjects(pageToken?: string): Promise<SocialMediaPage> {
    const page = await this.client.list(`${OBJECT_NAMESPACE}${CHECKIN_PREFIX}/`, {
      pageSize: OBJECT_STORAGE_LIST_PAGE_SIZE,
      pageToken,
    });
    return {
      // Só o que tem a forma de uma chave de mídia. Um objeto estranho sob o prefixo — um upload
      // manual, um resto de outra versão — não vira "órfão a apagar": ele não é nosso para apagar.
      objects: page.objects.flatMap((object) => toStoredMedia(object)),
      nextPageToken: page.nextPageToken,
    };
  }
}

function objectNameOf(storageKey: string): string {
  return `${OBJECT_NAMESPACE}${assertSafeStorageKey(storageKey)}`;
}

function toStoredMedia(object: StoredObjectSummary): StoredMediaObject[] {
  if (!object.name.startsWith(OBJECT_NAMESPACE)) {
    return [];
  }
  const storageKey = object.name.slice(OBJECT_NAMESPACE.length);
  if (!KEY_PATTERN.test(storageKey)) {
    return [];
  }
  return [{ storageKey, createdAt: object.createdAt }];
}

/** SHA-256 da representação **sanitizada** (§37). Integridade, nunca autorização. */
export function contentHashOf(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}
