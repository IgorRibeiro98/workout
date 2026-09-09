import { createHash, randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import { mkdir, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import type { Readable } from 'node:stream';
import { isAbsolute, join, resolve, sep } from 'node:path';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { OUTPUT_EXTENSION } from './social-media.limits';

/**
 * A fronteira de armazenamento de mídia social (T17.9 §22).
 *
 * ## Por que ela existe como interface
 *
 * Porque "onde os bytes moram" é a decisão que mais provavelmente muda depois: hoje é o volume da
 * VPS (ADR-0001, um processo, uma máquina), amanhã pode ser um bucket. Uma interface estreita — dá
 * para gravar, ler, apagar e listar — é o que permite trocar isso sem tocar em autorização,
 * validação, quota ou DTO. E é o que garante que **nada** além desta fronteira monte caminho de
 * arquivo: o resto do módulo conhece `storageKey`, e só.
 *
 * ## O que ela nunca aceita
 *
 * Um caminho vindo do cliente (§24/§25). A chave é **gerada aqui** ([newStorageKey]), e toda
 * operação a valida antes de tocar no disco. Um `../../etc/passwd` que chegasse pela rede não
 * atravessa `assertSafeKey`, e mesmo que atravessasse, `resolve` fora da raiz é recusado — duas
 * barreiras, porque path traversal é a falha em que uma barreira sozinha historicamente falha.
 */
export interface SocialMediaStore {
  /** Gera uma chave opaca nova. Nunca deriva de uid, nome, `friendCode` ou nome de arquivo (§23). */
  newStorageKey(): string;

  write(storageKey: string, bytes: Buffer): Promise<void>;

  /** `null` quando o arquivo não existe — restore inconsistente não pode derrubar o backend (§141). */
  openRead(storageKey: string): Promise<Readable | null>;

  exists(storageKey: string): Promise<boolean>;

  /** Idempotente: apagar o que já não existe é sucesso. */
  remove(storageKey: string): Promise<void>;

  /** Todas as chaves presentes no armazenamento, para a varredura de órfãos (§140). */
  listKeys(): Promise<string[]>;
}

export const SOCIAL_MEDIA_STORE = Symbol('SOCIAL_MEDIA_STORE');

/** O prefixo de todos os arquivos desta fase. Um diretório por propósito (§23). */
const CHECKIN_PREFIX = 'checkins';

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
 * Recusa tudo o que não for exatamente a forma gerada por [LocalSocialMediaStore.newStorageKey]:
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
 * O armazenamento em disco do volume persistente da VPS (§22/§26).
 *
 * A raiz vem de `SOCIAL_MEDIA_ROOT`, obrigatória em produção (§28) — ver `AppConfig`.
 */
@Injectable()
export class LocalSocialMediaStore implements SocialMediaStore {
  private readonly root: string;

  constructor(@Inject(APP_CONFIG) config: AppConfig) {
    this.root = resolve(config.socialMediaRoot);
  }

  newStorageKey(): string {
    const id = randomUUID();
    // Os dois níveis vêm do próprio identificador: nada de aleatoriedade extra a guardar, e a
    // chave continua sendo derivável de si mesma.
    return `${CHECKIN_PREFIX}/${id.slice(0, 2)}/${id.slice(2, 4)}/${id}.${OUTPUT_EXTENSION}`;
  }

  async write(storageKey: string, bytes: Buffer): Promise<void> {
    const target = this.absolutePathOf(storageKey);
    await mkdir(join(target, '..'), { recursive: true });
    // `wx`: falhar se já existir. Uma chave é usada uma vez; reusá-la seria sobrescrever a foto de
    // alguém, e prefiro que isso seja um erro barulhento a um silêncio.
    await writeFile(target, bytes, { flag: 'wx', mode: 0o640 });
  }

  async openRead(storageKey: string): Promise<Readable | null> {
    const target = this.absolutePathOf(storageKey);
    // `stat`, e não `existsSync`: além de ser assíncrono (esta é a única operação de leitura no
    // caminho de uma requisição de imagem), ele confirma que o caminho é um **arquivo**. Um
    // diretório com o nome certo abriria um stream que falha depois, no meio da resposta.
    try {
      const info = await stat(target);
      if (!info.isFile()) {
        return null;
      }
    } catch {
      // §141 — metadata apontando para arquivo ausente (restore parcial) devolve ausência, e não
      // uma exceção que derrubaria a requisição. Quem chama decide o que dizer ao cliente.
      return null;
    }
    return createReadStream(target);
  }

  async exists(storageKey: string): Promise<boolean> {
    try {
      const info = await stat(this.absolutePathOf(storageKey));
      return info.isFile();
    } catch {
      return false;
    }
  }

  async remove(storageKey: string): Promise<void> {
    await rm(this.absolutePathOf(storageKey), { force: true });
  }

  async listKeys(): Promise<string[]> {
    const base = join(this.root, CHECKIN_PREFIX);
    const found: string[] = [];
    await this.collect(base, CHECKIN_PREFIX, found);
    return found;
  }

  private async collect(directory: string, prefix: string, out: string[]): Promise<void> {
    let entries;
    try {
      entries = await readdir(directory, { withFileTypes: true });
    } catch {
      // Raiz ainda não criada é um estado normal em um servidor que nunca recebeu foto.
      return;
    }
    for (const entry of entries) {
      const key = `${prefix}/${entry.name}`;
      if (entry.isDirectory()) {
        await this.collect(join(directory, entry.name), key, out);
      } else if (entry.isFile() && KEY_PATTERN.test(key)) {
        out.push(key);
      }
    }
  }

  /**
   * A tradução de chave para caminho — o **único** ponto do servidor que a faz (§25).
   *
   * Duas barreiras, de propósito. A primeira é a allowlist de forma; a segunda confere que o
   * caminho resolvido continua dentro da raiz, o que pega qualquer coisa que a primeira deixasse
   * passar (um symlink no meio do caminho, uma codificação que o regex não previu). Uma barreira
   * só é o desenho em que path traversal reaparece na próxima refatoração.
   */
  private absolutePathOf(storageKey: string): string {
    const safe = assertSafeStorageKey(storageKey);
    const absolute = resolve(this.root, safe);
    if (isAbsolute(safe) || (absolute !== this.root && !absolute.startsWith(this.root + sep))) {
      throw new UnsafeStorageKeyError();
    }
    return absolute;
  }
}

/** SHA-256 da representação **sanitizada** (§37). Integridade, nunca autorização. */
export function contentHashOf(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}
