import { createHash } from 'node:crypto';

/**
 * A forma canônica de um documento JSON, e o SHA-256 sobre ela (T16.4).
 *
 * ## Por que não canonicalizar a partir do valor já parseado
 *
 * A saída óbvia — `JSON.parse` e reserializar com chaves ordenadas — falha por um motivo concreto:
 * o outro lado deste contrato é Kotlin. `Float.toString()` do Kotlin e `JSON.stringify` do
 * JavaScript não formatam o mesmo número do mesmo jeito, porque um serializa `Float` e o outro
 * `double`. Uma carga de `60.0 kg` viraria dois textos diferentes, dois hashes diferentes, e a
 * idempotência do backup passaria a depender de os dois lados imitarem o formatador do outro.
 *
 * Então a canonicalização acontece sobre o **texto**: os tokens escalares são copiados
 * literalmente da origem, e o servidor reproduz exatamente os bytes que o cliente produziu. Nenhum
 * dos dois precisa saber como o outro formata número.
 *
 * ## A definição (contracts/backup/v1/README.md §7)
 *
 * 1. nenhum espaço insignificante;
 * 2. membros de objeto ordenados pelo token da chave (unidade de código UTF-16);
 * 3. arrays na ordem em que chegaram;
 * 4. todo token escalar copiado verbatim;
 * 5. chave repetida no mesmo objeto é erro.
 *
 * [parseCanonical] devolve, além do texto, o valor JavaScript e a árvore — para que a validação
 * use o valor e a persistência guarde o **texto original de cada item**, sem reserializar.
 *
 * O parser é estrito de propósito: recusa o que `JSON.parse` também recusaria, e não tenta ser
 * tolerante. Entrada não confiável não ganha benefício da dúvida.
 */
export class CanonicalJsonError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CanonicalJsonError';
  }
}

export interface CanonicalNode {
  /** O texto canônico deste nó. */
  readonly text: string;
  /** O valor JavaScript equivalente, para validação. */
  readonly value: unknown;
  /** Membros, quando o nó é objeto. */
  readonly members?: ReadonlyMap<string, CanonicalNode>;
  /** Elementos, quando o nó é array. */
  readonly elements?: readonly CanonicalNode[];
}

/** O documento canonicalizado, com valor e árvore. */
export function parseCanonical(source: string): CanonicalNode {
  const scanner = new Scanner(source);
  scanner.skipWhitespace();
  const node = scanner.readValue();
  scanner.skipWhitespace();
  if (!scanner.atEnd) {
    throw new CanonicalJsonError('conteúdo depois do fim do documento JSON');
  }
  return node;
}

/** A forma canônica do documento JSON em [source]. */
export function canonicalize(source: string): string {
  return parseCanonical(source).text;
}

