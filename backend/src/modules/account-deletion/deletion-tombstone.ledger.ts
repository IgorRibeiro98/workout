import {
  closeSync,
  existsSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeSync,
} from 'node:fs';
import { dirname } from 'node:path';
import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../../config/app-config';

/**
 * Uma linha do ledger: `<hmac hex de 64 caracteres>\t<epoch em milissegundos>`.
 *
 * Estrita de propósito (§18). O arquivo é a **única** memória de uma exclusão que sobrevive à
 * substituição do arquivo do banco: se ele for lido com tolerância, um hash truncado por uma
 * escrita interrompida vira um hash que não casa com conta nenhuma, e a reconciliação passa por
 * ele sem apagar nada — em silêncio, que é o modo de falha que este arquivo existe para evitar.
 *
 * 64 hexadecimais é exatamente o comprimento de um HMAC-SHA256 em hex. Um timestamp com sinal, com
 * separador decimal ou em notação científica não é algo que este servidor escreve.
 */
const LEDGER_LINE = /^([0-9a-f]{64})\t(\d{1,15})$/;

/** O ledger não pôde ser lido, escrito ou validado. Nunca é engolida (§8). */
export class DeletionTombstoneLedgerError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DeletionTombstoneLedgerError';
  }
}

export interface LedgerContents {
  /** Os hashes distintos. Conjunto por contrato (§13): reconciliar o mesmo hash duas vezes é a mesma operação. */
  readonly hashes: Set<string>;
  /** Quantas linhas o arquivo tinha, incluindo repetições — o número que o operador confere. */
  readonly lineCount: number;
}

/**
 * O ledger anti-ressurreição de exclusões de conta (T17.13.1 §8–§13).
 *
 * ## O que este arquivo é
 *
 * `deletion_tombstones.tsv` é um append-only fora do SQLite que guarda o HMAC de cada conta
 * excluída. Ele existe por um motivo que o tombstone do banco não cobre: numa restauração de
 * desastre o **arquivo do banco inteiro** é substituído por uma cópia anterior, e com ela voltam
 * as contas que já tinham sido excluídas — inclusive o próprio `account_deletion_tombstones`, que
 * na versão restaurada ainda não conhece aquela exclusão.
 *
 * O ledger vive ao lado do banco e não é sobrescrito pelo restore. É ele que responde "quem já foi
 * excluído?" para a reconciliação que roda depois (`spark-reconcile-account-deletions`).
 *
 * ## Por que ele não é mais best-effort
 *
 * Até a T17.13 a escrita era `appendFileSync` dentro de um `catch {}` vazio, e a exclusão
 * respondia `DELETED` mesmo quando o arquivo não recebia nada. Disco cheio, volume montado
 * somente-leitura ou diretório com permissão errada produziam a pior combinação possível: conta
 * apagada do banco, pessoa informada de que a exclusão terminou, e nenhum registro capaz de
 * impedir o próximo restore de trazê-la de volta.
 *
 * Agora a falha propaga, e o serviço deixa o job em `LEDGER_PENDING` (§10).
 *
 * ## Durabilidade: por que `appendFileSync` não bastava (§12)
 *
 * `appendFileSync` retorna quando o `write(2)` foi aceito pelo **cache de página** do sistema
 * operacional — não quando os bytes alcançaram o disco. Ele é suficiente contra a queda do
 * processo e insuficiente contra a queda da máquina, que é exatamente o cenário de desastre em que
 * este arquivo é a única testemunha da exclusão. Uma queda de energia entre o `write` e o flush
 * periódico do kernel devolve um ledger sem a última exclusão.
 *
 * Por isso a escrita é explícita:
 *
 * 1. `openSync(path, 'a')` — `O_APPEND`, atômico para escritas pequenas mesmo com concorrência;
 * 2. `writeSync` — uma chamada, uma linha, sem escrita parcial de registro;
 * 3. `fsyncSync(fd)` — os bytes vão ao disco antes de a função retornar;
 * 4. quando o arquivo **acabou de ser criado**, `fsync` também no diretório: sem ele, o conteúdo
 *    estaria durável e a entrada de diretório que o nomeia não, e o arquivo poderia não existir
 *    após a queda.
 *
 * O custo é um `fsync` por exclusão de conta — uma operação rara, iniciada por uma pessoa, cuja
 * latência ninguém percebe. É a troca certa: aqui, durabilidade vale mais que vazão.
 */
