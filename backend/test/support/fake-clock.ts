import type { Clock } from '../../src/common/clock';

/**
 * Um relógio controlado, para os testes de ciclo de vida (T17.3 §243/§244).
 *
 * O desafio é a primeira coisa do Spark Backend cujo comportamento **deriva do tempo**:
 * `UPCOMING`, `ACTIVE` e `ENDED` são a comparação entre agora e a janela. Testar "aceitar depois
 * do início é recusado" com o relógio real exigiria criar um desafio que começa em segundos e
 * dormir — que é exatamente o teste instável que §244 proíbe.
 *
 * Ele fica em `test/`, e só em `test/`: não existe provider, variável de ambiente ou flag que faça
 * o processo de produção usá-lo. `SystemClock` é o único registrado no `CommonModule`.
 *
 * Deliberadamente pobre: um instante e um `set`. Sem `tick()` automático, sem agendamento e sem
 * interceptar `setTimeout` — congelar o mundo inteiro (`jest.useFakeTimers()`) alcançaria o
 * `better-sqlite3`, o logger e o Nest, e um teste que quebra por causa disso quebra por um motivo
 * que não tem nada a ver com a regra que ele afirma.
 */
export class FakeClock implements Clock {
  constructor(private current: number) {}

  now(): number {
    return this.current;
  }

  /** Move o relógio para um instante exato. */
  set(instantMs: number): void {
    this.current = instantMs;
  }

  /** Avança. Útil para "o desafio começou" sem ter de recalcular o instante à mão. */
  advance(deltaMs: number): void {
    this.current += deltaMs;
  }
}