/** SHA-256 hexadecimal minúsculo do texto informado, em UTF-8. */
export function sha256Hex(text: string): string {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

const WHITESPACE = new Set([' ', '\t', '\n', '\r']);

class Scanner {
  private index = 0;

  constructor(private readonly source: string) {}

  get atEnd(): boolean {
    return this.index >= this.source.length;
  }

  skipWhitespace(): void {
    while (this.index < this.source.length && WHITESPACE.has(this.source[this.index])) {
      this.index += 1;
    }
  }

  readValue(): CanonicalNode {
    if (this.atEnd) {
      throw new CanonicalJsonError('documento JSON vazio ou truncado');
    }
    const char = this.source[this.index];
    if (char === '{') return this.readObject();
    if (char === '[') return this.readArray();
    if (char === '"') {
      const token = this.readStringToken();
      return { text: token, value: JSON.parse(token) as string };
    }
    if (char === 't') return { text: this.readLiteral('true'), value: true };
    if (char === 'f') return { text: this.readLiteral('false'), value: false };
    if (char === 'n') return { text: this.readLiteral('null'), value: null };
    const token = this.readNumberToken();
    return { text: token, value: Number(token) };
  }

  private readObject(): CanonicalNode {
    this.expect('{');
    this.skipWhitespace();

    const members = new Map<string, CanonicalNode>();
    const ordered: Array<{ keyToken: string; text: string }> = [];
    const value: Record<string, unknown> = {};

    if (this.peek() === '}') {
      this.index += 1;
      return { text: '{}', value, members };
    }

    for (;;) {
      this.skipWhitespace();
      if (this.peek() !== '"') {
        throw new CanonicalJsonError('chave de objeto precisa ser string');
      }
      // O token da chave, com aspas, é o que ordena e o que é reemitido. Para as chaves deste
      // contrato — ASCII, sem escape — ordenar pelo token é idêntico a ordenar pela chave
      // decodificada, e não depende de decodificar escape nenhum.
      const keyToken = this.readStringToken();
      const key = JSON.parse(keyToken) as string;
      if (members.has(key)) {
        throw new CanonicalJsonError('chave repetida no mesmo objeto');
      }

      this.skipWhitespace();
      this.expect(':');
      this.skipWhitespace();
      const node = this.readValue();

      members.set(key, node);
      value[key] = node.value;
      ordered.push({ keyToken, text: `${keyToken}:${node.text}` });

      this.skipWhitespace();
      const next = this.peek();
      if (next === ',') {
        this.index += 1;
        continue;
      }
      if (next === '}') {
        this.index += 1;
        break;
      }
      throw new CanonicalJsonError('objeto JSON malformado');
    }

    ordered.sort((a, b) => (a.keyToken < b.keyToken ? -1 : a.keyToken > b.keyToken ? 1 : 0));
    return {
      text: `{${ordered.map((member) => member.text).join(',')}}`,
      value,
      members,
    };
  }

  private readArray(): CanonicalNode {
    this.expect('[');
    this.skipWhitespace();

    if (this.peek() === ']') {
      this.index += 1;
      return { text: '[]', value: [], elements: [] };
    }

    const elements: CanonicalNode[] = [];
    for (;;) {
      this.skipWhitespace();
      elements.push(this.readValue());
      this.skipWhitespace();
      const next = this.peek();
      if (next === ',') {
        this.index += 1;
        continue;
      }
      if (next === ']') {
        this.index += 1;
        break;
      }
      throw new CanonicalJsonError('array JSON malformado');
    }
    return {
      text: `[${elements.map((element) => element.text).join(',')}]`,
      value: elements.map((element) => element.value),
      elements,
    };
  }

  /** O token da string, com aspas e escapes exatamente como vieram. */
  private readStringToken(): string {
    const start = this.index;
    this.expect('"');
    for (;;) {
      if (this.atEnd) {
        throw new CanonicalJsonError('string JSON não terminada');
      }
      const char = this.source[this.index];
      if (char === '\\') {
        this.index += 2;
        continue;
      }
      this.index += 1;
      if (char === '"') {
        return this.source.slice(start, this.index);
      }
      // Caractere de controle cru dentro de string é inválido em JSON.
      if (char < ' ') {
        throw new CanonicalJsonError('caractere de controle não escapado em string JSON');
      }
    }
  }

  /** O token do número, verbatim. É esta cópia literal que faz os dois lados fecharem o hash. */
  private readNumberToken(): string {
    const start = this.index;
    if (this.peek() === '-') this.index += 1;

    // A parte inteira segue o JSON: `0` sozinho, ou `[1-9]` seguido de dígitos. Aceitar `01`
    // deixaria dois textos diferentes para o mesmo valor, e a forma canônica descreve o texto.
    const first = this.peek();
    if (first === '0') {
      this.index += 1;
      const following = this.peek();
      if (following !== undefined && following >= '0' && following <= '9') {
        throw new CanonicalJsonError('número JSON com zero à esquerda');
      }
    } else if (this.readDigits() === 0) {
      throw new CanonicalJsonError('número JSON malformado');
    }
    if (this.peek() === '.') {
      this.index += 1;
      if (this.readDigits() === 0) {
        throw new CanonicalJsonError('número JSON malformado');
      }
    }
    const exponent = this.peek();
    if (exponent === 'e' || exponent === 'E') {
      this.index += 1;
      const sign = this.peek();
      if (sign === '+' || sign === '-') this.index += 1;
      if (this.readDigits() === 0) {
        throw new CanonicalJsonError('número JSON malformado');
      }
    }
    return this.source.slice(start, this.index);
  }

  private readDigits(): number {
    const start = this.index;
    while (this.index < this.source.length) {
      const char = this.source[this.index];
      if (char < '0' || char > '9') break;
      this.index += 1;
    }
    return this.index - start;
  }

  private readLiteral(literal: string): string {
    if (this.source.startsWith(literal, this.index)) {
      this.index += literal.length;
      return literal;
    }
    throw new CanonicalJsonError('literal JSON inválido');
  }

  private peek(): string | undefined {
    return this.source[this.index];
  }

  private expect(char: string): void {
    if (this.source[this.index] !== char) {
      throw new CanonicalJsonError(`esperado "${char}" no documento JSON`);
    }
    this.index += 1;
  }
}
