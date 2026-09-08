import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  FRIEND_CODE_ALPHABET,
  FRIEND_CODE_PREFIX,
  FRIEND_CODE_RANDOM_LENGTH,
  FRIEND_CODE_SEPARATOR,
} from '../src/modules/social/social.limits';
import { normalizeFriendCode } from '../src/modules/social/social.identity';

interface NormalizationCase {
  readonly input: string;
  readonly normalized: string | null;
  readonly why: string;
}

interface Fixture {
  readonly canonical: {
    readonly prefix: string;
    readonly separator: string;
    readonly alphabet: string;
    readonly randomLength: number;
  };
  readonly cases: readonly NormalizationCase[];
}

const FIXTURE_PATH = join(
  __dirname,
  '..',
  '..',
  'contracts',
  'social',
  'v1',
  'friend-code-normalization.json',
);

/**
 * A normalização de `friendCode`, contra a fixture **compartilhada** com o Android.
 *
 * ## Por que uma fixture, e não só um teste de cada lado
 *
 * A T17.1 precisa de uma resposta imediata na tela — habilitar o botão "Procurar", recusar um QR
 * malformado antes de fazer requisição. Isso significa que o Android conhece o formato. E o
 * momento em que dois lados conhecem o mesmo formato é o momento em que eles começam a divergir:
 * alguém acrescenta um símbolo ao alfabeto no servidor, o app continua recusando-o, e o defeito
 * aparece como "o convite do meu amigo não funciona" — sem nenhum teste ficando vermelho.
 *
 * Esta fixture é o que impede isso. Ela é o mesmo arquivo lido pelos dois lados
 * (`FriendCodeContractTest.kt` no Android), então uma mudança unilateral quebra o teste de quem
 * mudou.
 *
 * **A autoridade continua sendo o servidor.** O Android nunca decide um lookup: ele manda o que o
 * usuário digitou, e quem normaliza e responde é `normalizeFriendCode` aqui.
 */
describe('Contrato do friendCode: normalização compartilhada', () => {
  const fixture = JSON.parse(readFileSync(FIXTURE_PATH, 'utf8')) as Fixture;

  it('a fixture descreve o formato que o servidor realmente usa', () => {
    // Se este teste falhar, a fixture está descrevendo outro produto — e todos os casos abaixo
    // estariam provando alguma coisa sobre um formato que não existe.
    expect(fixture.canonical.prefix).toBe(FRIEND_CODE_PREFIX);
    expect(fixture.canonical.separator).toBe(FRIEND_CODE_SEPARATOR);
    expect(fixture.canonical.alphabet).toBe(FRIEND_CODE_ALPHABET);
    expect(fixture.canonical.randomLength).toBe(FRIEND_CODE_RANDOM_LENGTH);
  });

  it('a fixture cobre aceitação e recusa', () => {
    expect(fixture.cases.some((testCase) => testCase.normalized !== null)).toBe(true);
    expect(fixture.cases.some((testCase) => testCase.normalized === null)).toBe(true);
    expect(fixture.cases.length).toBeGreaterThanOrEqual(15);
  });

  it.each(fixture.cases.map((c) => [c.input, c.normalized, c.why] as const))(
    'normalizeFriendCode(%p) === %p (%s)',
    (input, expected) => {
      expect(normalizeFriendCode(input)).toBe(expected);
    },
  );

  it('os ambíguos não são "corrigidos" para um vizinho', () => {
    // Recusar é a resposta certa: adivinhar que `O` era `0` inventaria o código de outra pessoa —
    // e o alfabeto existe justamente para que essa dúvida não apareça em um código gerado.
    for (const ambiguous of ['O', '0', 'I', '1', 'L']) {
      expect(FRIEND_CODE_ALPHABET.includes(ambiguous)).toBe(false);
      expect(normalizeFriendCode(`SPK-7K2P9D8${ambiguous}`)).toBeNull();
    }
  });
});
