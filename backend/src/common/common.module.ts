import { Global, Module } from '@nestjs/common';
import { CLOCK, SystemClock } from './clock';
import { SparkLogger } from './logger';

/**
 * A infraestrutura compartilhada do processo.
 *
 * `@Global` porque logger e relógio não pertencem a nenhum domínio: exigir que cada módulo
 * importe este aqui só para registrar uma linha ou perguntar as horas produziria uma lista de
 * imports que não descreve dependência nenhuma.
 *
 * O [CLOCK] entrou na T17.3, quando o primeiro comportamento **derivado do tempo** nasceu (o ciclo
 * de vida do desafio). Ele é `SystemClock` em produção e substituível em teste — ver
 * `common/clock.ts` para o porquê de a abstração ser deste tamanho.
 */
@Global()
@Module({
  providers: [SparkLogger, { provide: CLOCK, useClass: SystemClock }],
  exports: [SparkLogger, CLOCK],
})
export class CommonModule {}