@Injectable()
export class DeletionTombstoneLedger {
  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  get filePath(): string {
    return this.config.deletionTombstonesFilePath;
  }

  exists(): boolean {
    return existsSync(this.filePath);
  }

  /**
   * Acrescenta um hash ao ledger e só retorna quando ele está no disco.
   *
   * Lança [DeletionTombstoneLedgerError] em qualquer falha. Quem chama **precisa** tratar: um
   * `DELETED` devolvido depois de uma falha aqui é uma promessa que o servidor não pode cumprir.
   *
   * Repetir o mesmo hash é permitido e esperado (§13): o retry depois de uma falha parcial pode
   * escrever de novo, e o leitor consome hashes como conjunto.
   */
  appendDurably(uidHash: string, deletedAt: number): void {
    if (!/^[0-9a-f]{64}$/.test(uidHash)) {
      throw new DeletionTombstoneLedgerError('hash de tombstone fora do formato esperado');
    }

    const filePath = this.filePath;
    const directory = dirname(filePath);
    let fd: number | undefined;
    try {
      if (!existsSync(directory)) {
        mkdirSync(directory, { recursive: true });
      }
      const isNewFile = !existsSync(filePath);

      fd = openSync(filePath, 'a');
      writeSync(fd, `${uidHash}\t${Math.trunc(deletedAt)}\n`, null, 'utf8');
      fsyncSync(fd);
      closeSync(fd);
      fd = undefined;

      if (isNewFile) {
        // A entrada de diretório precisa ser durável junto com o conteúdo. Um `fsync` de
        // diretório não é suportado em todo sistema de arquivos (nem no Windows); onde ele falha,
        // o conteúdo já está sincronizado e a falha aqui não invalida a escrita.
        let dirFd: number | undefined;
        try {
          dirFd = openSync(directory, 'r');
          fsyncSync(dirFd);
        } catch {
          // Sem o caminho na mensagem, e sem derrubar a exclusão: ver acima.
        } finally {
          if (dirFd !== undefined) closeSync(dirFd);
        }
      }
    } catch (error) {
      if (fd !== undefined) {
        try {
          closeSync(fd);
        } catch {
          // Um descritor que não fecha não muda o veredito: a escrita falhou.
        }
      }
      // Sem o caminho e sem o hash na mensagem (§161): ela vai para log.
      throw new DeletionTombstoneLedgerError(
        `não foi possível persistir o ledger de exclusões: ${
          error instanceof Error ? error.name : 'erro desconhecido'
        }`,
      );
    }
  }

  /**
   * Lê e **valida** o ledger inteiro (§13/§17/§18).
   *
   * Ausência do arquivo e linha malformada são erro, e nunca "zero exclusões": as duas
   * interpretações produzem o mesmo resultado visível — a reconciliação não apaga nada — e uma
   * delas ressuscita contas. Quem decide se a ausência é aceitável é o chamador, que sabe se está
   * numa operação de DR declarada; o leitor não adivinha.
   */
  readHashes(): LedgerContents {
    const filePath = this.filePath;
    if (!existsSync(filePath)) {
      throw new DeletionTombstoneLedgerError(
        'o ledger de exclusões não existe no caminho configurado',
      );
    }

    let raw: string;
    try {
      raw = readFileSync(filePath, 'utf8');
    } catch (error) {
      throw new DeletionTombstoneLedgerError(
        `não foi possível ler o ledger de exclusões: ${
          error instanceof Error ? error.name : 'erro desconhecido'
        }`,
      );
    }

    const hashes = new Set<string>();
    let lineCount = 0;
    const lines = raw.split('\n');
    for (const [index, line] of lines.entries()) {
      // Um arquivo append-only terminado em `\n` produz um último elemento vazio. Só esse é
      // ignorado; uma linha em branco no meio é defeito e precisa aparecer.
      if (line === '' && index === lines.length - 1) {
        continue;
      }
      const match = LEDGER_LINE.exec(line);
      if (!match) {
        // O número da linha ajuda o operador; o conteúdo dela não vai para a mensagem.
        throw new DeletionTombstoneLedgerError(
          `linha ${index + 1} do ledger de exclusões está malformada`,
        );
      }
      hashes.add(match[1]);
      lineCount += 1;
    }

    return { hashes, lineCount };
  }
}
