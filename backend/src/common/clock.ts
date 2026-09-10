/**
 * O relógio do servidor, injetável (T17.3 §243–§245).
 *
 * ## Por que ele nasce agora, e não antes
 *
 * Até a T17.2 nenhum comportamento do Spark Backend **dependia** de que horas são. `Date.now()`
 * aparecia como carimbo (`created_at`, `updated_at`, `applied_at`) e como medida de duração — e um
 * carimbo não precisa ser controlável para ser testável: o teste compara o que foi gravado com o
 * que foi devolvido, e o valor exato não importa.
 *
 * O desafio muda isso. O ciclo de vida dele é **derivado do tempo** (§27): `UPCOMING`, `ACTIVE` e
 * `ENDED` não são colunas que alguém escreve, são a comparação entre o instante de agora e a
 * janela do desafio. Testar "aceitar depois do início é recusado" com `Date.now()` real exigiria
 * criar um desafio que começa em dois segundos e dormir — que é exatamente o teste instável que
 * §244 proíbe.
 *
 * ## Pequeno de propósito
 *
 * Um método. Sem `advance()`, sem congelamento global, sem fuso, sem formatação, sem
 * agendamento. §245 é explícito: introduzir uma abstração pequena e reutilizável, **não** um
 * framework de tempo. Quem precisa de fuso usa `social-time.ts`, que trata da outra pergunta —
 * "que dia local é este instante" — e não de "que instante é agora".
 *
 * ## O que ele não é
 *
 * Ele não é autoridade de tempo do **cliente**. O Android não envia `createdAt`, `startedAt` nem
 * `endedAt`, e nenhum valor de relógio de aparelho entra por aqui (§14). Este relógio é o do
 * processo do servidor, e é o único que decide se um desafio já começou.
 */
export interface Clock {
  /** O instante atual, em epoch millis UTC. */
  now(): number;
}

/** O relógio de verdade. É ele que o processo usa em produção. */
export class SystemClock implements Clock {
  now(): number {
    return Date.now();
  }
}

/**
 * O token de injeção.
 *
 * Interface no ponto de injeção, e não a classe concreta: é o que permite a um teste fixar o
 * instante sem tocar no relógio global do processo — `jest.useFakeTimers()` alcançaria também o
 * o driver `pg`, o logger e qualquer `setTimeout` do Nest, e um teste que congela o mundo
 * inteiro para verificar uma regra de domínio é um teste que quebra por motivos que não têm nada
 * a ver com a regra.
 */
export const CLOCK = Symbol('CLOCK');
